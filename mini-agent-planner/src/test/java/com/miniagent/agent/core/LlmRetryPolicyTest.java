package com.miniagent.agent.core;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 调用重试判据的回归测试。
 *
 * <p>背景：此前只重试 {@code InternalServerException}(503) 与 {@code IOException}，
 * 其余走 message 关键词匹配。于是 **429 完全不被重试** —— 而 Web GUI 永远走流式，
 * 且 langchain4j 的 {@code OpenAiStreamingChatModel} 没有内置重试包装
 * （阻塞版 {@code OpenAiChatModel} 有 maxRetries=2 + jitter），
 * 所以一次限流就直接结束整轮，用户看到的是"模型连接异常"。</p>
 *
 * <p>判据现在按**类型**：langchain4j 的 {@code RetriableException} 是官方"可重试"标记
 * （{@code RateLimitException}/{@code InternalServerException} 都继承它），
 * 而认证/参数错误必须快速失败 —— 重试它们只会白烧预算。</p>
 */
class LlmRetryPolicyTest {

    @Test
    void rateLimitAndServerErrorsAreRetriable() {
        assertTrue(AgentLoop.isRetriable(new RateLimitException("429 Too Many Requests")),
                "限流必须重试（这是本轮修复的核心）");
        assertTrue(AgentLoop.isRetriable(new InternalServerException("503 Service Unavailable")),
                "上游过载必须重试");
    }

    @Test
    void networkAndTimeoutErrorsAreRetriable() {
        assertTrue(AgentLoop.isRetriable(new IOException("connection reset")));
        assertTrue(AgentLoop.isRetriable(new SocketTimeoutException("read timed out")));
        assertTrue(AgentLoop.isRetriable(new TimeoutException("stream timeout")));
    }

    @Test
    void nestedCauseIsInspected() {
        // 流式断连常被包装成通用异常，根因藏在 cause 链里
        Exception wrapped = new RuntimeException("stream failed",
                new IOException("Connection reset by peer"));
        assertTrue(AgentLoop.isRetriable(wrapped), "必须沿 cause 链判断根因");
    }

    @Test
    void authAndInvalidRequestAreNotRetriable() {
        assertFalse(AgentLoop.isRetriable(new AuthenticationException("401 invalid api key")),
                "认证失败重试多少次都一样，且会白烧 token");
        assertFalse(AgentLoop.isRetriable(new InvalidRequestException("400 bad request")),
                "参数错误不可重试");
    }

    @Test
    void plainDomainErrorIsNotRetriable() {
        assertFalse(AgentLoop.isRetriable(new IllegalStateException("用户取消")),
                "业务异常不该被当成可重试的网络问题");
    }

    @Test
    void backoffIsBoundedAndJittered() {
        long previousMin = -1;
        for (int attempt = 0; attempt < 6; attempt++) {
            long base = Math.min(8000L, 500L << Math.min(attempt, 4));
            for (int i = 0; i < 40; i++) {
                long backoff = AgentLoop.retryBackoffMillis(attempt);
                assertTrue(backoff >= base / 2 && backoff <= base,
                        "退避要落在 [base/2, base] 区间（含全抖动）: " + backoff + " base=" + base);
            }
            assertTrue(base >= previousMin || previousMin < 0, "退避上限随时间不下降");
            previousMin = base;
        }
        // 抖动确实存在：同一 attempt 的多次取值不应完全相同
        boolean sawDifferent = false;
        long first = AgentLoop.retryBackoffMillis(2);
        for (int i = 0; i < 50 && !sawDifferent; i++) {
            sawDifferent = AgentLoop.retryBackoffMillis(2) != first;
        }
        assertTrue(sawDifferent, "必须有抖动，否则多副本会在同一时刻齐步重试");
    }
}
