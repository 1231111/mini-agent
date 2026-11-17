package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.contract.MemoryApi;
import com.miniagent.memory.contract.MemoryApi.Arm;
import com.miniagent.memory.contract.MemoryApi.AssembleResponse;
import com.miniagent.memory.contract.MemoryApi.AssembledProcedure;
import com.miniagent.memory.contract.MemoryApi.ScopeType;
import com.miniagent.memory.contract.MemoryApi.UserFactMutation;
import com.miniagent.memory.contract.MemoryApi.UserFactSync;
import com.miniagent.memory.contract.MemoryApi.UserFactSyncResult;
import com.miniagent.memory.contract.MemoryApi.UserFactView;
import com.miniagent.memory.contract.ProcedureContent;
import com.miniagent.common.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.cloud.CloudAccountClient;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudMemoryClientTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final UserRepository users = mock(UserRepository.class);
    private final CloudAccountClient accounts = mock(CloudAccountClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis =
            mock(ObjectProvider.class);

    @Test
    void remoteModeRequiresServiceUrlButNotClientSigningKey() {
        assertThrows(IllegalStateException.class,
                () -> client(new ProcedureSettlementModeProperties("remote", "", "x", 100, 100)));
        ProcedureSettlementModeProperties noKey = new ProcedureSettlementModeProperties(
                "remote", "http://memory", "x", "", 100, 100, 300, 6, "");
        org.junit.jupiter.api.Assertions.assertNotNull(client(noKey));
    }

    @Test
    void assembleFallsBackToEmptyWhenServiceIsDown() throws Exception {
        CloudMemoryClient cloud = client(props("http://127.0.0.1:1"));
        try (var ignored = OwnerContext.bind(new OwnerContext(7L, "t1"))) {
            CloudMemoryClient.Assembled assembled = cloud.assemble("s1:1", "周报");
            assertTrue(assembled.fallback());
            assertTrue(assembled.response().procedures().isEmpty());
        }
    }

    @Test
    void assembleReturnsLiveProcedures() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AssembleResponse payload = new AssembleResponse(List.of(
                new AssembledProcedure(3L, 1, ScopeType.USER, "周报导出",
                        new ProcedureContent("desc", List.of(), List.of("导出"), List.of()),
                        null, "", Arm.BASELINE, 0)), false);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(MemoryApi.ASSEMBLE, exchange -> {
            hits.incrementAndGet();
            byte[] body = json.writeValueAsBytes(ApiResponse.ok(payload));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            when(users.findMemoryAccessTokenById(7L)).thenReturn(java.util.Optional.of("opaque-token"));
            when(users.findCloudTenantIdById(7L)).thenReturn(java.util.Optional.of("t1"));
            CloudMemoryClient cloud = client(props("http://127.0.0.1:" + server.getAddress().getPort()));
            try (var ignored = OwnerContext.bind(new OwnerContext(7L, "t1"))) {
                CloudMemoryClient.Assembled assembled = cloud.assemble("s1:1", "");
                assertFalse(assembled.fallback());
                assertEquals(3L, assembled.response().procedures().getFirst().itemId());
                assertEquals(1, hits.get());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void userFactSyncAndPullUseAuthenticatedMemoryApi() throws Exception {
        Instant sourceAt = Instant.parse("2026-11-16T12:00:00Z");
        UserFactView canonical = new UserFactView("人物简介格式", "preference",
                "真实照片、基本信息、事业时间线、荣誉和来源", false, 3,
                sourceAt, sourceAt);
        AtomicInteger posts = new AtomicInteger();
        AtomicInteger gets = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> authorization =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> postedBody =
                new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(MemoryApi.USER_FACTS, exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body;
            if ("POST".equals(exchange.getRequestMethod())) {
                posts.incrementAndGet();
                postedBody.set(new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
                body = json.writeValueAsBytes(ApiResponse.ok(new UserFactSyncResult(List.of(canonical))));
            } else {
                gets.incrementAndGet();
                body = json.writeValueAsBytes(ApiResponse.ok(List.of(canonical)));
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            when(users.findMemoryAccessTokenById(7L)).thenReturn(java.util.Optional.of("opaque-token"));
            when(users.findCloudTenantIdById(7L)).thenReturn(java.util.Optional.of("t-cloud"));
            CloudMemoryClient cloud = client(props("http://127.0.0.1:" + server.getAddress().getPort()));
            try (var ignored = OwnerContext.bind(new OwnerContext(7L, "local-tenant"))) {
                UserFactSync request = new UserFactSync(List.of(new UserFactMutation(
                        "人物简介格式", "preference", canonical.objectValue(), false, sourceAt)));
                UserFactSyncResult saved = cloud.syncUserFacts(request);
                List<UserFactView> fetched = cloud.userFacts();
                assertEquals(3, saved.facts().getFirst().revision());
                assertEquals("真实照片、基本信息、事业时间线、荣誉和来源",
                        fetched.getFirst().objectValue());
                assertEquals(1, posts.get());
                assertEquals(1, gets.get());
                assertEquals("Bearer opaque-token", authorization.get());
                assertTrue(postedBody.get().contains("人物简介格式"));
                assertFalse(postedBody.get().contains("tenantId"));
                assertFalse(postedBody.get().contains("userId"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void expiredCredentialRefreshesOverRealAccountHttpAndRetriesReadsAndWrites() throws Exception {
        AtomicInteger assembleHits = new AtomicInteger();
        AtomicInteger deleteHits = new AtomicInteger();
        AtomicInteger refreshHits = new AtomicInteger();
        List<String> assembleAuthorization = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<String> deleteAuthorization = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<String> refreshBody = new AtomicReference<>();
        HttpServer memoryServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        memoryServer.createContext(MemoryApi.ASSEMBLE, exchange -> {
            assembleAuthorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int status = assembleHits.incrementAndGet() == 1
                    && "Bearer expired-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    ? 401 : 200;
            byte[] body = status == 401
                    ? json.writeValueAsBytes(ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED))
                    : json.writeValueAsBytes(ApiResponse.ok(new AssembleResponse(List.of(), false)));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        memoryServer.createContext(MemoryApi.ITEMS + "/42", exchange -> {
            deleteAuthorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int status = deleteHits.incrementAndGet() == 1
                    && "Bearer expired-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    ? 401 : 200;
            byte[] body = status == 401
                    ? json.writeValueAsBytes(ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED))
                    : json.writeValueAsBytes(ApiResponse.ok(null));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpServer accountServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        accountServer.createContext("/internal/oauth/refresh", exchange -> {
            refreshHits.incrementAndGet();
            refreshBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            byte[] body = json.writeValueAsBytes(ApiResponse.ok(Map.of(
                    "userId", 7L, "username", "user", "displayName", "User", "tenantId", 77L,
                    "role", "USER", "memoryToken", "renewed-token",
                    "memoryRefreshCredential", "refresh-credential")));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        memoryServer.start();
        accountServer.start();
        try {
            when(users.findMemoryAccessTokenById(7L)).thenReturn(
                    java.util.Optional.of("expired-token"), java.util.Optional.of("renewed-token"));
            when(users.findMemoryRefreshCredentialById(7L)).thenReturn(java.util.Optional.of("refresh-credential"));
            when(users.findCloudTenantIdById(7L)).thenReturn(java.util.Optional.of("cloud-tenant"));
            when(users.updateMemoryCredentialsIfUnchanged(7L, "refresh-credential",
                    "renewed-token", "renewed-refresh-credential")).thenReturn(1);

            CloudAccountClient realAccounts = new CloudAccountClient(json,
                    "http://127.0.0.1:" + accountServer.getAddress().getPort(),
                    1000, 1000, 1, 0, 2000);
            CloudMemoryClient cloud = client(
                    props("http://127.0.0.1:" + memoryServer.getAddress().getPort()), realAccounts);
            try (var ignored = OwnerContext.bind(new OwnerContext(7L, "local-tenant"))) {
                CloudMemoryClient.Assembled result = cloud.assemble("task-1", "记忆召回");
                assertFalse(result.fallback());
                cloud.delete(42L);
            }

            assertEquals(List.of("Bearer expired-token", "Bearer renewed-token"), assembleAuthorization);
            assertEquals(List.of("Bearer renewed-token"), deleteAuthorization);
            assertEquals(2, assembleHits.get());
            assertEquals(1, deleteHits.get());
            assertEquals(1, refreshHits.get());
            assertEquals("refresh-credential", json.readTree(refreshBody.get())
                    .path("refreshCredential").asText());
            verify(users).updateMemoryCredentialsIfUnchanged(7L, "refresh-credential",
                    "renewed-token", "refresh-credential");
        } finally {
            memoryServer.stop(0);
            accountServer.stop(0);
        }
    }

    private CloudMemoryClient client(ProcedureSettlementModeProperties props) {
        return new CloudMemoryClient(props, json, redis, new SimpleMeterRegistry(), users, accounts);
    }

    private CloudMemoryClient client(ProcedureSettlementModeProperties props, CloudAccountClient accountClient) {
        return new CloudMemoryClient(props, json, redis, new SimpleMeterRegistry(), users, accountClient);
    }

    private ProcedureSettlementModeProperties props(String url) {
        return new ProcedureSettlementModeProperties("remote", url, "", "", 100, 1000,
                300, 6, "env-1");
    }
}
