package com.miniagent.account.web;

import com.miniagent.account.entity.MembershipOrder;
import com.miniagent.account.service.MembershipService;
import com.miniagent.account.service.MembershipUsageEventService;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会员端点。
 *
 * <p>整组路径都被 {@link InternalKeyFilter} 拦着，要求 {@code X-Account-Internal-Key}。
 * 原因是本服务不签 token（见 {@code AccountApplication}），所以它没有"当前登录用户"
 * 这个概念，也就没法自己做用户级鉴权。
 *
 * <p>分工是：调用方（云端 agent / 客户机本地后端）先验掉用户自己的 token，
 * 从中拿到 userId，再带这个密钥转发过来。于是 {@code userId} 是<b>调用方担保的</b>，
 * 而不是从本服务的会话里读出来的 —— 这一点在排查"为什么能查到别人的会员"时必须先想到：
 * 那说明调用方的鉴权漏了，不是这里放行了。
 */
@RestController
@RequestMapping("/api/membership")
public class MembershipController {

    @Autowired
    private MembershipService membership;

    @Autowired
    private MembershipUsageEventService usageEvents;

    @Value("${agent.account.membership.simulated-payment-enabled:false}")
    private boolean simulatedPaymentEnabled;

    public record CreateOrderRequest(Long userId, String planCode) {
    }

    public record UsageRequest(Long userId, Long inputTokens, Long outputTokens, Integer llmCalls,
                               @Size(max = 128) @Pattern(regexp = ".*\\S.*") String eventId) {
        public UsageRequest(Long userId, Long inputTokens, Long outputTokens, Integer llmCalls) {
            this(userId, inputTokens, outputTokens, llmCalls, null);
        }
    }

    public record SimulatePaymentRequest(Long userId, String orderNo) {
    }

    public record OrderView(Long orderId, String orderNo, Long userId, String planCode,
                            long amountCents, String currency, String status,
                            String channel, String channelTxnId, LocalDateTime paidAt,
                            LocalDateTime expiresAt) {
        static OrderView of(MembershipOrder order) {
            return new OrderView(order.getId(), order.getOrderNo(), order.getUserId(),
                    order.getPlanCode(), order.getAmountCents(), order.getCurrency(),
                    order.getStatus() == null ? null : order.getStatus().name(),
                    order.getChannel(), order.getChannelTxnId(), order.getPaidAt(), order.getExpiresAt());
        }
    }

    /** 可售套餐。用于渲染价格表。 */
    @GetMapping(value = "/plans", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<List<MembershipService.PlanView>> plans() {
        return ApiResponse.ok(membership.sellablePlans());
    }

    /**
     * 某人当前的会员。
     *
     * <p>没有生效中的订阅时返回 {@code MEMBER_SUBSCRIPTION_NOT_FOUND} 而不是 {@code data:null} ——
     * 让调用方处理 null 的后果是各写各的兜底，最后界面上出现三种不同的"无会员"表现。
     */
    @GetMapping(value = "/current", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<MembershipService.MembershipView> current(@RequestParam("userId") Long userId) {
        return membership.current(userId)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail(ErrorCode.MEMBER_SUBSCRIPTION_NOT_FOUND,
                        "用户 " + userId + " 没有生效中的订阅"));
    }

    /** Pending orders for the authenticated agent user to resume checkout after refresh. */
    @GetMapping(value = "/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<List<OrderView>> pendingOrders(@RequestParam("userId") Long userId) {
        return ApiResponse.ok(membership.pendingOrdersOf(userId).stream()
                .map(OrderView::of)
                .toList());
    }

    /** 下单。只落一条 PENDING 订单，不碰配额；配额由支付回调触发。 */
    @PostMapping(value = "/orders", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<OrderView> createOrder(@RequestBody(required = false) CreateOrderRequest req) {
        if (req == null || req.userId() == null) {
            throw new BusinessException(ErrorCode.MEMBER_USER_NOT_FOUND, "缺少 userId");
        }
        return ApiResponse.ok(OrderView.of(membership.createOrder(req.userId(), req.planCode())));
    }

    /** Cancel the authenticated caller's own pending order. */
    @PostMapping(value = "/orders/cancel", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<OrderView> cancelOrder(@RequestBody(required = false) CancelOrderRequest req) {
        if (req == null || req.userId() == null || req.orderNo() == null || req.orderNo().isBlank()) {
            throw new BusinessException(ErrorCode.MEMBER_ORDER_NOT_FOUND, "订单不存在");
        }
        return ApiResponse.ok(OrderView.of(membership.cancelOrder(req.userId(), req.orderNo())));
    }

    public record CancelOrderRequest(Long userId, String orderNo) {
    }

    /**
     * Fail closed until a channel-specific adapter verifies the signature and the
     * provider's settled order, amount and currency. The shared internal key only
     * authenticates another service; it does not prove that a payment occurred.
     */
    @PostMapping(value = "/pay-callback", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<ApiResponse<OrderView>> payCallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(ErrorCode.MEMBER_PAYMENT_CALLBACK_DISABLED,
                        "支付回调已禁用：部署前必须接入并配置渠道签名、金额和币种校验"));
    }

    /** Explicitly gated test/demo settlement; InternalKeyFilter protects this route. */
    @PostMapping(value = "/simulate-payment", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<ApiResponse<OrderView>> simulatePayment(
            @RequestBody(required = false) SimulatePaymentRequest req) {
        if (!simulatedPaymentEnabled) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.fail(ErrorCode.MEMBER_PAYMENT_CALLBACK_DISABLED,
                            "模拟支付未启用；该功能仅供演示，不会产生真实扣款"));
        }
        if (req == null || req.userId() == null || req.orderNo() == null || req.orderNo().isBlank()) {
            throw new BusinessException(ErrorCode.MEMBER_ORDER_NOT_FOUND, "订单不存在");
        }
        return ResponseEntity.ok(ApiResponse.ok(
                OrderView.of(membership.simulatePayment(req.userId(), req.orderNo()))));
    }

    /** 客户机上报一次 LLM 调用。一次调用一行。 */
    @PostMapping(value = "/usage", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Void> usage(@Valid @RequestBody(required = false) UsageRequest req) {
        if (req == null || req.userId() == null) {
            throw new BusinessException(ErrorCode.MEMBER_USER_NOT_FOUND, "缺少 userId");
        }
        long input = req.inputTokens() == null ? 0 : req.inputTokens();
        long output = req.outputTokens() == null ? 0 : req.outputTokens();
        int calls = req.llmCalls() == null ? 0 : req.llmCalls();
        usageEvents.recordUsage(req.userId(), input, output, calls, req.eventId());
        return ApiResponse.ok(null);
    }
}
