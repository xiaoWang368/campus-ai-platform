package com.campus.service;

import com.campus.dto.Result;
import com.campus.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogCommentsService extends IService<BlogComments> {

    Result addComment(BlogComments blogComments);

    Result queryBlogComments(Long blogId, Integer current);

    Result likeComment(Long id);

    Result deleteComment(Long id);
}
