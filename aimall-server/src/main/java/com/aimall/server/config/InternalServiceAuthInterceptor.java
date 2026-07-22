package com.aimall.server.config;

import com.aimall.server.mapper.InternalRequestNonceMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@Component
public class InternalServiceAuthInterceptor implements HandlerInterceptor {
    public static final String TIMESTAMP_HEADER = "X-AIMall-Timestamp";
    public static final String NONCE_HEADER = "X-AIMall-Nonce";
    public static final String SIGNATURE_HEADER = "X-AIMall-Signature";
    public static final String KEY_ID_HEADER = "X-AIMall-Key-Id";
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;

    private final Map<String, String> inboundKeys;
    private final InternalRequestNonceMapper nonceMapper;

    @Autowired
    public InternalServiceAuthInterceptor(
            @Value("${aimall.internal-api.ai-to-java.current-key-id:legacy}") String currentKeyId,
            @Value("${aimall.internal-api.ai-to-java.current-secret:}") String currentSecret,
            @Value("${aimall.internal-api.ai-to-java.previous-key-id:}") String previousKeyId,
            @Value("${aimall.internal-api.ai-to-java.previous-secret:}") String previousSecret,
            @Value("${aimall.internal-api.secret:}") String legacySecret,
            InternalRequestNonceMapper nonceMapper
    ) {
        this.inboundKeys = configuredKeys(currentKeyId, currentSecret, previousKeyId, previousSecret, legacySecret);
        this.nonceMapper = nonceMapper;
    }

    InternalServiceAuthInterceptor(String legacySecret, InternalRequestNonceMapper nonceMapper) {
        this("legacy", legacySecret, "", "", legacySecret, nonceMapper);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String keyId = request.getHeader(KEY_ID_HEADER);
        String secret = keyId == null ? null : inboundKeys.get(keyId);
        if (secret == null || secret.isBlank()) throw new RuntimeException("内部服务认证失败");

        String timestampText = request.getHeader(TIMESTAMP_HEADER);
        String nonce = request.getHeader(NONCE_HEADER);
        String providedSignature = request.getHeader(SIGNATURE_HEADER);
        long timestamp = parseTimestamp(timestampText);
        if (timestamp <= 0 || Math.abs(Instant.now().getEpochSecond() - timestamp) > MAX_CLOCK_SKEW_SECONDS
                || nonce == null || nonce.length() < 16 || providedSignature == null) {
            throw new RuntimeException("内部服务认证失败");
        }
        byte[] body = request instanceof CachedBodyHttpServletRequest cachedRequest
                ? cachedRequest.cachedBody() : new byte[0];
        String token = request.getHeader("token") == null ? "" : request.getHeader("token");
        String canonical = request.getMethod().toUpperCase()
                + "\n" + request.getRequestURI()
                + "\n" + InternalRequestCanonicalizer.canonicalQuery(request.getQueryString())
                + "\n" + sha256(body)
                + "\n" + sha256(token.getBytes(StandardCharsets.UTF_8))
                + "\n" + keyId
                + "\n" + timestampText
                + "\n" + nonce;
        if (!constantTimeEquals(sign(secret, canonical), providedSignature)) {
            throw new RuntimeException("内部服务认证失败");
        }
        String nonceStorageKey = sha256((keyId + ":" + nonce).getBytes(StandardCharsets.UTF_8));
        if (nonceMapper.reserve(nonceStorageKey, LocalDateTime.now().plusSeconds(MAX_CLOCK_SKEW_SECONDS)) != 1) {
            throw new RuntimeException("内部服务请求已被使用");
        }
        return true;
    }

    private Map<String, String> configuredKeys(
            String currentKeyId,
            String currentSecret,
            String previousKeyId,
            String previousSecret,
            String legacySecret
    ) {
        Map<String, String> keys = new HashMap<>();
        String resolvedCurrentId = blankToDefault(currentKeyId, "legacy");
        String resolvedCurrentSecret = blankToDefault(currentSecret, legacySecret);
        if (!resolvedCurrentSecret.isBlank()) keys.put(resolvedCurrentId, resolvedCurrentSecret);
        if (previousKeyId != null && !previousKeyId.isBlank()
                && previousSecret != null && !previousSecret.isBlank()) {
            keys.put(previousKeyId.trim(), previousSecret.trim());
        }
        return Map.copyOf(keys);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? (defaultValue == null ? "" : defaultValue.trim()) : value.trim();
    }

    private long parseTimestamp(String timestamp) {
        try {
            return Long.parseLong(timestamp);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("内部服务签名初始化失败", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("内部请求摘要计算失败", exception);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
