package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        Shop shop = queryWithMutex(id);
        if (shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    //解决缓存穿透和使用互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id){
        String key = "shop:"+id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)){//判断是否为null，空字符串，对象
            //redis中存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //如果redis中是空字符串
        if (shopJson != null){
            //不为null，那就是空字符串
            return null;
        }

        //为空
        String lockKey = "lock:shop"+id;
        Shop shop = null;
        try {
            boolean lock = tryLock(lockKey);
            if (!lock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            shop = getById(id);
            //模拟重建的延时
            Thread.sleep(200);
            if (shop == null){
                //解决穿透，缓存控制
                stringRedisTemplate.opsForValue().set(key,"",10, TimeUnit.MICROSECONDS);
                return null;
            }
            //存在，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),30,TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }
        return shop;
    }


    //获取锁

    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}




























