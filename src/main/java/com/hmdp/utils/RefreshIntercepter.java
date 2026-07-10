package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

public class RefreshIntercepter implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    public RefreshIntercepter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1,获取请求头中的token
        String key = request.getHeader("authorization");
        //不存在，返回401状态码
        if(key == null){
            return true;
        }
        //2,获取token中存在的用户
        Map<Object, Object> user = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY+ key);

        //用户不存在，放行,但不填充userholder
        if(user.isEmpty()){
            return true;
        }
        //将map对象转换成dto对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(user, new UserDTO(), true);

        //存在，放行，保存到threadlocal
        UserHolder.saveUser(userDTO);

        //设置token过期时间
        stringRedisTemplate.expire(LOGIN_USER_KEY+key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
