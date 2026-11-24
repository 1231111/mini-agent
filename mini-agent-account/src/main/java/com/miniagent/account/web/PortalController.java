package com.miniagent.account.web;

import com.miniagent.account.entity.MembershipOrder;
import com.miniagent.account.entity.User;
import com.miniagent.account.repository.UserRepository;
import com.miniagent.account.service.AccountAuthService;
import com.miniagent.account.service.DesktopLoginTicketStore;
import com.miniagent.account.service.LlmCallUsageService;
import com.miniagent.account.service.MembershipService;
import com.miniagent.account.service.UsageQueryService;
import com.miniagent.account.web.dto.AccountUserResponse;
import com.miniagent.account.web.dto.LatencyStatsResponse;
import com.miniagent.account.web.dto.LlmCallUsageResponse;
import com.miniagent.account.web.dto.PageResponse;
import com.miniagent.account.web.dto.UsageSummaryResponse;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.RequestParam;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Base64;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;

/**
 * 浏览器上的账号与充值页。
 *
 * <p>这里的 HttpSession 只给这个网页用，响应里仍然没有 token。
 * 桌面端登录继续走 {@code POST /api/tokens}，由客户机自己签发 JWT。
 */
@Controller
public class PortalController {

    static final String SESSION_USER_ID = "portalUserId";
    static final String CSRF_COOKIE = "PORTAL-XSRF";
    static final String SECURE_CSRF_COOKIE = "__Host-PORTAL-XSRF";
    private static final SecureRandom CSRF_RANDOM = new SecureRandom();

    @Value("${server.servlet.session.cookie.secure:false}")
    private boolean secureCookie;

    @Value("${agent.account.membership.simulated-payment-enabled:false}")
    private boolean simulatedPaymentEnabled;

    @Autowired
    private AccountAuthService auth;
    @Autowired
    private MembershipService membership;
    @Autowired
    private UserRepository users;
    @Autowired
    private DesktopLoginTicketStore tickets;
    @Autowired
    private UsageQueryService usageQueries;
    @Autowired
    private LlmCallUsageService callUsage;

    public record LoginBody(String username, String password) {
    }

    public record RegisterBody(String username, String password, String displayName) {
    }

    public record OrderBody(String planCode) {
    }

    public record TicketView(String ticket) {
    }

    @GetMapping({"/", "/login", "/register", "/account"})
    public String page() {
        return "forward:/portal.html";
    }

    /** Issue a short-lived double-submit token for same-origin portal POSTs. */
    @GetMapping(value = "/api/portal/csrf", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Map<String, String>> csrf(HttpServletResponse response) {
        byte[] bytes = new byte[32];
        CSRF_RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String cookieName = secureCookie ? SECURE_CSRF_COOKIE : CSRF_COOKIE;
        ResponseCookie cookie = ResponseCookie.from(cookieName, token)
                .httpOnly(false)
                .secure(secureCookie)
                .path("/")
                .sameSite("Strict")
                .maxAge(Duration.ofHours(8))
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(Map.of("token", token));
    }

    @PostMapping(value = "/api/portal/session",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<AccountUserResponse> login(
            @RequestBody(required = false) LoginBody body,
            HttpServletRequest request) {
        if (body == null) {
            return ApiResponse.fail(ErrorCode.AUTH_LOGIN_FAILED);
        }
        return auth.login(body.username(), body.password())
                .map(user -> {
                    bind(request, user.getId());
                    return ApiResponse.ok(AccountUserResponse.of(user));
                })
                .orElse(ApiResponse.fail(ErrorCode.AUTH_LOGIN_FAILED));
    }

    @PostMapping(value = "/api/portal/users",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<AccountUserResponse> register(
            @RequestBody(required = false) RegisterBody body,
            HttpServletRequest request) {
        if (body == null) {
            return ApiResponse.fail(ErrorCode.AUTH_USERNAME_INVALID, "用户名不能为空");
        }
        AccountAuthService.RegisterResult result = auth.register(
                body.username(), body.password(), body.displayName());
        if (!result.success()) {
            return ApiResponse.fail(result.error(), result.detail());
        }
        User user = result.user();
        bind(request, user.getId());
        return ApiResponse.ok(AccountUserResponse.of(user));
    }

    /**
     * 刚注册并已建立门户会话的用户，换一张给桌面客户端用的一次性凭证。
     */
    @PostMapping(value = "/api/portal/desktop-ticket",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<TicketView> desktopTicket(HttpServletRequest request) {
        User user = requireUser(request);
        return ApiResponse.ok(new TicketView(tickets.issue(user.getId())));
    }

    @PostMapping(value = "/api/portal/logout",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ApiResponse.ok(null);
    }

    @GetMapping(value = "/api/portal/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<AccountUserResponse> me(HttpServletRequest request) {
        return ApiResponse.ok(AccountUserResponse.of(requireUser(request)));
    }

    @GetMapping(value = "/api/portal/plans", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<List<MembershipService.PlanView>> plans(HttpServletRequest request) {
        requireUser(request);
        return ApiResponse.ok(membership.sellablePlans());
    }

    @GetMapping(value = "/api/portal/membership",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<MembershipService.MembershipView> current(HttpServletRequest request) {
        Long userId = requireUser(request).getId();
        return membership.current(userId)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail(
                        ErrorCode.MEMBER_SUBSCRIPTION_NOT_FOUND, "没有生效中的订阅"));
    }

    @GetMapping(value = "/api/portal/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<MembershipService.UsageView> usage(HttpServletRequest request) {
        Long userId = requireUser(request).getId();
        return ApiResponse.ok(membership.usageOf(userId));
    }

    /**
     * 按「天 × 模型」汇总的用量。
     *
     * <p>数据来自 {@code user_daily_usage}，与旧的 {@code /api/portal/usage} 不是同一张表：
     * 旧接口读 {@code membership_usage_events}，只有输入/输出两个数；
     * 这里能给出缓存命中、费用、按模型拆分。</p>
     */
    @GetMapping(value = "/api/portal/usage/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<UsageSummaryResponse> usageSummary(
            HttpServletRequest request,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long userId = requireUser(request).getId();
        try {
            return ApiResponse.ok(usageQueries.summary(userId, from, to));
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, invalid.getMessage());
        }
    }

    /** 单次调用明细：首 token、耗时、输出 TPS、模型与端点。 */
    @GetMapping(value = "/api/portal/usage/calls", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<PageResponse<LlmCallUsageResponse>> usageCalls(
            HttpServletRequest request,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "50") int size) {
        Long userId = requireUser(request).getId();
        try {
            Page<LlmCallUsageResponse> result = callUsage.detail(userId, from, to, page, size);
            return ApiResponse.ok(PageResponse.of(result, item -> item));
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, invalid.getMessage());
        }
    }

    /** 区间延迟：平均首 token、平均耗时、最大耗时。 */
    @GetMapping(value = "/api/portal/usage/latency", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<LatencyStatsResponse> usageLatency(
            HttpServletRequest request,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long userId = requireUser(request).getId();
        try {
            return ApiResponse.ok(callUsage.latency(userId, from, to));
        } catch (IllegalArgumentException invalid) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, invalid.getMessage());
        }
    }

    @GetMapping(value = "/api/portal/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<List<MembershipController.OrderView>> orders(HttpServletRequest request) {
        Long userId = requireUser(request).getId();
        List<MembershipController.OrderView> views = membership.ordersOf(userId).stream()
                .map(MembershipController.OrderView::of)
                .toList();
        return ApiResponse.ok(views);
    }

    @GetMapping(value = "/api/portal/payment-mode", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Map<String, Boolean>> paymentMode(HttpServletRequest request) {
        requireUser(request);
        return ApiResponse.ok(Map.of("simulatedPaymentEnabled", simulatedPaymentEnabled));
    }

    @PostMapping(value = "/api/portal/orders",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<MembershipController.OrderView> createOrder(
            @RequestBody(required = false) OrderBody body,
            HttpServletRequest request) {
        Long userId = requireUser(request).getId();
        String planCode = body == null ? null : body.planCode();
        MembershipOrder order = membership.createOrder(userId, planCode);
        return ApiResponse.ok(MembershipController.OrderView.of(order));
    }

    @PostMapping(value = "/api/portal/orders/{orderNo}/confirm",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<MembershipController.OrderView> simulatePayment(
            @PathVariable String orderNo, HttpServletRequest request) {
        User user = requireUser(request);
        if (!simulatedPaymentEnabled) {
            return ApiResponse.fail(ErrorCode.MEMBER_PAYMENT_CALLBACK_DISABLED,
                    "模拟支付未启用；真实支付渠道尚未接入");
        }
        return ApiResponse.ok(MembershipController.OrderView.of(
                membership.simulatePayment(user.getId(), orderNo)));
    }

    @PostMapping(value = "/api/portal/orders/{orderNo}/cancel",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<MembershipController.OrderView> cancelOrder(
            @PathVariable String orderNo, HttpServletRequest request) {
        User user = requireUser(request);
        return ApiResponse.ok(MembershipController.OrderView.of(
                membership.cancelOrder(user.getId(), orderNo)));
    }

    private static void bind(HttpServletRequest request, Long userId) {
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        request.getSession(true).setAttribute(SESSION_USER_ID, userId);
    }

    private User requireUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object raw = session == null ? null : session.getAttribute(SESSION_USER_ID);
        if (!(raw instanceof Long userId)) {
            throw new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED, "请先登录");
        }
        return users.findById(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.AUTH_NOT_AUTHENTICATED, "请重新登录"));
    }
}
