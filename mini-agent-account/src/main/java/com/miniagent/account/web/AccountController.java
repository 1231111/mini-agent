package com.miniagent.account.web;

import com.miniagent.account.entity.User;
import com.miniagent.account.repository.UserRepository;
import com.miniagent.account.service.AccountAuthService;
import com.miniagent.account.service.DesktopLoginTicketStore;
import com.miniagent.account.web.dto.AccountUserResponse;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号端点。
 *
 * <p>路径与云端 agent 的 {@code MiniAgentChatPageController} 里的同名端点完全一致
 * （{@code POST /api/tokens}、{@code POST /api/users}）。
 * 这是刻意的：调用方（客户机本地后端的 {@code CloudAccountClient}）只换 base-url，
 * 不改路径 —— 拆分服务不该变成一次协议变更。
 *
 * <p>响应形状也与那边一致（{@code ApiResponse} 包一层，{@code data} 里是身份）。
 * 差别只有一处：这里没有 token —— 账号服务不签发会话，见 {@link AccountUserResponse}。
 */
@RestController
public class AccountController {

    @Autowired
    private AccountAuthService auth;
    @Autowired
    private DesktopLoginTicketStore tickets;
    @Autowired
    private UserRepository users;

    public record LoginRequest(String username, String password) {
    }

    public record TicketRequest(String ticket) {
    }

    public record RegisterRequest(String username, String password, String displayName) {
    }

    /**
     * 登录校验。
     *
     * <p>失败一律返回 {@code AUTH_LOGIN_FAILED}，不区分"用户不存在 / 密码错 / 账号被禁用"。
     * 分开返回等于提供一个用户名枚举接口。真实原因记在服务端日志里 ——
     * 那是运维该看的地方，不是响应体。
     */
    @PostMapping(value = "/api/tokens", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<AccountUserResponse> login(@RequestBody(required = false) LoginRequest req) {
        if (req == null) {
            return ApiResponse.fail(ErrorCode.AUTH_LOGIN_FAILED);
        }
        return auth.login(req.username(), req.password())
                .map(user -> ApiResponse.ok(AccountUserResponse.of(user)))
                .orElse(ApiResponse.fail(ErrorCode.AUTH_LOGIN_FAILED));
    }

    /**
     * 注册。按真实原因返回错误码，不把所有失败都说成"用户已存在" ——
     * 密码太短和重名对用户来说是完全不同的两件事。
     */
    @PostMapping(value = "/api/users", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<AccountUserResponse> register(@RequestBody(required = false) RegisterRequest req) {
        if (req == null) {
            return ApiResponse.fail(ErrorCode.AUTH_USERNAME_INVALID, "用户名不能为空");
        }
        AccountAuthService.RegisterResult result =
                auth.register(req.username(), req.password(), req.displayName());
        if (!result.success()) {
            return ApiResponse.fail(result.error(), result.detail());
        }
        User user = result.user();
        return ApiResponse.ok(AccountUserResponse.of(user));
    }

    /**
     * 用网页注册换来的一次性凭证取回身份。凭证无效、过期或已用过都当登录失败。
     */
    @PostMapping(value = "/api/desktop-tickets/redeem",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<AccountUserResponse> redeem(
            @RequestBody(required = false) TicketRequest req) {
        String ticket = req == null ? null : req.ticket();
        Long userId = tickets.consume(ticket).orElse(null);
        if (userId == null) {
            return ApiResponse.fail(ErrorCode.AUTH_SESSION_INVALID, "登录凭证无效或已使用");
        }
        return users.findById(userId)
                .map(user -> ApiResponse.ok(AccountUserResponse.of(user)))
                .orElseGet(() -> ApiResponse.fail(
                        ErrorCode.AUTH_NOT_AUTHENTICATED, "请重新注册"));
    }
}
