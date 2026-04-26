package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Set;

@Service
public class AvatarService {

    private static final Logger logger = LoggerFactory.getLogger(AvatarService.class);

    private static final String BUCKET = "avatars";
    private static final long MAX_SIZE = 2L * 1024 * 1024; // 2MB
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private UserRepository userRepository;

    @Value("${minio.publicUrl}")
    private String publicUrl;

    /**
     * 容器启动后确保 avatars bucket 存在且为 public-read，
     * 之后浏览器可直接通过固定 URL 读取头像，无需任何签名/转发。
     */
    @PostConstruct
    public void init() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            }
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],"
                    + "\"Resource\":[\"arn:aws:s3:::" + BUCKET + "/*\"]}]}";
            minioClient.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(BUCKET).config(policy).build());
        } catch (Exception e) {
            logger.warn("初始化 avatars bucket 失败（不影响服务启动）: {}", e.getMessage());
        }
    }

    public String uploadAvatar(String userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CustomException("文件不能为空", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > MAX_SIZE) {
            throw new CustomException("头像不能超过 2MB", HttpStatus.BAD_REQUEST);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new CustomException("仅支持 jpg/png/webp/gif 格式", HttpStatus.BAD_REQUEST);
        }

        String ext = switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "bin";
        };
        String objectName = userId + "." + ext;

        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(objectName)
                    .stream(in, file.getSize(), -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            logger.error("上传头像失败 userId={}: {}", userId, e.getMessage(), e);
            throw new CustomException("上传失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // 加 ?v=timestamp 让浏览器立刻刷新缓存
        String url = publicUrl + "/" + BUCKET + "/" + objectName + "?v=" + System.currentTimeMillis();

        User user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        user.setAvatarUrl(url);
        userRepository.save(user);

        return url;
    }

    /**
     * 注销账号时调用：把用户头像对象从公开桶里彻底删掉。
     * 用户上传时后端不记录扩展名，只能枚举所有允许的扩展名去删（不存在的对象删除不会报错）。
     */
    public void deleteAvatar(String userId) {
        for (String ext : new String[]{"jpg", "png", "webp", "gif"}) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(BUCKET)
                        .object(userId + "." + ext)
                        .build());
            } catch (Exception e) {
                logger.debug("删除头像 {}.{} 时忽略异常: {}", userId, ext, e.getMessage());
            }
        }
    }
}
