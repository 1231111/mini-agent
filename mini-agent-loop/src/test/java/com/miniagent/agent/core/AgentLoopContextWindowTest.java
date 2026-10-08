package com.miniagent.agent.core;

import com.miniagent.common.model.EffectiveModelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作窗口必须跟着**模型**走，而不是一个全局常量。
 *
 * <p>背景：{@code agent.context.max-tokens:512000} 是唯一的工作窗口，而模型是每轮按用户预设解析的
 * （预设里就有 gpt-4o-mini 这类 128k 模型）。于是选小窗口模型时压缩阈值永远不触发，
 * 请求被上游以 context-length 400 拒绝 —— 用户看到的是"模型连接异常"。</p>
 *
 * <p>同时，压缩阈值此前只统计消息，不统计每轮都要发的工具 schema（45+ 个工具），
 * 真实 prompt 比估算值大一截，压缩总是偏晚。</p>
 */
class AgentLoopContextWindowTest {

    private final AgentLoop loop = new AgentLoop();

    @AfterEach
    void tearDown() {
        EffectiveModelContext.clear();
    }

    private int effectiveWindow(int configured, int modelWindow) {
        ReflectionTestUtils.setField(loop, "maxContextTokens", configured);
        EffectiveModelContext.set(null, null, modelWindow);
        return (Integer) ReflectionTestUtils.invokeMethod(loop, "effectiveContextTokens");
    }

    @Test
    void modelWindowWinsWhenSmallerThanConfigured() {
        assertEquals(128000, effectiveWindow(512000, 128000),
                "128k 的模型不能按 512k 估算 —— 那会让压缩永不触发，请求被上游拒绝");
    }

    @Test
    void configuredCapWinsWhenSmallerThanModelWindow() {
        assertEquals(64000, effectiveWindow(64000, 200000),
                "运维显式配的上限同样要生效（可能出于成本考虑）");
    }

    @Test
    void unknownModelWindowFallsBackToConfigured() {
        assertEquals(512000, effectiveWindow(512000, 0),
                "厂商窗口未知时保持旧行为，不能凭空缩小窗口");
        assertEquals(512000, effectiveWindow(0, 0),
                "配置也为 0 时用默认兜底");
    }

    @Test
    void toolSchemaOverheadIsCounted() {
        assertEquals(0, AgentLoop.toolSchemaTokenEstimate(0),
                "没有工具就没有 schema 开销");
        assertTrue(AgentLoop.toolSchemaTokenEstimate(47) > 5000,
                "45+ 个工具的 schema 是实打实的 prompt 开销，不能按 0 计: "
                        + AgentLoop.toolSchemaTokenEstimate(47));
        assertTrue(AgentLoop.toolSchemaTokenEstimate(47)
                        > AgentLoop.toolSchemaTokenEstimate(10),
                "开销要随工具数单调增长");
    }

    @Test
    void windowIsScopedAndRestoredAcrossBinding() {
        EffectiveModelContext.set(null, null, 128000);
        assertEquals(128000, EffectiveModelContext.currentContextWindow());

        try (var ignored = EffectiveModelContext.bind(null, null, 32000)) {
            assertEquals(32000, EffectiveModelContext.currentContextWindow(),
                    "子作用域要能装自己的窗口（子代理/并行工具）");
        }
        assertEquals(128000, EffectiveModelContext.currentContextWindow(),
                "退出作用域必须精确恢复外层窗口");

        EffectiveModelContext.clear();
        assertEquals(0, EffectiveModelContext.currentContextWindow(),
                "clear 之后必须回到「未知」，否则会跨请求串窗口");
    }
}
