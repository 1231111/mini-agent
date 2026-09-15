package com.miniagent.agent.tool;

import java.util.Objects;
import java.util.Set;

/** 内置工具的保守执行策略。 */
public final class ToolConcurrencyPolicy {
    private static final Set<String> READ_ONLY = Set.of(
            "skill_list", "skill_view", "read_file", "list_files", "read_package",
            "search_code", "ast_search", "codebase_search", "web_search", "web_extract",
            "http_get", "browser_snapshot", "comfyui_status", "comfyui_workflows",
            "comfyui_models");

    /**
     * 超时后能靠一次只读观察（browser_snapshot）确定真实终态的工具。
     *
     * <p>这类工具作用在活着的页面上，超时不等于"副作用去向不明"：再拍一次快照就知道点没点上。
     * 不列进来的话，{@code AgentLoop.timeoutToolResult} 会判成 OUTCOME_UNKNOWN，
     * 而 OUTCOME_UNKNOWN 会直接中止整个 loop —— 一次点击超时就把整条多步任务打死。
     * browser_evaluate 执行任意 JS（可能发请求），不算可核验，故不在此列。
     */
    private static final Set<String> OUTCOME_VERIFIABLE = Set.of(
            "browser_navigate", "browser_click", "browser_type", "browser_press",
            "browser_scroll", "browser_screenshot", "browser_close");

    private ToolConcurrencyPolicy() {}

    public static ToolSideEffect sideEffectOf(String name) {
        if (isReadOnly(name)) {
            return ToolSideEffect.READ_ONLY;
        }
        if (Set.of("write_file", "edit_file", "todo", "memory", "skill_manage",
                "browser_screenshot").contains(name)) return ToolSideEffect.WRITE;
        return ToolSideEffect.EXTERNAL_WRITE;
    }

    public static boolean isReadOnly(String name) { return name != null && READ_ONLY.contains(name); }
    public static boolean isOutcomeVerifiable(String name) {
        return name != null && OUTCOME_VERIFIABLE.contains(name);
    }
    public static boolean isIdempotent(String name) {
        return isReadOnly(name) || "render_diagram".equals(name);
    }
    public static boolean isStreamPrefetchSafe(String name) { return isReadOnly(name); }

    public static long timeoutSecondsOf(String name) {
        if (Objects.isNull(name)) {
            return 60L;
        }
        return switch (name) {
            case "image_generate" -> 150L;
            case "comfyui_txt2img", "comfyui_img2img" -> 200L;
            case "comfyui_img2video" -> 620L;
            case "comfyui_tts" -> 140L;
            case "browser_extract_text" -> 300L;
            case "read_file", "list_files", "browser_close" -> 10L;
            case "write_file", "edit_file", "browser_snapshot", "browser_evaluate", "read_package" -> 15L;
            case "browser_screenshot" -> 20L;
            // 点击/输入经常等页面响应，10s 必超时并误伤整条任务
            case "browser_click", "browser_type", "browser_press", "browser_scroll",
                    "web_search", "web_extract", "http_get", "http_post", "exec_command",
                    "browser_navigate", "comfyui_execute" -> 30L;
            case "render_diagram" -> 90L;
            default -> 60L;
        };
    }

    public static int maxRetriesOf(String name) {
        return Set.of("web_search", "web_extract", "http_get", "codebase_search", "ast_search")
                .contains(name) ? 1 : 0;
    }

    public static String concurrencyKeyArgumentOf(String name) {
        if (name == null) {
            return "";
        }
        if (Set.of("read_file", "write_file", "edit_file", "list_files", "read_package",
                "search_code", "ast_search", "codebase_search").contains(name)) return "path";
        if (Set.of("web_search").contains(name)) {
            return "query";
        }
        if (Set.of("web_extract", "http_get", "http_post").contains(name)) {
            return "url";
        }
        if ("memory".equals(name)) {
            return "target";
        }
        return "";
    }

    public static ToolConcurrencyScope concurrencyScopeOf(String name) {
        String key = concurrencyKeyArgumentOf(name);
        if (name != null && name.startsWith("browser_")) {
            return key.isEmpty() ? ToolConcurrencyScope.SESSION : ToolConcurrencyScope.ARGUMENT;
        }
        if (Set.of("todo", "delegate_task").contains(name)) {
            return ToolConcurrencyScope.SESSION;
        }
        if (!key.isEmpty()) {
            return ToolConcurrencyScope.ARGUMENT;
        }
        return isReadOnly(name) ? ToolConcurrencyScope.NONE : ToolConcurrencyScope.GLOBAL;
    }
}
