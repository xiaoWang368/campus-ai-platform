package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.text.StrJoiner;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess)
            return Result.fail("新增笔记失败");
        //推送
        String key = FEED_KEY + user.getId();
        stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        // 返回id
        return Result.ok(blog.getId());
    }
    /*
    * 关注推送
    * */
    @Override
    public Result queryBlogFollow(Long max, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //查询收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedtuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedtuples.isEmpty()){
            return Result.ok();
        }
        //解析数据
        long minTime = 0;
        int os = 1;
        List<Long> ids = new ArrayList<>(typedtuples.size());
        for (ZSetOperations.TypedTuple<String> typedtuple : typedtuples) {
            long time = typedtuple.getScore().longValue();
            Long idstr = Long.valueOf(typedtuple.getValue());
            ids.add(idstr);
            //minTime设置
            if(time != minTime){
                minTime=time;
                os = 1;
            }else{
                os++;
            }
        }
        String idstr = StrUtil.join(",",ids);
        //查询blog
        List<Blog> blogs = query().in("id", ids)
                .last("order by field(id," + idstr + ")").list();
        for (Blog blog : blogs) {
            //blog信息设置
            queryBlogUser(blog);
            blog.setIsLike(getBlogLiked(blog));
        }
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    @Override
    public void likeBlog(Long id) {
        //获取点赞用户
        Long userId = UserHolder.getUser().getId();
        //判断用户是否点过赞
        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 不存在，修改点赞数量
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 存在，取消点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }

        }
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
/*        List<UserDTO> list = new ArrayList<>();
        top5.forEach(userId -> {
            Long ids = Long.valueOf(userId);
            User user = userService.getById(ids);
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            list.add(userDTO);
        });*/
        List<Long> ids = top5.stream()
                .map(userId -> Long.valueOf(userId))
                .collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> list = userService.query()
                .in("id",ids)
                .last("order by field(id,"+idStr+")").list()
                .stream().map(user -> BeanUtil.toBean(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(list);
    }



    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            blog.setIsLike(getBlogLiked(blog));
        });

        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        //查询blog有关的用户
        queryBlogUser(blog);
        //查询blog是否被点赞
        blog.setIsLike(getBlogLiked(blog));
            return Result.ok(blog);
    }


    private Boolean getBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //用户未登录，默认未点赞
            return false;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            return true;
        }
        return false;
    }


    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        //非空校验
        if (user == null) {
            blog.setName("用户不存在");
            blog.setIcon("https://cdn.jsdelivr.net/gh/xiaozhu-wang/images/2022/01/05/20220105152407.png");
            return;
        }
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
