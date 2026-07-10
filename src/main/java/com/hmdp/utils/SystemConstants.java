package com.hmdp.utils;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = "E:\\develope\\nginx-1.18.0\\nginx-1.18.0\\html\\hmdp\\imgs\\";
    public static final String USER_NICK_NAME_PREFIX = "jichen_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;

    // Agent 操作使用的默认用户 ID
    // 生产环境应从认证上下文中获取，这里使用一个测试用户
    public static final Long AGENT_USER_ID = 1L;
}
