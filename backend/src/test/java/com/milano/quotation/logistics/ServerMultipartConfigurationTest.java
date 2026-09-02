package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerMultipartConfigurationTest {
    @Test void tomcatPartLimitCoversOneHundredFilesAndFormFields() throws Exception {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            var yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yaml.contains("max-part-count: ${APP_MAX_PART_COUNT:110}"));
        }
    }
}
