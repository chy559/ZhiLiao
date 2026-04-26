package com.yizhaoqi.smartpai.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;

/**
 * 邮件发送服务。所有发送都走 @Async，保证接口不会因 SMTP 阻塞。
 */
@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    @Value("${paismart.mail.from-name:考辅智聊}")
    private String fromName;

    @Async
    public void sendVerificationCode(String to, String code, String scene) {
        String subject = switch (scene) {
            case "register" -> "【考辅智聊】注册验证码";
            case "reset" -> "【考辅智聊】重置密码验证码";
            case "bind" -> "【考辅智聊】绑定邮箱验证码";
            default -> "【考辅智聊】验证码";
        };
        String html = buildHtml(code, scene);
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(new InternetAddress(fromAddress, fromName, "UTF-8"));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(msg);
            logger.info("Verification email sent to {} (scene={})", to, scene);
        } catch (UnsupportedEncodingException e) {
            logger.error("Failed to encode sender name", e);
        } catch (Exception e) {
            logger.error("Failed to send verification email to {}", to, e);
        }
    }

    private String buildHtml(String code, String scene) {
        String action = switch (scene) {
            case "register" -> "注册账号";
            case "reset" -> "重置密码";
            case "bind" -> "绑定邮箱";
            default -> "验证身份";
        };
        return "<div style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;max-width:480px;margin:0 auto;padding:24px;\">"
                + "<h2 style=\"color:#1f2937;margin:0 0 16px 0;\">考辅智聊</h2>"
                + "<p style=\"color:#374151;font-size:14px;line-height:1.6;\">您正在" + action + "，验证码为：</p>"
                + "<div style=\"font-size:32px;font-weight:700;letter-spacing:6px;color:#2563eb;background:#f3f4f6;padding:16px 24px;text-align:center;border-radius:8px;margin:16px 0;\">"
                + code + "</div>"
                + "<p style=\"color:#6b7280;font-size:12px;line-height:1.6;\">验证码 10 分钟内有效，请勿泄露给他人。如非本人操作，请忽略此邮件。</p>"
                + "</div>";
    }
}
