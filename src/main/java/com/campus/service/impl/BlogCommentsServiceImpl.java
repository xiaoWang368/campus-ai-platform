package com.campus.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.dto.Result;
import com.campus.dto.UserDTO;
import com.campus.entity.BlogComments;
import com.campus.entity.User;
import com.campus.mapper.BlogCommentsMapper;
import com.campus.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.service.IUserService;
import com.campus.utils.SystemConstants;
import com.campus.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.campus.utils.RedisConstants.COMMENT_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result addComment(BlogComments blogComments) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 设置评论信息
        blogComments.setUserId(userId);
        blogComments.setLiked(0);
        blogComments.setStatus(false);
        if (blogComments.getParentId() == null) {
            blogComments.setParentId(0L);
        }
        if (blogComments.getAnswerId() == null) {
            blogComments.setAnswerId(0L);
        }
        // 保存评论
        boolean isSuccess = save(blogComments);
        if (!isSuccess) {
            return Result.fail("发表评论失败");
        }
        // 更新博客评论数量
        update().setSql("comments = comments + 1").eq("id", blogComments.getBlogId()).update();
        // 返回id
        return Result.ok(blogComments.getId());
    }

    @Override
    public Result queryBlogComments(Long blogId, Integer current) {
        // 查询一级评论，按时间倒序
        Page<BlogComments> page = query()
                .eq("blog_id", blogId)
                .eq("parent_id", 0)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<BlogComments> records = page.getRecords();
        if (records.isEmpty()) {
            return Result.ok(records);
        }
        // 填充用户信息和点赞状态，并查询子回复
        records.forEach(comment -> {
            queryCommentUser(comment);
            comment.setIsLike(getCommentLiked(comment));
            // 查询子回复
            List<BlogComments> replyList = query()
                    .eq("blog_id", blogId)
                    .eq("parent_id", comment.getId())
                    .orderByDesc("create_time")
                    .list();
            if (!replyList.isEmpty()) {
                replyList.forEach(reply -> {
                    queryCommentUser(reply);
                    reply.setIsLike(getCommentLiked(reply));
                });
                comment.setReplyList(replyList);
            }
        });
        return Result.ok(records);
    }

    @Override
    public Result likeComment(Long id) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 判断是否点过赞
        String key = COMMENT_LIKED_KEY + id;
        Boolean isLiked = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (Boolean.FALSE.equals(isLiked)) {
            // 未点赞，点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        } else {
            // 已点赞，取消点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result deleteComment(Long id) {
        // 获取评论
        BlogComments comment = getById(id);
        if (comment == null) {
            return Result.fail("评论不存在");
        }
        // 校验是否是本人操作
        Long userId = UserHolder.getUser().getId();
        if (!comment.getUserId().equals(userId)) {
            return Result.fail("只能删除自己的评论");
        }
        // 删除评论
        boolean isSuccess = removeById(id);
        if (!isSuccess) {
            return Result.fail("删除评论失败");
        }
        // 更新博客评论数量
        update().setSql("comments = comments - 1").eq("id", comment.getBlogId()).update();
        return Result.ok();
    }

    /**
     * 查询评论对应的用户信息
     */
    private void queryCommentUser(BlogComments blogComments) {
        Long userId = blogComments.getUserId();
        User user = userService.getById(userId);
        if (user == null) {
            blogComments.setNickName("用户不存在");
            blogComments.setIcon("https://cdn.jsdelivr.net/gh/xiaozhu-wang/images/2022/01/05/20220105152407.png");
            return;
        }
        blogComments.setNickName(user.getNickName());
        blogComments.setIcon(user.getIcon());
    }

    /**
     * 判断当前用户是否点赞了该评论
     */
    private Boolean getCommentLiked(BlogComments blogComments) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return false;
        }
        String key = COMMENT_LIKED_KEY + blogComments.getId();
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(key, user.getId().toString()));
    }
}
