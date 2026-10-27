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

    /**
     * 后端全挂时，错误文案必须指路降级工具。
     *
     * <p>{@code AgentLoop} 判到 {@code DEPENDENCY_UNAVAILABLE} 会把 image_generate 从下一轮
     * 工具表里移除。模型只看到「工具没了」，不知道换哪个，就会退化成写脚本画图或用 ASCII 凑数。
     * 这条文案是降级链路最后一道保险 —— 系统提示可能被裁剪，工具结果一定在上下文里。</p>
     */
    @Test
    void 后端不可用时错误文案指向降级工具() {
        // 不注入任何后端 key：fallback 链为空，直接走「无可用后端」的失败分支
        ImageGenerationService service = new ImageGenerationService();

        ToolResult r = ToolResult.fromLegacy(service.generate("a flowchart", "landscape"));

        assertEquals(ToolStatus.FAILED, r.status());
        assertEquals(ToolErrorCode.DEPENDENCY_UNAVAILABLE, r.errorCode());
        assertTrue(r.message().contains("render_diagram"), "要指名兜底工具：" + r.message());
        assertTrue(r.message().contains("插画"), "要说明插画类降不了级：" + r.message());
    }
}
