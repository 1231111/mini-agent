package com.miniagent.config.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 窗口选择规则：与 WorkBuddy {@code resolveEffectiveContextBudget} 同构。
 *
 * <p>选错窗口的后果不是"少压缩几次"：窗口报大了，压缩阈值高于上游真实上限，
 * 请求会先被 context-length 400 拒绝，而那条 400 常被上层判成"模型连接异常"。</p>
 */
class ContextWindowResolverTest {

    @Test
    void prefersDeclaredDefaultWhenItIsSupported() {
        assertEquals(200_000,
                ContextWindowResolver.resolve(200_000, List.of(128_000, 200_000, 1_000_000), 1_000_000));
    }

    @Test
    void fallsBackToSmallestSupportedWindow() {
        assertEquals(128_000,
                ContextWindowResolver.resolve(0, List.of(200_000, 128_000), 0));
        assertEquals(128_000,
                ContextWindowResolver.resolve(64_000, List.of(128_000, 200_000), 0),
                "默认档不在候选集里时不能直接用，否则按未开通的档位算压缩点");
    }

    @Test
    void singleDeclaredWindowIsAuthoritative() {
        assertEquals(128_000, ContextWindowResolver.resolve(128_000, List.of(), 0),
                "只声明一个窗口时它就是权威值（WorkBuddy 要求候选集 ≥2 项，是因为它的模型目录永远有多档）");
    }

    @Test
    void dropsWindowsAboveVendorHardLimit() {
        assertEquals(200_000,
                ContextWindowResolver.resolve(1_000_000, List.of(200_000, 1_000_000), 200_000),
                "超过 maxInputTokens 的档位不可能生效，必须先过滤");
    }

    @Test
    void fallsBackToMaxInputTokensWhenNoWindowDeclared() {
        assertEquals(64_000, ContextWindowResolver.resolve(0, List.of(), 64_000));
        assertEquals(0, ContextWindowResolver.resolve(0, List.of(), 0),
                "全部未声明返回 0，由循环回退到 agent.context.max-tokens");
    }

    @Test
    void ignoresInvalidEntries() {
        assertEquals(128_000,
                ContextWindowResolver.resolve(0, java.util.Arrays.asList(null, -1, 0, 128_000), 0));
    }

    @Test
    void presetUsesResolverInsteadOfRawField() {
        AgentModelsProperties.Preset preset = new AgentModelsProperties.Preset();
        preset.setContextWindowTokens(1_000_000);
        preset.setSupportedContextWindows(List.of(128_000, 1_000_000));
        preset.setMaxInputTokens(200_000);

        // 1M 超过厂商硬上限 → 被过滤；默认档 1M 不在剩余候选集里 → 取最小档。
        // 这正是「不能直接采信 context-window-tokens」的场景：配了个上游根本不接受的窗口。
        assertEquals(128_000, preset.effectiveContextWindow(),
                "预设必须走同一条解析规则，否则配置里写多档也不会生效");
    }
}
