package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * exec_command 的超时契约。
 *
 * <p>内外两层预算的关系是这里唯一要紧的事：内层（工具自己 waitFor）必须<b>窄于</b>
 * 外层（AgentLoop 的 future.get）。外层先触发会被判成「终态未知」并中止整轮，
 * 而内层触发能给出可控终态（强杀进程 + 部分输出）。
 * 改造前内外都是 30s，外层几乎总是先到，于是内层那套处理从来没生效过。</p>
 */
class ExecCommandParamsTest {

    private static ExecCommandParams params(String json) {
        return ToolParams.fromJson(json, ExecCommandParams.class);
    }

    @Test
    void defaultTimeoutIsUsedWhenNotDeclared() {
        assertEquals(ExecCommandParams.DEFAULT_TIMEOUT_SECONDS, params("{\"command\":\"ls\"}").requestedTimeoutSeconds());
        assertEquals(ExecCommandParams.DEFAULT_TIMEOUT_SECONDS, params("{\"command\":\"ls\",\"timeout\":null}").requestedTimeoutSeconds());
    }

    @Test
    void declaredTimeoutIsHonoredAndClamped() {
        assertEquals(300, params("{\"command\":\"mvnw package\",\"timeout\":300}").requestedTimeoutSeconds());
        assertEquals(ExecCommandParams.MAX_TIMEOUT_SECONDS,
                params("{\"command\":\"mvnw package\",\"timeout\":5000}").requestedTimeoutSeconds(),
                "超过上限要夹住，不能让调用方声明一个无限预算");
        assertEquals(1, params("{\"command\":\"ls\",\"timeout\":0}").requestedTimeoutSeconds());
        assertEquals(1, params("{\"command\":\"ls\",\"timeout\":-5}").requestedTimeoutSeconds());
    }

    @Test
    void outerGateIsAlwaysWiderThanTheToolBudget() {
        int tool = params("{\"command\":\"mvnw package\",\"timeout\":300}").requestedTimeoutSeconds();
        int gate = params("{\"command\":\"mvnw package\",\"timeout\":300}").outerGateSeconds();

        assertTrue(gate > tool, "外层必须先于内层之外，否则内层强杀逻辑是死代码");
        assertEquals(tool + 15, gate);
    }

    @Test
    void outerGateCanBeComputedFromRawArguments() {
        // 注册期把它挂成自适应函数，超时才能随调用走而不是被钉成常量
        assertEquals(315L, ExecCommandParams.outerGateSecondsOf("{\"command\":\"mvnw package\",\"timeout\":300}"));
        assertEquals(ExecCommandParams.defaultOuterGateSeconds(),
                ExecCommandParams.outerGateSecondsOf("{\"command\":\"ls\"}"));
        assertEquals(ExecCommandParams.defaultOuterGateSeconds(), ExecCommandParams.outerGateSecondsOf(null));
        assertEquals(ExecCommandParams.defaultOuterGateSeconds(), ExecCommandParams.outerGateSecondsOf("  "));
    }

    @Test
    void unparsableArgumentsFallBackToZeroSoTheCallerCanUseTheStaticValue() {
        // 返回 0 表示「求不出来」，由 ToolConcurrencyPolicy 回退到注册期常量；不能在这里抛异常
        assertEquals(0L, ExecCommandParams.outerGateSecondsOf("{not json"));
    }

    @Test
    void defaultOuterGateMatchesTheAdaptiveValueForTheDefaultTimeout() {
        assertEquals(ExecCommandParams.outerGateSecondsOf("{\"command\":\"ls\"}"),
                ExecCommandParams.defaultOuterGateSeconds(),
                "静态兜底与自适应值必须同源，否则参数残缺时闸门会悄悄变窄");
    }

    @Test
    void displayTextPrefersTheHumanDescription() {
        assertEquals("查看工作区状态（git status）",
                ExecCommandParams.displayText("{\"command\":\"git status\",\"description\":\"查看工作区状态\"}"));
        assertEquals("git status", ExecCommandParams.displayText("{\"command\":\"git status\"}"));
        assertEquals("执行命令", ExecCommandParams.displayText(null));
    }

    @Test
    void displayTextIsSingleLineAndBounded() {
        String long1 = "x".repeat(200);
        String text = ExecCommandParams.displayText("{\"command\":\"" + long1 + "\"}");

        assertTrue(text.length() < 100, "审批卡片上不能塞一整行超长命令");
        assertTrue(text.endsWith("…"));
    }

    @Test
    void baseCommandIgnoresPipelinesAndWrappers() {
        assertEquals("cd", params("{\"command\":\"cd /d repo && git status\"}").baseCommand());
        assertEquals("git", params("{\"command\":\"timeout 30 git status\"}").baseCommand());
    }

    @Test
    void schemaExposesCommandTimeoutAndDescription() {
        // schema 是平铺的「字段名 → 定义」，模型靠它决定要不要传 timeout
        java.util.Map<String, Object> schema = ToolParams.generateSchema(ExecCommandParams.class);

        assertTrue(schema.containsKey("command"));
        assertTrue(schema.containsKey("timeout"));
        assertTrue(schema.containsKey("description"));
        assertEquals("integer", ((java.util.Map<?, ?>) schema.get("timeout")).get("type"));
    }

    @Test
    void missingCommandIsAllowedAtParseTimeAndRejectedByTheTool() {
        // 必填校验在工具入口做（返回可读的 error JSON），解析期不该抛
        assertNull(params("{\"timeout\":10}").getCommand());
        // 完全不是 JSON 才抛
        assertThrows(Exception.class, () -> ToolParams.fromJson("", ExecCommandParams.class));
    }
}
