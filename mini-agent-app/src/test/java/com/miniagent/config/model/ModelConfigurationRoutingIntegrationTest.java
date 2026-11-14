package com.miniagent.config.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.core.NodeExecutor;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.planner.GoalCompiler;
import com.miniagent.agent.planner.GraphScheduler;
import com.miniagent.agent.planner.PlanValidator;
import com.miniagent.agent.planner.PlannerMetrics;
import com.miniagent.agent.planner.PlannerProperties;
import com.miniagent.agent.planner.PlannerStateStore;
import com.miniagent.agent.planner.PlanningLoop;
import com.miniagent.agent.planner.PlanningRoute;
import com.miniagent.agent.planner.RecoveryEngine;
import com.miniagent.agent.planner.SessionLock;
import com.miniagent.agent.planner.StepEvaluator;
import com.miniagent.agent.planner.TodoStateProjector;
import com.miniagent.agent.planner.ToolRouter;
import com.miniagent.agent.planner.ToolSuccessStats;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.common.model.EffectiveModelContext;
import com.miniagent.config.entity.UserModelConfig;
import com.miniagent.config.repository.UserModelConfigRepository;
import com.miniagent.config.security.SecretCryptoService;
import com.miniagent.config.service.UserModelConfigService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises the real saved configuration, OpenAI client and semantic router over loopback HTTP. */
class ModelConfigurationRoutingIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Long USER_ID = 73L;
    private static final String USER_KEY = "sk-fixture-user-key";
    private static final String USER_MODEL = "fixture-user-model";
    private static final String REQUEST = "帮我查询今日 AI 新闻";

    @BeforeEach
    void clearInheritedContext() {
        EffectiveModelContext.clear();
        PermissionContext.clear();
    }

    @AfterEach
    void clearContext() {
        EffectiveModelContext.clear();
        PermissionContext.clear();
    }

    @Test
    void savedEndpointKeyAndModelReachRouterWithoutUsingCachedPlatformClient() throws Exception {
        try (HttpFixture platform = new HttpFixture(200, successfulRouteResponse());
             HttpFixture custom = new HttpFixture(200, successfulRouteResponse())) {
            ConfigurationFixture configuration = configuration(platform.baseUrl());
            ModelClientFactory.ResolvedModels platformModels = configuration.factory().resolve(USER_ID);
            assertEquals(platform.baseUrl(), platformModels.settings().baseUrl());

            Map<String, Object> saved = configuration.service().save(USER_ID, "default",
                    custom.baseUrl(), USER_MODEL, USER_KEY);
            assertEquals(Boolean.TRUE, saved.get("success"));
            Map<?, ?> current = (Map<?, ?>) saved.get("current");
            assertEquals(custom.baseUrl(), current.get("baseUrl"));
            assertEquals(USER_MODEL, current.get("modelName"));
            assertEquals("user", current.get("apiKeySource"));
            assertFalse(saved.toString().contains(USER_KEY), "Settings response must never expose the full key");
            assertTrue(configuration.crypto().isEncrypted(configuration.stored().get().getCustomApiKey()));

            ModelClientFactory.ResolvedModels userModels = configuration.factory().resolve(USER_ID);
            assertNotSame(platformModels.chat(), userModels.chat(), "Saving settings must bypass the stale cache entry");
            assertEquals(custom.baseUrl(), userModels.settings().baseUrl());
            assertEquals(USER_MODEL, userModels.settings().modelName());
            assertEquals(USER_KEY, userModels.settings().apiKey());
            PlannerFixture planner = planner();

            PlanningRoute route = planner.loop().selectRoute(userModels.chat(), TaskPlan.of(REQUEST),
                    "custom-model-session", REQUEST, null, List.of());

            assertEquals(PlanningRoute.Strategy.DIRECT, route.strategy());
            assertEquals(Boolean.FALSE, route.artifactDeliveryRequested());
            assertEquals(1, custom.requests().size());
            assertUserRequest(custom.requests().get(0));
            assertTrue(platform.requests().isEmpty(), "The platform endpoint must receive no request");
            verifyNoInteractions(planner.executor(), planner.scheduler());
        }
    }

    @Test
    void upstream401RemainsAuthenticationFailureAndNeverDispatchesExecution() throws Exception {
        String error = JSON.writeValueAsString(Map.of("error", Map.of(
                "message", "fixture invalid key", "type", "invalid_request_error", "code", "invalid_key")));
        try (HttpFixture platform = new HttpFixture(200, successfulRouteResponse());
             HttpFixture custom = new HttpFixture(401, error)) {
            ConfigurationFixture configuration = configuration(platform.baseUrl());
            // Populate the default cache first, reproducing a settings change in a running desktop client.
            configuration.factory().resolve(USER_ID);
            Map<String, Object> saved = configuration.service().save(USER_ID, "default",
                    custom.baseUrl(), USER_MODEL, USER_KEY);
            assertEquals(Boolean.TRUE, saved.get("success"));
            ModelClientFactory.ResolvedModels models = configuration.factory().resolve(USER_ID);
            PlannerFixture planner = planner();
            String session = "invalid-key-session";

            PlanningRoute route = planner.loop().selectRoute(models.chat(), TaskPlan.of(REQUEST),
                    session, REQUEST, null, List.of());
            PlanningLoop.RunOutcome outcome = planner.loop().runWithOutcome(models.chat(), "system", REQUEST,
                    null, List.of(), TaskPlan.of(REQUEST), session, "fence", "execution", null, route);

            assertFalse(route.resolved());
            assertEquals("ROUTER_MODEL_AUTHENTICATION_FAILED", route.reason());
            assertEquals(PlanningLoop.Outcome.FAILED, outcome.outcome());
            assertEquals(route.reason(), outcome.reason());
            assertTrue(outcome.answer().contains("401"), outcome.answer());
            assertTrue(outcome.answer().contains("认证"), outcome.answer());
            assertFalse(outcome.answer().contains(USER_KEY));
            assertFalse(outcome.answer().contains("fixture invalid key"), "Do not expose upstream error bodies");
            assertFalse(custom.requests().isEmpty(), "The configured endpoint must actually be called");
            custom.requests().forEach(ModelConfigurationRoutingIntegrationTest::assertUserRequest);
            assertTrue(platform.requests().isEmpty(), "Authentication errors must not fall back to platform credentials");
            assertTrue(planner.store().get(session).isEmpty(), "A failed routing call must not install a task graph");
            verifyNoInteractions(planner.executor(), planner.scheduler());
        }
    }

    private static void assertUserRequest(CapturedRequest request) {
        assertEquals("POST", request.method());
        assertEquals("/v1/chat/completions", request.path());
        assertEquals("Bearer " + USER_KEY, request.authorization());
        assertEquals(USER_MODEL, request.body().path("model").asText());
        assertTrue(request.body().path("messages").isArray());
        assertTrue(request.body().path("messages").toString().contains(REQUEST));
    }

    private static ConfigurationFixture configuration(String platformBaseUrl) {
        AtomicReference<UserModelConfig> stored = new AtomicReference<>();
        UserModelConfigRepository repository = mock(UserModelConfigRepository.class);
        when(repository.findByUserId(anyLong())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.save(any(UserModelConfig.class))).thenAnswer(invocation -> {
            UserModelConfig row = invocation.getArgument(0);
            stored.set(row);
            return row;
        });
        SecretCryptoService crypto = new SecretCryptoService();
        ReflectionTestUtils.setField(crypto, "encodedKey", Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        ReflectionTestUtils.invokeMethod(crypto, "init");

        AgentModelsProperties properties = new AgentModelsProperties();
        AgentModelsProperties.Preset preset = new AgentModelsProperties.Preset();
        preset.setId("default");
        preset.setLabel("Platform fixture");
        preset.setBaseUrl(platformBaseUrl);
        preset.setModelName("fixture-platform-model");
        properties.setDefaultPreset("default");
        properties.setPresets(List.of(preset));

        UserModelConfigService service = new UserModelConfigService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "crypto", crypto);
        ReflectionTestUtils.setField(service, "modelsProperties", properties);
        ReflectionTestUtils.setField(service, "globalBaseUrl", platformBaseUrl);
        ReflectionTestUtils.setField(service, "globalModelName", "fixture-platform-model");
        ReflectionTestUtils.setField(service, "globalApiKey", "sk-fixture-platform-key");
        ReflectionTestUtils.setField(service, "customBaseUrlEnabled", true);

        ModelClientFactory factory = new ModelClientFactory();
        ReflectionTestUtils.setField(factory, "userModelConfigService", service);
        ReflectionTestUtils.setField(factory, "timeout", Duration.ofSeconds(5));
        ReflectionTestUtils.setField(factory, "visionPreset", "");
        return new ConfigurationFixture(service, factory, stored, crypto);
    }

    private static PlannerFixture planner() {
        PlannerProperties properties = new PlannerProperties();
        PlannerStateStore store = new PlannerStateStore();
        NodeExecutor executor = mock(NodeExecutor.class);
        GraphScheduler scheduler = mock(GraphScheduler.class);
        PlanningLoop loop = new PlanningLoop(properties, new GoalCompiler(properties), new PlanValidator(), store,
                scheduler, mock(ToolRouter.class), mock(StepEvaluator.class), mock(RecoveryEngine.class),
                mock(TodoStateProjector.class), executor, mock(PlannerMetrics.class), mock(SessionLock.class),
                mock(ToolSuccessStats.class));
        return new PlannerFixture(loop, executor, scheduler, store);
    }

    private static String successfulRouteResponse() throws IOException {
        String content = JSON.writeValueAsString(Map.of("route", "direct", "reason", "单次检索",
                "artifactDelivery", Map.of("requested", false, "evidence", "")));
        return JSON.writeValueAsString(Map.of(
                "id", "fixture-completion", "object", "chat.completion", "created", 1,
                "model", USER_MODEL,
                "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", content),
                        "finish_reason", "stop")),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 10, "total_tokens", 20)));
    }

    private record ConfigurationFixture(UserModelConfigService service, ModelClientFactory factory,
                                        AtomicReference<UserModelConfig> stored, SecretCryptoService crypto) {}

    private record PlannerFixture(PlanningLoop loop, NodeExecutor executor, GraphScheduler scheduler,
                                  PlannerStateStore store) {}

    private record CapturedRequest(String method, String path, String authorization, JsonNode body) {}

    private static final class HttpFixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

        private HttpFixture(int status, String responseBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "model-routing-http-fixture");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                    requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                            exchange.getRequestHeaders().getFirst("Authorization"), body));
                    byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        private List<CapturedRequest> requests() {
            return requests;
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
