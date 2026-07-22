package com.aimall.server.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Fails fast before a production-like instance can run with unsafe defaults. */
@Configuration
public class ProductionConfigurationValidator {
    private final String environment;
    private final String dbUsername;
    private final String dbPassword;
    private final String internalSecret;
    private final String javaToAiKeyId;
    private final String javaToAiSecret;
    private final String aiToJavaKeyId;
    private final String aiToJavaSecret;
    private final String adminPassword;
    private final boolean simulationEnabled;
    private final boolean apiDocsEnabled;
    private final String aiBaseUrl;
    private final String allowedOrigins;
    private final String paymentProvider;
    private final boolean emailEnabled;
    private final String smtpHost;
    private final String emailCodePepper;
    private final boolean uploadAntivirusEnabled;
    private final boolean uploadAntivirusRequired;
    private final String observabilityToken;

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionConfigurationValidator(
            @Value("${aimall.environment:local}") String environment,
            @Value("${spring.datasource.username:}") String dbUsername,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${aimall.internal-api.secret:}") String internalSecret,
            @Value("${aimall.internal-api.java-to-ai.key-id:}") String javaToAiKeyId,
            @Value("${aimall.internal-api.java-to-ai.secret:}") String javaToAiSecret,
            @Value("${aimall.internal-api.ai-to-java.current-key-id:}") String aiToJavaKeyId,
            @Value("${aimall.internal-api.ai-to-java.current-secret:}") String aiToJavaSecret,
            @Value("${AIMALL_ADMIN_BOOTSTRAP_PASSWORD:}") String adminPassword,
            @Value("${aimall.payment.simulation-enabled:false}") boolean simulationEnabled,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled,
            @Value("${ai-service.base-url:}") String aiBaseUrl,
            @Value("${aimall.security.allowed-origins:}") String allowedOrigins,
            @Value("${aimall.payment.provider:}") String paymentProvider,
            @Value("${aimall.email.enabled:false}") boolean emailEnabled,
            @Value("${spring.mail.host:}") String smtpHost,
            @Value("${aimall.email.code-pepper:}") String emailCodePepper,
            @Value("${aimall.security.upload.antivirus.enabled:false}") boolean uploadAntivirusEnabled,
            @Value("${aimall.security.upload.antivirus.required:false}") boolean uploadAntivirusRequired,
            @Value("${aimall.observability.token:}") String observabilityToken
    ) {
        this.environment = environment;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
        this.internalSecret = internalSecret;
        this.javaToAiKeyId = javaToAiKeyId;
        this.javaToAiSecret = javaToAiSecret;
        this.aiToJavaKeyId = aiToJavaKeyId;
        this.aiToJavaSecret = aiToJavaSecret;
        this.adminPassword = adminPassword;
        this.simulationEnabled = simulationEnabled;
        this.apiDocsEnabled = apiDocsEnabled;
        this.aiBaseUrl = aiBaseUrl;
        this.allowedOrigins = allowedOrigins;
        this.paymentProvider = paymentProvider;
        this.emailEnabled = emailEnabled;
        this.smtpHost = smtpHost;
        this.emailCodePepper = emailCodePepper;
        this.uploadAntivirusEnabled = uploadAntivirusEnabled;
        this.uploadAntivirusRequired = uploadAntivirusRequired;
        this.observabilityToken = observabilityToken;
    }

    ProductionConfigurationValidator(
            String environment, String dbUsername, String dbPassword, String internalSecret,
            String adminPassword, boolean simulationEnabled, boolean apiDocsEnabled,
            String aiBaseUrl, String allowedOrigins, String paymentProvider
    ) {
        this(environment, dbUsername, dbPassword, internalSecret,
                "java-to-ai-test", internalSecret + "-java-to-ai", "ai-to-java-test", internalSecret + "-ai-to-java",
                adminPassword, simulationEnabled,
                apiDocsEnabled, aiBaseUrl, allowedOrigins, paymentProvider, true,
                "smtp.example.com", "e".repeat(40), true, true, "o".repeat(40));
    }

    ProductionConfigurationValidator(
            String environment, String dbUsername, String dbPassword, String internalSecret,
            String adminPassword, boolean simulationEnabled, boolean apiDocsEnabled,
            String aiBaseUrl, String allowedOrigins, String paymentProvider,
            boolean emailEnabled, String smtpHost, String emailCodePepper
    ) {
        this(environment, dbUsername, dbPassword, internalSecret,
                "java-to-ai-test", internalSecret + "-java-to-ai", "ai-to-java-test", internalSecret + "-ai-to-java",
                adminPassword, simulationEnabled, apiDocsEnabled, aiBaseUrl, allowedOrigins, paymentProvider,
                emailEnabled, smtpHost, emailCodePepper, true, true, "o".repeat(40));
    }

    @PostConstruct
    public void validate() {
        if (!isProductionLike()) {
            return;
        }
        requireStrong("AIMALL_DB_PASSWORD", dbPassword, 12);
        if ("root".equalsIgnoreCase(dbUsername)) {
            throw new IllegalStateException("Production must not use the MySQL root account");
        }
        requireStrong("AIMALL_JAVA_TO_AI_SECRET", javaToAiSecret, 32);
        requireStrong("AIMALL_AI_TO_JAVA_SECRET", aiToJavaSecret, 32);
        if (javaToAiKeyId == null || javaToAiKeyId.isBlank()
                || aiToJavaKeyId == null || aiToJavaKeyId.isBlank()) {
            throw new IllegalStateException("Directional internal API key IDs are required in production");
        }
        if (javaToAiSecret.equals(aiToJavaSecret)) {
            throw new IllegalStateException("Java-to-AI and AI-to-Java secrets must be different in production");
        }
        requireStrong("AIMALL_ADMIN_BOOTSTRAP_PASSWORD", adminPassword, 12);
        if (simulationEnabled) {
            throw new IllegalStateException("Payment simulation is forbidden in production");
        }
        if (paymentProvider.isBlank() || "SIMULATE".equalsIgnoreCase(paymentProvider)
                || "MOCK".equalsIgnoreCase(paymentProvider)) {
            throw new IllegalStateException("A real payment provider is required in production");
        }
        if (apiDocsEnabled) {
            throw new IllegalStateException("OpenAPI is forbidden in production");
        }
        if (aiBaseUrl.contains("localhost") || aiBaseUrl.contains("127.0.0.1")) {
            throw new IllegalStateException("Production AI service URL must use the service network");
        }
        if (allowedOrigins.isBlank() || allowedOrigins.contains("localhost") || allowedOrigins.contains("127.0.0.1")) {
            throw new IllegalStateException("Production CORS origins must be explicit deployment domains");
        }
        if (!emailEnabled) {
            throw new IllegalStateException("Email delivery must be enabled in production");
        }
        requireStrong("AIMALL_EMAIL_CODE_PEPPER", emailCodePepper, 32);
        if (emailCodePepper.contains("mailhog") || emailCodePepper.contains("not-for-production")) {
            throw new IllegalStateException("Production email code pepper must not use the local MailHog default");
        }
        String normalizedSmtpHost = smtpHost == null ? "" : smtpHost.trim().toLowerCase();
        if (normalizedSmtpHost.isBlank() || normalizedSmtpHost.equals("localhost")
                || normalizedSmtpHost.equals("127.0.0.1") || normalizedSmtpHost.equals("mailhog")) {
            throw new IllegalStateException("Production SMTP host must use a real mail provider");
        }
        if (!uploadAntivirusEnabled || !uploadAntivirusRequired) {
            throw new IllegalStateException("Production uploads must use fail-closed antivirus scanning");
        }
        requireStrong("AIMALL_OBSERVABILITY_TOKEN", observabilityToken, 32);
    }

    private boolean isProductionLike() {
        String normalized = environment == null ? "" : environment.trim().toLowerCase();
        return normalized.equals("prod") || normalized.equals("production");
    }

    private void requireStrong(String name, String value, int minLength) {
        if (value == null || value.isBlank() || value.length() < minLength
                || value.equalsIgnoreCase("123456") || value.equalsIgnoreCase("change-me")
                || value.toLowerCase().contains("replace-with")) {
            throw new IllegalStateException(name + " is missing or uses an unsafe production value");
        }
    }
}
