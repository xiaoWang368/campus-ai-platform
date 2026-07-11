package com.campus.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.fasterxml.jackson.databind.type.LogicalType.Collection;
//为了解决：
// 1. 并发安全问题
// 2. 数据一致性保障
// 3. 幂等性控制: 防止重复提交、重复下单等业务场景

//redis分布式锁
public class SimpleRedisLock implements Ilock {
    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String ThreadId = ID_PREFIX + Thread.currentThread().getId();
        String key = KEY_PREFIX + name;
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, ThreadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }
// 释放锁
    @Override
    public void unlock() {
        //原执行保证
        //保证别的线程不会删除锁
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());

    }
/*    public void unlock() {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (id.equals(threadId)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
