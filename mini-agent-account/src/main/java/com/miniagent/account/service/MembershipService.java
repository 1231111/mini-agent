package com.miniagent.account.service;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 会员等级与充值。
 *
 * <h3>这个类最关键的一个设计：会员等级被翻译成 tenants.daily_token_limit</h3>
 *
 * <p>云端 agent 的配额闸门是 {@code DbTenantTokenQuota.check/consume(tenantId)}，
 * 它只读 {@code tenants.daily_token_limit}，不知道"会员"是什么。
 * 所以订阅生效时本服务把这个值写进用户自己那个 tenant ——
 * <b>agent 侧一行代码都不用改</b>，而这个服务的表结构和业务语义可以任意演进。
 *
 * <p>替代方案是让 agent 也认识"会员等级"，然后它自己按等级查表算配额。
 * 那样每加一个等级维度（并发数、可用模型）都要改 agent 的配额代码，
 * 而且配额在两侧各算一遍，迟早会出现"界面显示一个值、实际卡在另一个值"。
 *
 * <h3>为什么本服务不读用量</h3>
 *
 * <p>{@code tenant_daily_usage} 是 agent 的运行数据，本服务不映射它。
 * 「今日已用 X / 上限 Y」里的分子属于 agent 的职责，应由调用方组装。
 * 让账号服务去读 agent 的表，会把"账号与付费"和"运行计量"两条边界混掉，
 * 以后想把 agent 换成另一个实现时会被这张表钉住。
 */
@Service
public class MembershipService {

    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);

    /** 注册时自动建立的套餐。它是"新用户默认额度"的唯一来源，见 AccountAuthService.createPersonalTenant 的说明。 */
    public static final String FREE_PLAN_CODE = "free";

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

    /**
     * 会员视图。给调用方渲染"我的会员"用。
     *
     * <p>{@code dailyTokenLimit} 的单位与 {@code tenants.daily_token_limit} 完全一致 ——
     * 中间不做任何换算。有换算就有两套单位，就会出现"界面显示 100 万、实际只给 1 万"
     * 这种要靠对数才能发现的问题。
     */
    public record MembershipView(String planCode, String planName, long dailyTokenLimit,
                                 int maxConcurrentTasks, LocalDateTime expireAt,
                                 String sourceOrderNo) {
    }

    /** 可售套餐视图。刻意不直接序列化实体 —— 实体以后加了成本价之类的字段会静默泄露。 */
    public record PlanView(String code, String name, long dailyTokenLimit, int maxConcurrentTasks,
                           long priceCents, String currency, int durationDays) {
        static PlanView of(MembershipPlan plan) {
            return new PlanView(plan.getCode(), plan.getName(), plan.getDailyTokenLimit(),
                    plan.getMaxConcurrentTasks(), plan.getPriceCents(), plan.getCurrency(),
                    plan.getDurationDays());
        }
    }

    /** 注册时调用，把新用户放进 free 档并落一条订阅。 */
    @Transactional
    public MembershipSubscription startFreeSubscription(Long userId, Long tenantId) {
        MembershipPlan free = plans.findByCode(FREE_PLAN_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "membership_plans 里没有 '" + FREE_PLAN_CODE + "' 套餐，无法确定新用户的默认额度。"
                                + "这条记录由 db/migration/V2__membership.sql 插入；"
                                + "缺失说明迁移没跑，或者有人把它删了。"));
        // 这里刻意不检查 plan.isEnabled()：free 是系统保留套餐，"下架"的含义是
        // "不能再新买"，而注册不是买。若把两者混起来，运营一旦下架 free，
        // 全部新用户注册会直接失败 —— 一个后台操作引爆注册入口。
        return applyPlan(userId, tenantId, free, null);
    }

    /** 当前生效的订阅。正常情况注册时就会建一条，返回空说明数据被外部改动过。 */
    @Transactional(readOnly = true)
    public Optional<MembershipView> current(Long userId) {
        return subscriptions
                .findFirstByUserIdAndStatusOrderByIdDesc(userId, MembershipSubscription.Status.ACTIVE)
                .map(sub -> {
                    MembershipPlan plan = plans.findByCode(sub.getPlanCode())
                            .orElseThrow(() -> new IllegalStateException(
                                    "订阅 " + sub.getId() + " 引用的套餐 '" + sub.getPlanCode() + "' 不存在。"
                                            + "套餐只应下架（enabled=false），不应被删除。"));
                    return new MembershipView(plan.getCode(), plan.getName(),
                            plan.getDailyTokenLimit(), plan.getMaxConcurrentTasks(),
                            sub.getExpireAt(), sub.getSourceOrderNo());
                });
    }

    @Transactional(readOnly = true)
    public List<PlanView> sellablePlans() {
        return plans.findByEnabledTrueOrderByPriceCentsAsc().stream().map(PlanView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<MembershipOrder> ordersOf(Long userId) {
        return orders.findByUserIdOrderByIdDesc(userId);
    }

    /**
     * 网页门户确认到账。只允许订单本人操作。
     *
     * <p>ponytail: 渠道名固定为 portal。接真实支付后改为渠道回调，
     * 浏览器不能再直接把订单标成已支付。
     */
    @Transactional
    public MembershipOrder confirmPortalPayment(Long userId, String orderNo) {
        MembershipOrder order = orders.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.MEMBER_ORDER_NOT_FOUND,
                        "订单「" + orderNo + "」不存在"));
        if (userId == null || !userId.equals(order.getUserId())) {
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN, "不能支付他人的订单");
        }
        return markPaid(orderNo, "portal", orderNo);
    }

    /**
     * 下单。只落一条 PENDING 订单，不碰配额 —— 配额由支付回调触发。
     *
     * <p>金额在<b>下单时从套餐表抄一份</b>存进订单，之后不再跟套餐表联动。
     * 这样运营改价不会改变已下单未支付的订单金额（改价前后各有一笔，对账时能看清），
     * 也不会让"我下单时看到的是 39 元"这句话失去依据。
     */
    @Transactional
    public MembershipOrder createOrder(Long userId, String planCode) {
        MembershipPlan plan = plans.findByCode(planCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_PLAN_NOT_FOUND,
                        "套餐「" + planCode + "」不存在"));
        if (!plan.isEnabled()) {
            throw new BusinessException(ErrorCode.MEMBER_PLAN_DISABLED,
                    "套餐「" + plan.getName() + "」已下架");
        }
        if (!users.existsById(userId)) {
            throw new BusinessException(ErrorCode.MEMBER_USER_NOT_FOUND, "用户 " + userId + " 不存在");
        }

        MembershipOrder order = new MembershipOrder();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setPlanCode(plan.getCode());
        order.setAmountCents(plan.getPriceCents());
        order.setCurrency(plan.getCurrency());
        order.setStatus(MembershipOrder.Status.PENDING);
        MembershipOrder saved = orders.save(order);
        log.info("会员下单 orderNo={} userId={} plan={} amountCents={}",
                saved.getOrderNo(), userId, plan.getCode(), plan.getPriceCents());
        return saved;
    }

    /**
     * 标记订单已支付并让订阅生效。
     *
     * <p><b>幂等是必须的，不是优化。</b>支付渠道普遍会重复投递同一个成功回调 ——
     * 失败重试、多实例广播、人工补投都会造成重复。重复投递若不幂等，
     * 后果是同一笔钱开了两次会员，或者更糟：第二次把第一次的订阅置成 SUPERSEDED
     * 又建一条新的，账面上多出一个周期。
     *
     * <p>并发也要挡住：两条线程同时处理同一单时，都读到 PENDING 就会都去开订阅。
     * 所以这里用 {@code findByOrderNoForUpdate} 把同一单串行化 —— 拿不到锁的那条
     * 会在前一条提交后重新读到 PAID 走幂等分支。
     */
    @Transactional
    public MembershipOrder markPaid(String orderNo, String channel, String channelTxnId) {
        MembershipOrder order = orders.findByOrderNoForUpdate(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_ORDER_NOT_FOUND,
                        "订单「" + orderNo + "」不存在"));

        if (order.getStatus() == MembershipOrder.Status.PAID) {
            log.info("重复的支付回调，按幂等成功返回 orderNo={} channel={} txnId={}",
                    orderNo, channel, channelTxnId);
            return order;
        }
        if (order.getStatus() != MembershipOrder.Status.PENDING) {
            throw new BusinessException(ErrorCode.MEMBER_ORDER_STATE_INVALID,
                    "订单「" + orderNo + "」当前状态是 " + order.getStatus() + "，不能标记为已支付");
        }

        MembershipPlan plan = plans.findByCode(order.getPlanCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_PLAN_NOT_FOUND,
                        "订单「" + orderNo + "」引用的套餐「" + order.getPlanCode() + "」不存在"));
        User user = users.findById(order.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_USER_NOT_FOUND,
                        "订单「" + orderNo + "」引用的用户 " + order.getUserId() + " 不存在"));

        order.setStatus(MembershipOrder.Status.PAID);
        order.setChannel(channel);
        order.setChannelTxnId(channelTxnId);
        order.setPaidAt(LocalDateTime.now());

        applyPlan(order.getUserId(), user.getTenantId(), plan, orderNo);
        return order;
    }

    /**
     * 让一个套餐真正生效：把旧订阅收起、落新订阅、写入配额上限。
     *
     * <p>旧订阅置为 {@code SUPERSEDED} 而不是删除：升级与续费的历史是账单的依据，
     * 删掉之后"这个人上个月买过什么"就再也查不出来了，而对账恰恰是几个月后的事。
     *
     * <p><b>调用方必须已在事务内</b>：这里既有多次写，又在最后加锁改 tenant，
     * 拆成多个事务会出现"订阅是新档、配额还是旧档"的中间态，而那个中间态
     * 恰好就是 agent 读配额的时刻。
     */
    @Transactional
    public MembershipSubscription applyPlan(Long userId, Long tenantId, MembershipPlan plan,
                                           String sourceOrderNo) {
        LocalDateTime now = LocalDateTime.now();

        List<MembershipSubscription> active = subscriptions
                .findByUserIdAndStatus(userId, MembershipSubscription.Status.ACTIVE);
        for (MembershipSubscription old : active) {
            old.setStatus(MembershipSubscription.Status.SUPERSEDED);
        }

        MembershipSubscription sub = new MembershipSubscription();
        sub.setUserId(userId);
        sub.setTenantId(tenantId);
        sub.setPlanCode(plan.getCode());
        sub.setStatus(MembershipSubscription.Status.ACTIVE);
        sub.setStartedAt(now);
        // durationDays <= 0 表示永久（free 档是这种）。用 null 而不是 now+100年：
        // 后者会在任何按时间排序或比较的地方表现成一个很像真实日期的值，
        // 以后排查"这个会员到底什么时候到期"时要先搞清楚 2126 年是什么意思。
        sub.setExpireAt(plan.getDurationDays() <= 0 ? null : now.plusDays(plan.getDurationDays()));
        sub.setSourceOrderNo(sourceOrderNo);
        subscriptions.save(sub);

        // 这一步是"会员等级 → agent 认识的东西"的全部内容。
        // agent 侧的 DbTenantTokenQuota 只读这一列，它不知道会员、订单、套餐的存在。
        Tenant tenant = tenants.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "租户 " + tenantId + " 不存在，无法写入配额上限（用户 " + userId + "）"));
        tenant.setDailyTokenLimit(plan.getDailyTokenLimit());

        log.info("会员生效 userId={} tenantId={} plan={} dailyTokenLimit={} expireAt={} 替换掉的旧订阅={} 条",
                userId, tenantId, plan.getCode(), plan.getDailyTokenLimit(), sub.getExpireAt(), active.size());
        return sub;
    }

    /**
     * 单号：{@code MA} + 时间戳 + 6 位随机。
     *
     * <p>时间前缀是为了人工按日期检索（对账时最常做的事），随机后缀是为了避免
     * 同一毫秒内的多单撞号。真正的唯一性由 {@code uk_membership_orders_order_no} 兜底 ——
     * 这个方法的输出不需要做到数学上唯一，它只需让碰撞在实践中不发生，
     * 而万一发生了，唯一索引会让它变成一次明确的插入失败，而不是两笔订单互相覆盖。
     */
    private static String generateOrderNo() {
        String ts = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now());
        int rand = ThreadLocalRandom.current().nextInt(1_000_000);
        return String.format("MA%s%06d", ts, rand);
    }
}
