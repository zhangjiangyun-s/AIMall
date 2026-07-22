package com.aimall.server.service.impl;

import com.aimall.server.entity.UmsEmailVerificationCode;
import com.aimall.server.entity.UmsEmailVerificationSendLog;
import com.aimall.server.entity.UmsMember;
import com.aimall.server.mapper.UmsEmailVerificationCodeMapper;
import com.aimall.server.mapper.UmsEmailVerificationSendLogMapper;
import com.aimall.server.mapper.UmsMemberMapper;
import com.aimall.server.service.EmailVerificationService;
import com.aimall.server.service.EmailVerificationException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.function.IntSupplier;

@Service
public class EmailVerificationServiceImpl implements EmailVerificationService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$",
            Pattern.CASE_INSENSITIVE
    );

    private final UmsEmailVerificationCodeMapper codes;
    private final UmsEmailVerificationSendLogMapper sendLogs;
    private final UmsMemberMapper members;
    private final JavaMailSender mailSender;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final boolean enabled;
    private final String from;
    private final String pepper;
    private final int ttlMinutes;
    private final int cooldownSeconds;
    private final int maxAttempts;
    private final IntSupplier codeGenerator;

    @Autowired
    public EmailVerificationServiceImpl(
            UmsEmailVerificationCodeMapper codes,
            UmsEmailVerificationSendLogMapper sendLogs,
            UmsMemberMapper members,
            JavaMailSender mailSender,
            @Value("${aimall.email.enabled:true}") boolean enabled,
            @Value("${aimall.email.from:AIMall <no-reply@aimall.local>}") String from,
            @Value("${aimall.email.code-pepper:aimall-local-mailhog-pepper-not-for-production}") String pepper,
            @Value("${aimall.email.code-ttl-minutes:10}") int ttlMinutes,
            @Value("${aimall.email.send-cooldown-seconds:60}") int cooldownSeconds,
            @Value("${aimall.email.max-attempts:5}") int maxAttempts
    ) {
        this(codes, sendLogs, members, mailSender, enabled, from, pepper, ttlMinutes,
                cooldownSeconds, maxAttempts, () -> SECURE_RANDOM.nextInt(1_000_000));
    }

    EmailVerificationServiceImpl(
            UmsEmailVerificationCodeMapper codes,
            UmsEmailVerificationSendLogMapper sendLogs,
            UmsMemberMapper members,
            JavaMailSender mailSender,
            boolean enabled,
            String from,
            String pepper,
            int ttlMinutes,
            int cooldownSeconds,
            int maxAttempts,
            IntSupplier codeGenerator
    ) {
        this.codes = codes;
        this.sendLogs = sendLogs;
        this.members = members;
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
        this.pepper = pepper;
        this.ttlMinutes = Math.max(1, ttlMinutes);
        this.cooldownSeconds = Math.max(30, cooldownSeconds);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.codeGenerator = codeGenerator;
    }

    @Override
    public void sendCode(String rawEmail, Purpose purpose, String clientIp) {
        if (!enabled) {
            throw new IllegalStateException("Email delivery is disabled");
        }
        String email = normalize(rawEmail);
        enforceRateLimit(email, clientIp);
        if (purpose == Purpose.REGISTER && memberExists(email)) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        if (purpose == Purpose.PASSWORD_RESET && !memberExists(email)) {
            hash(email, purpose, "000000");
            recordSend(email, purpose, clientIp, true, null);
            return;
        }

        String code = "%06d".formatted(Math.floorMod(codeGenerator.getAsInt(), 1_000_000));
        LocalDateTime now = LocalDateTime.now();
        int affected = codes.upsertActive(
                email,
                purpose.name(),
                hash(email, purpose, code),
                maxAttempts,
                now.plusMinutes(ttlMinutes),
                now,
                now.minusSeconds(cooldownSeconds)
        );
        if (affected == 0) {
            throw new IllegalStateException("验证码发送过于频繁，请稍后再试");
        }
        try {
            sendMessage(email, purpose, code);
            recordSend(email, purpose, clientIp, true, null);
        } catch (Exception exception) {
            codes.update(null, new LambdaUpdateWrapper<UmsEmailVerificationCode>()
                    .eq(UmsEmailVerificationCode::getEmail, email)
                    .eq(UmsEmailVerificationCode::getPurpose, purpose.name())
                    .eq(UmsEmailVerificationCode::getStatus, "ACTIVE")
                    .set(UmsEmailVerificationCode::getStatus, "FAILED"));
            recordSend(email, purpose, clientIp, false, "SMTP_DELIVERY_FAILED");
            throw new IllegalStateException("验证码邮件发送失败，请稍后重试", exception);
        }
    }

    @Override
    @Transactional(noRollbackFor = EmailVerificationException.class)
    public void consume(String rawEmail, Purpose purpose, String code) {
        String email = normalize(rawEmail);
        if (code == null || !code.matches("\\d{6}")) {
            throw new EmailVerificationException("验证码格式错误");
        }
        UmsEmailVerificationCode challenge = codes.selectActiveForUpdate(email, purpose.name());
        LocalDateTime now = LocalDateTime.now();
        if (challenge == null) {
            throw new EmailVerificationException("验证码不存在或已失效");
        }
        if (challenge.getExpiresAt().isBefore(now)) {
            codes.update(null, new LambdaUpdateWrapper<UmsEmailVerificationCode>()
                    .eq(UmsEmailVerificationCode::getId, challenge.getId())
                    .eq(UmsEmailVerificationCode::getStatus, "ACTIVE")
                    .set(UmsEmailVerificationCode::getStatus, "EXPIRED"));
            throw new EmailVerificationException("验证码已过期");
        }
        int failedAttempts = challenge.getFailedAttempts() == null ? 0 : challenge.getFailedAttempts();
        int allowedAttempts = challenge.getMaxAttempts() == null ? maxAttempts : challenge.getMaxAttempts();
        if (!constantTimeEquals(challenge.getCodeHash(), hash(email, purpose, code))) {
            int nextAttempts = failedAttempts + 1;
            LambdaUpdateWrapper<UmsEmailVerificationCode> update = new LambdaUpdateWrapper<UmsEmailVerificationCode>()
                    .eq(UmsEmailVerificationCode::getId, challenge.getId())
                    .eq(UmsEmailVerificationCode::getStatus, "ACTIVE")
                    .set(UmsEmailVerificationCode::getFailedAttempts, nextAttempts);
            if (nextAttempts >= allowedAttempts) {
                update.set(UmsEmailVerificationCode::getStatus, "LOCKED");
            }
            codes.update(null, update);
            throw new EmailVerificationException(nextAttempts >= allowedAttempts ? "验证码已失效，请重新获取" : "验证码错误");
        }
        int updated = codes.update(null, new LambdaUpdateWrapper<UmsEmailVerificationCode>()
                .eq(UmsEmailVerificationCode::getId, challenge.getId())
                .eq(UmsEmailVerificationCode::getStatus, "ACTIVE")
                .set(UmsEmailVerificationCode::getStatus, "USED")
                .set(UmsEmailVerificationCode::getConsumedAt, now));
        if (updated != 1) {
            throw new EmailVerificationException("验证码已被使用");
        }
    }

    @Override
    public String normalize(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("邮箱格式错误");
        }
        return normalized;
    }

    private void enforceRateLimit(String email, String clientIp) {
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        long emailCount = sendLogs.selectCount(new LambdaQueryWrapper<UmsEmailVerificationSendLog>()
                .eq(UmsEmailVerificationSendLog::getEmail, email)
                .ge(UmsEmailVerificationSendLog::getCreatedAt, since));
        long ipCount = sendLogs.selectCount(new LambdaQueryWrapper<UmsEmailVerificationSendLog>()
                .eq(UmsEmailVerificationSendLog::getClientIp, clientIp)
                .ge(UmsEmailVerificationSendLog::getCreatedAt, since));
        if (emailCount >= 5 || ipCount >= 20) {
            throw new IllegalStateException("验证码发送次数过多，请稍后再试");
        }
    }

    private boolean memberExists(String email) {
        return members.selectCount(new LambdaQueryWrapper<UmsMember>().eq(UmsMember::getEmail, email)) > 0;
    }

    private void recordSend(String email, Purpose purpose, String clientIp, boolean success, String failureReason) {
        UmsEmailVerificationSendLog log = new UmsEmailVerificationSendLog();
        log.setEmail(email);
        log.setPurpose(purpose.name());
        log.setClientIp(clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
        log.setSuccess(success ? 1 : 0);
        log.setFailureReason(failureReason);
        log.setCreatedAt(LocalDateTime.now());
        sendLogs.insert(log);
    }

    private void sendMessage(String email, Purpose purpose, String code) throws Exception {
        String action = purpose == Purpose.REGISTER ? "完成账号注册" : "重置账号密码";
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(new InternetAddress(from));
        helper.setTo(email);
        helper.setSubject("AIMall 验证码");
        helper.setText("AIMall 验证码：" + code + "。用于" + action + "，" + ttlMinutes + "分钟内有效。", buildHtml(email, action, code));
        mailSender.send(message);
    }

    private String buildHtml(String email, String action, String code) {
        return """
                <!doctype html><html><body style="margin:0;background:#f4f5f7;font-family:Arial,'Microsoft YaHei',sans-serif;color:#1f2937">
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0"><tr><td align="center" style="padding:32px 16px">
                <table role="presentation" width="520" cellspacing="0" cellpadding="0" style="max-width:100%%;background:#fff;border:1px solid #e5e7eb">
                <tr><td style="padding:24px 28px;border-bottom:3px solid #ff5a3d"><strong style="font-size:24px">AIMall</strong></td></tr>
                <tr><td style="padding:28px"><h1 style="font-size:20px;margin:0 0 16px">邮箱安全验证</h1>
                <p style="margin:0 0 20px;line-height:1.7">你正在为 %s %s，请输入以下验证码：</p>
                <div style="font-size:32px;font-weight:700;letter-spacing:8px;background:#f9fafb;border:1px solid #d1d5db;padding:16px;text-align:center">%s</div>
                <p style="margin:20px 0 0;color:#6b7280;line-height:1.7">验证码 %d 分钟内有效，请勿转发。若非本人操作，请忽略此邮件。</p>
                </td></tr></table></td></tr></table></body></html>
                """.formatted(HtmlUtils.htmlEscape(email), HtmlUtils.htmlEscape(action), code, ttlMinutes);
    }

    private String hash(String email, Purpose purpose, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((purpose.name() + ":" + email + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to hash email verification code", exception);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return left != null && right != null && java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII)
        );
    }
}
