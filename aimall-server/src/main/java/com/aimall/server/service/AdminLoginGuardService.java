package com.aimall.server.service;

import com.aimall.server.mapper.AdminLoginAttemptMapper;
import org.springframework.stereotype.Service;

@Service
public class AdminLoginGuardService {

    private static final int MAX_FAILURES = 5;
    private final AdminLoginAttemptMapper attemptMapper;

    public AdminLoginGuardService(AdminLoginAttemptMapper attemptMapper) {
        this.attemptMapper = attemptMapper;
    }

    public void checkAllowed(String username, String clientIp) {
        if (attemptMapper.recentFailures(username, clientIp) >= MAX_FAILURES) {
            throw new RuntimeException("登录失败次数过多，请 15 分钟后重试");
        }
    }

    public void recordFailure(String username, String clientIp) {
        attemptMapper.recordFailure(username, clientIp);
    }

    public void recordSuccess(String username, String clientIp) {
        attemptMapper.clear(username, clientIp);
    }
}
