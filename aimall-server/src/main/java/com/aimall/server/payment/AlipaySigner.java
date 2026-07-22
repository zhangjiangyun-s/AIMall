package com.aimall.server.payment;

import com.aimall.server.config.AlipayProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

@Component
public class AlipaySigner {
    private final AlipayProperties properties;

    public AlipaySigner(AlipayProperties properties) {
        this.properties = properties;
    }

    public String sign(Map<String, String> params) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey());
            signature.update(signContent(params, false).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("支付宝请求签名失败", e);
        }
    }

    public boolean verify(Map<String, String> params, String sign) {
        if (sign == null || sign.isBlank()) return false;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey());
            signature.update(signContent(params, true).getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(sign));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean verifyContent(String content, String sign) {
        if (content == null || sign == null || sign.isBlank()) return false;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey());
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(sign));
        } catch (Exception e) {
            return false;
        }
    }

    private String signContent(Map<String, String> params, boolean excludeSignType) {
        TreeMap<String, String> sorted = new TreeMap<>();
        params.forEach((key, value) -> {
            if (value != null && !value.isBlank() && !"sign".equals(key)
                    && !(excludeSignType && "sign_type".equals(key))) {
                sorted.put(key, value);
            }
        });
        return sorted.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private PrivateKey privateKey() throws Exception {
        String pem = Files.readString(Path.of(properties.getPrivateKeyFile()), StandardCharsets.UTF_8);
        byte[] bytes = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", ""));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private PublicKey publicKey() throws Exception {
        String pem = Files.readString(Path.of(properties.getPublicKeyFile()), StandardCharsets.UTF_8);
        byte[] bytes = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", ""));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
    }
}
