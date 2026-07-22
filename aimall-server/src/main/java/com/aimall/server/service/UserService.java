package com.aimall.server.service;

import com.aimall.server.entity.UmsMember;

public interface UserService {
    UmsMember register(String username, String password, String nickname, String email, String verificationCode);
    void resetPassword(String email, String verificationCode, String newPassword);
    String login(String username, String password);
    UmsMember getById(Long id);
}
