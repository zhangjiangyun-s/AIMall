package com.aimall.server.service;

import com.aimall.server.entity.UmsAdmin;
import com.aimall.server.mapper.UmsAdminMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;

@Service
public class AdminMfaService {

    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private final UmsAdminMapper adminMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminMfaService(UmsAdminMapper adminMapper) {
        this.adminMapper = adminMapper;
    }

    public String prepare(Long adminId) {
        UmsAdmin admin = requireAdmin(adminId);
        byte[] secret = new byte[20];
        secureRandom.nextBytes(secret);
        String encoded = encodeBase32(secret);
        admin.setMfaSecret(encoded);
        admin.setMfaEnabled(0);
        adminMapper.updateById(admin);
        return encoded;
    }

    public void enable(Long adminId, String code) {
        UmsAdmin admin = requireAdmin(adminId);
        if (admin.getMfaSecret() == null || !verify(admin.getMfaSecret(), code)) {
            throw new RuntimeException("动态验证码错误");
        }
        admin.setMfaEnabled(1);
        adminMapper.updateById(admin);
    }

    public void disable(Long adminId, String code) {
        UmsAdmin admin = requireAdmin(adminId);
        if (admin.getMfaEnabled() == null || admin.getMfaEnabled() != 1 || !verify(admin.getMfaSecret(), code)) {
            throw new RuntimeException("动态验证码错误");
        }
        admin.setMfaEnabled(0);
        admin.setMfaSecret(null);
        adminMapper.updateById(admin);
    }

    public boolean verify(String secret, String code) {
        if (secret == null || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        long timeWindow = System.currentTimeMillis() / 30_000L;
        for (long candidate = timeWindow - 1; candidate <= timeWindow + 1; candidate++) {
            String expected = generateCode(secret, candidate);
            if (MessageDigest.isEqual(expected.getBytes(), code.getBytes())) {
                return true;
            }
        }
        return false;
    }

    private String generateCode(String secret, long timeWindow) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(timeWindow).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (Exception exception) {
            throw new IllegalStateException("MFA 验证初始化失败", exception);
        }
    }

    private UmsAdmin requireAdmin(Long adminId) {
        UmsAdmin admin = adminMapper.selectById(adminId);
        if (admin == null || admin.getStatus() == null || admin.getStatus() != 1) {
            throw new RuntimeException("管理员不存在或已禁用");
        }
        return admin;
    }

    private String encodeBase32(byte[] bytes) {
        StringBuilder output = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                output.append(BASE32[(buffer >> (bitsLeft - 5)) & 31]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            output.append(BASE32[(buffer << (5 - bitsLeft)) & 31]);
        }
        return output.toString();
    }

    private byte[] decodeBase32(String value) {
        String normalized = value.replace("=", "").toUpperCase();
        byte[] result = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (char character : normalized.toCharArray()) {
            int digit = character >= 'A' && character <= 'Z' ? character - 'A'
                    : character >= '2' && character <= '7' ? character - '2' + 26 : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("非法 MFA 密钥");
            }
            buffer = (buffer << 5) | digit;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return result;
    }
}
