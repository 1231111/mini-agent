package com.miniagent.account;

import com.miniagent.account.repository.UserDailyUsageRepository;
import com.miniagent.account.service.UsageQueryService;
import com.miniagent.account.service.UsageRecordingService;
import com.miniagent.account.web.dto.UsageSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用量累计与汇总的往返验证。
 *
 * <p>验证三件事：</p>
 * <ol>
 *   <li>同一「用户 × 日期 × 模型」多次上报是<b>累加</b>，不是覆盖</li>
 *   <li>不同模型分桶，合计等于各桶之和</li>
 *   <li>没有数据的用户返回零值结构，而不是 null 或 404</li>
 * </ol>
 *
 * <p>用 Hibernate {@code create-drop} 建表、关掉 Flyway：V8 是 MySQL 方言，
 * 在 H2 上验证的是业务语义，不是迁移脚本本身能否执行。迁移脚本的验证在
 * {@code ProductionMySqlAccountMigrationTest}（需要真实 MySQL）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:account-usage;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "agent.account.memory-token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "agent.account.internal-key=usage-test-internal-key"
})
@ActiveProfiles("test")
class UsageRecordingAndQueryTest {

    private static final Long USER = 900001L;
    private static final LocalDate DAY = LocalDate.of(2026, 11, 23);

    @Autowired
    private UsageRecordingService recorder;
    @Autowired
    private UsageQueryService queries;
    @Autowired
    private UserDailyUsageRepository repository;

    @BeforeEach
    void reset() {
        repository.deleteAll();
    }

    @Test
    void sameDaySameModelAccumulates() {
        assertTrue(recorder.record(USER, DAY, "deepseek-flash", 1000, 400, 200, 1200, 1500, 1));
        assertTrue(recorder.record(USER, DAY, "deepseek-flash", 500, 100, 300, 800, 900, 1));

        UsageSummaryResponse summary = queries.summary(USER, DAY, DAY);
        assertEquals(1500, summary.inputTokens());
        assertEquals(500, summary.cachedInputTokens());
        assertEquals(500, summary.outputTokens());
        assertEquals(2000, summary.totalTokens());
        assertEquals(2400, summary.costMicros());
        assertEquals(2, summary.eventCount());
        assertEquals(1, summary.dayCount());
        assertEquals(1, summary.byModel().size());
    }

    @Test
    void differentModelsAreBucketedSeparately() {
        recorder.record(USER, DAY, "deepseek-flash", 1000, 0, 200, 1200, 1500, 1);
        recorder.record(USER, DAY, "deepseek-vision", 300, 0, 100, 400, 800, 1);

        UsageSummaryResponse summary = queries.summary(USER, DAY, DAY);
        assertEquals(1300, summary.inputTokens());
        assertEquals(300, summary.outputTokens());
        assertEquals(2, summary.byModel().size());

        long modelInputSum = summary.byModel().stream()
                .mapToLong(UsageSummaryResponse.ModelUsage::inputTokens).sum();
        assertEquals(summary.inputTokens(), modelInputSum);
    }

    @Test
    void daysAreSplitAcrossRange() {
        LocalDate next = DAY.plusDays(1);
        recorder.record(USER, DAY, "m", 100, 0, 10, 110, 100, 1);
        recorder.record(USER, next, "m", 200, 0, 20, 220, 200, 1);

        UsageSummaryResponse summary = queries.summary(USER, DAY, next);
        assertEquals(2, summary.dayCount());
        assertEquals(300, summary.inputTokens());
        assertEquals(30, summary.outputTokens());
        assertEquals(300, summary.costMicros());
    }

    @Test
    void emptyUserReturnsZeroStructure() {
        UsageSummaryResponse summary = queries.summary(USER + 1, DAY, DAY);
        assertEquals(0, summary.totalTokens());
        assertEquals(0, summary.costMicros());
        assertEquals(0, summary.dayCount());
        assertTrue(summary.byModel().isEmpty());
        assertTrue(summary.byDay().isEmpty());
    }

    @Test
    void cachedTokensNeverExceedInput() {
        // 上游如果报出 cached > input，说明字段含义被理解错了；钳到 input 以内，
        // 避免下游算出「缓存命中率 > 100%」这种无法解释的数字。
        recorder.record(USER, DAY, "m", 100, 999, 10, 110, 0, 1);
        UsageSummaryResponse summary = queries.summary(USER, DAY, DAY);
        assertEquals(100, summary.cachedInputTokens());
    }

    @Test
    void rangeLimitRejectsHugeWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> queries.summary(USER, DAY.minusYears(5), DAY));
    }

    @Test
    void invertedRangeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> queries.summary(USER, DAY, DAY.minusDays(1)));
    }
}
