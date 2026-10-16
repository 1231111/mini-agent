package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `BuiltinTools.errorJson` 的产出一路走到 `ToolResult.fromLegacy` 的结果。
 *
 * <p>为什么这条契约值得钉住：工具失败一旦写成**裸文本**，
 * `fromLegacy` 会一路走到最后一行 `return success(rawText)` —— **「找不到」被判成成功**，
 * 失败闸门（连续失败计数、恢复引擎）全都不触发，而且不报错、不告警。
 * `skill_view` 的 {@code "Skill not found: …"} 就这样活了很久（2026-10-15 实测）。</p>
 */
class BuiltinToolsErrorJsonTest {

    @Test
    void 错误结果被判为失败且带得出错误码() {
        String raw = BuiltinTools.errorJson(ToolErrorCode.NOT_FOUND, "Skill not found: foo");

        ToolResult r = ToolResult.fromLegacy(raw);
        assertEquals(ToolStatus.FAILED, r.status(), "带 error 键的结果必须判失败");
        assertEquals(ToolErrorCode.NOT_FOUND, r.errorCode());
        assertTrue(r.legacyText().contains("Skill not found"), "原因文本要保留");
    }

    @Test
    void 安全拒绝判为不可重试() {
        ToolResult r = ToolResult.fromLegacy(BuiltinTools.errorJson(
                ToolErrorCode.PERMISSION_DENIED, "命令被安全策略拒绝：检测到危险操作模式。"));

        assertEquals(ToolErrorCode.PERMISSION_DENIED, r.errorCode());
        assertTrue(!r.retriable(),
                "被安全策略拒绝的命令重试同一份参数没有意义，应走换方案而不是重试");
    }

    /** 路径/命令输出里的引号不能破坏 JSON —— 旧写法直接拼接，这条会产出坏 JSON。 */
    @Test
    void 消息里的引号被转义不破坏JSON() {
        String raw = BuiltinTools.errorJson(ToolErrorCode.NOT_FOUND, "路径不存在: C:\\a\"b.txt");

        ToolResult r = ToolResult.fromLegacy(raw);
        assertEquals(ToolStatus.FAILED, r.status(), "JSON 被引号破坏时会降级成纯文本，状态就判错");
        assertTrue(r.legacyText().contains("b.txt"), "路径内容要完整保留");
    }
}
