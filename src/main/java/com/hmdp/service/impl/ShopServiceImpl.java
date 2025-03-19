package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    private boolean tryLock(String key) {
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


    @Override
    public Result queryById(Long id) {
        // 缓存穿透
//        Shop shop = quaryWithPassThrough(id);

        //互斥锁解决缓存击穿
//        Shop shop = quaryWithMutex(id);
        Shop shop = cacheClient.quaryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        用逻辑过期解决缓存击穿
//        Shop shop = quaryWithLogicalExpire(id);

        return Result.ok(shop);
    }

    public Shop quaryWithPassThrough(Long id) {
        //TODO 在Redis中查询
        String shop_key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shop_key);
        //如果存在返回
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null) {
            return null;
        }
        //如果不存在查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(shop_key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //将数据放到redis中
        stringRedisTemplate.opsForValue().set(shop_key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return shop;
    }

    public Shop quaryWithMutex(Long id) {
        //TODO 在Redis中查询
        String shop_key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shop_key);
        String lock_key = RedisConstants.LOCK_SHOP_KEY + id;
        //如果存在返回
        Shop shop = null;
        if (StrUtil.isNotBlank(shopJson)) {
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null) {
            return null;
        }
        try {
            //互斥锁解决缓存击穿
            // 4.1 获取互斥锁
            if (!tryLock(lock_key)) {
                // 4.2 判断互斥锁是否成功获取
                Thread.sleep(50);
                return quaryWithMutex(id);
            }
            //获取锁后查询数据库
            shop = getById(id);
            Thread.sleep(200);
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(shop_key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //将数据放到redis中
            stringRedisTemplate.opsForValue().set(shop_key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unLock(lock_key);
        }

        //返回
        return shop;
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop quaryWithLogicalExpire(Long id) {
        //TODO 在Redis中查询
        String shop_key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shop_key);
        //如果存在返回
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        //4 将Json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
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
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lock_key);
                }
            });
        }
        // 5.2.3 失败直接返回
        return shop;
    }


    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();


    }
}
