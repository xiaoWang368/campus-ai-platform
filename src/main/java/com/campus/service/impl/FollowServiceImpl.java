package com.campus.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.campus.dto.Result;
import com.campus.dto.UserDTO;
import com.campus.entity.Follow;
import com.campus.mapper.FollowMapper;
import com.campus.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.service.IUserService;
import com.campus.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
@Resource
private FollowMapper followMapper;
@Resource
private StringRedisTemplate stringRedisTemplate;
@Resource
private IUserService userService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follow:"+userId;
        if(isFollow){
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean isSuccess = save(follow);
            if(isSuccess){
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else{
            boolean isSuccess = followMapper.isNotFollow(userId, followUserId);
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query()
                .eq("user_id", userId).eq("follow_user_id", followUserId)
                .count();
        if(count == 1){
            return Result.ok(true);


        }
        return Result.ok(false);
    }

    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key1 = "follow:" + userId;
        String key2 = "follow:" + id;
        Set<String> intersects = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersects.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersects.stream()
                .map(intersect -> Long.valueOf(intersect)).collect(Collectors.toList());
        List<UserDTO> userDTOs = userService.listByIds(ids)
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOs);
    }
}
