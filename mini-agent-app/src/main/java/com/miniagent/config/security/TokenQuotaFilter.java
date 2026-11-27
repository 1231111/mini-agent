package com.miniagent.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.config.cloud.TokenEntitlementGate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 在「发起对话」这一个入口上拦额度。
 *
 * <h2>为什么放在 HTTP 层而不是业务层</h2>
 *
 * <p>业务层散点检查漏一个入口就等于没有：只要有一条路径能发起模型调用，额度就没拦住。
 * 这里按 URI 收口，只有两个入口需要覆盖 —— 同步消息与流式消息。</p>
 *
 * <h2>为什么不中断正在跑的流</h2>
 *
 * <p>额度检查只作用于「新请求」。已经建立连接的 SSE 流不会经过这里，所以
 * {@code AgentLoop} 正在跑的任务不受影响；用户看到的是「下一个对话发不出去」，
 * 而不是「跑到一半被打断」。中断正在执行的任务会造成半截产物，
 * 比让当前任务跑完更糟。</p>
 *
 * <h2>为什么不返回 SSE 格式</h2>
 *
 * <p>前端在 {@code !resp.ok} 时不解析流，而是读 body 里的 {@code message} 直接显示
 * （{@code chatHttpErrorMessage}）。所以返回普通 JSON 即可，不需要模拟事件流。</p>
 *
 * <p>这个过滤器必须挂在 {@link SignedSessionFilter} 之后：它依赖认证阶段写入的
 * {@code ATTR_USER_ID}，在认证之前读不到用户。</p>
 */
@Component
public class TokenQuotaFilter extends OncePerRequestFilter {

    /** 只有这两个端点会触发模型调用；新建会话、上传文件、读历史都不拦。 */
    private static final Pattern GATED = Pattern.compile(
            "^/api/conversations/[^/]+/(?:messages(?:/stream)?|todos/[0-9]+/continue)$");

    private final TokenEntitlementGate gate;
    private final ObjectMapper objectMapper;

    public TokenQuotaFilter(TokenEntitlementGate gate, ObjectMapper objectMapper) {
        this.gate = gate;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getServletPath();
        return path == null || !GATED.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Object attribute = request.getAttribute(JwtSessionService.ATTR_USER_ID);
        if (!(attribute instanceof Long userId)) {
            // 认证过滤器没有放用户：交给后续链处理，这里不判断身份有效性。
            chain.doFilter(request, response);
            return;
        }

        String reason = gate.rejectionReason(userId);
        if (reason == null) {
            chain.doFilter(request, response);
            return;
        }

        // 429 而不是 403：这不是「你没有权限」，而是「今天用完了，明天再来」。
        // Retry-After 让客户端知道什么时候该恢复，而不是靠猜。
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", String.valueOf(secondsUntilMidnight()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", reason);
        body.put("code", "TOKEN_QUOTA_EXCEEDED");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private static long secondsUntilMidnight() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return java.time.Duration.between(now, midnight).getSeconds();
    }
}
