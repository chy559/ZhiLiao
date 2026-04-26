package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.AvatarService;
import com.yizhaoqi.smartpai.service.UserService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.LogUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AvatarService avatarService;

    // 用户注册接口
    // 新用户必须使用 邮箱 + 验证码 注册；为了向后兼容，如果请求里带了 email+emailCode 就走新流程。
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody UserRequest request) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("USER_REGISTER");
        try {
            if (request.username() == null || request.username().isEmpty() ||
                    request.password() == null || request.password().isEmpty()) {
                LogUtils.logUserOperation("anonymous", "REGISTER", "validation", "FAILED_EMPTY_PARAMS");
                monitor.end("注册失败：参数为空");
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Username and password cannot be empty"));
            }
            if (request.email() == null || request.email().isEmpty() ||
                    request.emailCode() == null || request.emailCode().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "请先获取并输入邮箱验证码"));
            }

            userService.registerUserWithEmail(request.username(), request.password(), request.email(), request.emailCode());
            LogUtils.logUserOperation(request.username(), "REGISTER", "user_creation", "SUCCESS");
            monitor.end("注册成功");

            return ResponseEntity.ok(Map.of("code", 200, "message", "User registered successfully"));
        } catch (CustomException e) {
            LogUtils.logBusinessError("USER_REGISTER", request.username(), "用户注册失败: %s", e, e.getMessage());
            monitor.end("注册失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("USER_REGISTER", request.username(), "用户注册异常: %s", e, e.getMessage());
            monitor.end("注册异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "Internal server error"));
        }
    }

    // 用户登录接口
    // 如果 request.username 看起来像邮箱（含 @），则按邮箱登录；否则按用户名登录。前端不必区分。
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody UserRequest request) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("USER_LOGIN");
        try {
            if (request.username() == null || request.username().isEmpty() ||
                    request.password() == null || request.password().isEmpty()) {
                LogUtils.logUserOperation("anonymous", "LOGIN", "validation", "FAILED_EMPTY_PARAMS");
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Username and password cannot be empty"));
            }

            String identifier = request.username().trim();
            String username = identifier.contains("@")
                    ? userService.authenticateByEmail(identifier, request.password())
                    : userService.authenticateUser(identifier, request.password());
            if (username == null) {
                LogUtils.logUserOperation(request.username(), "LOGIN", "authentication", "FAILED_INVALID_CREDENTIALS");
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "Invalid credentials"));
            }
            
            String token = jwtUtils.generateToken(username);
            String refreshToken = jwtUtils.generateRefreshToken(username);
            LogUtils.logUserOperation(username, "LOGIN", "token_generation", "SUCCESS");
            monitor.end("登录成功");
            
            return ResponseEntity.ok(Map.of("code", 200, "message", "Login successful", "data", Map.of(
                "token", token,
                "refreshToken", refreshToken
            )));
        } catch (CustomException e) {
            LogUtils.logBusinessError("USER_LOGIN", request.username(), "登录失败: %s", e, e.getMessage());
            monitor.end("登录失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("USER_LOGIN", request.username(), "登录异常: %s", e, e.getMessage());
            monitor.end("登录异常: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("code", 500, "message", "Internal server error"));
        }
    }

    // 获取当前用户信息
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_USER_INFO");
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "GET_USER_INFO", "token_validation", "FAILED_INVALID_TOKEN");
                monitor.end("获取用户信息失败：无效token");
                throw new CustomException("Invalid token", HttpStatus.UNAUTHORIZED);
            }

            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));

            // 手动构建返回对象，不包含 password 字段
            Map<String, Object> displayUserData = new LinkedHashMap<>();
            displayUserData.put("id", user.getId());
            displayUserData.put("username", user.getUsername());
            displayUserData.put("role", user.getRole());
            
            // 添加组织标签信息
            if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
                List<String> orgTagsList = Arrays.asList(user.getOrgTags().split(","));
                displayUserData.put("orgTags", orgTagsList);
            } else {
                displayUserData.put("orgTags", List.of());
            }
            
            // 添加主组织标签信息
            displayUserData.put("primaryOrg", user.getPrimaryOrg());

            // 头像 URL（指向公开桶，浏览器直读，无需后端转发）
            displayUserData.put("avatarUrl", user.getAvatarUrl());

            // 邮箱 & 强制绑定标记：历史用户（email 为空）前端需引导其绑定
            displayUserData.put("email", user.getEmail());
            displayUserData.put("requireEmailBind", user.getEmail() == null || user.getEmail().isBlank());

            // 学校 / 学院 / 专业（用于个人中心的选择器回显）
            displayUserData.put("schoolTag", user.getSchoolTag());
            displayUserData.put("collegeTag", user.getCollegeTag());
            displayUserData.put("majorTag", user.getMajorTag());

            displayUserData.put("createdAt", user.getCreatedAt());
            displayUserData.put("updatedAt", user.getUpdatedAt());

            LogUtils.logUserOperation(username, "GET_USER_INFO", "user_profile", "SUCCESS");
            monitor.end("获取用户信息成功");

            // 返回响应
            return ResponseEntity.ok(Map.of("code", 200, "message", "Get user detail successful", "data", displayUserData));
        } catch (CustomException e) {
            LogUtils.logBusinessError("GET_USER_INFO", username, "获取用户信息失败: %s", e, e.getMessage());
            monitor.end("获取用户信息失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_USER_INFO", username, "获取用户信息异常: %s", e, e.getMessage());
            monitor.end("获取用户信息异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "Internal server error"));
        }
    }
    
    // 获取用户组织标签信息
    @GetMapping("/org-tags")
    public ResponseEntity<?> getUserOrgTags(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_USER_ORG_TAGS");
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "GET_ORG_TAGS", "token_validation", "FAILED_INVALID_TOKEN");
                monitor.end("获取组织标签失败：无效token");
                throw new CustomException("Invalid token", HttpStatus.UNAUTHORIZED);
            }
            
            Map<String, Object> orgTagsInfo = userService.getUserOrgTags(username);
            
            LogUtils.logUserOperation(username, "GET_ORG_TAGS", "organization_tags", "SUCCESS");
            monitor.end("获取组织标签成功");
            
            return ResponseEntity.ok(Map.of(
                "code", 200, 
                "message", "Get user organization tags successful", 
                "data", orgTagsInfo
            ));
        } catch (CustomException e) {
            LogUtils.logBusinessError("GET_USER_ORG_TAGS", username, "获取用户组织标签失败: %s", e, e.getMessage());
            monitor.end("获取组织标签失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_USER_ORG_TAGS", username, "获取用户组织标签异常: %s", e, e.getMessage());
            monitor.end("获取组织标签异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "Internal server error"));
        }
    }
    
    // 设置用户主组织标签
    @PutMapping("/primary-org")
    public ResponseEntity<?> setPrimaryOrg(@RequestHeader("Authorization") String token, @RequestBody PrimaryOrgRequest request) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("SET_PRIMARY_ORG");
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "SET_PRIMARY_ORG", "token_validation", "FAILED_INVALID_TOKEN");
                monitor.end("设置主组织失败：无效token");
                throw new CustomException("Invalid token", HttpStatus.UNAUTHORIZED);
            }
            
            if (request.primaryOrg() == null || request.primaryOrg().isEmpty()) {
                LogUtils.logUserOperation(username, "SET_PRIMARY_ORG", "validation", "FAILED_EMPTY_ORG");
                monitor.end("设置主组织失败：组织标签为空");
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Primary organization tag cannot be empty"));
            }
            
            userService.setUserPrimaryOrg(username, request.primaryOrg());
            
            LogUtils.logUserOperation(username, "SET_PRIMARY_ORG", request.primaryOrg(), "SUCCESS");
            monitor.end("设置主组织成功");
            
            return ResponseEntity.ok(Map.of("code", 200, "message", "Primary organization set successfully"));
        } catch (CustomException e) {
            LogUtils.logBusinessError("SET_PRIMARY_ORG", username, "设置主组织失败: %s", e, e.getMessage());
            monitor.end("设置主组织失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("SET_PRIMARY_ORG", username, "设置主组织异常: %s", e, e.getMessage());
            monitor.end("设置主组织异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "Internal server error"));
        }
    }

    // 获取当前用户组织标签信息 (供上传文件时使用)
    @GetMapping("/upload-orgs")
    public ResponseEntity<?> getUploadOrgTags(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_UPLOAD_ORG_TAGS");
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "GET_UPLOAD_ORG_TAGS", "token_validation", "FAILED_INVALID_TOKEN");
                monitor.end("获取上传组织标签失败：无效token");
                throw new CustomException("Invalid token", HttpStatus.UNAUTHORIZED);
            }

            LogUtils.logBusiness("GET_UPLOAD_ORG_TAGS", username, "获取用户上传组织标签信息");
            
            Map<String, Object> responseData = userService.getUploadOrgTags(username);
            
            LogUtils.logUserOperation(username, "GET_UPLOAD_ORG_TAGS", "upload_organizations", "SUCCESS");
            monitor.end("获取上传组织标签成功");
            
            return ResponseEntity.ok(Map.of(
                "code", 200, 
                "message", "获取用户上传组织标签成功", 
                "data", responseData
            ));
        } catch (CustomException e) {
            LogUtils.logBusinessError("GET_UPLOAD_ORG_TAGS", username, "获取用户上传组织标签失败: %s", e, e.getMessage());
            monitor.end("获取上传组织标签失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of(
                "code", e.getStatus().value(),
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_UPLOAD_ORG_TAGS", username, "获取用户上传组织标签失败: %s", e, e.getMessage());
            monitor.end("获取上传组织标签失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", 500, 
                "message", "获取用户上传组织标签失败: " + e.getMessage()
            ));
        }
    }

    // 修改昵称（用户名）。同时会更新该用户私人组织标签的显示名。
    // 因为 JWT 的 subject 是旧用户名，改完后必须重新签发 token 并把旧 token 失效。
    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestHeader("Authorization") String token,
                                           @RequestBody ProfileUpdateRequest request) {
        String currentUsername = null;
        try {
            String jwt = token.replace("Bearer ", "");
            currentUsername = jwtUtils.extractUsernameFromToken(jwt);
            if (currentUsername == null || currentUsername.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "Invalid token"));
            }

            String newUsername = userService.updateUsername(currentUsername, request.newUsername());

            // 让旧 token 失效，并签发新 token（subject 变成新用户名）
            jwtUtils.invalidateToken(jwt);
            String newToken = jwtUtils.generateToken(newUsername);
            String newRefresh = jwtUtils.generateRefreshToken(newUsername);

            LogUtils.logUserOperation(currentUsername, "UPDATE_USERNAME", newUsername, "SUCCESS");
            return ResponseEntity.ok(Map.of("code", 200, "message", "昵称修改成功",
                    "data", Map.of("username", newUsername, "token", newToken, "refreshToken", newRefresh)));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("UPDATE_USERNAME", currentUsername, "修改昵称异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "修改失败: " + e.getMessage()));
        }
    }

    // 修改密码。校验旧密码。
    @PutMapping("/password")
    public ResponseEntity<?> updatePassword(@RequestHeader("Authorization") String token,
                                            @RequestBody PasswordUpdateRequest request) {
        String username = null;
        try {
            String jwt = token.replace("Bearer ", "");
            username = jwtUtils.extractUsernameFromToken(jwt);
            if (username == null || username.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "Invalid token"));
            }
            userService.updatePassword(username, request.oldPassword(), request.newPassword());
            LogUtils.logUserOperation(username, "UPDATE_PASSWORD", "password", "SUCCESS");
            return ResponseEntity.ok(Map.of("code", 200, "message", "密码修改成功"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("UPDATE_PASSWORD", username, "修改密码异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "修改失败: " + e.getMessage()));
        }
    }

    // 上传用户头像 - 仅签发到公开桶，路径固定，前端可直接用 URL 显示
    // 注意：OrgTagAuthorizationFilter 不会为该路径写入 userId 属性，所以这里直接从 Authorization 头解析。
    @PostMapping("/avatar")
    public ResponseEntity<?> uploadAvatar(@RequestHeader("Authorization") String token,
                                          @RequestParam("file") MultipartFile file) {
        String userId = null;
        try {
            String jwt = token.replace("Bearer ", "");
            userId = jwtUtils.extractUserIdFromToken(jwt);
            if (userId == null || userId.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "Invalid token"));
            }
            String url = avatarService.uploadAvatar(userId, file);
            LogUtils.logUserOperation(userId, "UPLOAD_AVATAR", "avatar", "SUCCESS");
            return ResponseEntity.ok(Map.of("code", 200, "message", "上传成功",
                    "data", Map.of("avatarUrl", url)));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("UPLOAD_AVATAR", userId, "上传头像异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "上传失败: " + e.getMessage()));
        }
    }

    // 发送邮箱验证码 (scene: register / reset / bind)
    @PostMapping("/send-email-code")
    public ResponseEntity<?> sendEmailCode(@RequestBody SendEmailCodeRequest request,
                                           @RequestHeader(value = "Authorization", required = false) String token) {
        try {
            String scene = request.scene() == null ? "" : request.scene();
            String currentUsername = null;
            if (token != null && token.startsWith("Bearer ")) {
                try {
                    currentUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
                } catch (Exception ignore) {
                    // 无效 token 不影响发送注册/重置验证码
                }
            }
            // bind 场景必须已登录
            if ("bind".equals(scene) && (currentUsername == null || currentUsername.isEmpty())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "请先登录"));
            }
            userService.sendVerificationCode(request.email(), scene, currentUsername);
            return ResponseEntity.ok(Map.of("code", 200, "message", "验证码已发送，请查收"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("SEND_EMAIL_CODE", request.email(), "发送邮箱验证码异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "发送失败: " + e.getMessage()));
        }
    }

    // 忘记密码：通过邮箱 + 验证码 重置
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {
        try {
            userService.resetPasswordByEmail(request.email(), request.code(), request.newPassword());
            return ResponseEntity.ok(Map.of("code", 200, "message", "密码重置成功，请用新密码登录"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("RESET_PASSWORD", request.email(), "重置密码异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "重置失败: " + e.getMessage()));
        }
    }

    // 已登录用户绑定邮箱（老用户强制绑定走这个）
    @PostMapping("/bind-email")
    public ResponseEntity<?> bindEmail(@RequestHeader("Authorization") String token,
                                       @RequestBody BindEmailRequest request) {
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "Invalid token"));
            }
            userService.bindEmail(username, request.email(), request.code());
            return ResponseEntity.ok(Map.of("code", 200, "message", "邮箱绑定成功"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("BIND_EMAIL", username, "绑定邮箱异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "绑定失败: " + e.getMessage()));
        }
    }

    // 学校+学院级联列表，供个人中心选择器使用。登录用户可读。
    @GetMapping("/schools-and-colleges")
    public ResponseEntity<?> getSchoolsAndColleges() {
        try {
            return ResponseEntity.ok(Map.of("code", 200, "message", "ok",
                    "data", userService.getSchoolsAndColleges()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "获取失败: " + e.getMessage()));
        }
    }

    // 专业字典
    @GetMapping("/majors")
    public ResponseEntity<?> getMajors() {
        try {
            return ResponseEntity.ok(Map.of("code", 200, "message", "ok",
                    "data", userService.getMajors()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "获取失败: " + e.getMessage()));
        }
    }

    // 当前用户设置自己的专业，空串/null 清除
    @PutMapping("/major")
    public ResponseEntity<?> setMajor(@RequestHeader("Authorization") String token,
                                      @RequestBody MajorRequest request) {
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "Invalid token"));
            }
            userService.updateUserMajor(username, request.majorTag());
            return ResponseEntity.ok(Map.of("code", 200, "message", "已保存"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("UPDATE_MAJOR", username, "保存专业异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "保存失败: " + e.getMessage()));
        }
    }

    // 当前用户设置自己的学校/学院。两者都可为空字符串表示清除。
    @PutMapping("/school-college")
    public ResponseEntity<?> setSchoolCollege(@RequestHeader("Authorization") String token,
                                              @RequestBody SchoolCollegeRequest request) {
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "Invalid token"));
            }
            userService.updateUserSchoolCollege(username, request.schoolTag(), request.collegeTag());
            return ResponseEntity.ok(Map.of("code", 200, "message", "已保存"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("UPDATE_SCHOOL_COLLEGE", username, "保存学校学院异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "保存失败: " + e.getMessage()));
        }
    }

    // 注销账号：彻底删除当前用户及其所有资源；管理员不允许调用。
    // 成功后旧 token 全部被失效，前端需清本地 token 并回到登录页。
    @DeleteMapping("/me")
    public ResponseEntity<?> deleteAccount(@RequestHeader("Authorization") String token) {
        String username = null;
        try {
            username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            if (username == null || username.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "Invalid token"));
            }
            userService.deleteAccount(username);
            LogUtils.logUserOperation(username, "DELETE_ACCOUNT", "account", "SUCCESS");
            return ResponseEntity.ok(Map.of("code", 200, "message", "账号已注销"));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("DELETE_ACCOUNT", username, "注销账号异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "注销失败: " + e.getMessage()));
        }
    }

    // 用户登出接口
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("USER_LOGOUT");
        String username = null;
        try {
            if (token == null || !token.startsWith("Bearer ")) {
                LogUtils.logUserOperation("anonymous", "LOGOUT", "validation", "FAILED_INVALID_TOKEN");
                monitor.end("登出失败：token格式无效");
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Invalid token format"));
            }

            String jwtToken = token.replace("Bearer ", "");
            username = jwtUtils.extractUsernameFromToken(jwtToken);
            
            if (username == null || username.isEmpty()) {
                LogUtils.logUserOperation("anonymous", "LOGOUT", "token_extraction", "FAILED_NO_USERNAME");
                monitor.end("登出失败：无法提取用户名");
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "Invalid token"));
            }

            // 使当前token失效:加入黑名单 remove出缓存
            jwtUtils.invalidateToken(jwtToken);
            
            LogUtils.logUserOperation(username, "LOGOUT", "token_invalidation", "SUCCESS");
            monitor.end("登出成功");

            return ResponseEntity.ok(Map.of("code", 200, "message", "Logout successful"));
        } catch (Exception e) {
            LogUtils.logBusinessError("USER_LOGOUT", username, "登出异常: %s", e, e.getMessage());
            monitor.end("登出异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "Internal server error"));
        }
    }

    // 用户批量登出接口（登出所有设备）
    @PostMapping("/logout-all")
    public ResponseEntity<?> logoutAll(@RequestHeader("Authorization") String token) {
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("USER_LOGOUT_ALL");
        String username = null;
        try {
            if (token == null || !token.startsWith("Bearer ")) {
                LogUtils.logUserOperation("anonymous", "LOGOUT_ALL", "validation", "FAILED_INVALID_TOKEN");
                monitor.end("批量登出失败：token格式无效");
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Invalid token format"));
            }

            String jwtToken = token.replace("Bearer ", "");
            username = jwtUtils.extractUsernameFromToken(jwtToken);
            String userId = jwtUtils.extractUserIdFromToken(jwtToken);
            
            if (username == null || username.isEmpty() || userId == null) {
                LogUtils.logUserOperation("anonymous", "LOGOUT_ALL", "token_extraction", "FAILED_NO_USER_INFO");
                monitor.end("批量登出失败：无法提取用户信息");
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "Invalid token"));
            }

            // 使用户所有token失效
            jwtUtils.invalidateAllUserTokens(userId);
            
            LogUtils.logUserOperation(username, "LOGOUT_ALL", "all_tokens_invalidation", "SUCCESS");
            monitor.end("批量登出成功");

            return ResponseEntity.ok(Map.of("code", 200, "message", "Logout from all devices successful"));
        } catch (Exception e) {
            LogUtils.logBusinessError("USER_LOGOUT_ALL", username, "批量登出异常: %s", e, e.getMessage());
            monitor.end("批量登出异常: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code", 500, "message", "Internal server error"));
        }
    }
}

// 用户请求记录类(这种写法比写dto简单):但是数据都是final，get方法的名字就叫变量而不是getO
// 新增 email / emailCode：注册时必填；登录时忽略。
record UserRequest(String username, String password, String email, String emailCode) {}

// 主组织标签请求记录类
record PrimaryOrgRequest(String primaryOrg) {}

// 修改昵称请求体
record ProfileUpdateRequest(String newUsername) {}

// 修改密码请求体
record PasswordUpdateRequest(String oldPassword, String newPassword) {}

// 发送邮箱验证码请求体。scene = register | reset | bind
record SendEmailCodeRequest(String email, String scene) {}

// 忘记密码 - 重置密码
record ResetPasswordRequest(String email, String code, String newPassword) {}

// 绑定邮箱
record BindEmailRequest(String email, String code) {}

// 设置学校/学院
record SchoolCollegeRequest(String schoolTag, String collegeTag) {}

// 设置专业
record MajorRequest(String majorTag) {}
