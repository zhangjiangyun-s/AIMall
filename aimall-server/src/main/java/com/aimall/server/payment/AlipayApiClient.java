package com.aimall.server.payment;

import com.aimall.server.config.AlipayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AlipayApiClient {
    private final AlipayProperties properties;
    private final AlipaySigner signer;
    private final ObjectMapper objectMapper;

    public AlipayApiClient(AlipayProperties properties, AlipaySigner signer, ObjectMapper objectMapper) {
        this.properties = properties;
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    public JsonNode execute(String method, String bizContent, String responseField) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", properties.getAppId());
        params.put("method", method);
        params.put("format", properties.getFormat());
        params.put("charset", properties.getCharset());
        params.put("sign_type", properties.getSignType());
        params.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        params.put("version", "1.0");
        params.put("biz_content", bizContent);
        params.put("sign", signer.sign(params));
        try {
            String response = post(params);
            JsonNode root = objectMapper.readTree(response);
            JsonNode payload = root.path(responseField);
            if (payload.isMissingNode()) throw new IllegalStateException("支付宝响应缺少 " + responseField);
            String responseSign = root.path("sign").asText("");
            String signedContent = extractObject(response, responseField);
            if (!signer.verifyContent(signedContent, responseSign)) {
                throw new IllegalStateException("支付宝响应验签失败");
            }
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException("支付宝接口请求失败", e);
        }
    }

    private String post(Map<String, String> params) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(properties.getGatewayUrl()).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(10000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=" + properties.getCharset());
        String body = params.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        try (var stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            if (stream == null) throw new IOException("支付宝返回空响应");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String extractObject(String json, String field) {
        int fieldIndex = json.indexOf("\"" + field + "\"");
        int start = fieldIndex < 0 ? -1 : json.indexOf('{', fieldIndex);
        if (start < 0) throw new IllegalStateException("无法提取支付宝签名响应体");
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char current = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') quoted = true;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return json.substring(start, i + 1);
        }
        throw new IllegalStateException("支付宝签名响应体不完整");
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
