package com.aimall.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.io.IOException;
import com.aimall.server.mapper.InternalRequestNonceMapper;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalServiceAuthInterceptorTest {

    private InternalRequestNonceMapper acceptingNonceMapper() {
        InternalRequestNonceMapper mapper = mock(InternalRequestNonceMapper.class);
        when(mapper.reserve(anyString(), any())).thenReturn(1);
        return mapper;
    }

    @Test
    void acceptsMatchingServiceSecret() {
        InternalServiceAuthInterceptor interceptor = new InternalServiceAuthInterceptor("test-secret", acceptingNonceMapper());
        MockHttpServletRequest request = signedRequest("test-secret", "nonce-1234567890abcdef", Instant.now().getEpochSecond());

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void rejectsMissingOrWrongServiceSecret() {
        InternalServiceAuthInterceptor interceptor = new InternalServiceAuthInterceptor("test-secret", acceptingNonceMapper());

        assertThrows(
                RuntimeException.class,
                () -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object())
        );

        MockHttpServletRequest wrongRequest = signedRequest("wrong-secret", "nonce-abcdef1234567890", Instant.now().getEpochSecond());
        assertThrows(
                RuntimeException.class,
                () -> interceptor.preHandle(wrongRequest, new MockHttpServletResponse(), new Object())
        );
    }

    @Test
    void failsClosedWhenServiceSecretIsNotConfigured() {
        InternalServiceAuthInterceptor interceptor = new InternalServiceAuthInterceptor("", acceptingNonceMapper());
        MockHttpServletRequest request = signedRequest("anything", "nonce-0000000000000000", Instant.now().getEpochSecond());

        assertThrows(
                RuntimeException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object())
        );
    }

    @Test
    void rejectsReplayAndExpiredTimestamp() {
        InternalRequestNonceMapper nonceMapper = mock(InternalRequestNonceMapper.class);
        when(nonceMapper.reserve(anyString(), any())).thenReturn(1, 0);
        InternalServiceAuthInterceptor interceptor = new InternalServiceAuthInterceptor("test-secret", nonceMapper);
        MockHttpServletRequest request = signedRequest("test-secret", "nonce-replay-123456789", Instant.now().getEpochSecond());
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertThrows(
                RuntimeException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object())
        );

        MockHttpServletRequest expired = signedRequest(
                "test-secret",
                "nonce-expired-12345678",
                Instant.now().minusSeconds(301).getEpochSecond()
        );
        assertThrows(
                RuntimeException.class,
                () -> interceptor.preHandle(expired, new MockHttpServletResponse(), new Object())
        );
    }

    @Test
    void rejectsTamperedQueryBodyAndUserToken() throws IOException {
        InternalServiceAuthInterceptor interceptor = new InternalServiceAuthInterceptor("test-secret", acceptingNonceMapper());
        long timestamp = Instant.now().getEpochSecond();

        assertThrows(RuntimeException.class, () -> interceptor.preHandle(
                signedRequestWithActualContent(
                        "test-secret", "nonce-query-123456789", timestamp,
                        "page=1", "{}", "user-token",
                        "page=2", "{}", "user-token"
                ),
                new MockHttpServletResponse(),
                new Object()
        ));
        assertThrows(RuntimeException.class, () -> interceptor.preHandle(
                signedRequestWithActualContent(
                        "test-secret", "nonce-body-1234567890", timestamp,
                        "", "{\"quantity\":1}", "user-token",
                        "", "{\"quantity\":2}", "user-token"
                ),
                new MockHttpServletResponse(),
                new Object()
        ));
        assertThrows(RuntimeException.class, () -> interceptor.preHandle(
                signedRequestWithActualContent(
                        "test-secret", "nonce-token-123456789", timestamp,
                        "", "{}", "user-token",
                        "", "{}", "another-token"
                ),
                new MockHttpServletResponse(),
                new Object()
        ));
    }

    private MockHttpServletRequest signedRequest(String secret, String nonce, long timestamp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/ai/actions/cart/add");
        String timestampText = String.valueOf(timestamp);
        String emptyHash = sha256(new byte[0]);
        String keyId = "legacy";
        String canonical = "POST\n/internal/ai/actions/cart/add\n\n"
                + emptyHash + "\n" + emptyHash + "\n" + keyId + "\n" + timestampText + "\n" + nonce;
        request.addHeader(InternalServiceAuthInterceptor.KEY_ID_HEADER, keyId);
        request.addHeader(InternalServiceAuthInterceptor.TIMESTAMP_HEADER, timestampText);
        request.addHeader(InternalServiceAuthInterceptor.NONCE_HEADER, nonce);
        request.addHeader(InternalServiceAuthInterceptor.SIGNATURE_HEADER, sign(secret, canonical));
        return request;
    }

    private CachedBodyHttpServletRequest signedRequestWithActualContent(
            String secret,
            String nonce,
            long timestamp,
            String signedQuery,
            String signedBody,
            String signedToken,
            String actualQuery,
            String actualBody,
            String actualToken
    ) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/ai/actions/cart/add");
        request.setQueryString(actualQuery);
        request.setContent(actualBody.getBytes(StandardCharsets.UTF_8));
        request.addHeader("token", actualToken);
        String timestampText = String.valueOf(timestamp);
        String keyId = "legacy";
        String canonical = "POST\n/internal/ai/actions/cart/add\n"
                + InternalRequestCanonicalizer.canonicalQuery(signedQuery)
                + "\n" + sha256(signedBody.getBytes(StandardCharsets.UTF_8))
                + "\n" + sha256(signedToken.getBytes(StandardCharsets.UTF_8))
                + "\n" + keyId + "\n" + timestampText + "\n" + nonce;
        request.addHeader(InternalServiceAuthInterceptor.KEY_ID_HEADER, keyId);
        request.addHeader(InternalServiceAuthInterceptor.TIMESTAMP_HEADER, timestampText);
        request.addHeader(InternalServiceAuthInterceptor.NONCE_HEADER, nonce);
        request.addHeader(InternalServiceAuthInterceptor.SIGNATURE_HEADER, sign(secret, canonical));
        return new CachedBodyHttpServletRequest(request, 1024);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
