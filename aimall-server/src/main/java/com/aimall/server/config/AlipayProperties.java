package com.aimall.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aimall.payment.alipay")
public class AlipayProperties {
    private boolean enabled;
    private String appId;
    private String sellerId;
    private String privateKeyFile;
    private String publicKeyFile;
    private String gatewayUrl;
    private String notifyBaseUrl;
    private String returnUrl;
    private String signType = "RSA2";
    private String charset = "UTF-8";
    private String format = "json";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getSellerId() { return sellerId; }
    public void setSellerId(String sellerId) { this.sellerId = sellerId; }
    public String getPrivateKeyFile() { return privateKeyFile; }
    public void setPrivateKeyFile(String privateKeyFile) { this.privateKeyFile = privateKeyFile; }
    public String getPublicKeyFile() { return publicKeyFile; }
    public void setPublicKeyFile(String publicKeyFile) { this.publicKeyFile = publicKeyFile; }
    public String getGatewayUrl() { return gatewayUrl; }
    public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }
    public String getNotifyBaseUrl() { return notifyBaseUrl; }
    public void setNotifyBaseUrl(String notifyBaseUrl) { this.notifyBaseUrl = notifyBaseUrl; }
    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }
    public String getSignType() { return signType; }
    public void setSignType(String signType) { this.signType = signType; }
    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
}
