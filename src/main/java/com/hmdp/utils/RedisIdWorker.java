package com.hmdp.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class RedisIdWorker {


    // 开始时间戳
    private static final long BEGIN_TIMESTAMP = 1785974400L;
    private static final int COUNT_BITS = 32;
    private final StringRedisTemplate stringRedisTemplate;

    public long nextID(String keyPrefix) {
        // 1.获取当前时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;


        // 2.生成序列号
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        // 3.自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + data);

        // 4.返回
        return timestamp << COUNT_BITS | count;
    }
}
