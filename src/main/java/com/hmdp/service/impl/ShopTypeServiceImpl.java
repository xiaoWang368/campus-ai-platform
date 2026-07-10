package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.apache.coyote.Response;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
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

    private final RedisTemplate redisTemplate;

    public ShopTypeServiceImpl(RedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }


    @Override
    public List<ShopType> queryyList() {
        List<ShopType> shopTypeList = new ArrayList<>();
        //查询redis，存在直接返回
        String key = RedisConstants.SHOP_LIST_KEY ;
        String shopType = (String) redisTemplate.opsForValue().get(key);
        List<ShopType> typeList = JSONUtil.toList(shopType, ShopType.class);
        if(StrUtil.isNotBlank(shopType)){
            return shopTypeList;
        }
        if(StrUtil.isBlank(shopType)){
            //不存在，查询数据库
            typeList = query().orderByAsc("sort").list();
            return typeList;
            //数据库不存在，报错
        }
        if(CollectionUtil.isEmpty(typeList)){
            return null;
        }
        //存在，写入redis
        redisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(typeList));
        return typeList;
    }
}
