package com.miniagent.agent.tool;

import com.miniagent.agent.web.ImageGenerationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * image_generate 的「内层预算 < 外层闸门」执行契约。
 *
 * <p>挡的是这次修掉的倒挔回归：内层 HTTP 等待 500s，外层闸门却只有 150s，
 * 外层先触发就把超时升级成「终态未知」并中止整轮（外层触发 = future.get 砍掉工具线程，
 * 后端生成还在跑，钱照花、结果丢弃）。</p>
 */
class ImageGenerateTimeoutContractTest {

    @Test
    void staticProfileBudgetStaysInSyncWithServiceDefault() {
        ToolExecutionProfile profile = ToolConcurrencyPolicy.profileOf("image_generate");
        assertEquals(ImageGenerationService.DEFAULT_TOTAL_BUDGET_SECONDS, profile.executionBudgetSeconds(),
                "REMOTE_GENERATION 档的内层预算必须与 ImageGenerationService.DEFAULT_TOTAL_BUDGET_SECONDS 同源，"
                        + "防止两处各写一个数后再次漂移");
    }

    @Test
    void outerGateAlwaysExceedsInnerBudget() {
        ToolExecutionProfile profile = ToolConcurrencyPolicy.profileOf("image_generate");
        assertTrue(profile.outerGateMarginSeconds() > 0,
                "有内层强杀的工具必须留正余量，否则工具自己的可控超时永远轮不到");
        assertEquals(profile.executionBudgetSeconds() + profile.outerGateMarginSeconds(),
                profile.outerGateSeconds(), "外层闸门 = 内层预算 + 余量");
        assertTrue(profile.outerGateSeconds() > profile.executionBudgetSeconds());
    }

    @Test
    void unconfiguredServiceFallsBackToTheDefaultBudget() {
        // @Value 字段未注入时为 0，totalBudgetSeconds() 必须回退默认值而不是返回 0
        ImageGenerationService service = new ImageGenerationService();
        assertEquals(ImageGenerationService.DEFAULT_TOTAL_BUDGET_SECONDS, service.totalBudgetSeconds());
    }

    @Test
    void registrationTimeDerivationKeepsTheInvariant() {
        // 注册期（BuiltinTools）用配置值 withBudget 派生：派生后不变式必须仍然成立
        ToolExecutionProfile derived = ToolConcurrencyPolicy.profileOf("image_generate").withBudget(700);
        assertEquals(700, derived.executionBudgetSeconds());
        assertEquals(730, derived.outerGateSeconds(), "外层必须比内层宽出 30s 余量");
        assertEquals(ToolConcurrencyScope.SHARED_RESOURCE, derived.concurrencyScope());
        assertEquals("image-api", derived.sharedResourceKey());
        assertEquals(ToolTimeoutRecovery.VERIFY, derived.timeoutRecovery());
    }
}
