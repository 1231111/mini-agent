package com.miniagent.memory.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MemoryServiceClientHttpStatusTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void nonJsonAuthenticationChallengeKeepsItsStatusAndDoesNotEchoBody() {
        String body = "proxy authentication diagnostic containing private data";
        MemoryServiceClient client = client(401, body);
        MemoryServiceClient.CallFailed failure = assertThrows(MemoryServiceClient.CallFailed.class,
                () -> client.call(HttpMethod.GET, MemoryApi.USER_FACTS, null, "test-token", String.class));
        assertEquals(401, failure.httpStatus());
        assertFalse(failure.getMessage().contains(body));
    }

    @Test
    void emptyAuthenticationChallengeKeepsItsStatus() {
        MemoryServiceClient client = client(401, "");
        MemoryServiceClient.CallFailed failure = assertThrows(MemoryServiceClient.CallFailed.class,
                () -> client.call(HttpMethod.GET, MemoryApi.USER_FACTS, null, "test-token", String.class));
        assertEquals(401, failure.httpStatus());
    }

    @Test
    void gatewayFailureWithSuccessEnvelopeCannotCertifyAWrite() throws Exception {
        MemoryServiceClient client = client(503, json.writeValueAsString(ApiResponse.ok("not-committed")));
        MemoryServiceClient.CallFailed failure = assertThrows(MemoryServiceClient.CallFailed.class,
                () -> client.call(HttpMethod.POST, MemoryApi.USER_FACTS, null, "test-token", String.class));
        assertEquals(503, failure.httpStatus());
    }

    @Test
    void malformedSuccessfulResponseIsRejectedWithoutEchoingBody() {
        String body = "invalid response containing private data";
        MemoryServiceClient client = client(200, body);
        MemoryServiceClient.CallFailed failure = assertThrows(MemoryServiceClient.CallFailed.class,
                () -> client.call(HttpMethod.GET, MemoryApi.USER_FACTS, null, "test-token", String.class));
        assertEquals(200, failure.httpStatus());
        assertFalse(failure.getMessage().contains(body));
    }

    @Test
    void successfulTransportStillRequiresSuccessfulBusinessEnvelope() throws Exception {
        MemoryServiceClient client = client(200, json.writeValueAsString(
                ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN)));
        MemoryServiceClient.CallFailed failure = assertThrows(MemoryServiceClient.CallFailed.class,
                () -> client.call(HttpMethod.GET, MemoryApi.USER_FACTS, null, "test-token", String.class));
        assertEquals(200, failure.httpStatus());
        assertNotNull(failure.code());
    }

    @Test
    void validSuccessReturnsTheCanonicalPayload() throws Exception {
        MemoryServiceClient client = client(200, json.writeValueAsString(ApiResponse.ok("canonical-value")));
        assertEquals("canonical-value", client.call(HttpMethod.GET, MemoryApi.USER_FACTS,
                null, "test-token", String.class));
    }

    private MemoryServiceClient client(int status, String body) {
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        return new MemoryServiceClient("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(5), json);
    }
}
