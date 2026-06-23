package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Component
@Slf4j
// 封装Redis常用方法的工具类
public class CacheClient {

    // 构造器注入方式 最好加上final字段修饰
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 缓存数据
     * @param key 键
     * @param value 值
     * @param time 时间
     * @param timeUnit 时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }
    /**
     * 缓存逻辑过期时间数据
     * @param key 键
     * @param value 值
     * @param time 时间
     * @param timeUnit 时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));// 使用时间单位转换成秒
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null){
            return null;
        }

        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.不存在，返回错误
        if (r == null){
            // 缓存穿透解决方案 缓存空对象
            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误
            return null;
        }

        //6.存在，写入redis
        this.set(key, r, time, timeUnit);

        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //3.存在，直接返回
            return null;
        }

        //4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1 未过期，直接返回店铺信息
            return r;
        }

        //5.2 已过期，需要缓存重建
        //6. 缓存重建
        //6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取成功
        if (isLock){
            // 6.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入Redis
                    this.setWithLogicalExpire(key, r1, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }

        //6.4 返回过期信息
        return r;
    }
}
