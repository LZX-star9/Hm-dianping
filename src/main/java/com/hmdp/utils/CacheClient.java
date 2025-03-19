package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private boolean tryLock(String key) {
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R quaryWithPassThrough(String keyPrefix,ID id,Class<R> type,
                                         Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        //TODO 在Redis中查询
        String key = keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果存在返回
        if (StrUtil.isNotBlank(json)) {
            R bean = JSONUtil.toBean(json, type);
            return bean;
        }
        if (json != null) {
            return null;
        }
        //如果不存在查询数据库
        R r= dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //将数据放到redis中
        this.set(key, r, time, unit);
        //返回
        return r;
    }


    public <R,ID> R quaryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,
                                           Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        //TODO 在Redis中查询
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果存在返回
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //4 将Json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 没过期直接返回
            return r;
        }
        //5.2 过期了，需要缓存重建
        //5.2.1缓存重建
        // 5.2.2 获取互斥锁
        String lock_key = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lock_key);
        // 5.2.3 判断是否获取成功(成功开启独立线程，实现缓存重建)
        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lock_key);
                }
            });
        }
        // 5.2.3 失败直接返回
        return r;
    }

}
