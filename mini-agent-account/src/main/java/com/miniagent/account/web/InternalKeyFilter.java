package com.miniagent.account.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 会员接口的服务间鉴权。
 *
 * <p>只拦 {@code /api/membership/**}，登录与注册不经过它 —— 那两个是按设计公开的，
 * 而且客户机的注册流程要在"用户还没有任何凭证"时就能调通。
 *
 * <h3>为什么是服务间密钥，而不是用户会话</h3>
 *
 * <p>账号服务不签发 token（见 {@code AccountApplication}），所以它没有"当前登录用户"
 * 这个概念，也就没法自己做用户级鉴权。于是分工是：调用方先验掉用户自己的 token、
 * 拿到 userId，再带这个密钥转发过来。{@code userId} 是<b>调用方担保的</b>。
 *
 * <p>这意味着一件事要在排查时先想到：如果能查到别人的会员，问题在调用方的鉴权，
 * 不在这个过滤器 —— 它只保证"请求来自可信服务"，不保证"这个 userId 就是你"。
 */
@Component
public class InternalKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalKeyFilter.class);

    private static final String PROTECTED_PREFIX = "/api/membership/";
    private static final String HEADER = "X-Account-Internal-Key";

    @Value("${agent.account.internal-key:}")
    private String configuredKey;

    @Autowired
    private ObjectMapper objectMapper;

    private byte[] expected;

    @PostConstruct
    void init() {
        expected = configuredKey == null
                ? new byte[0]
                : configuredKey.trim().getBytes(StandardCharsets.UTF_8);
        if (expected.length == 0) {
            log.warn("未配置 agent.account.internal-key（env ACCOUNT_INTERNAL_KEY）—— "
                    + "会员接口全部返回 503。登录与注册不受影响。");
        } else {
            log.info("会员接口已启用：需要 {} 头", HEADER);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 预检请求不带自定义头，拦下来会让浏览器侧永远看不到真正的错误原因。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        if (!stripContextPath(request).startsWith(PROTECTED_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        if (expected.length == 0) {
            // 未配置时选择"拒绝"而不是"放行"。放行等于说：一个忘了配密钥的部署
            // 会把所有人的会员与订单数据，暴露给任何能访问到这个端口的人 ——
            // 而且没有任何迹象表明出了事。
            writeJson(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ErrorCode.MEMBER_INTERNAL_KEY_MISSING,
                    "会员接口未启用：未配置 agent.account.internal-key");
            return;
        }

        if (!constantTimeEquals(expected, request.getHeader(HEADER))) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                    ErrorCode.AUTH_FORBIDDEN,
                    "缺少或错误的 " + HEADER);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 常量时间比较。
     *
     * <p>用 {@code String.equals} 会在第一个不同的字节上就返回，
     * 攻击者能靠响应时间一个字节一个字节地把密钥猜出来。
     * {@link MessageDigest#isEqual} 对长度不同的输入也会走完整比较路径。
     */
    private static boolean constantTimeEquals(byte[] expected, String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided.trim().getBytes(StandardCharsets.UTF_8));
    }

    private void writeJson(HttpServletResponse response, int status, ErrorCode code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        // 用 ObjectMapper 而不是手拼字符串：detail 现在是写死的常量，
        // 但手拼的写法一旦被后人传进来外部输入（比如把路径片段带进来），
        // 就是一个 JSON 注入点，而那种改动看起来完全无害。
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(code, detail)));
    }

    private static String stripContextPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            return path.substring(ctx.length());
        }
        return path;
    }
}
