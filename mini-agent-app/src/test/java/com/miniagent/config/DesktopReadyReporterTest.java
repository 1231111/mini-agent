package com.miniagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class DesktopReadyReporterTest {

    @Test
    void readyLineCarriesPortNonceAndBuildId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DesktopReadyReporter reporter = new DesktopReadyReporter(
                objectMapper,
                mock(WebServerApplicationContext.class),
                "nonce-123");

        String line = reporter.readyLine(49152);
        JsonNode payload = objectMapper.readTree(
                line.substring(DesktopReadyReporter.READY_PREFIX.length()));

        assertEquals(49152, payload.path("port").asInt());
        assertEquals("nonce-123", payload.path("nonce").asText());
        assertFalse(payload.path("buildId").asText().isBlank());
    }
}
