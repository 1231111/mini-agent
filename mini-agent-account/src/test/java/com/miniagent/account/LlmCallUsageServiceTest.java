package com.miniagent.account;

import com.miniagent.account.repository.UserLlmCallUsageRepository;
import com.miniagent.account.service.LlmCallUsageService;
import com.miniagent.account.web.dto.LatencyStatsResponse;
import com.miniagent.account.web.dto.LlmCallUsageResponse;
import org.junit.jupiter.api.BeforeEach;
import com.miniagent.account.service.UsageQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调用明细的幂等、分页与延迟统计。
 *
 * <p>延迟统计要验证的是「非流式不参与首 token 平均」——把 0 计进去会把平均拉低一半，
 * 而这个错误在平均值上不容易看出来，只有 ttftSamples 会暴露。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:account-calls;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "agent.account.memory-token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "agent.account.internal-key=calls-test-internal-key"
})
@ActiveProfiles("test")
class LlmCallUsageServiceTest {

    private static final Long USER = 910001L;
    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private LlmCallUsageService service;
    @Autowired
    private UserLlmCallUsageRepository repository;
    @Autowired
    private UsageQueryService queries;
    @Autowired
    private com.miniagent.account.repository.UserDailyUsageRepository aggregateRepository;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        // 聚合表按「用户 × 日期 × 模型」唯一，前一个用例留下的行会让计数断言失真。
        aggregateRepository.deleteAll();
    }

    private LlmCallUsageService.RecordCommand command(String requestId, boolean streaming,
                                                      long output, long ttft, long duration) {
        return new LlmCallUsageService.RecordCommand(
                USER, "s_test", requestId, "gpt-6.1-sol", "Xhigh", "/responses",
                streaming, "TOKEN", "abcd",
                35422, 198700, output, 35422 + output, 5062, ttft, duration,
                "Codex Desktop/0.171.0");
    }

    @Test
    void duplicateRequestIdIsIdempotent() {
        assertEquals(LlmCallUsageService.RecordOutcome.INSERTED, service.record(command("req-1", true, 131, 4990, 7890)));
        // 第二次同 requestId：明细不新增，返回值必须能区分出这是重复。
        assertEquals(LlmCallUsageService.RecordOutcome.DUPLICATE, service.record(command("req-1", true, 131, 4990, 7890)));
        assertEquals(1, repository.count());
    }

    @Test
    void detailReturnsNewestFirst() {
        service.record(command("req-a", true, 100, 1000, 2000));
        service.record(command("req-b", true, 200, 1100, 2100));

        List<LlmCallUsageResponse> items =
                service.detail(USER, TODAY, TODAY, 0, 10).getContent();
        assertEquals(2, items.size());
        assertTrue(items.get(0).createdAt().compareTo(items.get(1).createdAt()) >= 0);
    }

    @Test
    void ttftAverageSkipsNonStreaming() {
        service.record(command("s1", true, 10, 100, 1000));
        service.record(command("s2", true, 10, 200, 1000));
        service.record(command("s3", true, 10, 300, 1000));
        service.record(command("n1", false, 10, 0, 1000));

        LatencyStatsResponse stats = service.latency(USER, TODAY, TODAY);
        assertEquals(4, stats.callCount());
        assertEquals(3, stats.ttftSamples());
        assertEquals(200d, stats.avgTtftMillis(), 0.001);
        assertEquals(1000d, stats.avgDurationMillis(), 0.001);
    }

    @Test
    void outputTpsUsesTotalDuration() {
        service.record(command("tps-1", true, 100, 1000, 2000));
        LlmCallUsageResponse item = service.detail(USER, TODAY, TODAY, 0, 10).getContent().get(0);
        assertEquals(50d, item.outputTps(), 0.001);
    }

    @Test
    void pageSizeIsCapped() {
        for (int i = 0; i < 5; i++) {
            service.record(command("cap-" + i, true, 10, 100, 1000));
        }
        var page = service.detail(USER, TODAY, TODAY, 0, 10_000);
        assertEquals(LlmCallUsageService.MAX_PAGE_SIZE, page.getSize());
        assertEquals(5, page.getTotalElements());
    }

    @Test
    void missingRequestIdRejected() {
        assertEquals(LlmCallUsageService.RecordOutcome.REJECTED, service.record(command("", true, 10, 100, 1000)));
        assertEquals(0, repository.count());
    }

    @Test
    void cachedTokensClampedToInput() {
        LlmCallUsageService.RecordCommand oversized = new LlmCallUsageService.RecordCommand(
                USER, "s", "clamp-1", "m", "", "", true, "TOKEN", "",
                100, 9999, 10, 110, 0, 100, 1000, "");
        service.record(oversized);
        LlmCallUsageResponse item = service.detail(USER, TODAY, TODAY, 0, 10).getContent().get(0);
        assertEquals(100, item.cachedInputTokens());
    }

    /** -1 是调用方「没拿到缓存明细」的约定值，必须落成 NULL，不能落成 0。 */
    @Test
    void unknownCachedIsStoredAsNull() {
        service.record(new LlmCallUsageService.RecordCommand(
                USER, "s", "unknown-cached", "m", "", "", true, "TOKEN", "",
                10_000, -1, 500, 10_500, 0, 100, 1000, ""));
        LlmCallUsageResponse item = service.detail(USER, TODAY, TODAY, 0, 10).getContent().get(0);
        assertNull(item.cachedInputTokens());
    }

    /** 0 是「确认零命中」，与 NULL 必须区分，否则下游无从判断该不该按缓存价计费。 */
    @Test
    void zeroCachedStaysZero() {
        service.record(new LlmCallUsageService.RecordCommand(
                USER, "s", "zero-cached", "m", "", "", true, "TOKEN", "",
                10_000, 0, 500, 10_500, 0, 100, 1000, ""));
        LlmCallUsageResponse item = service.detail(USER, TODAY, TODAY, 0, 10).getContent().get(0);
        assertEquals(0, item.cachedInputTokens());
        assertNotNull(item.cachedInputTokens());
    }

    /** 真实命中值必须原样落库 —— 这正是「拿到了但没人读」那个静默 bug 的回归测试。 */
    @Test
    void cachedHitIsPersistedVerbatim() {
        service.record(new LlmCallUsageService.RecordCommand(
                USER, "s", "hit-cached", "m", "", "", true, "TOKEN", "",
                19_666, 19_584, 82, 19_748, 0, 120, 900, ""));
        LlmCallUsageResponse item = service.detail(USER, TODAY, TODAY, 0, 10).getContent().get(0);
        assertEquals(19_584, item.cachedInputTokens());
        assertEquals(19_666, item.inputTokens());
    }

    /** 明细落库后，按天聚合必须同步可见 —— 否则页面上「按模型」永远为空。 */
    @Test
    void aggregateIsWrittenAlongsideDetail() {
        service.record(new LlmCallUsageService.RecordCommand(
                USER, "s", "agg-1", "model-a", "", "", true, "TOKEN", "",
                2_000, 0, 300, 2_300, 500, 100, 900, ""));

        var summary = queries.summary(USER, LocalDate.now(), LocalDate.now());
        assertEquals(1, summary.byModel().size());
        assertEquals("model-a", summary.byModel().get(0).model());
        assertEquals(2_000, summary.inputTokens());
        assertEquals(300, summary.outputTokens());
        assertEquals(500, summary.costMicros());
    }

    /**
     * 重复上报同一 requestId 时，明细不新增、聚合也不能重复累加。
     *
     * <p>这是聚合接入后最容易出错的地方：重试与断线重连都会重复投递同一次调用，
     * 如果累加不区分 INSERTED/DUPLICATE，费用会被算两遍。</p>
     */
    @Test
    void duplicateReportDoesNotDoubleCountAggregate() {
        LlmCallUsageService.RecordCommand cmd = new LlmCallUsageService.RecordCommand(
                USER, "s", "agg-dup", "model-b", "", "", true, "TOKEN", "",
                10_000, 0, 500, 10_500, 1_000, 100, 1_000, "");

        assertEquals(LlmCallUsageService.RecordOutcome.INSERTED, service.record(cmd));
        assertEquals(LlmCallUsageService.RecordOutcome.DUPLICATE, service.record(cmd));

        var summary = queries.summary(USER, LocalDate.now(), LocalDate.now());
        assertEquals(1, summary.eventCount());
        assertEquals(10_000, summary.inputTokens());
        assertEquals(1_000, summary.costMicros());
    }
}
