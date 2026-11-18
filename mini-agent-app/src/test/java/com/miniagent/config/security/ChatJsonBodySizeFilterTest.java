package com.miniagent.config.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import com.miniagent.web.dto.ChatRequest;
import jakarta.validation.Validation;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatJsonBodySizeFilterTest {

    @Test
    void rejectsDeclaredOversizedJsonBeforeDispatch() throws Exception {
        ChatJsonBodySizeFilter filter = filter(4);
        MockHttpServletRequest request = request("12345");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] dispatched = {false};

        filter.doFilter(request, response, (req, res) -> dispatched[0] = true);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("maxBytes"));
        assertFalse(dispatched[0]);
    }

    @Test
    void capsChunkedJsonWhenContentLengthIsUnknown() throws Exception {
        ChatJsonBodySizeFilter filter = filter(4);
        MockHttpServletRequest base = request("12345");
        HttpServletRequest unknownLength = new jakarta.servlet.http.HttpServletRequestWrapper(base) {
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1L; }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(unknownLength, response, (req, res) -> {
            byte[] chunk = new byte[3];
            while (req.getInputStream().read(chunk) >= 0) {
                // Drain the body as an HTTP message converter would.
            }
        });

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("exceeds configured limit"));
    }

    @Test
    void allowsBodyAtConfiguredLimit() throws Exception {
        ChatJsonBodySizeFilter filter = filter(4);
        MockHttpServletRequest request = request("1234");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] dispatched = {false};

        filter.doFilter(request, response, (req, res) -> {
            byte[] body = req.getInputStream().readAllBytes();
            assertEquals("1234", new String(body, StandardCharsets.UTF_8));
            dispatched[0] = true;
        });

        assertEquals(200, response.getStatus());
        assertTrue(dispatched[0]);
    }

    @Test
    void validatesMessageAndCollectionBounds() {
        ChatRequest request = new ChatRequest();
        request.setMessage("x".repeat(20_001));
        request.setMediaRefs(java.util.Collections.nCopies(17, null));

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);
            assertTrue(violations.size() >= 2);
        }
    }

    private static ChatJsonBodySizeFilter filter(long maxBytes) {
        ChatJsonBodySizeFilter filter = new ChatJsonBodySizeFilter();
        ReflectionTestUtils.setField(filter, "maxBytes", maxBytes);
        return filter;
    }

    private static MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/conversations/session-1/messages/stream");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
