package com.campus.controller;


import com.campus.dto.Result;
import com.campus.entity.BlogComments;
import com.campus.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {
    @Resource
    private IBlogCommentsService blogCommentsService;

    @PostMapping
    public Result addComment(@RequestBody BlogComments blogComments) {
        return blogCommentsService.addComment(blogComments);
    }

    @GetMapping("/{blogId}")
    public Result queryBlogComments(@PathVariable("blogId") Long blogId,
                                    @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogCommentsService.queryBlogComments(blogId, current);
    }

    @PutMapping("/like/{id}")
    public Result likeComment(@PathVariable("id") Long id) {
        return blogCommentsService.likeComment(id);
    }

    @DeleteMapping("/{id}")
    public Result deleteComment(@PathVariable("id") Long id) {
        return blogCommentsService.deleteComment(id);
    }
}
