package com.miniagent.memoryservice;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ApiResponse;
import com.miniagent.memory.contract.MemoryApi;
import com.miniagent.memory.contract.MemoryApi.Arm;
import com.miniagent.memory.contract.MemoryApi.AssembleRequest;
import com.miniagent.memory.contract.MemoryApi.AssembleResponse;
import com.miniagent.memory.contract.MemoryApi.AssembledProcedure;
import com.miniagent.memory.contract.MemoryApi.CaseKind;
import com.miniagent.memory.contract.MemoryApi.CaseVerdict;
import com.miniagent.memory.contract.MemoryApi.EnvironmentChange;
import com.miniagent.memory.contract.MemoryApi.EvalCase;
import com.miniagent.memory.contract.MemoryApi.EvalJob;
import com.miniagent.memory.contract.MemoryApi.EvalLeaseRequest;
import com.miniagent.memory.contract.MemoryApi.EvalMode;
import com.miniagent.memory.contract.MemoryApi.EvalResult;
import com.miniagent.memory.contract.MemoryApi.ItemView;
import com.miniagent.memory.contract.MemoryApi.ItemWrite;
import com.miniagent.memory.contract.MemoryApi.OutcomeAck;
import com.miniagent.memory.contract.MemoryApi.OutcomeBatch;
import com.miniagent.memory.contract.MemoryApi.OutcomeBatchResult;
import com.miniagent.memory.contract.MemoryApi.OutcomeEvent;
import com.miniagent.memory.contract.MemoryApi.OutcomeResult;
import com.miniagent.memory.contract.MemoryApi.PolicyUpdate;
import com.miniagent.memory.contract.MemoryApi.ProposalView;
import com.miniagent.memory.contract.MemoryApi.ScopeType;
import com.miniagent.memory.contract.MemoryApi.Verdict;
import com.miniagent.memory.contract.ProcedureContent;
import com.miniagent.memory.contract.ServiceToken;
import com.miniagent.memory.contract.ServiceToken.Principal;
import com.miniagent.memoryservice.evolution.EvolutionWorker;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * 进化闭环端到端（H2 MySQL 模式 + 真实 ES256 服务令牌 + 假修订模型 + 可拨动时钟）：
 * 失败簇 → 候选 → 离线评估 → 在线实验 → 晋升 → 监控 → STABLE → 回归集；监控期复发回滚；
 * 环境变更重验；留存组。评估 worker 的 HTTP 协议由测试扮演。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:closed-loop;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "memory.service.role=api",
        "memory.evolution.holdout.bps=0",
        "memory.evolution.trigger.debounce-seconds=0",
        "memory.evolution.experiment.min-samples-per-arm=5",
        "memory.evolution.eval.min-success-cases=3",
        "memory.evolution.metrics.refresh-ms=3600000"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(EvolutionClosedLoopTest.Fakes.class)
class EvolutionClosedLoopTest {
    private static final Instant T0 = Instant.parse("2026-11-02T00:00:00Z");
    private static final KeyPair APP_KEYS = ecKeys();
    private static final KeyPair EVAL_KEYS = ecKeys();
    private static final String APP_ISSUER = "app";
    private static final String EVAL_ISSUER = "eval-worker";
    private static final String ERROR_CODE = "EXECUTION_FAILED";
    private static final String FAILURE_REASON = "导出 PDF 时模板缺少页脚字段 footer";
    private static final String USER_MESSAGE = "生成本周周报并导出 PDF";
    private static final String DESCRIPTION = "每周五生成周报并导出 PDF";
    private static final List<String> BASE_STEPS = List.of("打开周报模板", "填写本周数据", "导出 PDF");
    private static final int SEED_SUCCESSES = 15;
    private static final int SEED_FAILURES = 7;
    private static final int ARM_TASKS = 8;
    private static final int BASELINE_ARM_SUCCESSES = 2;

    record Ctx(String tenant, long user) {
    }

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("memory.service.issuers.app.public-keys", () -> encode(APP_KEYS));
        registry.add("memory.service.issuers.app.roles", () -> "app,admin");
        registry.add("memory.service.issuers.eval-worker.public-keys", () -> encode(EVAL_KEYS));
        registry.add("memory.evolution.experiment.require-live-arms", () -> "true");
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }

        @Bean
        ChatModel evolutionLlm(ObjectMapper json) {
            return new FakeReviser(json);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    MutableClock clock;
    @Autowired
    EvolutionWorker worker;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void failureClusterIsFixedPromotedStabilizedAndRegressionSetGuardsTheFix() throws Exception {
        Ctx c = new Ctx("t1", 7);
        long itemId = createItem(c, "周报导出-t1").id();
        long proposalId = driveToExperiment(c, itemId, "周报导出-t1");
        driveToMonitoring(c, itemId, proposalId);

        clock.advance(Duration.ofDays(7).plusHours(1));
        worker.runOnce();
        assertEquals("STABLE", proposal(c, proposalId).state());
        assertEquals(SEED_FAILURES + ARM_TASKS - BASELINE_ARM_SUCCESSES, jdbc.queryForObject(
                "SELECT COUNT(*) FROM evo_regression_case WHERE item_id = ?", Integer.class, itemId),
                "播种失败 + 实验期对照臂的同指纹失败都是被修好的案例，各会话一条进回归集");
        assertEquals("env-1", jdbc.queryForObject(
                "SELECT validated_env_version FROM mem_item WHERE id = ?", String.class, itemId));

        for (int i = 1; i <= 2; i++) {
            report(c, "r" + i + ":1", itemId, OutcomeResult.FAILURE);
        }
        worker.runOnce();
        ProposalView recurrence = latestProposal(c, itemId);
        assertEquals("RECURRENCE", recurrence.triggerType());
        assertEquals("EVALUATING", recurrence.state());
        assertEquals(2, recurrence.baseRevisionNo());

        EvalJob job = leaseFor("周报导出-t1");
        assertTrue(job.cases().stream().anyMatch(k -> k.kind() == CaseKind.REGRESSION),
                "复发提案的评估必须带上回归集");
        complete(job, k -> k.kind() == CaseKind.REGRESSION ? Verdict.LOSS : Verdict.WIN);
        assertEquals("EVAL_FAILED", proposal(c, recurrence.id()).state(),
                "重新弄坏已修案例的候选不能过离线评估");
    }

    @Test
    void recurrenceDuringMonitoringRollsBackAndCountsInSlo() throws Exception {
        Ctx c = new Ctx("t2", 8);
        long itemId = createItem(c, "周报导出-t2").id();
        long proposalId = driveToExperiment(c, itemId, "周报导出-t2");
        driveToMonitoring(c, itemId, proposalId);

        int recurred = 0;
        for (int i = 0; recurred < 2; i++) {
            AssembledProcedure p = assembled(c, "m" + i + ":1", itemId);
            if (p.arm() == Arm.CANDIDATE) {
                report(c, "m" + i + ":1", p, OutcomeResult.FAILURE);
                recurred++;
            }
        }
        clock.advance(Duration.ofMinutes(61));
        worker.runOnce();

        assertEquals("ROLLED_BACK", proposal(c, proposalId).state());
        assertEquals(1, item(c, itemId).revisionNo(), "回滚后激活指针回到基线");
        Map<?, ?> slo = call(HttpMethod.GET, MemoryApi.ADMIN + "/slo?tenantId=t2", admin(), null,
                Map.class);
        assertEquals(1.0, ((Number) slo.get("autoPromotionRollbackRate")).doubleValue(), 1e-9);
    }

    @Test
    void environmentChangeRevalidatesAndDegradedItemOpensProposal() throws Exception {
        Ctx c = new Ctx("t3", 9);
        long itemId = createItem(c, "周报导出-t3").id();
        for (int i = 1; i <= 6; i++) {
            report(c, "v" + i + ":1", itemId, OutcomeResult.SUCCESS);
        }
        Integer queued = call(HttpMethod.POST, MemoryApi.ADMIN + "/environment-versions", admin(),
                new EnvironmentChange("t3", "env-2"), Integer.class);
        assertEquals(1, queued);

        EvalJob job = leaseFor("周报导出-t3");
        assertEquals(EvalMode.REVALIDATE, job.mode());
        assertNull(job.baseline());
        complete(job, k -> Verdict.LOSS);
        assertEquals("DEGRADED", item(c, itemId).health());

        worker.runOnce();
        ProposalView p = latestProposal(c, itemId);
        assertEquals("REVALIDATION", p.triggerType());
        assertEquals("EVALUATING", p.state(), "重验失败的案例作为证据生成了候选");
    }

    @Test
    void holdoutTasksSeeTheFrozenRevisionAndTokensAreScopedByRole() throws Exception {
        Ctx c = new Ctx("t4", 10);
        call(HttpMethod.PUT, MemoryApi.ADMIN + "/policies/t4", admin(),
                new PolicyUpdate(null, null, 9999), Map.class);
        long itemId = createItem(c, "周报导出-t4").id();

        AssembleResponse r = call(HttpMethod.POST, MemoryApi.ASSEMBLE, user(c),
                new AssembleRequest("h1:1", null), AssembleResponse.class);
        assertTrue(r.holdout());
        AssembledProcedure p = r.procedures().stream().filter(x -> x.itemId() == itemId)
                .findFirst().orElseThrow();
        assertNull(p.proposalId());
        Map<?, ?> holdout = call(HttpMethod.GET, MemoryApi.ADMIN + "/holdout?tenantId=t4", admin(),
                null, Map.class);
        assertEquals(9999, ((Number) holdout.get("holdoutBps")).intValue());

        assertEquals(401, status(HttpMethod.GET, MemoryApi.ITEMS, null));
        assertEquals(403, status(HttpMethod.GET, MemoryApi.ADMIN + "/jobs", user(c)));
        assertEquals(403, status(HttpMethod.POST, MemoryApi.ASSEMBLE, eval()));
        String deniedErase = mvc.perform(build(HttpMethod.DELETE, MemoryApi.SUBJECTS + "/11",
                        user(c), null)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(json.readTree(deniedErase).path("success").asBoolean(),
                "普通用户不能删除同租户其他用户的数据");
        String ownErase = mvc.perform(build(HttpMethod.DELETE, MemoryApi.SUBJECTS + "/10",
                        user(c), null)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(json.readTree(ownErase).path("success").asBoolean(),
                "令牌主体仍可删除自己的数据");
    }

    // ---------- 闭环驱动 ----------

    /** 播种成功 / 失败 → 检测 + 生成 → 评估通过 → AUTO 租户进在线实验。 */
    private long driveToExperiment(Ctx c, long itemId, String name) throws Exception {
        for (int i = 1; i <= SEED_SUCCESSES; i++) {
            report(c, "s" + i + ":1", itemId, OutcomeResult.SUCCESS);
        }
        for (int i = 1; i <= SEED_FAILURES; i++) {
            report(c, "f" + i + ":1", itemId, OutcomeResult.FAILURE);
        }
        worker.runOnce();
        ProposalView p = latestProposal(c, itemId);
        assertEquals("FAILURE", p.triggerType());
        assertEquals("EVALUATING", p.state(), () -> "检测与生成后应等待离线评估：" + p.stateReason());
        assertEquals(2, p.candidateRevisionNo());

        EvalJob job = leaseFor(name);
        assertEquals(EvalMode.PAIRWISE, job.mode());
        assertEquals(SEED_FAILURES, job.cases().stream()
                .filter(k -> k.kind() == CaseKind.TRIGGER).count());
        complete(job, k -> k.kind() == CaseKind.TRIGGER ? Verdict.WIN : Verdict.TIE);
        assertEquals("EXPERIMENTING", proposal(c, p.id()).state());
        return p.id();
    }

    /** 两臂各 8 个任务：候选全成功、对照 2/8 → 下一次检查晋升，进入带同期对照的监控期。 */
    private void driveToMonitoring(Ctx c, long itemId, long proposalId) throws Exception {
        int candidate = 0;
        int baseline = 0;
        for (int i = 0; candidate < ARM_TASKS || baseline < ARM_TASKS; i++) {
            String taskKey = "x" + i + ":1";
            AssembledProcedure p = assembled(c, taskKey, itemId);
            assertEquals(proposalId, p.proposalId());
            if (p.arm() == Arm.CANDIDATE && candidate < ARM_TASKS) {
                report(c, taskKey, p, OutcomeResult.SUCCESS);
                candidate++;
            } else if (p.arm() == Arm.BASELINE && baseline < ARM_TASKS) {
                report(c, taskKey, p, baseline < BASELINE_ARM_SUCCESSES
                        ? OutcomeResult.SUCCESS : OutcomeResult.FAILURE);
                baseline++;
            }
        }
        clock.advance(Duration.ofMinutes(61));
        worker.runOnce();
        assertEquals("MONITORING", proposal(c, proposalId).state());
        assertEquals(2, item(c, itemId).revisionNo(), "晋升后激活候选修订");
        assertEquals(90, assembled(c, "probe:1", itemId).trafficPercent(), "监控期保留 10% 同期对照");
    }

    // ---------- 评估 worker 协议 ----------

    private EvalJob leaseFor(String itemName) throws Exception {
        for (int i = 0; i < 10; i++) {
            EvalJob job = call(HttpMethod.POST, MemoryApi.EVAL_LEASE, eval(),
                    new EvalLeaseRequest("test-worker"), EvalJob.class);
            assertNotNull(job, "没有可领取的评估作业");
            if (job.itemName().equals(itemName)) {
                return job;
            }
            complete(job, k -> Verdict.ERROR);
        }
        return fail("一直领不到 " + itemName + " 的评估作业");
    }

    private void complete(EvalJob job, Function<EvalCase, Verdict> verdict) throws Exception {
        boolean pairwise = job.mode() == EvalMode.PAIRWISE;
        List<CaseVerdict> verdicts = job.cases().stream()
                .map(k -> new CaseVerdict(k.caseId(), verdict.apply(k), pairwise, true, null))
                .toList();
        call(HttpMethod.POST, MemoryApi.EVAL_JOBS + "/" + job.jobId() + ":complete", eval(),
                new EvalResult(job.leaseToken(), verdicts, 1000, "planner-test", "judge-test"),
                Void.class);
    }

    // ---------- App 侧调用 ----------

    private ItemView createItem(Ctx c, String name) throws Exception {
        return call(HttpMethod.POST, MemoryApi.ITEMS, user(c), new ItemWrite(ScopeType.USER, null,
                name, new ProcedureContent(DESCRIPTION, List.of(), BASE_STEPS,
                List.of("PDF 已导出")), null), ItemView.class);
    }

    private ItemView item(Ctx c, long itemId) throws Exception {
        return call(HttpMethod.GET, MemoryApi.ITEMS + "/" + itemId, user(c), null, ItemView.class);
    }

    private AssembledProcedure assembled(Ctx c, String taskKey, long itemId) throws Exception {
        AssembleResponse r = call(HttpMethod.POST, MemoryApi.ASSEMBLE, user(c),
                new AssembleRequest(taskKey, null), AssembleResponse.class);
        return r.procedures().stream().filter(p -> p.itemId() == itemId).findFirst()
                .orElseThrow(() -> new AssertionError("组装结果里没有条目 " + itemId));
    }

    private void report(Ctx c, String taskKey, long itemId, OutcomeResult result) throws Exception {
        report(c, taskKey, assembled(c, taskKey, itemId), result);
    }

    private void report(Ctx c, String taskKey, AssembledProcedure p, OutcomeResult result)
            throws Exception {
        boolean failure = result == OutcomeResult.FAILURE;
        OutcomeEvent event = new OutcomeEvent(UUID.randomUUID().toString(), c.tenant(), c.user(),
                null, p.itemId(), p.revisionNo(), p.proposalId(),
                p.proposalId() == null ? null : p.arm(), p.trafficPercent(), taskKey, "n1",
                result, failure ? ERROR_CODE : null, failure ? FAILURE_REASON : null,
                USER_MESSAGE, "导出 PDF", "env-1", clock.instant());
        OutcomeBatchResult r = call(HttpMethod.POST, MemoryApi.OUTCOMES, system(c),
                new OutcomeBatch(List.of(event)), OutcomeBatchResult.class);
        OutcomeAck ack = r.results().getFirst();
        assertEquals(MemoryApi.AckStatus.ACCEPTED, ack.status(), () -> "结果被拒：" + ack.code());
        assertNull(ack.code(), "分臂复算应一致");
    }

    private ProposalView proposal(Ctx c, long id) throws Exception {
        return call(HttpMethod.GET, MemoryApi.PROPOSALS + "/" + id, user(c), null,
                ProposalView.class);
    }

    private ProposalView latestProposal(Ctx c, long itemId) throws Exception {
        JavaType list = json.getTypeFactory().constructCollectionType(List.class,
                ProposalView.class);
        List<ProposalView> all = call(HttpMethod.GET, MemoryApi.PROPOSALS + "?limit=200", user(c),
                null, list);
        return all.stream().filter(p -> p.itemId() == itemId)
                .max(Comparator.comparingLong(ProposalView::id))
                .orElseThrow(() -> new AssertionError("条目 " + itemId + " 没有提案"));
    }

    // ---------- HTTP 与令牌 ----------

    private <T> T call(HttpMethod method, String uri, String token, Object body, Class<T> type)
            throws Exception {
        return call(method, uri, token, body, json.getTypeFactory().constructType(type));
    }

    private <T> T call(HttpMethod method, String uri, String token, Object body, JavaType type)
            throws Exception {
        String text = mvc.perform(build(method, uri, token, body)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        ApiResponse<T> response = json.readValue(text, json.getTypeFactory()
                .constructParametricType(ApiResponse.class, type));
        assertTrue(response.isSuccess(), () -> method + " " + uri + " 失败：" + text);
        return response.getData();
    }

    private int status(HttpMethod method, String uri, String token) throws Exception {
        return mvc.perform(build(method, uri, token, null)).andReturn().getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder build(HttpMethod method, String uri, String token,
                                                Object body) throws Exception {
        MockHttpServletRequestBuilder req = request(method, uri);
        if (token != null) {
            req.header(ServiceToken.HEADER, ServiceToken.BEARER + token);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        }
        return req;
    }

    private static String user(Ctx c) {
        return sign(APP_KEYS, new Principal(APP_ISSUER, ServiceToken.ROLE_APP, c.tenant(), c.user(),
                null));
    }

    private static String system(Ctx c) {
        return sign(APP_KEYS, new Principal(APP_ISSUER, ServiceToken.ROLE_APP, c.tenant(), null,
                null));
    }

    private static String admin() {
        return sign(APP_KEYS, new Principal(APP_ISSUER, ServiceToken.ROLE_ADMIN, null, null, null));
    }

    private static String eval() {
        return sign(EVAL_KEYS, new Principal(EVAL_ISSUER, ServiceToken.ROLE_EVAL, null, null, null));
    }

    private static String sign(KeyPair keys, Principal principal) {
        return ServiceToken.sign(keys.getPrivate(), principal, Duration.ofMinutes(10));
    }

    private static KeyPair ecKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(KeyPair keys) {
        return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
    }

    // ---------- 假模型与时钟 ----------

    /** 把「导出前检查页脚」插进第 3 步，证据引用照抄提示词里给出的全部失败证据 id。 */
    static final class FakeReviser implements ChatModel {
        private static final Pattern EVIDENCE_ID = Pattern.compile("\\[(T:e\\d+)]");
        private final ObjectMapper json;
        private final AtomicInteger round = new AtomicInteger();

        FakeReviser(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            String prompt = ((UserMessage) request.messages().getLast()).singleText();
            List<String> refs = EVIDENCE_ID.matcher(prompt).results().map(m -> m.group(1))
                    .distinct().toList();
            List<String> steps = new ArrayList<>(BASE_STEPS);
            steps.add(2, "导出前检查页脚字段 footer 已填写（修订 " + round.incrementAndGet() + "）");
            try {
                String text = json.writeValueAsString(Map.of("description", DESCRIPTION,
                        "preconditions", List.of(), "steps", steps,
                        "successConditions", List.of("PDF 已导出且带页脚"),
                        "rationale", "失败都卡在缺页脚字段，导出前先检查", "evidenceRefs", refs));
                return ChatResponse.builder().aiMessage(AiMessage.from(text))
                        .tokenUsage(new TokenUsage(120, 80)).build();
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
