package com.aimall.server.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Stage8ControllerContractTest {
    private static final Pattern MAP_REQUEST_BODY = Pattern.compile(
            "@RequestBody\\s+(?:java\\.util\\.)?Map(?:\\s*<|\\s+[A-Za-z_$])"
    );

    @Test
    void controllersMustUseTypedRequestBodies() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        try (var paths = Files.walk(sourceRoot)) {
            List<String> violations = paths
                    .filter(path -> path.toString().endsWith("Controller.java"))
                    .filter(path -> containsMapRequestBody(path))
                    .map(Path::toString)
                    .toList();

            assertTrue(violations.isEmpty(),
                    "Controller request bodies must use validated DTOs: " + violations);
        }
    }

    private boolean containsMapRequestBody(Path path) {
        try {
            return MAP_REQUEST_BODY.matcher(Files.readString(path, StandardCharsets.UTF_8)).find();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect controller source: " + path, exception);
        }
    }
}
