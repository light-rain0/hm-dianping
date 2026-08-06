package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class,
                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);


        // 缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,
//                id, Shop.class, this::getById,
//                CACHE_SHOP_TTL, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }


    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;

        // 1. 查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {   // 空值缓存命中
            return null;
        }

        // 2. 抢锁（限次重试，避免无限递归）
        Shop shop = null;
        boolean isHoldLock = false;
        int retry = 3;
        try {
            while (retry-- > 0) {
                isHoldLock = tryLock(lockKey);
                if (!isHoldLock) {
                    Thread.sleep(50);
                    continue;
                }
                // 3. 拿到锁后 double-check redis是否命中有就直接返回
                shopJson = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(shopJson)) {
                    return JSONUtil.toBean(shopJson, Shop.class);
                }
                // 4. 查 DB
                shop = getById(id);
                if (shop == null) {
                    // redis设置null防止缓存穿透
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                // 5. 写缓存
                int randomNum = RandomUtil.randomInt(0, 30);
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                        CACHE_SHOP_TTL + randomNum, TimeUnit.MINUTES);
                return shop;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (isHoldLock) {
                unLock(lockKey);
            }
        }
        return shop;
    }

    // 抢锁：SETNX 语义，仅一个线程能写入成功；LOCK_SHOP_TTL 兜底防死锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShop2RedisCache(Long id, Long expireSeconds) {
        // 1.查询店铺信息
        Shop shop = getById(id);
        // 2.封装数据
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {

        // 1.更新数据库
        updateById(shop);

        Long shopId = shop.getId();
        if (shopId == null) {
            return Result.fail("店铺Id为空");
        }
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
        return Result.ok(shop);
    }


//    @Override
//    public Result queryById(Long id) {
//        Shop shop = queryWithMutex(id);
//        if (shop == null){
//            return Result.fail("店铺不存在");
//        }
//        return Result.ok(shop);
//    }
//
//    //解决缓存穿透和使用互斥锁解决缓存击穿
//    public Shop queryWithMutex(Long id){
//        String key = "shop:"+id;
//
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        if (StrUtil.isNotBlank(shopJson)){//判断是否为null，空字符串，对象
//            //redis中存在，直接返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//
//        //如果redis中是空字符串
//        if (shopJson != null){
//            //不为null，那就是空字符串
//            return null;
//        }
//
//        //为空
//        String lockKey = "lock:shop"+id;
//        Shop shop = null;
//        try {
//            boolean lock = tryLock(lockKey);
//            if (!lock){
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//            shop = getById(id);
//            //模拟重建的延时
//            Thread.sleep(200);
//            if (shop == null){
//                //解决穿透，缓存控制
//                stringRedisTemplate.opsForValue().set(key,"",10, TimeUnit.MICROSECONDS);
//                return null;
//            }
//            //存在，写入redis
//            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),30,TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            unLock(lockKey);
//        }
//        return shop;
//    }
//
//
//    //获取锁
//
//    public boolean tryLock(String key){
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }
//
//    //释放锁
//    public void unLock(String key){
//        stringRedisTemplate.delete(key);
//    }
}




























