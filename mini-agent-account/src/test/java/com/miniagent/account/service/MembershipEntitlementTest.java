package com.miniagent.account.service;

import com.miniagent.account.config.PasswordEncoderConfig;
import com.miniagent.account.entity.MembershipPlan;
import com.miniagent.account.entity.User;
import com.miniagent.account.entity.UserDailyUsage;
import com.miniagent.account.repository.MembershipPlanRepository;
import com.miniagent.account.repository.UserDailyUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 额度判定。
 *
 * <p>守两条：</p>
 * <ol>
 *   <li><b>超额必须拦。</b> 判定放在任务级而不是每次模型调用，所以漏一次就是整个任务放行。</li>
 *   <li><b>已用量取两套数据里大的那个。</b> 过渡期 {@code user_daily_usage}（新）和
 *       {@code membership_usage_events}（旧）可能一方有、一方没有，取小的会让实际超额被放过。</li>
 * </ol>
 */
@DataJpaTest
@Import({AccountAuthService.class, MembershipService.class, PasswordEncoderConfig.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "agent.account.registration-enabled=true",
        "agent.account.password-min-length=8",
        "agent.account.bcrypt-strength=4"
})
class MembershipEntitlementTest {

    private static final String PASSWORD = "12345678";
    private static final long FREE_TOKENS = 200_000L;
    private static final ZoneId QUOTA_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private MembershipService membership;
    @Autowired
    private AccountAuthService auth;
    @Autowired
    private MembershipPlanRepository plans;
    @Autowired
    private UserDailyUsageRepository dailyUsage;

    @BeforeEach
    void seedPlan() {
        if (plans.findByCode(MembershipService.FREE_PLAN_CODE).isEmpty()) {
            MembershipPlan p = new MembershipPlan();
            p.setCode(MembershipService.FREE_PLAN_CODE);
            p.setName("免费版");
            p.setDailyTokenLimit(FREE_TOKENS);
            p.setMaxConcurrentTasks(2);
            p.setPriceCents(0L);
            p.setCurrency("CNY");
            p.setDurationDays(0);
            p.setEnabled(true);
            plans.save(p);
        }
    }

    private User newUser(String name) {
        AccountAuthService.RegisterResult r = auth.register(name, PASSWORD, null);
        assertThat(r.success()).as("测试前置数据准备失败").isTrue();
        return r.user();
    }

    private void seedDetailUsage(User user, long input, long output) {
        UserDailyUsage row = new UserDailyUsage();
        row.setUserId(user.getId());
        row.setUsageDay(LocalDate.now(QUOTA_ZONE));
        row.setModel("test-model");
        row.setInputTokens(input);
        // 聚合表这一列不可空：未知用 0 计入，另有 cached_unknown_calls 单独计数（V11）。
        row.setCachedInputTokens(0);
        row.setOutputTokens(output);
        row.setTotalTokens(input + output);
        row.setCostMicros(0);
        row.setEventCount(1);
        dailyUsage.saveAndFlush(row);
    }

    @Test
    void noUsageAllowsTask() {
        User user = newUser("ent_none");
        var view = membership.entitlementOf(user.getId());

        assertThat(view.allowed()).isTrue();
        assertThat(view.dailyLimitTokens()).isEqualTo(FREE_TOKENS);
        assertThat(view.usedTokens()).isZero();
        assertThat(view.remainingTokens()).isEqualTo(FREE_TOKENS);
        assertThat(view.reason()).isEmpty();
    }

    @Test
    void overLimitBlocksNextTask() {
        User user = newUser("ent_over");
        seedDetailUsage(user, 150_000, 100_000);   // 250k > 200k

        var view = membership.entitlementOf(user.getId());

        assertThat(view.allowed()).isFalse();
        assertThat(view.usedTokens()).isEqualTo(250_000);
        assertThat(view.remainingTokens()).isZero();
        assertThat(view.reason()).contains("额度已用完");
    }

    @Test
    void exactlyAtLimitBlocks() {
        User user = newUser("ent_edge");
        seedDetailUsage(user, 120_000, 80_000);    // 正好 200k

        var view = membership.entitlementOf(user.getId());

        // 等于上限时不允许再发起新任务：上限是「最多用到」，不是「可以再用一次」。
        assertThat(view.allowed()).isFalse();
        assertThat(view.remainingTokens()).isZero();
    }

    @Test
    void resetAtIsTomorrow() {
        User user = newUser("ent_reset");
        var view = membership.entitlementOf(user.getId());

        assertThat(view.resetAt()).isEqualTo(LocalDate.now(QUOTA_ZONE).plusDays(1));
    }
}
