package com.miniagent.account.service;

import com.miniagent.account.config.PasswordEncoderConfig;
import com.miniagent.account.entity.MembershipPlan;
import com.miniagent.account.entity.Tenant;
import com.miniagent.account.entity.User;
import com.miniagent.account.repository.MembershipPlanRepository;
import com.miniagent.account.repository.TenantRepository;
import com.miniagent.account.repository.UserRepository;
import com.miniagent.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册与登录的边界。
 *
 * <h3>为什么用 {@code ddl-auto=create-drop} 而不是跑 Flyway</h3>
 *
 * <p>迁移脚本是 MySQL 方言（{@code ENGINE=InnoDB} / {@code utf8mb4} 等），H2 解析不了。
 * 桌面档的既有做法也是同一个思路：嵌入式库用 Hibernate 建表，不用迁移脚本。
 * 代价是<b>测不到迁移脚本本身</b>——那一层的验证只能靠真 MySQL，见 README 的部署顺序一节。
 *
 * <p>套餐种子数据也因此要在下面手写一份，而不是依赖 {@code V2__membership.sql} 的 INSERT：
 * 注册流程会去读 {@code free} 套餐，没有它就直接抛。
 */
@DataJpaTest
@Import({AccountAuthService.class, MembershipService.class, PasswordEncoderConfig.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "agent.account.registration-enabled=true",
        "agent.account.password-min-length=8",
        // 4 是 BCrypt 允许的最低轮数。用 10 会让每个用例多花约 100ms，
        // 而这里要验的是"能不能登录"而不是"哈希够不够慢"——强度本身由配置断言覆盖。
        "agent.account.bcrypt-strength=4"
})
class AccountAuthServiceTest {

    private static final String PASSWORD = "12345678";
    private static final long FREE_DAILY_TOKENS = 200_000L;

    @Autowired
    private AccountAuthService auth;
    @Autowired
    private MembershipService membership;
    @Autowired
    private UserRepository users;
    @Autowired
    private TenantRepository tenants;
    @Autowired
    private MembershipPlanRepository plans;

    @BeforeEach
    void seedPlans() {
        if (plans.findByCode(MembershipService.FREE_PLAN_CODE).isEmpty()) {
            plans.save(plan(MembershipService.FREE_PLAN_CODE, "免费版", FREE_DAILY_TOKENS, 2, 0L, 0));
        }
        if (plans.findByCode("pro").isEmpty()) {
            plans.save(plan("pro", "专业版", 5_000_000L, 5, 3900L, 30));
        }
    }

    private static MembershipPlan plan(String code, String name, long dailyTokens,
                                       int maxTasks, long cents, int days) {
        MembershipPlan p = new MembershipPlan();
        p.setCode(code);
        p.setName(name);
        p.setDailyTokenLimit(dailyTokens);
        p.setMaxConcurrentTasks(maxTasks);
        p.setPriceCents(cents);
        p.setCurrency("CNY");
        p.setDurationDays(days);
        p.setEnabled(true);
        return p;
    }

    // ==================== 注册 ====================

    @Test
    @DisplayName("注册成功：建专属租户，并把 free 套餐的额度写进该租户")
    void registerCreatesPersonalTenantAndFreeSubscription() {
        AccountAuthService.RegisterResult result = auth.register("alice", PASSWORD, null);

        assertThat(result.success()).isTrue();
        User user = result.user();

        Tenant tenant = tenants.findById(user.getTenantId()).orElseThrow();
        // 每用户独立租户 —— 迁移前所有人共用 system 租户，配额是一个共享池，
        // "谁用得多别人就没得用"。这条断言就是那个缺陷的回归防线。
        assertThat(tenant.getSlug()).isEqualTo("u-alice");
        assertThat(tenant.getDailyTokenLimit())
                .as("free 套餐的额度应当已经写进租户")
                .isEqualTo(FREE_DAILY_TOKENS);

        var current = membership.current(user.getId());
        assertThat(current).isPresent();
        assertThat(current.get().planCode()).isEqualTo(MembershipService.FREE_PLAN_CODE);
        assertThat(current.get().dailyTokenLimit()).isEqualTo(FREE_DAILY_TOKENS);
    }

    @Test
    @DisplayName("两个用户不共用租户：各自的额度互不影响")
    void twoUsersDoNotShareTenant() {
        User a = auth.register("alice", PASSWORD, null).user();
        User b = auth.register("bob", PASSWORD, null).user();

        assertThat(a.getTenantId()).isNotEqualTo(b.getTenantId());
        assertThat(tenants.findById(b.getTenantId()).orElseThrow().getSlug()).isEqualTo("u-bob");
    }

    @Test
    @DisplayName("用户名超长被拒，且原因说的是长度而不是重名")
    void registerRejectsOverlongUsername() {
        String tooLong = "a".repeat(51);

        AccountAuthService.RegisterResult result = auth.register(tooLong, PASSWORD, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo(ErrorCode.AUTH_USERNAME_INVALID);
        // 这条断言的用意：如果长度校验被挪到存在性检查之后，
        // INSERT 会撞列宽限制抛 DataIntegrityViolationException，
        // 被 catch 说成"用户名已被占用" —— 用户没重名却被要求改名，怎么改都不对。
        assertThat(result.detail()).contains("50").doesNotContain("占用");
    }

    @Test
    @DisplayName("用户名恰好 50 位可以通过（边界不多不少）")
    void registerAcceptsUsernameOfExactlyMaxLength() {
        String exact = "b".repeat(50);

        AccountAuthService.RegisterResult result = auth.register(exact, PASSWORD, null);

        assertThat(result.success()).isTrue();
        assertThat(result.user().getUsername()).hasSize(50);
    }

    @Test
    @DisplayName("用户名前后空格会被归一，落库与查重用的是同一个值")
    void registerTrimsUsername() {
        auth.register("  bob  ", PASSWORD, null);

        // 归一之前，existsByUsername 查的是原值、setUsername 存的是 trim 值，
        // 于是 " bob " 绕过重名检查后撞唯一约束，抛成 500。
        assertThat(users.findByUsername("bob")).isPresent();
        assertThat(users.findByUsername("  bob  ")).isEmpty();
        assertThat(tenants.findBySlug("u-bob")).isPresent();
    }

    @Test
    @DisplayName("重名被拒")
    void registerRejectsDuplicateUsername() {
        auth.register("alice", PASSWORD, null);

        AccountAuthService.RegisterResult again = auth.register("alice", PASSWORD, null);

        assertThat(again.success()).isFalse();
        assertThat(again.error()).isEqualTo(ErrorCode.AUTH_USER_EXISTS);
    }

    @Test
    @DisplayName("密码过短被拒，且提示里带上实际位数")
    void registerRejectsShortPassword() {
        AccountAuthService.RegisterResult result = auth.register("alice", "123", null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo(ErrorCode.AUTH_PASSWORD_TOO_SHORT);
        assertThat(result.detail()).contains("8").contains("3");
    }

    @Test
    @DisplayName("空用户名与空密码被各自拒绝")
    void registerRejectsBlankFields() {
        assertThat(auth.register("   ", PASSWORD, null).error())
                .isEqualTo(ErrorCode.AUTH_USERNAME_INVALID);
        assertThat(auth.register("alice", "", null).error())
                .isEqualTo(ErrorCode.AUTH_PASSWORD_REQUIRED);
    }

    // ==================== 登录 ====================

    @Test
    @DisplayName("登录成功")
    void loginSucceedsWithCorrectPassword() {
        auth.register("alice", PASSWORD, null);

        assertThat(auth.login("alice", PASSWORD)).isPresent();
    }

    @Test
    @DisplayName("未提供密码时返回空，而不是抛 IllegalArgumentException")
    void loginWithNullPasswordReturnsEmpty() {
        auth.register("alice", PASSWORD, null);

        // BCryptPasswordEncoder.matches(null, hash) 抛 IllegalArgumentException，
        // 冒到全局处理器就成一个 HTTP 500「系统内部错误」—— 前端会当成服务端故障去重试，
        // 而真正的原因只是请求少了一个字段。
        assertThat(auth.login("alice", null)).isEmpty();
        assertThat(auth.login("alice", "")).isEmpty();
    }

    @Test
    @DisplayName("密码错、账号不存在、账号被禁用三种情况对外表现完全一致")
    void loginFailuresAreIndistinguishable() {
        User user = auth.register("alice", PASSWORD, null).user();

        User disabled = auth.register("carol", PASSWORD, null).user();
        disabled.setEnabled(false);
        users.saveAndFlush(disabled);

        // 三者都返回 empty。分开返回等于提供用户名枚举接口：
        // 攻击者能逐个个试出哪些用户名存在、哪些被禁用了。
        assertThat(auth.login("alice", "wrong-password")).isEmpty();
        assertThat(auth.login("nobody", PASSWORD)).isEmpty();
        assertThat(auth.login("carol", PASSWORD)).isEmpty();
        // 而正常账号仍能登录 —— 上面三条不是因为登录整体坏了
        assertThat(auth.login("alice", PASSWORD)).isPresent().get()
                .extracting(User::getId).isEqualTo(user.getId());
    }
}
