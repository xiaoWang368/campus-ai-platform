package com.campus.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

public interface Ilock {
    boolean tryLock(long timeoutSec);
    void unlock();
}
