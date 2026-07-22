package com.aimall.server.service.impl;

import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.entity.UmsMember;
import com.aimall.server.mapper.UmsMemberMapper;
import com.aimall.server.service.EmailVerificationService;
import com.aimall.server.service.EmailVerificationException;
import com.aimall.server.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class UserServiceImpl implements UserService {
    private final UmsMemberMapper memberMapper;
    private final AccountSecurityService accountSecurityService;
    private final EmailVerificationService emailVerificationService;

    public UserServiceImpl(
            UmsMemberMapper memberMapper,
            AccountSecurityService accountSecurityService,
            EmailVerificationService emailVerificationService
    ) {
        this.memberMapper = memberMapper;
        this.accountSecurityService = accountSecurityService;
        this.emailVerificationService = emailVerificationService;
    }

    @Override
    @Transactional(noRollbackFor = EmailVerificationException.class)
    public UmsMember register(
            String username,
            String password,
            String nickname,
            String email,
            String verificationCode
    ) {
        accountSecurityService.validatePassword(password);
        String normalizedEmail = emailVerificationService.normalize(email);
        if (memberMapper.selectCount(
                new LambdaQueryWrapper<UmsMember>().eq(UmsMember::getUsername, username)
        ) > 0) {
            throw new IllegalArgumentException("用户名已存在");
        }
        if (memberMapper.selectCount(
                new LambdaQueryWrapper<UmsMember>().eq(UmsMember::getEmail, normalizedEmail)
        ) > 0) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        emailVerificationService.consume(
                normalizedEmail,
                EmailVerificationService.Purpose.REGISTER,
                verificationCode
        );

        UmsMember member = new UmsMember();
        member.setUsername(username.trim());
        member.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        member.setNickname(nickname == null || nickname.isBlank() ? username.trim() : nickname.trim());
        member.setEmail(normalizedEmail);
        member.setStatus(1);
        member.setMemberLevel("NORMAL");
        member.setPasswordChangedAt(LocalDateTime.now());
        memberMapper.insert(member);
        return member;
    }

    @Override
    @Transactional(noRollbackFor = EmailVerificationException.class)
    public void resetPassword(String email, String verificationCode, String newPassword) {
        accountSecurityService.validatePassword(newPassword);
        String normalizedEmail = emailVerificationService.normalize(email);
        UmsMember member = memberMapper.selectOne(
                new LambdaQueryWrapper<UmsMember>().eq(UmsMember::getEmail, normalizedEmail)
        );
        if (member == null || member.getStatus() == null || member.getStatus() != 1) {
            throw new IllegalArgumentException("验证码不存在或已失效");
        }
        emailVerificationService.consume(
                normalizedEmail,
                EmailVerificationService.Purpose.PASSWORD_RESET,
                verificationCode
        );
        if (BCrypt.checkpw(newPassword, member.getPassword())) {
            throw new IllegalArgumentException("新密码不能与原密码相同");
        }
        int updated = memberMapper.update(null, new LambdaUpdateWrapper<UmsMember>()
                .eq(UmsMember::getId, member.getId())
                .eq(UmsMember::getStatus, 1)
                .set(UmsMember::getPassword, BCrypt.hashpw(newPassword, BCrypt.gensalt()))
                .set(UmsMember::getPasswordChangedAt, LocalDateTime.now()));
        if (updated != 1) {
            throw new IllegalStateException("密码重置失败，请重新操作");
        }
        StpUtil.logout(member.getId());
    }

    @Override
    public String login(String username, String password) {
        UmsMember member = memberMapper.selectOne(
                new LambdaQueryWrapper<UmsMember>().eq(UmsMember::getUsername, username)
        );
        if (member == null || !BCrypt.checkpw(password, member.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (member.getStatus() == null || member.getStatus() != 1) {
            throw new IllegalStateException("账号不可用");
        }
        StpUtil.login(member.getId());
        return StpUtil.getTokenValue();
    }

    @Override
    public UmsMember getById(Long id) {
        return memberMapper.selectById(id);
    }
}
