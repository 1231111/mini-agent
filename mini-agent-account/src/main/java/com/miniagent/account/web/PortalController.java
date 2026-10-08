package com.miniagent.account.web;

import com.miniagent.account.entity.MembershipOrder;
import com.miniagent.account.entity.User;
import com.miniagent.account.repository.UserRepository;
import com.miniagent.account.service.AccountAuthService;
import com.miniagent.account.service.DesktopLoginTicketStore;
import com.miniagent.account.service.MembershipService;
import com.miniagent.account.web.dto.AccountUserResponse;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * 浏览器上的账号与充值页。
 *
 * <p>这里的 HttpSession 只给这个网页用，响应里仍然没有 token。
 * 桌面端登录继续走 {@code POST /api/tokens}，由客户机自己签发 JWT。
 */
@Controller
public class PortalController {

    static final String SESSION_USER_ID = "portalUserId";

    @Autowired
    private AccountAuthService auth;
    @Autowired
    private MembershipService membership;
    @Autowired
    private UserRepository users;
    @Autowired
    private DesktopLoginTicketStore tickets;

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

    @GetMapping(value = "/api/portal/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<List<MembershipController.OrderView>> orders(HttpServletRequest request) {
        Long userId = requireUser(request).getId();
        List<MembershipController.OrderView> views = membership.ordersOf(userId).stream()
                .map(MembershipController.OrderView::of)
                .toList();
        return ApiResponse.ok(views);
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

    /**
     * 浏览器把订单标记为已支付。
     *
     * <p><b>默认关闭，且默认关闭是安全边界而不是临时开关。</b>这个端点没有任何支付凭证校验，
     * 任何登录用户都能把自己的订单（含 pro 档）直接置为 PAID 并立刻拿到对应额度 ——
     * 等于所有付费档免费。订单状态只能由**签名校验过的渠道回调**流转；
     * 在真实回调接入前，这里仅在运维显式打开开关（{@code agent.account.portal-confirm-enabled=true}）
     * 时才可用，用于本地联调。</p>
     */
    @Value("${agent.account.portal-confirm-enabled:false}")
    private boolean portalConfirmEnabled;

    @PostMapping(value = "/api/portal/orders/{orderNo}/confirm",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<MembershipController.OrderView> confirm(
            @PathVariable String orderNo, HttpServletRequest request) {
        Long userId = requireUser(request).getId();
        if (!portalConfirmEnabled) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN,
                    "该入口已关闭：订单状态只能由支付渠道回调确认，浏览器不能自行标记已支付");
        }
        MembershipOrder order = membership.confirmPortalPayment(userId, orderNo);
        return ApiResponse.ok(MembershipController.OrderView.of(order));
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
