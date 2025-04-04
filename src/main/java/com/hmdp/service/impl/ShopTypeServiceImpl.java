package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

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

    @Resource
    private IShopTypeService typeService;

    @Override
    public Result queryTypeList() {
        String shopType = stringRedisTemplate.opsForValue().get("cache:shopType");
        if (StrUtil.isNotBlank(shopType)) {
            return Result.ok(JSONUtil.toList(shopType, ShopType.class));
        }
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        if (typeList == null) {
            return Result.fail("未查询到类型信息");
        }
        stringRedisTemplate.opsForValue().set("cache:shopType", JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);
    }
}
