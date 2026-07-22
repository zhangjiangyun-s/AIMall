package com.aimall.server.user;

import cn.dev33.satoken.stp.StpUtil;
import com.aimall.server.common.ApiResponse;
import com.aimall.server.common.ClientIpResolver;
import com.aimall.server.common.RequestUtils;
import com.aimall.server.entity.UmsMember;
import com.aimall.server.service.EmailVerificationService;
import com.aimall.server.service.UserService;
import com.aimall.server.service.impl.AccountSecurityService;
import com.aimall.server.user.dto.EmailCodeRequest;
import com.aimall.server.user.dto.MemberRegisterRequest;
import com.aimall.server.user.dto.PasswordResetRequest;
import com.aimall.server.user.dto.UserLoginRequest;
import com.aimall.server.user.dto.PasswordChangeRequest;
import com.aimall.server.user.dto.PrivacyConsentRequest;
import com.aimall.server.user.dto.AccountCancelRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {
    private final UserService userService;
    private final AccountSecurityService accountSecurityService;
    private final ClientIpResolver clientIpResolver;
    private final EmailVerificationService emailVerificationService;

    public UserController(
            UserService userService,
            AccountSecurityService accountSecurityService,
            ClientIpResolver clientIpResolver,
            EmailVerificationService emailVerificationService
    ) {
        this.userService = userService;
        this.accountSecurityService = accountSecurityService;
        this.clientIpResolver = clientIpResolver;
        this.emailVerificationService = emailVerificationService;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(
            @Valid @RequestBody MemberRegisterRequest params,
            HttpServletRequest request
    ) {
        String ip = clientIpResolver.resolve(request);
        String userAgent = request.getHeader("User-Agent");
        accountSecurityService.checkRegistrationAllowed(ip, userAgent);
        UmsMember member;
        try {
            member = userService.register(
                    params.username(), params.password(), params.nickname(),
                    params.email(), params.verificationCode()
            );
            accountSecurityService.recordRegistration(ip, userAgent, true);
        } catch (RuntimeException exception) {
            accountSecurityService.recordRegistration(ip, userAgent, false);
            throw exception;
        }
        return ApiResponse.success(userInfo(member));
    }

    @PostMapping("/email/code")
    public ApiResponse<Map<String, Object>> sendEmailCode(
            @Valid @RequestBody EmailCodeRequest params,
            HttpServletRequest request
    ) {
        emailVerificationService.sendCode(
                params.email(),
                EmailVerificationService.Purpose.valueOf(params.purpose()),
                clientIpResolver.resolve(request)
        );
        return ApiResponse.success(Map.of("accepted", true, "cooldownSeconds", 60));
    }

    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(
            @Valid @RequestBody PasswordResetRequest params,
            HttpServletRequest request
    ) {
        String ip = clientIpResolver.resolve(request);
        String userAgent = request.getHeader("User-Agent");
        accountSecurityService.checkPasswordResetAllowed(params.email(), ip, userAgent);
        try {
            userService.resetPassword(params.email(), params.verificationCode(), params.newPassword());
            accountSecurityService.recordPasswordReset(params.email(), ip, userAgent, true);
        } catch (RuntimeException exception) {
            accountSecurityService.recordPasswordReset(params.email(), ip, userAgent, false);
            throw exception;
        }
        return ApiResponse.success(null);
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(
            @Valid @RequestBody UserLoginRequest params,
            HttpServletRequest request
    ) {
        String username = params.username();
        String ip = clientIpResolver.resolve(request);
        String userAgent = request.getHeader("User-Agent");
        accountSecurityService.checkLoginAllowed(username, ip, userAgent);
        String token;
        try {
            token = userService.login(username, params.password());
        } catch (RuntimeException exception) {
            accountSecurityService.recordLogin(null, username, ip, userAgent, false, "AUTH_FAILED");
            throw exception;
        }
        UmsMember member = userService.getById(StpUtil.getLoginIdAsLong());
        boolean riskLogin = accountSecurityService.recordLogin(member.getId(), username, ip, userAgent, true, null);
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userInfo", userInfo(member));
        data.put("riskLogin", riskLogin);
        return ApiResponse.success(data);
    }

    @PostMapping("/password/change")
    public ApiResponse<Void> changePassword(@Valid @RequestBody PasswordChangeRequest params) {
        accountSecurityService.changePassword(
                StpUtil.getLoginIdAsLong(),
                params.oldPassword(),
                params.newPassword()
        );
        return ApiResponse.success(null);
    }

    @GetMapping("/security/login-history")
    public ApiResponse<java.util.List<com.aimall.server.entity.UmsMemberLoginHistory>> loginHistory() {
        return ApiResponse.success(accountSecurityService.history(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/security/devices")
    public ApiResponse<java.util.List<com.aimall.server.entity.UmsMemberDevice>> devices() {
        return ApiResponse.success(accountSecurityService.devices(StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/security/devices/{id}/revoke")
    public ApiResponse<Void> revoke(@PathVariable Long id) {
        accountSecurityService.revokeDevice(StpUtil.getLoginIdAsLong(), id);
        return ApiResponse.success(null);
    }

    @PostMapping("/privacy/consent")
    public ApiResponse<Void> consent(@Valid @RequestBody PrivacyConsentRequest params) {
        accountSecurityService.consent(
                StpUtil.getLoginIdAsLong(), params.version()
        );
        return ApiResponse.success(null);
    }

    @PostMapping("/freeze")
    public ApiResponse<Void> freeze() {
        accountSecurityService.freeze(StpUtil.getLoginIdAsLong());
        return ApiResponse.success(null);
    }

    @PostMapping("/cancel")
    public ApiResponse<Void> cancel(@Valid @RequestBody AccountCancelRequest params) {
        accountSecurityService.cancel(
                StpUtil.getLoginIdAsLong(), params.password()
        );
        return ApiResponse.success(null);
    }

    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info() {
        return ApiResponse.success(userInfo(userService.getById(StpUtil.getLoginIdAsLong())));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        StpUtil.logout();
        return ApiResponse.success(null);
    }

    private Map<String, Object> userInfo(UmsMember member) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", member.getId());
        data.put("username", member.getUsername());
        data.put("nickname", member.getNickname());
        data.put("phone", member.getPhone());
        data.put("email", member.getEmail());
        return data;
    }
}
