package com.aimall.server.config;

import cn.dev33.satoken.secure.BCrypt;
import com.aimall.server.entity.UmsAdmin;
import com.aimall.server.mapper.UmsAdminMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aimall.server.service.AdminAuthorizationService;

@Component
public class AdminInitializer implements CommandLineRunner {

    private final UmsAdminMapper adminMapper;
    private final String bootstrapUsername;
    private final String bootstrapPassword;
    private final boolean resetDefaultPassword;
    private final AdminAuthorizationService authorizationService;

    public AdminInitializer(
            UmsAdminMapper adminMapper,
            AdminAuthorizationService authorizationService,
            @Value("${AIMALL_ADMIN_BOOTSTRAP_USERNAME:admin}") String bootstrapUsername,
            @Value("${AIMALL_ADMIN_BOOTSTRAP_PASSWORD:}") String bootstrapPassword,
            @Value("${AIMALL_ADMIN_RESET_DEFAULT_PASSWORD:false}") boolean resetDefaultPassword
    ) {
        this.adminMapper = adminMapper;
        this.authorizationService = authorizationService;
        this.bootstrapUsername = bootstrapUsername == null ? "admin" : bootstrapUsername.trim();
        this.bootstrapPassword = bootstrapPassword == null ? "" : bootstrapPassword;
        this.resetDefaultPassword = resetDefaultPassword;
    }

    @Override
    public void run(String... args) {
        if (bootstrapPassword.isBlank()) {
            return;
        }
        if (bootstrapPassword.length() < 12) {
            throw new IllegalStateException("管理员初始密码长度不能少于 12 位");
        }
        UmsAdmin admin = adminMapper.selectOne(
                new LambdaQueryWrapper<UmsAdmin>()
                        .eq(UmsAdmin::getUsername, bootstrapUsername)
                        .last("limit 1")
        );
        if (admin == null) {
            String passwordHash = BCrypt.hashpw(bootstrapPassword, BCrypt.gensalt());
            UmsAdmin newAdmin = new UmsAdmin();
            newAdmin.setUsername(bootstrapUsername);
            newAdmin.setPassword(passwordHash);
            newAdmin.setNickName("\u7ba1\u7406\u5458");
            newAdmin.setStatus(1);
            adminMapper.insert(newAdmin);
            authorizationService.grantBootstrapAdmin(newAdmin.getId());
        } else if (resetDefaultPassword) {
            admin.setPassword(BCrypt.hashpw(bootstrapPassword, BCrypt.gensalt()));
            admin.setStatus(1);
            adminMapper.updateById(admin);
        }
        if (admin != null) {
            authorizationService.grantBootstrapAdmin(admin.getId());
        }
    }
}
