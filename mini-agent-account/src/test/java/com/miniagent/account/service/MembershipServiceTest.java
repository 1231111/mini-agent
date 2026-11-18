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
import com.miniagent.account.repository.UserRepository;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会员的等级翻译与付费幂等。
 *
 * <p>这个类守的是几条不变量：
 * <ol>
 *   <li><b>个人会员变更不能覆盖租户共享额度。</b> 套餐额度通过会员视图呈现，
 *       运行时个人限额由 agent 侧用户配额配置执行。</li>
 *   <li><b>到期订阅在 current 查询中转成 EXPIRED，续购会保留当前剩余有效期。</b></li>
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
    @Autowired
    private UserRepository users;

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
        assertThat(order.getExpiresAt()).isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
                .isBefore(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusMinutes(16));
        // 下单不碰配额 —— 配额只由支付回调触发
        assertThat(tenantQuotaOf(user)).isZero();
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

    @Test
    @DisplayName("刷新结账：用户可恢复自己的待支付订单，取消后不再出现在待处理列表")
    void pendingOrdersCanBeRecoveredAndCanceled() {
        User user = newUser("order-recovery");
        MembershipOrder order = membership.createOrder(user.getId(), "pro");

        assertThat(membership.pendingOrdersOf(user.getId()))
                .extracting(MembershipOrder::getOrderNo)
                .containsExactly(order.getOrderNo());

        membership.cancelOrder(user.getId(), order.getOrderNo());

        assertThat(membership.pendingOrdersOf(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("刷新结账：到期订单立即转为 EXPIRED，不再显示为待支付")
    void pendingOrderExpiryIsVisibleWithoutWaitingForTheBackgroundSweep() {
        User user = newUser("expired-order-recovery");
        MembershipOrder order = membership.createOrder(user.getId(), "pro");
        order.setExpiresAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1));
        orders.saveAndFlush(order);

        assertThat(membership.pendingOrdersOf(user.getId())).isEmpty();
        assertThat(orders.findByOrderNo(order.getOrderNo()).orElseThrow().getStatus())
                .isEqualTo(MembershipOrder.Status.EXPIRED);
    }

    // ==================== 支付回调 ====================

    @Test
    @DisplayName("支付成功：激活 pro 会员并收起旧订阅，不改共享租户额度")
    void markPaidActivatesPlanAndSupersedesOldSubscription() {
        User user = newUser("alice");
        long originalTenantQuota = tenantQuotaOf(user);

        MembershipOrder order = membership.createOrder(user.getId(), "pro");
        membership.markPaid(order.getOrderNo(), "alipay", "TXN-1");

        // ═══ 这一行是整个模块与 agent 的唯一耦合点 ═══
        // agent 侧的 DbTenantTokenQuota 只读 tenants.daily_token_limit，
        // 它不知道会员、订单、套餐的存在。这一列没写到，agent 侧毫无察觉。
        assertThat(tenantQuotaOf(user))
                .as("个人会员变更不能覆盖租户共享额度")
                .isEqualTo(originalTenantQuota);

        List<MembershipSubscription> active = subscriptions
                .findByUserIdAndStatus(user.getId(), MembershipSubscription.Status.ACTIVE);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getPlanCode()).isEqualTo("pro");
        assertThat(active.get(0).getExpireAt()).isNotNull();   // pro 有期限
        assertThat(membership.current(user.getId()).orElseThrow().dailyTokenLimit())
                .isEqualTo(PRO_TOKENS);
        User entitled = users.findById(user.getId()).orElseThrow();
        assertThat(entitled.getMembershipDailyTokenLimit()).isEqualTo(PRO_TOKENS);
        assertThat(entitled.getMembershipMaxConcurrentTasks()).isEqualTo(5);
        assertThat(Math.abs(java.time.Duration.between(active.get(0).getExpireAt(),
                entitled.getMembershipExpiresAt()).toNanos()))
                .isLessThanOrEqualTo(1_000L); // H2/MySQL DATETIME(6) rounds to microseconds.
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

        // 3. 重复回调不会写共享租户额度
        assertThat(tenantQuotaOf(user)).isZero();

        // 4. 流水号保持第一次的值：它是与渠道对账的凭据，被后者覆盖就再也对不上了
        MembershipOrder reloaded = orders.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MembershipOrder.Status.PAID);
        assertThat(reloaded.getChannelTxnId()).isEqualTo("TXN-1");
    }

    @Test
    @DisplayName("模拟支付只允许本人确认待支付订单，并且重试仍然幂等")
    void simulatedPaymentChecksOwnerAndActivatesOnce() {
        User owner = newUser("alice");
        User other = newUser("bob");
        MembershipOrder order = membership.createOrder(owner.getId(), "pro");

        assertThatThrownBy(() -> membership.simulatePayment(other.getId(), order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_ORDER_NOT_FOUND);
        assertThat(orders.findByOrderNo(order.getOrderNo()).orElseThrow().getStatus())
                .isEqualTo(MembershipOrder.Status.PENDING);

        membership.simulatePayment(owner.getId(), order.getOrderNo());
        membership.simulatePayment(owner.getId(), order.getOrderNo());

        MembershipOrder paid = orders.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(MembershipOrder.Status.PAID);
        assertThat(paid.getChannel()).isEqualTo("SIMULATED");
        assertThat(paid.getChannelTxnId()).isEqualTo("SIMULATED-" + order.getOrderNo());
        assertThat(subscriptions.findByUserIdAndStatus(
                owner.getId(), MembershipSubscription.Status.ACTIVE)).hasSize(1);
    }

    @Test
    @DisplayName("模拟支付不能确认过期订单，并且把订单收敛为 EXPIRED")
    void simulatedPaymentRejectsExpiredOrder() {
        User owner = newUser("expired-order");
        MembershipOrder order = membership.createOrder(owner.getId(), "pro");
        order.setExpiresAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1));
        orders.saveAndFlush(order);

        assertThatThrownBy(() -> membership.simulatePayment(owner.getId(), order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_ORDER_STATE_INVALID);
        assertThat(orders.findByOrderNo(order.getOrderNo()).orElseThrow().getStatus())
                .isEqualTo(MembershipOrder.Status.EXPIRED);
        assertThat(subscriptions.findByUserIdAndStatus(owner.getId(), MembershipSubscription.Status.ACTIVE))
                .hasSize(1); // The original free subscription only.
    }

    @Test
    @DisplayName("用户只能取消本人待支付订单")
    void cancelOrderChecksOwnerAndPendingState() {
        User owner = newUser("cancel-owner");
        User other = newUser("cancel-other");
        MembershipOrder order = membership.createOrder(owner.getId(), "pro");

        assertThatThrownBy(() -> membership.cancelOrder(other.getId(), order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_ORDER_NOT_FOUND);
        assertThat(membership.cancelOrder(owner.getId(), order.getOrderNo()).getStatus())
                .isEqualTo(MembershipOrder.Status.CANCELED);
    }

    @Test
    @DisplayName("后台扫描把支付截止时间已过的 PENDING 订单改为 EXPIRED")
    void scheduledSweepExpiresPendingOrders() {
        User owner = newUser("sweep-expired");
        MembershipOrder order = membership.createOrder(owner.getId(), "pro");
        order.setExpiresAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1));
        orders.saveAndFlush(order);

        assertThat(membership.expirePendingOrders()).isEqualTo(1);
        assertThat(orders.findByOrderNo(order.getOrderNo()).orElseThrow().getStatus())
                .isEqualTo(MembershipOrder.Status.EXPIRED);
    }

    @Test
    @DisplayName("续购从当前有效期末日堆叠，不损失剩余时长")
    void renewalStacksOnCurrentExpiry() {
        User user = newUser("renewal");
        MembershipOrder firstOrder = membership.createOrder(user.getId(), "pro");
        membership.markPaid(firstOrder.getOrderNo(), "alipay", "TXN-1");
        LocalDateTime firstExpiry = membership.current(user.getId()).orElseThrow().expireAt();

        MembershipOrder renewal = membership.createOrder(user.getId(), "pro");
        membership.markPaid(renewal.getOrderNo(), "alipay", "TXN-2");

        MembershipSubscription current = subscriptions.findFirstByUserIdAndStatusOrderByIdDesc(
                user.getId(), MembershipSubscription.Status.ACTIVE).orElseThrow();
        assertThat(current.getExpireAt()).isEqualTo(firstExpiry.plusDays(30));
        assertThat(current.getSourceOrderNo()).isEqualTo(renewal.getOrderNo());
        assertThat(subscriptions.findByUserIdAndStatus(user.getId(), MembershipSubscription.Status.SUPERSEDED))
                .hasSize(2);
    }

    @Test
    @DisplayName("current 查询到期会员时返回空并持久化 EXPIRED")
    void currentExpiresSubscriptionAtExpiryBoundary() {
        User user = newUser("expired");
        MembershipOrder order = membership.createOrder(user.getId(), "pro");
        membership.markPaid(order.getOrderNo(), "alipay", "TXN-1");
        MembershipSubscription subscription = subscriptions.findFirstByUserIdAndStatusOrderByIdDesc(
                user.getId(), MembershipSubscription.Status.ACTIVE).orElseThrow();
        subscription.setExpireAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1));
        subscriptions.saveAndFlush(subscription);

        assertThat(membership.current(user.getId())).isEmpty();
        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getStatus())
                .isEqualTo(MembershipSubscription.Status.EXPIRED);
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

    @Test
    @DisplayName("还没有上报时，今日用量是 0，额度仍是当前套餐")
    void usageOfEmptyDayShowsPlanLimit() {
        User user = newUser("usage-empty");

        MembershipService.UsageView view = membership.usageOf(user.getId());

        assertThat(view.dailyTokenLimit()).isEqualTo(FREE_TOKENS);
        assertThat(view.inputTokens()).isZero();
        assertThat(view.outputTokens()).isZero();
        assertThat(view.totalTokens()).isZero();
        assertThat(view.llmCalls()).isZero();
        assertThat(view.events()).isEmpty();
    }

    @Test
    @DisplayName("每次上报各记一行，今天的次数是行数")
    void eachReportIsItsOwnRow() {
        User user = newUser("usage-each");

        membership.recordUsage(user.getId(), 10, 4, 1);
        membership.recordUsage(user.getId(), 3, 1, 1);
        MembershipService.UsageView view = membership.usageOf(user.getId());

        assertThat(view.llmCalls()).isEqualTo(2);
        assertThat(view.inputTokens()).isEqualTo(13);
        assertThat(view.outputTokens()).isEqualTo(5);
        assertThat(view.totalTokens()).isEqualTo(18);
        assertThat(view.events()).hasSize(2);
    }

    @Test
    @DisplayName("上报给不存在的用户时拒绝，不写用量行")
    void recordUsageRejectsUnknownUser() {
        assertThatThrownBy(() -> membership.recordUsage(9_999_999L, 10, 20, 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_USER_NOT_FOUND);
    }
}
