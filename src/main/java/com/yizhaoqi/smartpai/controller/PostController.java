package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.Post;
import com.yizhaoqi.smartpai.service.PostService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/posts")
public class PostController {

    @Autowired
    private PostService postService;

    @Autowired
    private JwtUtils jwtUtils;

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader("Authorization") String token,
                                  @RequestParam(name = "filter", defaultValue = "all") String filter,
                                  @RequestParam(name = "page", defaultValue = "0") int page,
                                  @RequestParam(name = "size", defaultValue = "20") int size) {
        String username = extractUsername(token);
        try {
            return ok(postService.listPosts(username, filter, page, size));
        } catch (CustomException e) {
            return err(e);
        } catch (Exception e) {
            LogUtils.logBusinessError("LIST_POSTS", username, "查询帖子异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "查询失败: " + e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader("Authorization") String token,
                                    @RequestBody PostUpsertRequest request) {
        String username = extractUsername(token);
        try {
            Post saved = postService.createPost(username, request.title(), request.content());
            LogUtils.logUserOperation(username, "CREATE_POST", String.valueOf(saved.getId()), "SUCCESS");
            return ok(Map.of("id", saved.getId()));
        } catch (CustomException e) {
            return err(e);
        } catch (Exception e) {
            LogUtils.logBusinessError("CREATE_POST", username, "发帖异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "发布失败: " + e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@RequestHeader("Authorization") String token,
                                    @PathVariable("id") Long id,
                                    @RequestBody PostUpsertRequest request) {
        String username = extractUsername(token);
        try {
            postService.updatePost(username, id, request.title(), request.content());
            return ok(Map.of());
        } catch (CustomException e) {
            return err(e);
        } catch (Exception e) {
            LogUtils.logBusinessError("UPDATE_POST", username, "编辑帖子异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "编辑失败: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@RequestHeader("Authorization") String token,
                                    @PathVariable("id") Long id) {
        String username = extractUsername(token);
        try {
            postService.deletePost(username, id);
            return ok(Map.of());
        } catch (CustomException e) {
            return err(e);
        } catch (Exception e) {
            LogUtils.logBusinessError("DELETE_POST", username, "删除帖子异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "删除失败: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/like")
    public ResponseEntity<?> toggleLike(@RequestHeader("Authorization") String token,
                                        @PathVariable("id") Long id) {
        String username = extractUsername(token);
        try {
            return ok(postService.toggleLike(username, id));
        } catch (CustomException e) {
            return err(e);
        } catch (Exception e) {
            LogUtils.logBusinessError("LIKE_POST", username, "点赞异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "操作失败: " + e.getMessage()));
        }
    }

    private String extractUsername(String token) {
        if (token == null || !token.startsWith("Bearer ")) return null;
        try {
            return jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private ResponseEntity<?> ok(Object data) {
        return ResponseEntity.ok(Map.of("code", 200, "message", "ok", "data", data));
    }

    private ResponseEntity<?> err(CustomException e) {
        return ResponseEntity.status(e.getStatus())
                .body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
    }
}

record PostUpsertRequest(String title, String content) {}
