package com.miniagent.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class McpStdioClientFrameLimitTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsBoundedContentLengthFrame() throws Exception {
        byte[] body = JSON.writeValueAsBytes(JSON.createObjectNode().put("jsonrpc", "2.0"));
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] frame = new byte[header.length + body.length];
        System.arraycopy(header, 0, frame, 0, header.length);
        System.arraycopy(body, 0, frame, header.length, body.length);

        assertEquals("2.0", McpStdioClient.readOneMessage(new ByteArrayInputStream(frame), 4096)
                .path("jsonrpc").asText());
    }

    @Test
    void rejectsOversizedDeclaredFrameBeforeReadingBody() {
        byte[] header = "Content-Length: 2147483647\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

        assertThrows(IllegalStateException.class,
                () -> McpStdioClient.readOneMessage(new ByteArrayInputStream(header), 4096));
    }

    @Test
    void rejectsOverlongHeaderLineAndCumulativeHeaders() {
        byte[] overlongLine = ("X-Header: " + "a".repeat(16 * 1024) + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] cumulative = ("X-A: " + "a".repeat(9_000) + "\r\n"
                + "X-B: " + "b".repeat(9_000) + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        assertThrows(IllegalStateException.class,
                () -> McpStdioClient.readOneMessage(new ByteArrayInputStream(overlongLine), 4096));
        assertThrows(IllegalStateException.class,
                () -> McpStdioClient.readOneMessage(new ByteArrayInputStream(cumulative), 4096));
    }

    @Test
    void rejectsDuplicateContentLengthHeaders() {
        byte[] duplicate = "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}"
                .getBytes(StandardCharsets.US_ASCII);

        assertThrows(IllegalStateException.class,
                () -> McpStdioClient.readOneMessage(new ByteArrayInputStream(duplicate), 4096));
    }

    @Test
    void ndjsonUsesFrameLimitRatherThanHeaderLimit() throws Exception {
        String text = "{\"payload\":\"" + "x".repeat(20_000) + "\"}";

        assertEquals(20_000, McpStdioClient.readOneMessage(
                new ByteArrayInputStream((text + "\n").getBytes(StandardCharsets.UTF_8)), 30_000)
                .path("payload").asText().length());
    }
}
