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
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.cloud.CloudAccountClient;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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

    private CloudMemoryClient client(ProcedureSettlementModeProperties props) {
        return new CloudMemoryClient(props, json, redis, new SimpleMeterRegistry(), users, accounts);
    }

    private ProcedureSettlementModeProperties props(String url) {
        return new ProcedureSettlementModeProperties("remote", url, "", "", 100, 1000,
                300, 6, "env-1");
    }
}
