package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheClient {

    // 建立线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private final StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    // 逻辑过期
    public void setWithLogicExpireTime(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {

        String key = prefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 命中真实数据，直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 命中空值缓存（json 是 "" 而非 null），直接返回防穿透
        if (json != null) {
            return null;
        }
        // key 不存在才查 DB
        R r = dbFallback.apply(id);
        // 数据库也无 -> 写空值缓存挡住后续穿透
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 数据库有 -> 写缓存并返回
        this.set(key, r, time, timeUnit);

        return r;
    }

    // 封装逻辑过期方法
    public <R, ID> R queryWithLogicalExpire(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {

        String key = prefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 未预热直接返回 null（逻辑过期要求 key 提前写入 Redis）
        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 未过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        // 已过期：抢到锁则异步重建，无论是否抢到都返回旧数据（不阻塞）
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicExpireTime(key, r1, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    this.unLock(lockKey);
                }
            });
        }

        return r;
    }

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

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

}
