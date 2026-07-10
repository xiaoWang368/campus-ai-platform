package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

public interface Ilock {
    boolean tryLock(long timeoutSec);
    void unlock();
}
