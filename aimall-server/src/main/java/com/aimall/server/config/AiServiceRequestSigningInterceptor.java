package com.aimall.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class AiServiceRequestSigningInterceptor implements ClientHttpRequestInterceptor {
    private final String keyId;
    private final String secret;

    @Autowired
    public AiServiceRequestSigningInterceptor(
            @Value("${aimall.internal-api.java-to-ai.key-id:legacy}") String keyId,
            @Value("${aimall.internal-api.java-to-ai.secret:}") String secret,
            @Value("${aimall.internal-api.secret:}") String legacySecret
    ) {
        this.keyId = keyId == null || keyId.isBlank() ? "legacy" : keyId.trim();
        this.secret = secret == null || secret.isBlank()
                ? (legacySecret == null ? "" : legacySecret.trim()) : secret.trim();
    }

    AiServiceRequestSigningInterceptor(String legacySecret) {
        this("legacy", legacySecret, legacySecret);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (secret.isBlank()) throw new IOException("AI 内部服务认证未配置");
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String query = InternalRequestCanonicalizer.canonicalQuery(request.getURI().getRawQuery());
        String token = request.getHeaders().getFirst("token");
        String canonical = request.getMethod().name()
                + "\n" + request.getURI().getRawPath()
                + "\n" + query
                + "\n" + sha256(body)
                + "\n" + sha256((token == null ? "" : token).getBytes(StandardCharsets.UTF_8))
                + "\n" + keyId
                + "\n" + timestamp
                + "\n" + nonce;
        HttpHeaders headers = request.getHeaders();
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            headers.set("X-Trace-Id", traceId);
        }
        headers.set(InternalServiceAuthInterceptor.KEY_ID_HEADER, keyId);
        headers.set(InternalServiceAuthInterceptor.TIMESTAMP_HEADER, timestamp);
        headers.set(InternalServiceAuthInterceptor.NONCE_HEADER, nonce);
        headers.set(InternalServiceAuthInterceptor.SIGNATURE_HEADER, sign(canonical));
        return execution.execute(request, body);
    }

    private String sign(String canonical) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IOException("AI 内部服务签名失败", exception);
        }
    }

    private String sha256(byte[] value) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IOException("AI 内部请求摘要失败", exception);
        }
    }
}
