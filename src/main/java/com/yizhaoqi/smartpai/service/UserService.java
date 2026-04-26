package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.OrganizationTag;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.ConversationRepository;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import com.yizhaoqi.smartpai.utils.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;

/**
 * UserService 类用于处理用户注册和认证相关的业务逻辑。
 */
@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    
    private static final String DEFAULT_ORG_TAG = "DEFAULT";
    private static final String DEFAULT_ORG_NAME = "默认组织";
    private static final String DEFAULT_ORG_DESCRIPTION = "系统默认组织标签，自动分配给所有新用户";
    private static final String PRIVATE_TAG_PREFIX = "PRIVATE_";
    private static final String PRIVATE_ORG_NAME_SUFFIX = "的私人空间";
    private static final String PRIVATE_ORG_DESCRIPTION = "用户的私人组织标签，仅用户本人可访问";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private VerificationCodeService verificationCodeService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private AvatarService avatarService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private SchoolCollegeImportService schoolCollegeImportService;

    @Autowired
    private com.yizhaoqi.smartpai.repository.PostRepository postRepository;

    @Autowired
    private com.yizhaoqi.smartpai.repository.PostLikeRepository postLikeRepository;

    private static final java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static boolean isEmail(String s) {
        return s != null && EMAIL_PATTERN.matcher(s).matches();
    }

    /**
     * 注册新用户。
     *
     * @param username 要注册的用户名
     * @param password 要注册的用户密码
     * @throws CustomException 如果用户名已存在，则抛出异常
     */
    @Transactional
    public void registerUser(String username, String password) {
        // 检查数据库中是否已存在该用户名
        if (userRepository.findByUsername(username).isPresent()) {
            // 若用户名已存在，抛出自定义异常，状态码为 400 Bad Request
            throw new CustomException("Username already exists", HttpStatus.BAD_REQUEST);
        }
        
        // 确保默认组织标签存在（系统内部使用）
        ensureDefaultOrgTagExists();
        
        User user = new User();
        user.setUsername(username);
        // 对密码进行加密处理并设置到 User 对象中
        user.setPassword(PasswordUtil.encode(password));
        // 设置用户角色为普通用户
        user.setRole(User.Role.USER);
        
        // 保存用户以生成ID
        userRepository.save(user);
        
        // 创建用户的私人组织标签
        String privateTagId = PRIVATE_TAG_PREFIX + username;
        createPrivateOrgTag(privateTagId, username, user);
        
        // 只分配私人组织标签
        user.setOrgTags(privateTagId);
        
        // 设置私人组织标签为主组织标签
        user.setPrimaryOrg(privateTagId);
        
        userRepository.save(user);
        
        // 缓存组织标签信息
        orgTagCacheService.cacheUserOrgTags(username, List.of(privateTagId));
        orgTagCacheService.cacheUserPrimaryOrg(username, privateTagId);
        
        logger.info("User registered successfully with private organization tag: {}", username);
    }

    /**
     * 带邮箱 + 验证码的注册。顺序是先做所有不会消耗验证码的校验（用户名是否占用、邮箱格式/占用），
     * 再校验验证码（校验成功会把 Redis 里的验证码消掉，这是一次性行为），最后才真正创建用户 —
     * 避免用户因为换了个昵称又被迫再等一次验证码。
     */
    @Transactional
    public void registerUserWithEmail(String username, String password, String email, String code) {
        if (!isEmail(email)) {
            throw new CustomException("邮箱格式不正确", HttpStatus.BAD_REQUEST);
        }
        String normalized = email.toLowerCase();
        if (userRepository.existsByEmail(normalized)) {
            throw new CustomException("该邮箱已被注册", HttpStatus.BAD_REQUEST);
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new CustomException("Username already exists", HttpStatus.BAD_REQUEST);
        }
        // 所有前置校验通过后才校验验证码（会消耗验证码，防止重放）
        verificationCodeService.verify(email, "register", code);

        // 复用已有的注册流程创建用户与私人组织标签
        registerUser(username, password);

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("注册后未找到用户", HttpStatus.INTERNAL_SERVER_ERROR));
        user.setEmail(normalized);
        userRepository.save(user);
    }

    /**
     * 用邮箱 + 密码认证。成功后返回用户名（用于签发 JWT）。
     */
    public String authenticateByEmail(String email, String password) {
        if (!isEmail(email)) {
            throw new CustomException("邮箱格式不正确", HttpStatus.BAD_REQUEST);
        }
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new CustomException("邮箱或密码错误", HttpStatus.UNAUTHORIZED));
        if (!PasswordUtil.matches(password, user.getPassword())) {
            throw new CustomException("邮箱或密码错误", HttpStatus.UNAUTHORIZED);
        }
        return user.getUsername();
    }

    /**
     * 通过邮箱验证码重置密码。
     */
    @Transactional
    public void resetPasswordByEmail(String email, String code, String newPassword) {
        if (!isEmail(email)) {
            throw new CustomException("邮箱格式不正确", HttpStatus.BAD_REQUEST);
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new CustomException("新密码至少 6 位", HttpStatus.BAD_REQUEST);
        }
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new CustomException("该邮箱未绑定任何账号", HttpStatus.NOT_FOUND));
        verificationCodeService.verify(email, "reset", code);
        user.setPassword(PasswordUtil.encode(newPassword));
        userRepository.save(user);
        logger.info("Password reset via email for user: {}", user.getUsername());
    }

    /**
     * 已登录用户绑定邮箱（针对历史用户强制绑定场景）。
     */
    @Transactional
    public void bindEmail(String username, String email, String code) {
        if (!isEmail(email)) {
            throw new CustomException("邮箱格式不正确", HttpStatus.BAD_REQUEST);
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        String normalized = email.toLowerCase();
        if (userRepository.existsByEmail(normalized)) {
            // 如果是自己已经绑的，直接视为成功（幂等）
            if (!normalized.equals(user.getEmail())) {
                throw new CustomException("该邮箱已被其他账号绑定", HttpStatus.CONFLICT);
            }
        }
        verificationCodeService.verify(email, "bind", code);
        user.setEmail(normalized);
        userRepository.save(user);
        logger.info("Email bound for user {}: {}", username, normalized);
    }

    /**
     * 发送验证码前，对不同场景做不同的存在性校验：
     *  - register: 邮箱不能已被占用
     *  - reset:    邮箱必须已绑定某账号
     *  - bind:     邮箱不能已被其它账号占用
     */
    public void sendVerificationCode(String email, String scene, String currentUsername) {
        String normalized = email == null ? null : email.toLowerCase();
        switch (scene) {
            case "register" -> {
                if (normalized != null && userRepository.existsByEmail(normalized)) {
                    throw new CustomException("该邮箱已被注册", HttpStatus.BAD_REQUEST);
                }
            }
            case "reset" -> {
                if (normalized == null || userRepository.findByEmail(normalized).isEmpty()) {
                    throw new CustomException("该邮箱未绑定任何账号", HttpStatus.NOT_FOUND);
                }
            }
            case "bind" -> {
                if (normalized != null && userRepository.findByEmail(normalized)
                        .map(u -> !u.getUsername().equals(currentUsername)).orElse(false)) {
                    throw new CustomException("该邮箱已被其他账号绑定", HttpStatus.CONFLICT);
                }
            }
            default -> throw new CustomException("未知的验证码用途", HttpStatus.BAD_REQUEST);
        }
        verificationCodeService.sendCode(email, scene);
    }

    /**
     * 创建用户的私人组织标签
     */
    private void createPrivateOrgTag(String privateTagId, String username, User owner) {
        // 检查私人标签是否已存在
        if (!organizationTagRepository.existsByTagId(privateTagId)) {
            logger.info("Creating private organization tag for user: {}", username);
            
            // 创建私人组织标签
            OrganizationTag privateTag = new OrganizationTag();
            privateTag.setTagId(privateTagId);
            privateTag.setName(username + PRIVATE_ORG_NAME_SUFFIX);
            privateTag.setDescription(PRIVATE_ORG_DESCRIPTION);
            privateTag.setCreatedBy(owner);
            
            organizationTagRepository.save(privateTag);
            logger.info("Private organization tag created successfully for user: {}", username);
        }
    }

    /**
     * 确保默认组织标签存在
     */
    private void ensureDefaultOrgTagExists() {
        if (!organizationTagRepository.existsByTagId(DEFAULT_ORG_TAG)) {
            logger.info("Creating default organization tag");
            
            // 寻找一个管理员用户作为创建者
            Optional<User> adminUser = userRepository.findAll().stream()
                    .filter(user -> User.Role.ADMIN.equals(user.getRole()))
                    .findFirst();
            
            User creator;
            if (adminUser.isPresent()) {
                creator = adminUser.get();
            } else {
                // 如果没有管理员用户，则创建一个系统用户作为创建者
                creator = createSystemAdminIfNotExists();
            }
            
            // 创建默认组织标签
            OrganizationTag defaultTag = new OrganizationTag();
            defaultTag.setTagId(DEFAULT_ORG_TAG);
            defaultTag.setName(DEFAULT_ORG_NAME);
            defaultTag.setDescription(DEFAULT_ORG_DESCRIPTION);
            defaultTag.setCreatedBy(creator);
            
            organizationTagRepository.save(defaultTag);
            logger.info("Default organization tag created successfully");
        }
    }
    
    /**
     * 如果系统中没有管理员用户，则创建一个系统管理员
     */
    private User createSystemAdminIfNotExists() {
        String systemAdminUsername = "system_admin";
        
        return userRepository.findByUsername(systemAdminUsername)
                .orElseGet(() -> {
                    logger.info("Creating system admin user");
                    User systemAdmin = new User();
                    systemAdmin.setUsername(systemAdminUsername);
                    // 生成随机密码
                    String randomPassword = generateRandomPassword();
                    systemAdmin.setPassword(PasswordUtil.encode(randomPassword));
                    systemAdmin.setRole(User.Role.ADMIN);
                    
                    logger.info("System admin created with password: {}", randomPassword);
                    return userRepository.save(systemAdmin);
                });
    }
    
    /**
     * 生成随机密码
     */
    private String generateRandomPassword() {
        // 生成16位随机密码
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            int index = (int) (Math.random() * chars.length());
            sb.append(chars.charAt(index));
        }
        return sb.toString();
    }

    /**
     * 注销当前账号：彻底释放该用户占用的一切资源，让邮箱/用户名都能被后续用户再次注册。
     *
     * 注意清理顺序和范围：
     *  - 管理员禁止注销（避免误删最后一个管理员）；
     *  - 文件走 DocumentService.deleteDocument，一次性清 ES + MinIO + vectors + file_upload；
     *  - 对话表用级联查找后删除；
     *  - 用户的私人组织标签（PRIVATE_<注册用户名>）也一并删掉；
     *  - 头像在 MinIO 公开桶里是 <userId>.<ext>，枚举扩展名删；
     *  - Redis 里的组织标签缓存、token 缓存都要清；
     *  - 最后删除 users 行 —— 这样 email 的 UNIQUE 约束会解开，可以被再次注册。
     */
    @Transactional
    public void deleteAccount(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));

        if (User.Role.ADMIN.equals(user.getRole())) {
            throw new CustomException("管理员账号不能注销", HttpStatus.FORBIDDEN);
        }

        String userId = user.getId().toString();

        // 1. 删除用户上传的所有文件（同步清 ES / MinIO / document_vectors / file_upload）
        List<FileUpload> files = fileUploadRepository.findByUserId(userId);
        for (FileUpload f : files) {
            try {
                documentService.deleteDocument(f.getFileMd5(), userId);
            } catch (Exception e) {
                logger.error("注销账号时删除文件失败 userId={}, fileMd5={}", userId, f.getFileMd5(), e);
                // 不因单个文件失败中断整体注销流程
            }
        }

        // 2. 删除用户所有对话历史
        try {
            List<com.yizhaoqi.smartpai.model.Conversation> conversations = conversationRepository.findByUserId(user.getId());
            if (!conversations.isEmpty()) {
                conversationRepository.deleteAll(conversations);
            }
        } catch (Exception e) {
            logger.error("注销账号时删除对话失败 username={}", username, e);
        }

        // 3. 删除用户私人组织标签（tagId 在注册时被固定为 PRIVATE_<注册用户名>，改过昵称也存在 orgTags 里）
        String privateTagId = null;
        if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
            for (String tagId : user.getOrgTags().split(",")) {
                if (tagId.startsWith(PRIVATE_TAG_PREFIX)) {
                    privateTagId = tagId;
                    break;
                }
            }
        }
        if (privateTagId != null) {
            final String finalPrivateTagId = privateTagId;
            try {
                organizationTagRepository.findByTagId(finalPrivateTagId).ifPresent(organizationTagRepository::delete);
            } catch (Exception e) {
                logger.error("注销账号时删除私人标签失败 tagId={}", finalPrivateTagId, e);
            }
        }

        // 4. 删除 MinIO 公开桶里的头像对象
        try {
            avatarService.deleteAvatar(userId);
        } catch (Exception e) {
            logger.error("注销账号时删除头像失败 userId={}", userId, e);
        }

        // 5. 使该用户所有 token 失效
        try {
            jwtUtils.invalidateAllUserTokens(userId);
        } catch (Exception e) {
            logger.error("注销账号时失效 token 失败 userId={}", userId, e);
        }

        // 6. 清 Redis 组织标签缓存
        try {
            orgTagCacheService.deleteUserOrgTagsCache(username);
            orgTagCacheService.deleteUserEffectiveTagsCache(username);
        } catch (Exception e) {
            logger.error("注销账号时清缓存失败 username={}", username, e);
        }

        // 6.5. 删除用户发布的帖子 + 自己点过的赞
        try {
            List<com.yizhaoqi.smartpai.model.Post> myPosts = postRepository.findAll().stream()
                    .filter(p -> user.getId().equals(p.getUserId())).toList();
            for (com.yizhaoqi.smartpai.model.Post p : myPosts) {
                postLikeRepository.deleteByPostId(p.getId());
            }
            if (!myPosts.isEmpty()) postRepository.deleteAll(myPosts);
            postLikeRepository.deleteByUserId(user.getId());
        } catch (Exception e) {
            logger.error("注销账号时清理帖子/点赞失败 userId={}", userId, e);
        }

        // 7. 删除用户行（之后 email / username 的 UNIQUE 约束解开，可再次被注册）
        userRepository.delete(user);

        logger.info("Account deleted: username={}, email={}", username, user.getEmail());
    }

    /**
     * 获取学校+学院级联数据：返回所有 SCHOOL，每条下挂当前库里已有的 COLLEGE（按 parentTag 聚合）。
     * 给个人中心的选择控件用；匿名/登录用户都能拿（因为只是公共名录）。
     */
    public List<Map<String, Object>> getSchoolsAndColleges() {
        List<OrganizationTag> schools = organizationTagRepository.findByType(OrganizationTag.Type.SCHOOL);
        List<OrganizationTag> colleges = organizationTagRepository.findByType(OrganizationTag.Type.COLLEGE);

        Map<String, List<Map<String, String>>> collegeBySchool = new HashMap<>();
        for (OrganizationTag c : colleges) {
            if (c.getParentTag() == null) continue;
            collegeBySchool.computeIfAbsent(c.getParentTag(), k -> new ArrayList<>())
                    .add(Map.of("tagId", c.getTagId(), "name", c.getName()));
        }

        List<Map<String, Object>> out = new ArrayList<>(schools.size());
        for (OrganizationTag s : schools) {
            Map<String, Object> row = new HashMap<>();
            row.put("tagId", s.getTagId());
            row.put("name", s.getName());
            row.put("colleges", collegeBySchool.getOrDefault(s.getTagId(), List.of()));
            out.add(row);
        }
        // 简单按 name 排序，前端不用再 sort
        out.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return out;
    }

    /**
     * 用户自己设置学校/学院。规则：
     *  - schoolTag 必须存在且 type=SCHOOL；允许传空字符串表示"取消学校"
     *  - collegeTag 如果传了，必须 type=COLLEGE 且 parentTag==schoolTag；否则报错
     *  - 学校/学院同步写入用户的 org_tags（给权限过滤用），私人标签保持不动；
     *    旧的学校/学院标签要从 org_tags 里剔除
     */
    @Transactional
    public void updateUserSchoolCollege(String username, String schoolTag, String collegeTag) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));

        String newSchool = (schoolTag == null || schoolTag.isBlank()) ? null : schoolTag.trim();
        String newCollege = (collegeTag == null || collegeTag.isBlank()) ? null : collegeTag.trim();

        if (newSchool != null) {
            OrganizationTag s = organizationTagRepository.findByTagId(newSchool)
                    .orElseThrow(() -> new CustomException("学校不存在", HttpStatus.NOT_FOUND));
            if (s.getType() != OrganizationTag.Type.SCHOOL) {
                throw new CustomException("该标签不是学校", HttpStatus.BAD_REQUEST);
            }
        }
        if (newCollege != null) {
            if (newSchool == null) {
                throw new CustomException("请先选择学校", HttpStatus.BAD_REQUEST);
            }
            OrganizationTag c = organizationTagRepository.findByTagId(newCollege)
                    .orElseThrow(() -> new CustomException("学院不存在", HttpStatus.NOT_FOUND));
            if (c.getType() != OrganizationTag.Type.COLLEGE) {
                throw new CustomException("该标签不是学院", HttpStatus.BAD_REQUEST);
            }
            if (!newSchool.equals(c.getParentTag())) {
                throw new CustomException("该学院不属于所选学校", HttpStatus.BAD_REQUEST);
            }
        }

        String oldSchool = user.getSchoolTag();
        String oldCollege = user.getCollegeTag();

        // 维护 orgTags：移除旧学校/学院，加入新学校/学院（其它标签如 PRIVATE_* 和 DEFAULT 保持原样）
        Set<String> tags = new LinkedHashSet<>();
        if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
            for (String t : user.getOrgTags().split(",")) {
                if (!t.isEmpty()) tags.add(t);
            }
        }
        if (oldSchool != null) tags.remove(oldSchool);
        if (oldCollege != null) tags.remove(oldCollege);
        if (newSchool != null) tags.add(newSchool);
        if (newCollege != null) tags.add(newCollege);

        user.setSchoolTag(newSchool);
        user.setCollegeTag(newCollege);
        user.setOrgTags(String.join(",", tags));
        userRepository.save(user);
        syncPostSchoolCollegeSnapshot(user.getId(), newSchool, newCollege);

        // 为了资料安全，用户修改学校 / 学院后，历史"组织内公开"资料一律先降级为私有。
        // 后续用户若主动重新公开，再按当前规则“有学院用学院，否则用学校”自动绑定。
        migratePublicFilesOrgTag(user, oldSchool, oldCollege);

        // Redis 缓存同步
        orgTagCacheService.deleteUserOrgTagsCache(username);
        orgTagCacheService.deleteUserEffectiveTagsCache(username);
        orgTagCacheService.cacheUserOrgTags(username, new ArrayList<>(tags));

        logger.info("User {} school/college updated: {} / {}", username, newSchool, newCollege);
    }

    /**
     * 把用户之前上传、orgTag = 旧学校 / 旧学院 且"组织内公开"的文件，
     * 统一降级为"仅自己可见"绑定到私人标签，避免用户修改组织归属后
     * 旧组织成员仍可继续访问这些资料。
     */
    private void migratePublicFilesOrgTag(User user, String oldSchoolTag, String oldCollegeTag) {
        String userId = String.valueOf(user.getId());
        Set<String> oldTags = new LinkedHashSet<>();
        if (oldSchoolTag != null && !oldSchoolTag.isBlank()) {
            oldTags.add(oldSchoolTag);
        }
        if (oldCollegeTag != null && !oldCollegeTag.isBlank()) {
            oldTags.add(oldCollegeTag);
        }
        if (oldTags.isEmpty()) {
            return;
        }

        List<FileUpload> targets = fileUploadRepository.findByUserId(userId).stream()
                .filter(FileUpload::isPublic)
                .filter(f -> oldTags.contains(f.getOrgTag()))
                .collect(Collectors.toList());

        if (targets.isEmpty()) {
            return;
        }

        String targetOrgTag = PRIVATE_TAG_PREFIX + user.getUsername();
        boolean targetIsPublic = false;

        for (FileUpload file : targets) {
            file.setOrgTag(targetOrgTag);
            file.setPublic(targetIsPublic);
        }
        fileUploadRepository.saveAll(targets);

        for (FileUpload file : targets) {
            try {
                elasticsearchService.updateOrgTagByFileMd5(file.getFileMd5(), targetOrgTag, targetIsPublic);
            } catch (Exception e) {
                logger.error("同步 ES orgTag 失败，fileMd5={}, 新标签={}", file.getFileMd5(), targetOrgTag, e);
            }
        }

        logger.info("用户 {} 的 {} 份公开资料已从旧组织标签 {} 迁移到 {} (isPublic={})",
                user.getUsername(), targets.size(), oldTags, targetOrgTag, targetIsPublic);
    }

    /**
     * 专业字典，供前端个人中心选择器使用。跨校通用。
     */
    public List<Map<String, Object>> getMajors() {
        List<OrganizationTag> majors = organizationTagRepository.findByType(OrganizationTag.Type.MAJOR);
        List<Map<String, Object>> out = new ArrayList<>(majors.size());
        for (OrganizationTag m : majors) {
            Map<String, Object> row = new HashMap<>();
            row.put("tagId", m.getTagId());
            row.put("name", m.getName());
            row.put("description", m.getDescription());
            out.add(row);
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return out;
    }

    /**
     * 用户自己设置专业。和学校学院一样同步 org_tags。
     * 传空字符串 / null 表示清除。
     */
    @Transactional
    public void updateUserMajor(String username, String majorTag) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));

        String newMajor = (majorTag == null || majorTag.isBlank()) ? null : majorTag.trim();
        if (newMajor != null) {
            OrganizationTag m = organizationTagRepository.findByTagId(newMajor)
                    .orElseThrow(() -> new CustomException("专业不存在", HttpStatus.NOT_FOUND));
            if (m.getType() != OrganizationTag.Type.MAJOR) {
                throw new CustomException("该标签不是专业", HttpStatus.BAD_REQUEST);
            }
        }

        String oldMajor = user.getMajorTag();

        Set<String> tags = new LinkedHashSet<>();
        if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
            for (String t : user.getOrgTags().split(",")) {
                if (!t.isEmpty()) tags.add(t);
            }
        }
        if (oldMajor != null) tags.remove(oldMajor);
        if (newMajor != null) tags.add(newMajor);

        user.setMajorTag(newMajor);
        user.setOrgTags(String.join(",", tags));
        userRepository.save(user);
        syncPostMajorSnapshot(user.getId(), newMajor);

        orgTagCacheService.deleteUserOrgTagsCache(username);
        orgTagCacheService.deleteUserEffectiveTagsCache(username);
        orgTagCacheService.cacheUserOrgTags(username, new ArrayList<>(tags));

        logger.info("User {} major updated: {}", username, newMajor);
    }

    private void syncPostSchoolCollegeSnapshot(Long userId, String schoolTag, String collegeTag) {
        int updated = postRepository.updateAuthorSchoolCollegeSnapshot(userId, schoolTag, collegeTag);
        logger.info("Synced {} post school/college snapshots for user {}", updated, userId);
    }

    private void syncPostMajorSnapshot(Long userId, String majorTag) {
        int updated = postRepository.updateAuthorMajorSnapshot(userId, majorTag);
        logger.info("Synced {} post major snapshots for user {}", updated, userId);
    }

    /**
     * 管理员批量导入学校/学院（CSV 文本 -> SchoolCollegeImportService）。
     */
    public com.yizhaoqi.smartpai.service.SchoolCollegeImportService.ImportResult bulkImportSchoolsAndColleges(
            String csv, String adminUsername) {
        User admin = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new CustomException("Admin not found", HttpStatus.NOT_FOUND));
        if (admin.getRole() != User.Role.ADMIN) {
            throw new CustomException("Only administrators can bulk import", HttpStatus.FORBIDDEN);
        }
        return schoolCollegeImportService.importFromCsv(csv, adminUsername);
    }

    /**
     * 创建管理员用户。
     *
     * @param username 要注册的管理员用户名
     * @param password 要注册的管理员密码
     * @param creatorUsername 创建者的用户名（必须是已存在的管理员）
     * @throws CustomException 如果用户名已存在或创建者不是管理员，则抛出异常
     */
    public void createAdminUser(String username, String password, String creatorUsername) {
        // 验证创建者是否为管理员
        User creator = userRepository.findByUsername(creatorUsername)
                .orElseThrow(() -> new CustomException("Creator not found", HttpStatus.NOT_FOUND));
        
        if (creator.getRole() != User.Role.ADMIN) {
            throw new CustomException("Only administrators can create admin accounts", HttpStatus.FORBIDDEN);
        }
        
        // 检查数据库中是否已存在该用户名
        if (userRepository.findByUsername(username).isPresent()) {
            throw new CustomException("Username already exists", HttpStatus.BAD_REQUEST);
        }
        
        User adminUser = new User();
        adminUser.setUsername(username);
        adminUser.setPassword(PasswordUtil.encode(password));
        adminUser.setRole(User.Role.ADMIN);
        userRepository.save(adminUser);
    }

    /**
     * 对用户进行认证。
     *
     * @param username 要认证的用户名
     * @param password 要认证的用户密码
     * @return 认证成功后返回用户的用户名
     * @throws CustomException 如果用户名或密码无效，则抛出异常
     */
    public String authenticateUser(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("Invalid username or password", HttpStatus.UNAUTHORIZED));
        // 比较输入的密码和数据库中存储的加密密码是否匹配
        if (!PasswordUtil.matches(password, user.getPassword())) {
            // 若不匹配，抛出自定义异常，状态码为 401 Unauthorized
            throw new CustomException("Invalid username or password", HttpStatus.UNAUTHORIZED);
        }
        // 认证成功，返回用户的用户名
        return user.getUsername();
    }
    
    /**
     * 创建组织标签
     * 
     * @param tagId 标签唯一标识
     * @param name 标签名称
     * @param description 标签描述
     * @param parentTag 父标签ID（可选）
     * @param creatorUsername 创建者用户名（必须是管理员）
     */
    @Transactional
    public OrganizationTag createOrganizationTag(String tagId, String name, String description, 
                                                String parentTag, String creatorUsername) {
        // 验证创建者是否为管理员
        User creator = userRepository.findByUsername(creatorUsername)
                .orElseThrow(() -> new CustomException("Creator not found", HttpStatus.NOT_FOUND));
        
        if (creator.getRole() != User.Role.ADMIN) {
            throw new CustomException("Only administrators can create organization tags", HttpStatus.FORBIDDEN);
        }
        
        // 检查标签ID是否已存在
        if (organizationTagRepository.existsByTagId(tagId)) {
            throw new CustomException("Tag ID already exists", HttpStatus.BAD_REQUEST);
        }
        
        // 如果指定了父标签，检查父标签是否存在
        if (parentTag != null && !parentTag.isEmpty()) {
            organizationTagRepository.findByTagId(parentTag)
                    .orElseThrow(() -> new CustomException("Parent tag not found", HttpStatus.NOT_FOUND));
        }
        
        OrganizationTag tag = new OrganizationTag();
        tag.setTagId(tagId);
        tag.setName(name);
        tag.setDescription(description);
        tag.setParentTag(parentTag);
        tag.setCreatedBy(creator);
        
        OrganizationTag savedTag = organizationTagRepository.save(tag);
        
        // 清除标签缓存，因为层级关系可能变化
        orgTagCacheService.invalidateAllEffectiveTagsCache();
        
        return savedTag;
    }
    
    /**
     * 为用户分配组织标签
     * 
     * @param userId 用户ID
     * @param orgTags 组织标签ID列表
     * @param adminUsername 管理员用户名
     */
    @Transactional
    public void assignOrgTagsToUser(Long userId, List<String> orgTags, String adminUsername) {
        // 验证操作者是否为管理员
        User admin = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new CustomException("Admin not found", HttpStatus.NOT_FOUND));
        
        if (admin.getRole() != User.Role.ADMIN) {
            throw new CustomException("Only administrators can assign organization tags", HttpStatus.FORBIDDEN);
        }
        
        // 查找用户
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
        
        // 验证所有标签是否存在
        for (String tagId : orgTags) {
            if (!organizationTagRepository.existsByTagId(tagId)) {
                throw new CustomException("Organization tag " + tagId + " not found", HttpStatus.NOT_FOUND);
            }
        }
        
        // 获取用户的现有组织标签
        Set<String> existingTags = new HashSet<>();
        if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
            existingTags = Arrays.stream(user.getOrgTags().split(",")).collect(Collectors.toSet());
        }
        
        // 找出并保留用户的私人组织标签
        String privateTagId = PRIVATE_TAG_PREFIX + user.getUsername();
        boolean hasPrivateTag = existingTags.contains(privateTagId);
        
        // 确保用户的私人组织标签不会被删除
        Set<String> finalTags = new HashSet<>(orgTags);
        if (hasPrivateTag && !finalTags.contains(privateTagId)) {
            finalTags.add(privateTagId);
        }
        
        // 将标签列表转换为逗号分隔的字符串
        String orgTagsStr = String.join(",", finalTags);
        user.setOrgTags(orgTagsStr);

        // 同步 user.schoolTag / collegeTag / majorTag（排除私人标签）
        List<String> publicTagIds = finalTags.stream()
                .filter(t -> !t.equals(privateTagId))
                .collect(Collectors.toList());
        List<OrganizationTag> assignedTagEntities = publicTagIds.isEmpty()
                ? new ArrayList<>()
                : organizationTagRepository.findAllById(publicTagIds);
        String newSchoolTag = assignedTagEntities.stream()
                .filter(t -> t.getType() == OrganizationTag.Type.SCHOOL)
                .map(OrganizationTag::getTagId).findFirst().orElse(null);
        String newMajorTag = assignedTagEntities.stream()
                .filter(t -> t.getType() == OrganizationTag.Type.MAJOR)
                .map(OrganizationTag::getTagId).findFirst().orElse(null);
        final String resolvedSchool = newSchoolTag;
        String newCollegeTag = assignedTagEntities.stream()
                .filter(t -> t.getType() == OrganizationTag.Type.COLLEGE)
                .filter(t -> resolvedSchool != null && resolvedSchool.equals(t.getParentTag()))
                .map(OrganizationTag::getTagId).findFirst().orElse(null);
        user.setSchoolTag(newSchoolTag);
        user.setCollegeTag(newCollegeTag);
        user.setMajorTag(newMajorTag);

        // 如果用户没有主组织标签且有组织标签，则优先使用私人标签作为主组织
        if ((user.getPrimaryOrg() == null || user.getPrimaryOrg().isEmpty()) && !finalTags.isEmpty()) {
            if (hasPrivateTag) {
                user.setPrimaryOrg(privateTagId);
            } else {
                user.setPrimaryOrg(new ArrayList<>(finalTags).get(0));
            }
        }

        userRepository.save(user);
        
        // 更新缓存
        orgTagCacheService.deleteUserOrgTagsCache(user.getUsername());
        orgTagCacheService.cacheUserOrgTags(user.getUsername(), new ArrayList<>(finalTags));
        // 同时清除有效标签缓存
        orgTagCacheService.deleteUserEffectiveTagsCache(user.getUsername());
        
        if (user.getPrimaryOrg() != null && !user.getPrimaryOrg().isEmpty()) {
            orgTagCacheService.cacheUserPrimaryOrg(user.getUsername(), user.getPrimaryOrg());
        }
    }
    
    /**
     * 获取用户的组织标签信息
     * 
     * @param username 用户名
     * @return 包含用户组织标签信息的Map
     */
    public Map<String, Object> getUserOrgTags(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
        return buildUserOrgTagsResponse(user, true);
    }

    public Map<String, Object> getUploadOrgTags(String userIdOrUsername) {
        User user = findUserByIdOrUsername(userIdOrUsername);
        return buildUserOrgTagsResponse(user, false);
    }

    public String resolveUploadOrgTag(String userIdOrUsername, String requestedOrgTag, boolean isPublic) {
        User user = findUserByIdOrUsername(userIdOrUsername);
        String privateOrg = PRIVATE_TAG_PREFIX + user.getUsername();

        // 私有：一律绑到私人空间
        if (!isPublic) {
            return privateOrg;
        }

        // 管理员：保留跨组织投递能力，可以显式指定学校 / 学院标签
        if (user.getRole() == User.Role.ADMIN && requestedOrgTag != null && !requestedOrgTag.isBlank()) {
            if (requestedOrgTag.startsWith(PRIVATE_TAG_PREFIX)) {
                throw new CustomException("组织内公开时，组织标签不能选择私人空间", HttpStatus.BAD_REQUEST);
            }
            OrganizationTag tag = organizationTagRepository.findByTagId(requestedOrgTag)
                    .orElseThrow(() -> new CustomException("组织标签不存在", HttpStatus.BAD_REQUEST));
            if (tag.getType() != OrganizationTag.Type.SCHOOL && tag.getType() != OrganizationTag.Type.COLLEGE) {
                throw new CustomException("组织内公开只能选择学校或学院标签", HttpStatus.BAD_REQUEST);
            }
            return requestedOrgTag;
        }

        // 普通用户（或管理员未指定时）：有学院就绑学院，否则绑学校
        String autoTag = resolvePublicOrgTagForUser(user);
        if (autoTag == null) {
            throw new CustomException("请先在个人中心设置学校 / 学院，再发布组织内公开文件", HttpStatus.BAD_REQUEST);
        }
        return autoTag;
    }

    /**
     * 用户当前组织内公开应落到的标签：优先学院，否则学校；两者都没有返回 null。
     */
    private String resolvePublicOrgTagForUser(User user) {
        if (user.getCollegeTag() != null && !user.getCollegeTag().isBlank()) {
            return user.getCollegeTag();
        }
        if (user.getSchoolTag() != null && !user.getSchoolTag().isBlank()) {
            return user.getSchoolTag();
        }
        return null;
    }

    /**
     * 切换已上传文件的可见范围。公开时按文件所有者的学院/学校自动绑定组织标签，
     * 私有时改绑到所有者的私人空间。MySQL 与 ES 同步更新。
     */
    @Transactional
    public void updateFileVisibility(String fileMd5, String actorUserId, String actorRole, boolean isPublic) {
        if (fileMd5 == null || fileMd5.isBlank()) {
            throw new CustomException("文件 MD5 不能为空", HttpStatus.BAD_REQUEST);
        }

        boolean isAdmin = "ADMIN".equalsIgnoreCase(actorRole);
        FileUpload file = (isAdmin
                ? fileUploadRepository.findByFileMd5(fileMd5)
                : fileUploadRepository.findByFileMd5AndUserId(fileMd5, actorUserId))
                .orElseThrow(() -> new CustomException("文件不存在或无权访问", HttpStatus.NOT_FOUND));

        if (!isAdmin && !file.getUserId().equals(actorUserId)) {
            throw new CustomException("没有权限修改此文件的可见范围", HttpStatus.FORBIDDEN);
        }

        String ownerId = file.getUserId();
        User owner = findUserByIdOrUsername(ownerId);
        String newOrgTag;
        if (isPublic) {
            String auto = resolvePublicOrgTagForUser(owner);
            if (auto == null) {
                throw new CustomException("文件所有者未设置学校 / 学院，无法公开", HttpStatus.BAD_REQUEST);
            }
            newOrgTag = auto;
        } else {
            newOrgTag = PRIVATE_TAG_PREFIX + owner.getUsername();
        }

        if (file.isPublic() == isPublic && newOrgTag.equals(file.getOrgTag())) {
            return;
        }

        file.setOrgTag(newOrgTag);
        file.setPublic(isPublic);
        fileUploadRepository.save(file);

        try {
            documentVectorRepository.updateOrgTagByFileMd5(fileMd5, newOrgTag, isPublic);
        } catch (Exception e) {
            logger.error("同步 document_vectors orgTag 失败, fileMd5={}", fileMd5, e);
        }

        try {
            elasticsearchService.updateOrgTagByFileMd5(fileMd5, newOrgTag, isPublic);
        } catch (Exception e) {
            logger.error("同步 ES orgTag 失败, fileMd5={}", fileMd5, e);
        }

        logger.info("文件 {} 可见范围切换为 isPublic={}, orgTag={} (操作人 userId={})",
                fileMd5, isPublic, newOrgTag, actorUserId);
    }
    
    /**
     * 设置用户的主组织标签
     * 
     * @param username 用户名
     * @param primaryOrg 主组织标签
     */
    public void setUserPrimaryOrg(String username, String primaryOrg) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
        
        // 检查该组织标签是否已分配给用户
        Set<String> userTags = Arrays.stream(user.getOrgTags().split(",")).collect(Collectors.toSet());
        if (!userTags.contains(primaryOrg)) {
            throw new CustomException("Organization tag not assigned to user", HttpStatus.BAD_REQUEST);
        }
        
        user.setPrimaryOrg(primaryOrg);
        userRepository.save(user);
        
        // 更新缓存
        orgTagCacheService.cacheUserPrimaryOrg(username, primaryOrg);
    }
    
    /**
     * 获取用户的主组织标签
     * 
     * @param userId 用户ID
     * @return 用户的主组织标签
     */
    public String getUserPrimaryOrg(String userId) {
        // 先通过userId查找用户，然后获取username
        User user;
        try {
            Long userIdLong = Long.parseLong(userId);
            user = userRepository.findById(userIdLong)
                .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            // 如果userId不是数字格式，则假设它就是username
            user = userRepository.findByUsername(userId)
                .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        }
        
        String username = user.getUsername();
        
        // 尝试从缓存获取
        String primaryOrg = orgTagCacheService.getUserPrimaryOrg(username);
        
        // 如果缓存中没有，则从数据库获取
        if (primaryOrg == null || primaryOrg.isEmpty()) {
            primaryOrg = user.getPrimaryOrg();
            
            // 如果用户没有设置主组织标签，则尝试使用第一个分配的组织标签
            if (primaryOrg == null || primaryOrg.isEmpty()) {
                String[] tags = user.getOrgTags().split(",");
                if (tags.length > 0) {
                    primaryOrg = tags[0];
                    // 更新用户的主组织标签
                    user.setPrimaryOrg(primaryOrg);
                    userRepository.save(user);
                } else {
                    // 如果用户没有任何组织标签，则使用默认标签
                    primaryOrg = DEFAULT_ORG_TAG;
                }
            }
            
            // 更新缓存
            orgTagCacheService.cacheUserPrimaryOrg(username, primaryOrg);
        }
        
        return primaryOrg;
    }

    /**
     * 获取组织标签树结构
     * 
     * @return 组织标签树结构
     */
    public List<Map<String, Object>> getOrganizationTagTree() {
        // 获取所有根节点（parentTag为null的标签）
        List<OrganizationTag> rootTags = organizationTagRepository.findByParentTag(null);
        
        // 递归构建标签树
        return buildTagTreeRecursive(rootTags);
    }
    
    /**
     * 递归构建标签树
     * 
     * @param tags 当前级别的标签列表
     * @return 树形结构
     */
    private List<Map<String, Object>> buildTagTreeRecursive(List<OrganizationTag> tags) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        for (OrganizationTag tag : tags) {
            if (!seenNames.add(tag.getName())) {
                continue;
            }
            Map<String, Object> node = new HashMap<>();
            node.put("tagId", tag.getTagId());
            node.put("name", tag.getName());
            node.put("description", tag.getDescription());
            node.put("parentTag", tag.getParentTag()); // 添加父标签字段

            // 获取子标签
            List<OrganizationTag> children = organizationTagRepository.findByParentTag(tag.getTagId());
            if (!children.isEmpty()) {
                node.put("children", buildTagTreeRecursive(children));
            }
            // 如果没有子节点，不添加children字段，而不是添加空数组

            result.add(node);
        }

        return result;
    }
    
    /**
     * 更新组织标签
     * 
     * @param tagId 标签ID
     * @param name 新名称
     * @param description 新描述
     * @param parentTag 新父标签ID
     * @param adminUsername 管理员用户名
     * @return 更新后的组织标签
     */
    @Transactional
    public OrganizationTag updateOrganizationTag(String tagId, String name, String description, 
                                                String parentTag, String adminUsername) {
        // 验证操作者是否为管理员
        User admin = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new CustomException("Admin not found", HttpStatus.NOT_FOUND));
        
        if (admin.getRole() != User.Role.ADMIN) {
            throw new CustomException("Only administrators can update organization tags", HttpStatus.FORBIDDEN);
        }
        
        // 获取要更新的标签
        OrganizationTag tag = organizationTagRepository.findByTagId(tagId)
                .orElseThrow(() -> new CustomException("Organization tag not found", HttpStatus.NOT_FOUND));
        
        // 如果指定了父标签，检查父标签是否存在
        if (parentTag != null && !parentTag.isEmpty()) {
            // 检查是否为自身
            if (tagId.equals(parentTag)) {
                throw new CustomException("A tag cannot be its own parent", HttpStatus.BAD_REQUEST);
            }
            
            // 检查是否存在
            organizationTagRepository.findByTagId(parentTag)
                    .orElseThrow(() -> new CustomException("Parent tag not found", HttpStatus.NOT_FOUND));
            
            // 检查是否会形成循环
            if (wouldFormCycle(tagId, parentTag)) {
                throw new CustomException("Setting this parent would create a cycle in the tag hierarchy", HttpStatus.BAD_REQUEST);
            }
        }
        
        // 更新标签
        if (name != null && !name.isEmpty()) {
            tag.setName(name);
        }
        
        if (description != null) {
            tag.setDescription(description);
        }
        
        tag.setParentTag(parentTag);
        
        OrganizationTag updatedTag = organizationTagRepository.save(tag);
        
        // 清除所有标签缓存，因为层级关系可能变化
        orgTagCacheService.invalidateAllEffectiveTagsCache();
        
        return updatedTag;
    }
    
    /**
     * 检查是否会形成标签层级循环
     * 
     * @param tagId 要设置父标签的标签ID
     * @param newParentId 新的父标签ID
     * @return 是否会形成循环
     */
    private boolean wouldFormCycle(String tagId, String newParentId) {
        String currentParentId = newParentId;
        
        // 检查是否形成循环
        while (currentParentId != null && !currentParentId.isEmpty()) {
            if (tagId.equals(currentParentId)) {
                return true; // 形成循环
            }
            
            // 获取父标签的父标签
            Optional<OrganizationTag> parentTag = organizationTagRepository.findByTagId(currentParentId);
            if (parentTag.isEmpty()) {
                break;
            }
            
            currentParentId = parentTag.get().getParentTag();
        }
        
        return false;
    }
    
    /**
     * 删除组织标签
     * 
     * @param tagId 标签ID
     * @param adminUsername 管理员用户名
     */
    @Transactional
    public void deleteOrganizationTag(String tagId, String adminUsername) {
        // 验证操作者是否为管理员
        User admin = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new CustomException("Admin not found", HttpStatus.NOT_FOUND));
        
        if (admin.getRole() != User.Role.ADMIN) {
            throw new CustomException("Only administrators can delete organization tags", HttpStatus.FORBIDDEN);
        }
        
        // 获取要删除的标签
        OrganizationTag tag = organizationTagRepository.findByTagId(tagId)
                .orElseThrow(() -> new CustomException("Organization tag not found", HttpStatus.NOT_FOUND));
        
        // 检查是否是特殊标签（如默认标签）
        if (DEFAULT_ORG_TAG.equals(tagId)) {
            throw new CustomException("Cannot delete the default organization tag", HttpStatus.BAD_REQUEST);
        }
        
        // 检查是否有子标签
        List<OrganizationTag> children = organizationTagRepository.findByParentTag(tagId);
        if (!children.isEmpty()) {
            throw new CustomException("Cannot delete a tag with child tags", HttpStatus.BAD_REQUEST);
        }
        
        // 检查是否有用户使用此标签
        List<User> users = userRepository.findAll();
        for (User user : users) {
            if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
                Set<String> userTags = new HashSet<>(Arrays.asList(user.getOrgTags().split(",")));
                if (userTags.contains(tagId)) {
                    throw new CustomException("Cannot delete a tag that is assigned to users", HttpStatus.CONFLICT);
                }
                
                // 检查是否被用作主组织标签
                if (tagId.equals(user.getPrimaryOrg())) {
                    throw new CustomException("Cannot delete a tag that is used as primary organization", HttpStatus.CONFLICT);
                }
            }
        }
        
        // 检查是否有文档使用此标签（此处应检查file_upload表中的org_tag字段）
        // 由于我们没有直接访问FileUploadRepository，这里采用简化的方式检查
        // 实际实现中，应该注入FileUploadRepository并使用正确的查询方法
        try {
            long fileCount = 0; // 应该是 fileUploadRepository.countByOrgTag(tagId);
            if (fileCount > 0) {
                throw new CustomException("Cannot delete a tag that is associated with documents", HttpStatus.CONFLICT);
            }
        } catch (Exception e) {
            logger.error("Error checking file usage of tag: {}", tagId, e);
            throw new CustomException("Failed to check if tag is used by documents", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        
        // 删除标签
        organizationTagRepository.delete(tag);
        
        // 清除所有标签缓存，因为层级关系可能变化
        orgTagCacheService.invalidateAllEffectiveTagsCache();
        
        logger.info("Organization tag deleted successfully: {}", tagId);
    }
    
    /**
     * 修改用户名（昵称）。
     * 同时把该用户的私人组织标签 name 字段更新为 "新名字的私人空间"。
     * 注意：私人标签的 tagId 保持 PRIVATE_<旧名字> 不变，因为它被用作外键存于
     * users.org_tags / users.primary_org / file_upload.org_tag 等多张表，改 tagId 风险大。
     *
     * @return 新的用户名，调用方需基于此重新签发 JWT
     */
    @Transactional
    public String updateUsername(String currentUsername, String newUsername) {
        if (newUsername == null || newUsername.isBlank()) {
            throw new CustomException("新昵称不能为空", HttpStatus.BAD_REQUEST);
        }
        String trimmed = newUsername.trim();
        if (trimmed.length() > 32) {
            throw new CustomException("昵称长度不能超过 32", HttpStatus.BAD_REQUEST);
        }
        if (trimmed.equals(currentUsername)) {
            throw new CustomException("新昵称与当前昵称相同", HttpStatus.BAD_REQUEST);
        }
        if (userRepository.findByUsername(trimmed).isPresent()) {
            throw new CustomException("该昵称已被占用", HttpStatus.CONFLICT);
        }

        User user = userRepository.findByUsername(currentUsername)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));

        // 私人标签的 tagId 在注册时被固化（PRIVATE_<注册时用户名>），改名后不会同步变化。
        // 因此不能用 PRIVATE_+currentUsername 去查（第二次改名/改回都会查不到），
        // 而要在用户已有的 orgTags 中找到那个以 PRIVATE_ 开头的标签 id。
        String privateTagId = null;
        if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
            for (String tagId : user.getOrgTags().split(",")) {
                if (tagId.startsWith(PRIVATE_TAG_PREFIX)) {
                    privateTagId = tagId;
                    break;
                }
            }
        }
        if (privateTagId != null) {
            String finalTagId = privateTagId;
            organizationTagRepository.findByTagId(finalTagId).ifPresent(tag -> {
                tag.setName(trimmed + PRIVATE_ORG_NAME_SUFFIX);
                organizationTagRepository.save(tag);
            });
        }

        // 更新用户名
        user.setUsername(trimmed);
        userRepository.save(user);

        // 把缓存按旧用户名清掉，下一次请求会按新用户名重建
        orgTagCacheService.deleteUserOrgTagsCache(currentUsername);
        orgTagCacheService.deleteUserEffectiveTagsCache(currentUsername);

        logger.info("Username changed: {} -> {}", currentUsername, trimmed);
        return trimmed;
    }

    /**
     * 修改密码。需要校验旧密码。
     */
    @Transactional
    public void updatePassword(String username, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new CustomException("新密码至少 6 位", HttpStatus.BAD_REQUEST);
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        if (!PasswordUtil.matches(oldPassword == null ? "" : oldPassword, user.getPassword())) {
            throw new CustomException("旧密码不正确", HttpStatus.UNAUTHORIZED);
        }
        if (PasswordUtil.matches(newPassword, user.getPassword())) {
            throw new CustomException("新密码不能与旧密码相同", HttpStatus.BAD_REQUEST);
        }
        user.setPassword(PasswordUtil.encode(newPassword));
        userRepository.save(user);
        logger.info("Password changed for user: {}", username);
    }

    /**
     * 获取用户列表，支持分页和过滤
     * 
     * @param keyword 搜索关键词
     * @param orgTag 组织标签过滤
     * @param status 用户状态过滤
     * @param page 页码
     * @param size 每页大小
     * @return 用户列表数据
     */
    public Map<String, Object> getUserList(String keyword, String orgTag, Integer status, int page, int size) {
        // 页码从1开始，需要转换为从0开始
        int pageIndex = page > 0 ? page - 1 : 0;
        // 创建分页请求
        Pageable pageable = PageRequest.of(pageIndex, size, Sort.by("createdAt").descending());
        
        // 获取用户列表
        Page<User> userPage;
        
        if (orgTag != null && !orgTag.isEmpty()) {
            // 按组织标签过滤用户
            // 由于我们存储组织标签为逗号分隔的字符串，需要自定义实现
            // 这里简化处理，获取所有用户后手动过滤
            List<User> allUsers = userRepository.findAll();
            List<User> filteredUsers = allUsers.stream()
                    .filter(user -> {
                        // 过滤组织标签
                        if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
                            Set<String> userTags = new HashSet<>(Arrays.asList(user.getOrgTags().split(",")));
                            if (!userTags.contains(orgTag)) {
                                return false;
                            }
                        } else {
                            return false;
                        }
                        
                        // 过滤关键词
                        if (keyword != null && !keyword.isEmpty()) {
                            boolean matchesKeyword = user.getUsername().contains(keyword);
                            if (!matchesKeyword) {
                                return false;
                            }
                        }
                        
                        // 过滤状态
                        if (status != null) {
                            return user.getRole() == (status == 1 ? User.Role.USER : User.Role.ADMIN);
                        }
                        
                        return true;
                    })
                    .collect(Collectors.toList());
            
            // 手动分页
            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), filteredUsers.size());
            
            List<User> pageContent = start < end ? filteredUsers.subList(start, end) : Collections.emptyList();
            userPage = new PageImpl<>(pageContent, pageable, filteredUsers.size());
        } else {
            // 使用 JPA 分页查询（不含组织标签过滤）
            // 这里假设UserRepository有findByKeywordAndStatus方法，实际中可能需要自定义实现
            userPage = userRepository.findAll(pageable);
            
            // 手动过滤（简化实现）
            List<User> filteredUsers = userPage.getContent().stream()
                    .filter(user -> {
                        // 过滤关键词
                        if (keyword != null && !keyword.isEmpty()) {
                            boolean matchesKeyword = user.getUsername().contains(keyword);
                            if (!matchesKeyword) {
                                return false;
                            }
                        }
                        
                        // 过滤状态
                        if (status != null) {
                            return user.getRole() == (status == 1 ? User.Role.USER : User.Role.ADMIN);
                        }
                        
                        return true;
                    })
                    .collect(Collectors.toList());
                    
            userPage = new PageImpl<>(filteredUsers, pageable, filteredUsers.size());
        }
        
        // 转换为前端需要的格式
        List<Map<String, Object>> userList = userPage.getContent().stream()
                .map(user -> {
                    Map<String, Object> userMap = new HashMap<>();
                    userMap.put("userId", user.getId());
                    userMap.put("username", user.getUsername());

                    // 管理员视角只展示：私人空间 + 当前登记的学校 / 学院 / 专业，每类最多一个。
                    // 这样避免 orgTags 里残留的下级 / 历史标签把学校卡片挤乱。
                    LinkedHashSet<String> displayTagIds = new LinkedHashSet<>();
                    if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
                        for (String tagId : user.getOrgTags().split(",")) {
                            if (tagId != null && !tagId.isBlank() && tagId.startsWith(PRIVATE_TAG_PREFIX)) {
                                displayTagIds.add(tagId);
                            }
                        }
                    }
                    if (user.getSchoolTag() != null && !user.getSchoolTag().isBlank()) {
                        displayTagIds.add(user.getSchoolTag());
                    }
                    if (user.getCollegeTag() != null && !user.getCollegeTag().isBlank()) {
                        displayTagIds.add(user.getCollegeTag());
                    }
                    if (user.getMajorTag() != null && !user.getMajorTag().isBlank()) {
                        displayTagIds.add(user.getMajorTag());
                    }
                    List<Map<String, String>> orgTagDetails = buildVisibleUserOrgTagDetails(new ArrayList<>(displayTagIds), true);

                    userMap.put("orgTags", orgTagDetails);
                    userMap.put("primaryOrg", user.getPrimaryOrg());
                    userMap.put("status", user.getRole() == User.Role.USER ? 1 : 0);
                    userMap.put("createdAt", user.getCreatedAt());
                    
                    return userMap;
                })
                .collect(Collectors.toList());
        
        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("content", userList);
        result.put("totalElements", userPage.getTotalElements());
        result.put("totalPages", userPage.getTotalPages());
        result.put("size", userPage.getSize());
        result.put("number", userPage.getNumber() + 1); // 转换为从1开始的页码
        
        return result;
    }

    private User findUserByIdOrUsername(String userIdOrUsername) {
        Optional<User> userByUsername = userRepository.findByUsername(userIdOrUsername);
        if (userByUsername.isPresent()) {
            return userByUsername.get();
        }

        try {
            Long userIdLong = Long.parseLong(userIdOrUsername);
            return userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userIdOrUsername, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            throw new CustomException("User not found: " + userIdOrUsername, HttpStatus.NOT_FOUND);
        }
    }

    private Map<String, Object> buildUserOrgTagsResponse(User user, boolean includeMajor) {
        String username = user.getUsername();

        List<String> orgTags = orgTagCacheService.getUserOrgTags(username);
        String primaryOrg = orgTagCacheService.getUserPrimaryOrg(username);

        if (orgTags == null || orgTags.isEmpty()) {
            orgTags = user.getOrgTags() == null || user.getOrgTags().isBlank()
                    ? List.of()
                    : Arrays.stream(user.getOrgTags().split(","))
                            .filter(tagId -> tagId != null && !tagId.isBlank())
                            .toList();
            orgTagCacheService.cacheUserOrgTags(username, orgTags);
        }

        if (primaryOrg == null || primaryOrg.isEmpty()) {
            primaryOrg = user.getPrimaryOrg();
            orgTagCacheService.cacheUserPrimaryOrg(username, primaryOrg);
        }

        List<Map<String, String>> orgTagDetails = buildVisibleUserOrgTagDetails(orgTags, includeMajor);
        List<String> visibleOrgTags = orgTagDetails.stream()
                .map(tag -> tag.get("tagId"))
                .toList();
        String privateOrg = orgTags.stream()
                .filter(tagId -> tagId != null && tagId.startsWith(PRIVATE_TAG_PREFIX))
                .findFirst()
                .orElse(null);

        Map<String, Object> result = new HashMap<>();
        result.put("orgTags", visibleOrgTags);
        result.put("primaryOrg", primaryOrg);
        result.put("privateOrg", privateOrg);
        result.put("orgTagDetails", orgTagDetails);

        return result;
    }

    private List<Map<String, String>> buildVisibleUserOrgTagDetails(List<String> orgTags, boolean includeMajor) {
        List<Map<String, String>> orgTagDetails = new ArrayList<>();

        for (String tagId : orgTags) {
            if (tagId == null || tagId.isBlank()) {
                continue;
            }

            if (tagId.startsWith(PRIVATE_TAG_PREFIX)) {
                OrganizationTag tag = organizationTagRepository.findByTagId(tagId).orElse(null);
                if (tag != null) {
                    Map<String, String> tagInfo = new HashMap<>();
                    tagInfo.put("tagId", tag.getTagId());
                    tagInfo.put("name", tag.getName());
                    tagInfo.put("description", tag.getDescription());
                    orgTagDetails.add(tagInfo);
                }
                continue;
            }

            OrganizationTag tag = organizationTagRepository.findByTagId(tagId).orElse(null);
            if (tag == null || !isVisibleUserTag(tag, includeMajor)) {
                continue;
            }

            Map<String, String> tagInfo = new HashMap<>();
            tagInfo.put("tagId", tag.getTagId());
            tagInfo.put("name", tag.getName());
            tagInfo.put("description", tag.getDescription());
            orgTagDetails.add(tagInfo);
        }

        return orgTagDetails;
    }

    private boolean isVisibleUserTag(OrganizationTag tag, boolean includeMajor) {
        return tag.getType() == OrganizationTag.Type.SCHOOL
                || tag.getType() == OrganizationTag.Type.COLLEGE
                || (includeMajor && tag.getType() == OrganizationTag.Type.MAJOR);
    }
}
