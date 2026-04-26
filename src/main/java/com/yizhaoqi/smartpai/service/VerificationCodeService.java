package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 邮箱验证码服务。基于 Redis 存储 6 位数字验证码 + 60 秒发送频控。
 *
 *  Redis Keys:
 *    email:code:{scene}:{email}      -> 6 位验证码字符串   TTL=10min
 *    email:code:lock:{scene}:{email} -> "1"               TTL=60s （防刷）
 */
@Service
public class VerificationCodeService {

    private static final Logger logger = LoggerFactory.getLogger(VerificationCodeService.class);
    private static final Set<String> ALLOWED_SCENES = Set.of("register", "reset", "bind");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private EmailService emailService;

    @Value("${paismart.mail.code-ttl-seconds:600}")
    private long codeTtl;

    @Value("${paismart.mail.send-lock-seconds:60}")
    private long sendLockTtl;

    public void sendCode(String email, String scene) {
        validate(email, scene);

        String lockKey = lockKey(email, scene);
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", sendLockTtl, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(locked)) {
            throw new CustomException("发送过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS);
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(codeKey(email, scene), code, codeTtl, TimeUnit.SECONDS);
        emailService.sendVerificationCode(email, code, scene);
        logger.info("Verification code generated for {} (scene={})", email, scene);
    }

    /**
     * 校验验证码；正确则立即删除，防止重放。
     */
    public void verify(String email, String scene, String code) {
        validate(email, scene);
        if (code == null || code.isBlank()) {
            throw new CustomException("请输入验证码", HttpStatus.BAD_REQUEST);
        }
        String key = codeKey(email, scene);
        String saved = redis.opsForValue().get(key);
        if (saved == null) {
            throw new CustomException("验证码已失效，请重新获取", HttpStatus.BAD_REQUEST);
        }
        if (!saved.equals(code.trim())) {
            throw new CustomException("验证码不正确", HttpStatus.BAD_REQUEST);
        }
        redis.delete(key);
    }

    private void validate(String email, String scene) {
        if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new CustomException("邮箱格式不正确", HttpStatus.BAD_REQUEST);
        }
        if (!ALLOWED_SCENES.contains(scene)) {
            throw new CustomException("未知的验证码用途", HttpStatus.BAD_REQUEST);
        }
    }

    private String codeKey(String email, String scene) {
        return "email:code:" + scene + ":" + email.toLowerCase();
    }

    private String lockKey(String email, String scene) {
        return "email:code:lock:" + scene + ":" + email.toLowerCase();
    }
}
