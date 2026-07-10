package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringredisTemplate;

    //发送短信验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1,校验手机号
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);

        //2，如果不符合，返回错误信息
        if (phoneInvalid) {
            log.info("手机号格式错误");
            return Result.fail("手机号格式错误");
        }
        //3，符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4，保存验证码到reids，写入日志
        log.info("验证码为"+code);
        stringredisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL,TimeUnit.MINUTES);

        //5，发送验证码
        log.info("发送验证码成功");
        return Result.ok();

    }
    /*
     * 短信验证码登录功能
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        //反向校验
        if (phoneInvalid) {
            return Result.fail("手机号格式错误");
        }
        //校验验证码
        String code = loginForm.getCode();
        String cacheCode = stringredisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        if (cacheCode == null || !cacheCode.equals(code)) {
            //不一致，报错
            return Result.fail("验证码错误");
        }

        //存在的话，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //用户是否存在，不存在，创建新用户
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        //保存用户信息到redis
        //1，随机生成token，当登陆令牌
        String token = UUID.randomUUID().toString();
        //2，将user对象转为hash作为存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //转成map
        Map<String,Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));
        //3，保存到redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringredisTemplate.opsForHash().putAll(tokenKey,userMap);
        //设置token过期时间
        stringredisTemplate.expire(tokenKey, 30, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    /*
    * 签到功能
    * */
    @Override
    public Result sign() {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        
        //获取日期
        LocalDateTime date = LocalDateTime.now();

        //拼接key
        String suffix = date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String key = USER_SIGN_KEY + userId + ":" + suffix;
        //本月的第几天
        int day = date.getDayOfMonth();
        //写入redis bitset
        stringredisTemplate.opsForValue().setBit(key,day-1,true);
        return Result.ok();
    }

    /*
    * 统计连续签到功能
    * */
    @Override
    public Result signCount() {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime date = LocalDateTime.now();
        //本月第几天
        int day = date.getDayOfMonth();
        //拼接key
        String suffix = date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String key = USER_SIGN_KEY + userId + ":" + suffix;
        //获取本月的签到记录
        //返回值是10进制
        List<Long> result = stringredisTemplate.opsForValue()
                .bitField(key,
                        BitFieldSubCommands.create()
                                .get(BitFieldSubCommands.BitFieldType.unsigned(day))
                                .valueAt(0));
        if (result == null || result.size() == 0) {
            return Result.ok(0);
        }
        Long signCount = result.get(0);
        if(signCount == null || signCount == 0L){
            return Result.ok(0);
        }
        int count = 0;
        /*
        * 与运算
        * */
        while(true){
//让这个数字与1做&&运算，判断返回值是否为0，不为0则说明这个数字的二进制位为1，为0则说明这个数字的二进制位为0
            if((signCount & 1) == 0){
                //未签到，结束
                break;
            }else{
                count++;
            }
            signCount >>>= 1;
        }

        return Result.ok(count);
    }

    //新建一个随机用户
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }

}
