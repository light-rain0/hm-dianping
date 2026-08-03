package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RequiredArgsConstructor
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    @Override
    public Result queryTypeList() {
        // 1. 从 Redis 查缓存
        String typeListJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        // 2. 命中则直接返回
        if (StrUtil.isNotBlank(typeListJson)) {
            List<ShopType> typeList = this.objectMapper.readValue(typeListJson, new TypeReference<List<ShopType>>() {
            });
//           JSONUtil.toList(typeListJson, ShopType.class);
            return Result.ok(typeList);
        }
        // 3. 未命中，查数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (typeList == null || typeList.isEmpty()) {
            return Result.fail("商铺类型不存在");
        }
        // 4. 写入 Redis，设过期时间
        stringRedisTemplate.opsForValue().set(
                CACHE_SHOP_TYPE_KEY,
                JSONUtil.toJsonStr(typeList),
                CACHE_SHOP_TYPE_TTL,
                TimeUnit.MINUTES
        );
        // 5. 返回
        return Result.ok(typeList);
    }
}
