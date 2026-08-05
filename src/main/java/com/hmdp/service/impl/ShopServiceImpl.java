package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private final CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);


//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,
//                id, Shop.class, this::getById,
//                CACHE_SHOP_TTL, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 逻辑过期解决缓存击穿问题
     * <p>
     * 核心思想：缓存【永不在 Redis 层面过期】（写入时不设 Redis TTL，只借助 RedisData 记录一个逻辑过期时间）。
     * 查询时的处理：
     * 1) 未到逻辑过期时间 -> 直接返回，命中缓存（最快路径，绝大多数请求走这里）；
     * 2) 已到逻辑过期时间 -> 返回【旧数据】+ 异步重建缓存，不让请求阻塞、也不让请求打到 DB。
     * 优点：读路径永不查 DB、永不阻塞，从根本上防住缓存击穿（热点 key 失效瞬间大量请求也不会压垮数据库）。
     * 代价：允许返回短暂旧数据（弱一致性），这是用一致性换取高可用的典型取舍。
     * 前置条件：热点 key 必须提前“预热”写入 Redis（带逻辑过期时间），否则未命中会直接返回 null。
     */
    public Shop queryWithLogicalExpire(Long id) {

        String key = CACHE_SHOP_KEY + id;

        // 1. 查缓存（逻辑过期模式下，热点 key 必须提前预热写入 Redis，否则未命中直接返回 null）
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 缓存未命中（说明该 key 没有预热），直接返回 null，交给上层返回“店铺不存在”
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        // 3. 命中，先把 JSON 反序列化为 RedisData（内部包含 data 和逻辑过期时间 expireTime）
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 3.1 再从 RedisData 中把真正的店铺数据 data 反序列化为 Shop 对象
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 4. 未到逻辑过期时间 -> 数据仍然新鲜，直接返回（最快路径）
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }

        // 5. 已到逻辑过期时间 -> 尝试抢分布式锁，抢到锁的线程负责异步重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 抢到锁：提交一个异步任务去重建缓存（读 DB 写 Redis），不阻塞当前读请求
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建并写入新的 RedisData（含新的逻辑过期时间），覆盖旧值
                    this.saveShop2RedisCache(id, 20L);
                } catch (Exception e) {
                    // 重建失败仅抛出，不影响本次返回旧数据
                    throw new RuntimeException(e);
                } finally {
                    // 重建完成（无论成功失败）必须释放锁，让其他线程后续能抢到锁继续重建
                    this.unLock(lockKey);
                }
            });
        }

        // 6. 无论是否抢到锁，都返回旧数据
        return shop;
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

    /**
     * 尝试获取分布式互斥锁（上锁）
     * <p>
     * 利用 Redis 的 SETNX 语义：只有当 key 不存在时才会写入成功。
     * 由于 Redis 单线程执行命令，同一时刻多个并发线程同时来抢，只有一个能成功，
     * 从而把「查 DB 重建缓存」这一步串行化，解决缓存击穿问题。
     *
     * @param key 锁的 key，通常用 LOCK_SHOP_KEY + 商品id，保证不同商品之间互不干扰
     * @return true=抢锁成功（当前线程有权查 DB 并重建缓存）；false=锁已被别的线程持有，需等待重试
     */
    private boolean tryLock(String key) {
        // setIfAbsent 对应 Redis 的 SETNX：key 不存在才写入，原子操作，并发下只有一个能成功
        // 参数说明："1" 是占位的 value（内容不重要，只看 key 存不存在）；
        //           LOCK_SHOP_TTL 是锁的过期时间（秒），即使持有锁的线程崩溃没执行 unLock，锁也会自动过期释放，避免死锁
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 返回值用 Boolean 包装类（可能为 null），用 BooleanUtil.isTrue 安全转换，
        // 避免直接 return flag 在 null 时自动拆箱抛 NullPointerException
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放分布式互斥锁（释放锁）
     * <p>
     * 删除锁对应的 key，让其他正在等待的线程可以抢到锁并重建缓存。
     * 重要：调用方必须先通过 isHoldLock 判断「这把锁确实是当前线程持有的」再调用本方法，
     * 绝不能释放别人持有的锁，否则会导致互斥失效、缓存击穿重现。
     *
     * @param key 要释放的锁 key
     */
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




























