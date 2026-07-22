package com.aimall.server.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Stage22SecurityConfigurationTest {

    @Test
    void securityPolicyExceptionsAndWorkflowAreStructurallyComplete() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode policy = mapper.readTree(root.resolve("docs/security/stage22-security-scan-policy.json").toFile());
        JsonNode exceptions = mapper.readTree(root.resolve("docs/security/stage22-security-exceptions.json").toFile());
        assertEquals("AIMALL_STAGE22_SECURITY_POLICY_V1", policy.path("schemaVersion").asText());
        assertEquals(7.0, policy.path("blockCvssAtOrAbove").asDouble());
        assertEquals(9, policy.path("requiredReports").size());
        assertEquals(7, policy.path("exceptionRequiredFields").size());
        assertEquals(0, exceptions.path("exceptions").size());

        Path workflow = root.resolve(".github/workflows/stage22-security.yml");
        try (InputStream input = Files.newInputStream(workflow)) {
            Map<?, ?> parsed = new Yaml().load(input);
            assertNotNull(parsed);
            assertTrue(parsed.containsKey("jobs"));
        }
        String source = Files.readString(workflow, StandardCharsets.UTF_8);
        for (String required : new String[]{
                "semgrep", "dependency-check-maven", "audit --audit-level=high", "pip-audit",
                "gitleaks", "checkov", "trivy-action", "action-baseline"
        }) {
            assertTrue(source.contains(required), required);
        }
    }
}
