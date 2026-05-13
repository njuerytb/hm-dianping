package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.apache.tomcat.util.buf.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        User user = userService.getById(blog.getUserId());
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        // 这里补上！
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    public void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return ;
        }
        Long userId = UserHolder.getUser().getId();
        Double score= stringRedisTemplate.opsForZSet().score("blog:liked:" + blog.getId(), userId.toString());
        blog.setIsLike(score!= null);
    }

    @Override
    public Result likeBlog(Long id) {

        Long userId = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score("blog:liked:" + id, userId.toString());

        if (score== null) {
            // 点赞 +1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add("blog:liked:" + id, userId.toString(),System.currentTimeMillis());
            }
        } else {
            // 取消点赞 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove("blog:liked:" + id, userId.toString());
            }
        }

        // 把下面这一整段删除！！！
        // boolean update = update().setSql("liked = liked + 1").eq("id", id).update();
        // if (!update){
        //     return Result.fail("点赞失败");
        // }

        return Result.ok();
    }
    @Override
    public Result queryBlogLikes(Long id) {
     //1.查询top5的点赞用户
   Set<String> top5 = stringRedisTemplate.opsForZSet().range("blog:liked:" + id, 0, 4);
   if (top5 == null || top5.isEmpty()) {
       return Result.ok(Collections.emptyList());
   }
     //2.解析出其中的用户id
      List<Long> ids =  top5.stream().map(Long::valueOf).collect(Collectors.toList());
   String idStr = StrUtil.join(",",ids);
      //3.根据用户id查询用户
    List<UserDTO> users =  userService.query().in("id", ids).last("ORDER BY FIELD(id,"+idStr+")").list()
              .stream()
              .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
              .collect(Collectors.toList());
     //4.返回用户
        return Result.ok(users);
    }
}