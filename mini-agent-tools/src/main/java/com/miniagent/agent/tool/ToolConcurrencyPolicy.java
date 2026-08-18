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

    private ToolConcurrencyPolicy() {}

    public static ToolSideEffect sideEffectOf(String name) {
        if (isReadOnly(name)) return ToolSideEffect.READ_ONLY;
        if (Set.of("write_file", "edit_file", "todo", "memory", "skill_manage",
                "browser_screenshot").contains(name)) return ToolSideEffect.WRITE;
        return ToolSideEffect.EXTERNAL_WRITE;
    }

    public static boolean isReadOnly(String name) { return name != null && READ_ONLY.contains(name); }
    public static boolean isIdempotent(String name) { return isReadOnly(name); }
    public static boolean isStreamPrefetchSafe(String name) { return isReadOnly(name); }

    public static long timeoutSecondsOf(String name) {
        if (Objects.isNull(name)) return 60L;
        return switch (name) {
            case "image_generate" -> 150L;
            case "comfyui_txt2img", "comfyui_img2img" -> 200L;
            case "comfyui_img2video" -> 620L;
            case "comfyui_tts" -> 140L;
            case "browser_extract_text" -> 300L;
            case "read_file", "list_files", "browser_click", "browser_type", "browser_press",
                    "browser_scroll", "browser_close" -> 10L;
            case "write_file", "edit_file", "browser_snapshot", "browser_evaluate", "read_package" -> 15L;
            case "browser_screenshot" -> 20L;
            case "web_search", "web_extract", "http_get", "http_post", "exec_command",
                    "browser_navigate", "comfyui_execute" -> 30L;
            default -> 60L;
        };
    }

    public static int maxRetriesOf(String name) {
        return Set.of("web_search", "web_extract", "http_get", "codebase_search", "ast_search")
                .contains(name) ? 1 : 0;
    }

    public static String concurrencyKeyArgumentOf(String name) {
        if (name == null) return "";
        if (Set.of("read_file", "write_file", "edit_file", "list_files", "read_package",
                "search_code", "ast_search", "codebase_search").contains(name)) return "path";
        if (Set.of("web_search").contains(name)) return "query";
        if (Set.of("web_extract", "http_get", "http_post").contains(name)) return "url";
        if ("memory".equals(name)) return "target";
        return "";
    }

    public static ToolConcurrencyScope concurrencyScopeOf(String name) {
        String key = concurrencyKeyArgumentOf(name);
        if (name != null && name.startsWith("browser_")) {
            return key.isEmpty() ? ToolConcurrencyScope.SESSION : ToolConcurrencyScope.ARGUMENT;
        }
        if (Set.of("todo", "delegate_task").contains(name)) return ToolConcurrencyScope.SESSION;
        if (!key.isEmpty()) return ToolConcurrencyScope.ARGUMENT;
        return isReadOnly(name) ? ToolConcurrencyScope.NONE : ToolConcurrencyScope.GLOBAL;
    }
}
