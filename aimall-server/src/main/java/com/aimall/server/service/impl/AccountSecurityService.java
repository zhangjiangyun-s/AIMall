package com.aimall.server.service.impl;

import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.entity.UmsMember;
import com.aimall.server.entity.UmsMemberDevice;
import com.aimall.server.entity.UmsMemberLoginHistory;
import com.aimall.server.mapper.UmsMemberDeviceMapper;
import com.aimall.server.mapper.UmsMemberLoginHistoryMapper;
import com.aimall.server.mapper.UmsMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class AccountSecurityService {
    private static final int LOGIN_ACCOUNT_IP_LIMIT = 8;
    private static final int LOGIN_ACCOUNT_LIMIT = 12;
    private static final int LOGIN_IP_LIMIT = 30;
    private static final int LOGIN_DEVICE_LIMIT = 12;
    private static final String REGISTER_EVENT = "__REGISTER__";
    private static final String PASSWORD_RESET_PREFIX = "__PASSWORD_RESET__:";

    private final UmsMemberMapper members;
    private final UmsMemberLoginHistoryMapper history;
    private final UmsMemberDeviceMapper devices;

    public AccountSecurityService(
            UmsMemberMapper members,
            UmsMemberLoginHistoryMapper history,
            UmsMemberDeviceMapper devices
    ) {
        this.members = members;
        this.history = history;
        this.devices = devices;
    }

    public void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 72
                || !password.matches(".*[A-Z].*") || !password.matches(".*[a-z].*")
                || !password.matches(".*\\d.*") || !password.matches(".*[^A-Za-z0-9].*")) {
            throw new IllegalArgumentException("密码必须为 12-72 位并包含大小写字母、数字和特殊字符");
        }
    }

    public void checkLoginAllowed(String username, String ip, String userAgent) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(15);
        String deviceHash = deviceHash(userAgent, ip);
        long accountIpFailures = history.selectCount(failedLogins(since)
                .eq(UmsMemberLoginHistory::getUsername, username)
                .eq(UmsMemberLoginHistory::getClientIp, ip));
        long accountFailures = history.selectCount(failedLogins(since)
                .eq(UmsMemberLoginHistory::getUsername, username));
        long ipFailures = history.selectCount(failedLogins(since)
                .eq(UmsMemberLoginHistory::getClientIp, ip));
        long deviceFailures = history.selectCount(failedLogins(since)
                .eq(UmsMemberLoginHistory::getDeviceHash, deviceHash));
        if (accountIpFailures >= LOGIN_ACCOUNT_IP_LIMIT || accountFailures >= LOGIN_ACCOUNT_LIMIT
                || ipFailures >= LOGIN_IP_LIMIT || deviceFailures >= LOGIN_DEVICE_LIMIT) {
            throw new RuntimeException("登录失败次数过多，请稍后再试");
        }
    }

    public void checkRegistrationAllowed(String ip, String userAgent) {
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        long ipAttempts = history.selectCount(new LambdaQueryWrapper<UmsMemberLoginHistory>()
                .eq(UmsMemberLoginHistory::getUsername, REGISTER_EVENT)
                .eq(UmsMemberLoginHistory::getClientIp, ip)
                .ge(UmsMemberLoginHistory::getCreateTime, since));
        long deviceAttempts = history.selectCount(new LambdaQueryWrapper<UmsMemberLoginHistory>()
                .eq(UmsMemberLoginHistory::getUsername, REGISTER_EVENT)
                .eq(UmsMemberLoginHistory::getDeviceHash, deviceHash(userAgent, ip))
                .ge(UmsMemberLoginHistory::getCreateTime, since));
        if (ipAttempts >= 10 || deviceAttempts >= 5) {
            throw new RuntimeException("注册请求过于频繁，请稍后再试");
        }
    }

    public void recordRegistration(String ip, String userAgent, boolean success) {
        recordLogin(null, REGISTER_EVENT, ip, userAgent, success, success ? null : "REGISTER_FAILED");
    }

    public void checkPasswordResetAllowed(String email, String ip, String userAgent) {
        String eventKey = passwordResetEvent(email);
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        long accountAttempts = history.selectCount(new LambdaQueryWrapper<UmsMemberLoginHistory>()
                .eq(UmsMemberLoginHistory::getUsername, eventKey)
                .ge(UmsMemberLoginHistory::getCreateTime, since));
        long ipAttempts = history.selectCount(new LambdaQueryWrapper<UmsMemberLoginHistory>()
                .likeRight(UmsMemberLoginHistory::getUsername, PASSWORD_RESET_PREFIX)
                .eq(UmsMemberLoginHistory::getClientIp, ip)
                .ge(UmsMemberLoginHistory::getCreateTime, since));
        long deviceAttempts = history.selectCount(new LambdaQueryWrapper<UmsMemberLoginHistory>()
                .likeRight(UmsMemberLoginHistory::getUsername, PASSWORD_RESET_PREFIX)
                .eq(UmsMemberLoginHistory::getDeviceHash, deviceHash(userAgent, ip))
                .ge(UmsMemberLoginHistory::getCreateTime, since));
        if (accountAttempts >= 5 || ipAttempts >= 15 || deviceAttempts >= 8) {
            throw new RuntimeException("密码重置请求过于频繁，请稍后再试");
        }
    }

    public void recordPasswordReset(String email, String ip, String userAgent, boolean success) {
        recordLogin(null, passwordResetEvent(email), ip, userAgent, success,
                success ? null : "PASSWORD_RESET_FAILED");
    }

    public void changePassword(Long id, String oldPassword, String newPassword) {
        UmsMember member = require(id);
        if (!BCrypt.checkpw(oldPassword, member.getPassword())) throw new RuntimeException("原密码错误");
        validatePassword(newPassword);
        if (BCrypt.checkpw(newPassword, member.getPassword())) {
            throw new IllegalArgumentException("新密码不能与原密码相同");
        }
        members.update(null, new LambdaUpdateWrapper<UmsMember>()
                .eq(UmsMember::getId, id)
                .set(UmsMember::getPassword, BCrypt.hashpw(newPassword, BCrypt.gensalt()))
                .set(UmsMember::getPasswordChangedAt, LocalDateTime.now()));
        StpUtil.logout(id);
    }

    public boolean recordLogin(
            Long memberId,
            String username,
            String ip,
            String userAgent,
            boolean success,
            String reason
    ) {
        String deviceHash = deviceHash(userAgent, ip);
        boolean known = memberId != null && devices.selectCount(new LambdaQueryWrapper<UmsMemberDevice>()
                .eq(UmsMemberDevice::getMemberId, memberId)
                .eq(UmsMemberDevice::getDeviceHash, deviceHash)
                .eq(UmsMemberDevice::getRevoked, 0)) > 0;
        UmsMemberLoginHistory event = new UmsMemberLoginHistory();
        event.setMemberId(memberId);
        event.setUsername(username);
        event.setClientIp(ip);
        event.setUserAgent(userAgent);
        event.setDeviceHash(deviceHash);
        event.setSuccess(success ? 1 : 0);
        event.setRiskFlag(success && !known ? 1 : 0);
        event.setFailureReason(reason);
        event.setCreateTime(LocalDateTime.now());
        history.insert(event);
        if (success && memberId != null) {
            devices.touch(memberId, deviceHash, deviceName(userAgent), ip);
        }
        return !known;
    }

    public List<UmsMemberLoginHistory> history(Long id) {
        return history.selectList(new LambdaQueryWrapper<UmsMemberLoginHistory>()
                .eq(UmsMemberLoginHistory::getMemberId, id)
                .orderByDesc(UmsMemberLoginHistory::getId)
                .last("LIMIT 200"));
    }

    public List<UmsMemberDevice> devices(Long id) {
        return devices.selectList(new LambdaQueryWrapper<UmsMemberDevice>()
                .eq(UmsMemberDevice::getMemberId, id)
                .orderByDesc(UmsMemberDevice::getLastSeenTime));
    }

    public void revokeDevice(Long memberId, Long deviceId) {
        if (devices.update(null, new LambdaUpdateWrapper<UmsMemberDevice>()
                .eq(UmsMemberDevice::getId, deviceId)
                .eq(UmsMemberDevice::getMemberId, memberId)
                .set(UmsMemberDevice::getRevoked, 1)) != 1) {
            throw new RuntimeException("设备不存在");
        }
    }

    public void consent(Long id, String version) {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("隐私协议版本不能为空");
        members.update(null, new LambdaUpdateWrapper<UmsMember>()
                .eq(UmsMember::getId, id)
                .set(UmsMember::getPrivacyConsentVersion, version.trim())
                .set(UmsMember::getPrivacyConsentTime, LocalDateTime.now()));
    }

    public void freeze(Long id) {
        members.update(null, new LambdaUpdateWrapper<UmsMember>()
                .eq(UmsMember::getId, id)
                .eq(UmsMember::getStatus, 1)
                .set(UmsMember::getStatus, 2));
        StpUtil.logout(id);
    }

    public void cancel(Long id, String password) {
        UmsMember member = require(id);
        if (!BCrypt.checkpw(password, member.getPassword())) throw new RuntimeException("密码错误");
        String tombstone = "cancelled_" + id + "_" + System.currentTimeMillis();
        members.update(null, new LambdaUpdateWrapper<UmsMember>()
                .eq(UmsMember::getId, id)
                .set(UmsMember::getStatus, 3)
                .set(UmsMember::getUsername, tombstone)
                .set(UmsMember::getPhone, null)
                .set(UmsMember::getEmail, null)
                .set(UmsMember::getNickname, "已注销用户")
                .set(UmsMember::getCancelledAt, LocalDateTime.now()));
        StpUtil.logout(id);
    }

    private LambdaQueryWrapper<UmsMemberLoginHistory> failedLogins(LocalDateTime since) {
        return new LambdaQueryWrapper<UmsMemberLoginHistory>()
                .eq(UmsMemberLoginHistory::getSuccess, 0)
                .ge(UmsMemberLoginHistory::getCreateTime, since);
    }

    private String passwordResetEvent(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return PASSWORD_RESET_PREFIX + hash(normalized).substring(0, 32);
    }

    private UmsMember require(Long id) {
        UmsMember member = members.selectById(id);
        if (member == null) throw new RuntimeException("账号不存在");
        return member;
    }

    private String deviceHash(String userAgent, String ip) {
        return hash((userAgent == null ? "" : userAgent) + "|" + ip);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String deviceName(String userAgent) {
        if (userAgent == null) return "Unknown";
        return userAgent.length() > 100 ? userAgent.substring(0, 100) : userAgent;
    }
}
