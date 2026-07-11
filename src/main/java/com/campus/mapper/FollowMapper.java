package com.campus.mapper;

import com.campus.entity.Follow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Mapper
public interface FollowMapper extends BaseMapper<Follow> {
@Delete("delete from tb_follow where follow_user_id = #{followUserId} and user_id = #{userId} ")
    boolean isNotFollow(Long userId, Long followUserId);
}
