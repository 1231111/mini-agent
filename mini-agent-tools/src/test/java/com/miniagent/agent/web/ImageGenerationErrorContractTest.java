package com.miniagent.agent.web;

import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「产错方声明错误码 → 解析方直接采信」这条跨类契约。
 *
 * <p>为什么值得单独测：这两端分属不同模块、不同人改。产错方少写一个 {@code errorCode}，
 * 解析方就会退回拿文案刮关键字 —— 不报错、不告警，只是让上游 {@code RecoveryEngine}
 * 悄悄选错恢复档。2026-10-15 的实测症状就是「429 被降级成执行失败」。</p>
 */
class ImageGenerationErrorContractTest {

    @Test
    void 声明的错误码被解析方采信() {
        String raw = ImageGenerationService.errorJson(
                ToolErrorCode.DEPENDENCY_UNAVAILABLE, "所有后端均失败: siliconflow：HTTP 503");

        ToolResult r = ToolResult.fromLegacy(raw);
        assertEquals(ToolStatus.FAILED, r.status());
        assertEquals(ToolErrorCode.DEPENDENCY_UNAVAILABLE, r.errorCode(),
                "带 errorCode 时不该再去猜文案");
        assertTrue(r.retriable(), "依赖不可用属于可重试，否则模型会被误导成放弃");
    }

    @Test
    void 参数错的错误码是INVALID_ARGUMENT且不可重试() {
        String raw = ImageGenerationService.errorJson(ToolErrorCode.INVALID_ARGUMENT, "prompt 不能为空");

        ToolResult r = ToolResult.fromLegacy(raw);
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, r.errorCode());
        assertTrue(!r.retriable(), "参数错重试同一份参数没有意义");
    }

    /** 不带 errorCode 的老式调用仍要能走通，且失败原因本身不能丢。 */
    @Test
    void 单参重载仍产出可解析的失败结果() {
        String raw = ImageGenerationService.errorJson("后端 x 失败");

        ToolResult r = ToolResult.fromLegacy(raw);
        assertEquals(ToolStatus.FAILED, r.status());
        assertTrue(r.legacyText().contains("后端 x 失败"), "原因文本必须保留");
    }
}
