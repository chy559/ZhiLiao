package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.OrganizationTag;
import com.yizhaoqi.smartpai.model.Post;
import com.yizhaoqi.smartpai.model.PostLike;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import com.yizhaoqi.smartpai.repository.PostLikeRepository;
import com.yizhaoqi.smartpai.repository.PostRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PostService {

    private static final Logger logger = LoggerFactory.getLogger(PostService.class);
    private static final int MAX_TITLE_LEN = 200;
    private static final int MAX_CONTENT_LEN = 5000;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private PostLikeRepository postLikeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Transactional
    public Post createPost(String username, String title, String content) {
        User user = requireUser(username);
        String cleanTitle = validateTitle(title);
        String cleanContent = validateContent(content);

        Post post = new Post();
        post.setUserId(user.getId());
        post.setTitle(cleanTitle);
        post.setContent(cleanContent);
        post.setSchoolTagSnapshot(user.getSchoolTag());
        post.setCollegeTagSnapshot(user.getCollegeTag());
        post.setMajorTagSnapshot(user.getMajorTag());
        post.setLikeCount(0);
        return postRepository.save(post);
    }

    @Transactional
    public Post updatePost(String username, Long id, String title, String content) {
        User user = requireUser(username);
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new CustomException("帖子不存在", HttpStatus.NOT_FOUND));
        if (!post.getUserId().equals(user.getId())) {
            throw new CustomException("只能编辑自己的帖子", HttpStatus.FORBIDDEN);
        }
        post.setTitle(validateTitle(title));
        post.setContent(validateContent(content));
        return postRepository.save(post);
    }

    @Transactional
    public void deletePost(String username, Long id) {
        User user = requireUser(username);
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new CustomException("帖子不存在", HttpStatus.NOT_FOUND));
        boolean isAdmin = user.getRole() == User.Role.ADMIN;
        if (!isAdmin && !post.getUserId().equals(user.getId())) {
            throw new CustomException("只能删除自己的帖子", HttpStatus.FORBIDDEN);
        }
        postLikeRepository.deleteByPostId(id);
        postRepository.delete(post);
    }

    /**
     * 切换点赞状态。返回 map: liked=boolean, likeCount=int
     */
    @Transactional
    public Map<String, Object> toggleLike(String username, Long postId) {
        User user = requireUser(username);
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new CustomException("帖子不存在", HttpStatus.NOT_FOUND));

        var existing = postLikeRepository.findByPostIdAndUserId(postId, user.getId());
        int currentCount = post.getLikeCount() == null ? 0 : post.getLikeCount();
        boolean liked;
        int newCount;
        if (existing.isPresent()) {
            postLikeRepository.delete(existing.get());
            postLikeRepository.flush();
            postRepository.decrementLikeCount(postId);
            liked = false;
            newCount = Math.max(0, currentCount - 1);
        } else {
            PostLike like = new PostLike();
            like.setPostId(postId);
            like.setUserId(user.getId());
            postLikeRepository.save(like);
            postLikeRepository.flush();
            postRepository.incrementLikeCount(postId);
            liked = true;
            newCount = currentCount + 1;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("liked", liked);
        resp.put("likeCount", newCount);
        return resp;
    }

    /**
     * 分页列表。
     * @param filter all | school | major
     */
    public Map<String, Object> listPosts(String username, String filter, int page, int size) {
        User me = requireUser(username);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        Page<Post> result;
        if ("school".equalsIgnoreCase(filter)) {
            if (me.getSchoolTag() == null || me.getSchoolTag().isBlank()) {
                return emptyPage(page, size);
            }
            result = postRepository.findByAuthorSchoolTag(me.getSchoolTag(), pageable);
        } else if ("major".equalsIgnoreCase(filter)) {
            if (me.getMajorTag() == null || me.getMajorTag().isBlank()) {
                return emptyPage(page, size);
            }
            result = postRepository.findByAuthorMajorTag(me.getMajorTag(), pageable);
        } else if ("mine".equalsIgnoreCase(filter)) {
            result = postRepository.findByUserIdOrderByCreatedAtDesc(me.getId(), pageable);
        } else {
            result = postRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        List<Map<String, Object>> items = toDtos(result.getContent(), me);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", result.getTotalElements());
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        out.put("items", items);
        return out;
    }

    /** 单条帖子详情（目前未必需要，留着备用） */
    public Map<String, Object> getPost(String username, Long id) {
        User me = requireUser(username);
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new CustomException("帖子不存在", HttpStatus.NOT_FOUND));
        return toDtos(List.of(post), me).get(0);
    }

    /**
     * 批量把 posts 组装成前端 DTO：包含作者 username/avatar/学校名/学校描述/学院名/专业名，以及当前用户是否已点赞、是否本人。
     */
    private List<Map<String, Object>> toDtos(List<Post> posts, User me) {
        if (posts.isEmpty()) return List.of();

        Set<Long> userIds = posts.stream().map(Post::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        Set<String> tagIds = new HashSet<>();
        for (Post p : posts) {
            User author = userMap.get(p.getUserId());
            String schoolTag = author != null ? author.getSchoolTag() : p.getSchoolTagSnapshot();
            String collegeTag = author != null ? author.getCollegeTag() : p.getCollegeTagSnapshot();
            String majorTag = author != null ? author.getMajorTag() : p.getMajorTagSnapshot();

            if (schoolTag != null) tagIds.add(schoolTag);
            if (collegeTag != null) tagIds.add(collegeTag);
            if (majorTag != null) tagIds.add(majorTag);
        }

        Map<String, OrganizationTag> tagMap = tagIds.isEmpty()
                ? Map.of()
                : organizationTagRepository.findAllById(tagIds).stream()
                    .collect(Collectors.toMap(OrganizationTag::getTagId, t -> t));

        // 我当前点过赞的帖子
        List<Long> postIds = posts.stream().map(Post::getId).toList();
        Set<Long> likedSet = postLikeRepository.findByUserIdAndPostIdIn(me.getId(), postIds)
                .stream().map(PostLike::getPostId).collect(Collectors.toSet());

        List<Map<String, Object>> out = new ArrayList<>(posts.size());
        for (Post p : posts) {
            User author = userMap.get(p.getUserId());
            String schoolTag = author != null ? author.getSchoolTag() : p.getSchoolTagSnapshot();
            String collegeTag = author != null ? author.getCollegeTag() : p.getCollegeTagSnapshot();
            String majorTag = author != null ? author.getMajorTag() : p.getMajorTagSnapshot();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.getId());
            item.put("title", p.getTitle());
            item.put("content", p.getContent());
            item.put("likeCount", p.getLikeCount() == null ? 0 : p.getLikeCount());
            item.put("liked", likedSet.contains(p.getId()));
            item.put("createdAt", p.getCreatedAt());
            item.put("updatedAt", p.getUpdatedAt());
            item.put("isOwn", author != null && author.getId().equals(me.getId()));

            Map<String, Object> authorDto = new LinkedHashMap<>();
            if (author != null) {
                authorDto.put("id", author.getId());
                authorDto.put("username", author.getUsername());
                authorDto.put("avatarUrl", author.getAvatarUrl());
            } else {
                authorDto.put("id", null);
                authorDto.put("username", "(已注销用户)");
                authorDto.put("avatarUrl", null);
            }
            authorDto.put("schoolName", nameOf(tagMap, schoolTag));
            authorDto.put("schoolDescription", descOf(tagMap, schoolTag));
            authorDto.put("collegeName", nameOf(tagMap, collegeTag));
            authorDto.put("majorName", nameOf(tagMap, majorTag));
            item.put("author", authorDto);

            out.add(item);
        }
        return out;
    }

    private static String nameOf(Map<String, OrganizationTag> m, String tagId) {
        if (tagId == null) return null;
        OrganizationTag t = m.get(tagId);
        return t == null ? null : t.getName();
    }

    private static String descOf(Map<String, OrganizationTag> m, String tagId) {
        if (tagId == null) return null;
        OrganizationTag t = m.get(tagId);
        return t == null ? null : t.getDescription();
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
    }

    private String validateTitle(String title) {
        if (title == null) throw new CustomException("标题不能为空", HttpStatus.BAD_REQUEST);
        String t = title.trim();
        if (t.isEmpty()) throw new CustomException("标题不能为空", HttpStatus.BAD_REQUEST);
        if (t.length() > MAX_TITLE_LEN) throw new CustomException("标题过长（上限 " + MAX_TITLE_LEN + " 字）", HttpStatus.BAD_REQUEST);
        return t;
    }

    private String validateContent(String content) {
        if (content == null) throw new CustomException("内容不能为空", HttpStatus.BAD_REQUEST);
        String c = content.trim();
        if (c.isEmpty()) throw new CustomException("内容不能为空", HttpStatus.BAD_REQUEST);
        if (c.length() > MAX_CONTENT_LEN) throw new CustomException("内容过长（上限 " + MAX_CONTENT_LEN + " 字）", HttpStatus.BAD_REQUEST);
        return c;
    }

    private Map<String, Object> emptyPage(int page, int size) {
        Map<String, Object> m = new HashMap<>();
        m.put("total", 0L);
        m.put("page", page);
        m.put("size", size);
        m.put("items", List.of());
        return m;
    }
}
