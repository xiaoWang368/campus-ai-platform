package com.campus.config;

import com.campus.utils.LoginIntercepter;
import com.campus.utils.RefreshIntercepter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    //登录校验拦截
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginIntercepter())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login",
                        "/agent/**",
                        "/chat.html",
                        "/**/*.html",
                        "/**/*.js",
                        "/**/*.css"
                ).order(1);

        registry.addInterceptor(new RefreshIntercepter(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
