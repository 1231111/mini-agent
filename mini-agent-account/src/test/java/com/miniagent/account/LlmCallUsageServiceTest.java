package com.miniagent.account;

import com.miniagent.account.repository.UserLlmCallUsageRepository;
import com.miniagent.account.service.LlmCallUsageService;
import com.miniagent.account.web.dto.LatencyStatsResponse;
import com.miniagent.account.web.dto.LlmCallUsageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @BeforeEach
    void reset() {
        repository.deleteAll();
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
        assertTrue(service.record(command("req-1", true, 131, 4990, 7890)));
        assertTrue(service.record(command("req-1", true, 131, 4990, 7890)));
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
        assertEquals(false, service.record(command("", true, 10, 100, 1000)));
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
}
