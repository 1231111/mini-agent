package com.miniagent.account.service;

import com.miniagent.account.config.PasswordEncoderConfig;
import com.miniagent.account.entity.MembershipOrder;
import com.miniagent.account.entity.MembershipPlan;
import com.miniagent.account.entity.MembershipSubscription;
import com.miniagent.account.entity.Tenant;
import com.miniagent.account.entity.User;
import com.miniagent.account.repository.MembershipOrderRepository;
import com.miniagent.account.repository.MembershipPlanRepository;
import com.miniagent.account.repository.MembershipSubscriptionRepository;
import com.miniagent.account.repository.TenantRepository;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会员的等级翻译与付费幂等。
 *
 * <p>这个类守的是两条不变量：
 * <ol>
 *   <li><b>会员等级 → {@code tenants.daily_token_limit} 的写入必须发生。</b>
 *       这是账号服务与 agent 之间唯一的耦合点，agent 侧的
 *       {@code DbTenantTokenQuota} 只读这一列。写漏了不会有任何报错，
 *       表现只是"买了会员但额度没变"。</li>
 *   <li><b>支付回调必须幂等。</b>渠道会重复投递（失败重试 / 多实例广播 / 人工补投）。
 *       不幂等的后果是同一笔钱开两次会员，或者更糟：第二次把第一次的订阅置成
 *       SUPERSEDED 又建一条新的，账面上多出一个周期。</li>
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
class MembershipServiceTest {

    private static final String PASSWORD = "12345678";
    private static final long FREE_TOKENS = 200_000L;
    private static final long PRO_TOKENS = 5_000_000L;
    private static final long PRO_PRICE_CENTS = 3900L;

    @Autowired
    private MembershipService membership;
    @Autowired
    private AccountAuthService auth;
    @Autowired
    private MembershipPlanRepository plans;
    @Autowired
    private MembershipSubscriptionRepository subscriptions;
    @Autowired
    private MembershipOrderRepository orders;
    @Autowired
    private TenantRepository tenants;

    @BeforeEach
    void seedPlans() {
        if (plans.findByCode(MembershipService.FREE_PLAN_CODE).isEmpty()) {
            plans.save(plan(MembershipService.FREE_PLAN_CODE, "免费版", FREE_TOKENS, 2, 0L, 0, true));
        }
        if (plans.findByCode("pro").isEmpty()) {
            plans.save(plan("pro", "专业版", PRO_TOKENS, 5, PRO_PRICE_CENTS, 30, true));
        }
        if (plans.findByCode("retired").isEmpty()) {
            plans.save(plan("retired", "下架版", 1_000L, 1, 100L, 30, false));
        }
    }

    private static MembershipPlan plan(String code, String name, long dailyTokens, int maxTasks,
                                       long cents, int days, boolean enabled) {
        MembershipPlan p = new MembershipPlan();
        p.setCode(code);
        p.setName(name);
        p.setDailyTokenLimit(dailyTokens);
        p.setMaxConcurrentTasks(maxTasks);
        p.setPriceCents(cents);
        p.setCurrency("CNY");
        p.setDurationDays(days);
        p.setEnabled(enabled);
        return p;
    }

    /** 走真实注册，拿到「有租户、有 free 订阅」的用户 —— 与生产路径一致。 */
    private User newUser(String name) {
        AccountAuthService.RegisterResult r = auth.register(name, PASSWORD, null);
        assertThat(r.success()).as("测试前置数据准备失败").isTrue();
        return r.user();
    }

    private long tenantQuotaOf(User user) {
        // 每次重新读，避免拿到同一个持久化上下文里被改过的实例后误判
        Tenant t = tenants.findById(user.getTenantId()).orElseThrow();
        return t.getDailyTokenLimit();
    }

    // ==================== 下单 ====================

    @Test
    @DisplayName("下单：落一条 PENDING，并把下单时的价格抄进订单")
    void createOrderSnapshotsPrice() {
        User user = newUser("alice");

        MembershipOrder order = membership.createOrder(user.getId(), "pro");

        assertThat(order.getStatus()).isEqualTo(MembershipOrder.Status.PENDING);
        assertThat(order.getAmountCents()).isEqualTo(PRO_PRICE_CENTS);
        assertThat(order.getOrderNo()).startsWith("MA");
        // 下单不碰配额 —— 配额只由支付回调触发
        assertThat(tenantQuotaOf(user)).isEqualTo(FREE_TOKENS);
    }

    @Test
    @DisplayName("下单：套餐不存在与已下架是两个不同的错误码")
    void createOrderRejectsUnknownAndDisabledPlans() {
        User user = newUser("alice");

        assertThatThrownBy(() -> membership.createOrder(user.getId(), "no-such-plan"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_PLAN_NOT_FOUND);

        // 已下架的套餐要和"不存在"分开：前者运营能自助处理（换个套餐或重新上架），
        // 后者是配置问题。合成一个码会让客服去查一套并不存在的数据。
        assertThatThrownBy(() -> membership.createOrder(user.getId(), "retired"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_PLAN_DISABLED);
    }

    // ==================== 支付回调 ====================

    @Test
    @DisplayName("支付成功：租户配额被改成 pro 的额度，且旧订阅变成 SUPERSEDED")
    void markPaidWritesQuotaAndSupersedesOldSubscription() {
        User user = newUser("alice");
        assertThat(tenantQuotaOf(user)).isEqualTo(FREE_TOKENS);

        MembershipOrder order = membership.createOrder(user.getId(), "pro");
        membership.markPaid(order.getOrderNo(), "alipay", "TXN-1");

        // ═══ 这一行是整个模块与 agent 的唯一耦合点 ═══
        // agent 侧的 DbTenantTokenQuota 只读 tenants.daily_token_limit，
        // 它不知道会员、订单、套餐的存在。这一列没写到，agent 侧毫无察觉。
        assertThat(tenantQuotaOf(user))
                .as("会员等级必须被翻译进 tenants.daily_token_limit")
                .isEqualTo(PRO_TOKENS);

        List<MembershipSubscription> active = subscriptions
                .findByUserIdAndStatus(user.getId(), MembershipSubscription.Status.ACTIVE);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getPlanCode()).isEqualTo("pro");
        assertThat(active.get(0).getExpireAt()).isNotNull();   // pro 有期限
        assertThat(active.get(0).getSourceOrderNo()).isEqualTo(order.getOrderNo());

        // 旧订阅是"收起"不是"删除"：升级与续费的历史是账单的依据，
        // 删掉之后"这个人上个月买过什么"就再也查不出来了，而对账是几个月后的事。
        List<MembershipSubscription> superseded = subscriptions
                .findByUserIdAndStatus(user.getId(), MembershipSubscription.Status.SUPERSEDED);
        assertThat(superseded).hasSize(1);
        assertThat(superseded.get(0).getPlanCode()).isEqualTo(MembershipService.FREE_PLAN_CODE);
    }

    @Test
    @DisplayName("支付回调重复投递：幂等，不重复开会员也不改写已有流水号")
    void markPaidIsIdempotent() {
        User user = newUser("alice");
        MembershipOrder order = membership.createOrder(user.getId(), "pro");
        membership.markPaid(order.getOrderNo(), "alipay", "TXN-1");

        // 渠道的重复投递有两种形态，都要挡住：
        membership.markPaid(order.getOrderNo(), "alipay", "TXN-1");   // 完全相同的重投
        membership.markPaid(order.getOrderNo(), "alipay", "TXN-2");   // 同一单换了流水号

        // 1. 订阅没有变多
        List<MembershipSubscription> actives = subscriptions
                .findByUserIdAndStatus(user.getId(), MembershipSubscription.Status.ACTIVE);
        assertThat(actives)
                .as("重复回调不得再开一次会员")
                .hasSize(1);
        assertThat(actives.get(0).getSourceOrderNo()).isEqualTo(order.getOrderNo());

        // 2. 没有多出一条 SUPERSEDED（如果第二次走的是"开新订阅"路径，就会出现两条）
        assertThat(subscriptions.findByUserIdAndStatus(
                user.getId(), MembershipSubscription.Status.SUPERSEDED))
                .as("重复回调不得把第一次的订阅收起后重开")
                .hasSize(1);

        // 3. 配额仍是 pro 的值
        assertThat(tenantQuotaOf(user)).isEqualTo(PRO_TOKENS);

        // 4. 流水号保持第一次的值：它是与渠道对账的凭据，被后者覆盖就再也对不上了
        MembershipOrder reloaded = orders.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MembershipOrder.Status.PAID);
        assertThat(reloaded.getChannelTxnId()).isEqualTo("TXN-1");
    }

    @Test
    @DisplayName("网页确认到账不能支付别人的订单，原订单保持 PENDING")
    void confirmPortalPaymentRejectsAnotherUser() {
        User alice = newUser("alice");
        User bob = newUser("bob");
        MembershipOrder order = membership.createOrder(alice.getId(), "pro");

        assertThatThrownBy(() ->
                membership.confirmPortalPayment(bob.getId(), order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_FORBIDDEN);

        MembershipOrder reloaded = orders.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MembershipOrder.Status.PENDING);
        assertThat(tenantQuotaOf(alice)).isEqualTo(FREE_TOKENS);
    }

    @Test
    @DisplayName("订单不存在时报 MEMBER.01.03")
    void markPaidRejectsUnknownOrder() {
        assertThatThrownBy(() -> membership.markPaid("MA-nope", "alipay", "TXN"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_ORDER_NOT_FOUND);
    }

    // ==================== 读取 ====================

    @Test
    @DisplayName("可售套餐只返回上架的，按下限从低到高排列")
    void sellablePlansExcludeDisabled() {
        List<MembershipService.PlanView> list = membership.sellablePlans();

        assertThat(list).extracting(MembershipService.PlanView::code)
                .containsExactly(MembershipService.FREE_PLAN_CODE, "pro")
                .doesNotContain("retired");
    }

    @Test
    @DisplayName("注册即享免费套餐，所以 current 永远有答案")
    void currentIsAlwaysPresentForRegisteredUser() {
        User user = newUser("alice");

        assertThat(membership.current(user.getId()))
                .as("注册时落一条 free 订阅，就是为了让'我的会员是什么'不出现空值")
                .isPresent()
                .get()
                .extracting(MembershipService.MembershipView::planCode)
                .isEqualTo(MembershipService.FREE_PLAN_CODE);

        // free 无期限，expireAt 用 null 而不是 now+100年 ——
        // 后者会在任何按时间排序或比较的地方表现成一个很像真实日期的值
        assertThat(membership.current(user.getId()).orElseThrow().expireAt()).isNull();
    }
}
