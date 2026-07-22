package com.aimall.server.observability;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Stage21CapacityConfigurationTest {

    @Test
    @SuppressWarnings("unchecked")
    void prometheusCapacityRulesAreValidYamlAndComplete() throws Exception {
        Path alerts = Path.of("..", "docker", "observability", "alerts.yml").normalize();
        try (InputStream input = Files.newInputStream(alerts)) {
            Map<String, Object> root = new Yaml().load(input);
            List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
            assertNotNull(groups);
            Map<String, Object> capacity = groups.stream()
                    .filter(group -> "aimall-stage21-capacity".equals(group.get("name")))
                    .findFirst()
                    .orElseThrow();
            List<Map<String, Object>> rules = (List<Map<String, Object>>) capacity.get("rules");
            assertEquals(8, rules.size());
            assertTrue(rules.stream().allMatch(rule ->
                    rule.containsKey("alert") && rule.containsKey("expr") && rule.containsKey("for")
                            && rule.containsKey("labels") && rule.containsKey("annotations")
            ));
        }
    }
}
