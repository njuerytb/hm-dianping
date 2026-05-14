package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.apache.tomcat.util.buf.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
    @Resource
    private IFollowService followService;

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
    @Override
    public Result saveBlog(Blog blog) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2.保存探店笔记
        boolean save = save(blog);
        if (!save) {
            return Result.fail("新增笔记失败");
        }

        //3.查询笔记作者的所有粉丝
     List<Follow> fans =   followService.query().eq("follow_user_id", user.getId()).list();
        //4.推送笔记id给粉丝
        for (Follow fan : fans){
            //4.1获取粉丝id
            Long userId = fan.getUserId();
            //4.2推送
            String key = "feed:" + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        //5.返回id
        return Result.ok(blog.getId());
    }
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱
        String key = "feed:" + userId;
    Set<ZSetOperations.TypedTuple<String>> typedTuples =    stringRedisTemplate.opsForZSet()
            .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
    //3.非空判断
    if (typedTuples == null || typedTuples.isEmpty()) {
        return Result.ok();
    }
    //4.解析数据：blogId，mintime（时间戳）， offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
    long minTime = 0;
    int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples){
            //4.1.获取id
            String idStr = tuple.getValue();
            ids.add(Long.valueOf(idStr));
            //4.2获取分数
          long time = tuple.getScore().longValue();
          if (time == minTime) {
              os++;
          } else {
              minTime = time;
              os = 1;
          }
        }
        //5.根据blogId查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + StrUtil.join(",", ids) + ")")
                .list();
        for (Blog blog : blogs){
            User user = userService.getById(blog.getUserId());
            blog.setIcon(user.getIcon());
            blog.setName(user.getNickName());
            isBlogLiked(blog);
        }
        //6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }
}