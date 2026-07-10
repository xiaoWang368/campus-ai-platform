package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final RedisTemplate redisTemplate;

    public CacheClient(RedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }



    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 从redis查询
        String json = (String) redisTemplate.opsForValue().get(key);
        // 存在且不为空值
        if (json != null && !"".equals(json)) {
            // 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 存在且为空值,直接返回null
        if (json != null && "".equals(json)) {
            return null;
        }
        // 不存在，查询数据库
        R r = dbFallback.apply(id);  //查询数据库的函数,这个方法里只能穿入根据id查询对象的方法
        // 数据库也不存在，缓存空值
        if (r == null) {
            redisTemplate.opsForValue().set(key, "", time, unit); //键 , 值, 时间数量, 时间单位
            return null;
        }
        // 存在，写入redis
        this.set(key, r, time, unit);
        return r;
    }
    public void setWithLogicalExpire(String key, Object value, Long time) {
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        //写入reids
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
        //逻辑过期,redisdata里的expire是逻辑过期时间,当做value存储
    }
    //逻辑过期代码
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix,
            ID id, Class<R> type,
            Function<ID, R> dbFallback,
            Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //从redis里查询
        String json = (String) redisTemplate.opsForValue().get(key);
        if(json == null || "".equals(json)){
            R r = dbFallback.apply(id);
            if(r == null) return null;
            //设置逻辑过期时间
            this.setWithLogicalExpire(key,r,time);
            return r;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        if(redisData.getExpireTime().isBefore(LocalDateTime.now())){
            //缓存重建,还要重新查一遍数据库
            R r = dbFallback.apply(id);
            //重建逻辑过期
            setWithLogicalExpire(key,r,time);
            return r;
        }


        //存在的话,将对象转换成所需dto类型
        Object data = redisData.getData();
        if (data instanceof String) {
            return JSONUtil.toBean((String) data, type);
        } else {
            // 如果数据已经是对象形式，直接转换为目标类型
            return JSONUtil.toBean(JSONUtil.toJsonStr(data), type);
        }
/*        String key = keyPrefix + id;
        // 从redis查询
        String json = (String) redisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (json == null || "".equals(json)) {
            // 不存在，查询数据库
            R r = dbFallback.apply(id);
            // 数据库也不存在，返回null
            if (r == null) {
                return null;
            }
            // 存在，写入redis并设置逻辑过期时间
            this.setWithLogicalExpire(key, r, time);
            return r;
        }*/
/*        // 存在，判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 过期时间早于当前时间，说明已过期
        if (expireTime.isBefore(LocalDateTime.now())) {
            // 缓存重建
            R r = dbFallback.apply(id);
            if (r == null) {
                return null;
            }
            this.setWithLogicalExpire(key, r, time);
            return r;
        }

        // 未过期，返回数据
        // 修复：确保数据字段被正确转换为JSON字符串
        Object data = redisData.getData();
        if (data instanceof String) {
            return JSONUtil.toBean((String) data, type);
        } else {
            // 如果数据已经是对象形式，直接转换为目标类型
            return JSONUtil.toBean(JSONUtil.toJsonStr(data), type);
        }*/
    }

    //上锁
    private boolean trylock(String key) {
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        boolean a = BooleanUtil.isTrue(flag);
        return a;
    }

    //解锁
    private void unlock(String key) {
        redisTemplate.delete(key);
    }

}

