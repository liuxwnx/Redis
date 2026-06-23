package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        //1.从缓存中查询类型
        String key = RedisConstants.CACHE_SHOP_KEY;
        List<String> shopTypeListJson = stringRedisTemplate.opsForList().range(key, 0, -1);
        //2.判断是否存在
        //将所有jsonList转化为对象list,排序，返回
        if (CollectionUtil.isNotEmpty(shopTypeListJson)) {
            List<ShopType> shopTypeList = shopTypeListJson.stream()
                    .map(str -> JSONUtil.toBean(str, ShopType.class))
                    .sorted(Comparator.comparing(ShopType::getSort))
                    .collect(Collectors.toList());
            //3.存在，返回数据
            return Result.ok(shopTypeList);
        }
        //4.不存在，查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        //5.不存在，返回错误
        if (CollectionUtil.isEmpty(shopTypeList)) {
            return Result.fail("商铺类型列表为空");
        }

        shopTypeListJson = shopTypeList.stream().sorted(Comparator.comparingInt(ShopType::getSort))
                .map(shopType -> JSONUtil.toJsonStr(shopType))
                .collect(Collectors.toList());
        //6.存在，写入缓存
        stringRedisTemplate.opsForList().rightPushAll(key,shopTypeListJson);
        //7.返回
        return Result.ok(shopTypeList);
    }
}
