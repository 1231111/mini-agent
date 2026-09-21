package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.ExecCommandParams;
import com.miniagent.agent.tool.impl.SongGenerateParams;

import java.util.Objects;
import java.util.Set;

/** 内置工具的保守执行策略。 */
public final class ToolConcurrencyPolicy {

    /** 需要按命令行内容判定的工具：只有它一条命令的只读性会随参数变化。 */
    public static final String EXEC_TOOL = "exec_command";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> READ_ONLY = Set.of(
            "skill_list", "skill_view", "read_file", "list_files", "read_package",
            "search_code", "ast_search", "codebase_search", "web_search", "web_extract",
            "http_get", "browser_snapshot", "comfyui_status", "comfyui_workflows",
            "comfyui_models", "ask_user_question");

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

    /** 真正等待由 AgentLoop 让出；此外层超时只防止拦截失效时拖死循环。 */
    private static final long ASK_USER_TIMEOUT_SECONDS = 3600L;

    /**
     * 完全不占本地资源、但单次调用要跑几百秒的工具。
     *
     * <p>这类工具必须显式声明无并发约束（{@link ToolConcurrencyScope#NONE}）：
     * 默认的 GLOBAL 会让它长期霸占 {@code ToolExecutionGuards} 那把全局互斥锁，
     * 把别的会话里任何一个写类工具堵到撞自己的闸门 —— 而写类工具超时会被判成
     * {@code OUTCOME_UNKNOWN} 并<b>中止整轮任务</b>。</p>
     *
     * <p>ComfyUI 系列（{@code comfyui_img2video} 620s、{@code comfyui_txt2img} 200s）
     * <b>不在</b>此列：它们要抢本地同一块 GPU，串行是有意为之。</p>
     */
    private static final Set<String> NO_LOCAL_RESOURCE_TOOLS =
            Set.of(SongGenerateParams.TOOL_NAME);

    private ToolConcurrencyPolicy() {}

    // ─── 参数感知的判定入口 ───
    //
    // 只有 exec_command 的语义随参数变化：同一条工具，git status 是只读、format C: 不是。
    // 其余工具的参数感知版本直接退回按名字判定，调用方不必区分。

    /** 把工具名 + 参数合起来判副作用。 */
    public static ToolSideEffect sideEffectOf(String name, String args) {
        if (isExec(name)) {
            return execReadOnly(args) ? ToolSideEffect.READ_ONLY : ToolSideEffect.EXTERNAL_WRITE;
        }
        return sideEffectOf(name);
    }

    /** exec_command 里已经判定为只读的命令，才算只读工具。 */
    public static boolean isReadOnly(String name, String args) {
        if (isExec(name)) {
            return execReadOnly(args);
        }
        return isReadOnly(name);
    }

    /** 只读命令天然幂等：它不改任何东西，重跑一次结果一样。 */
    public static boolean isIdempotent(String name, String args) {
        if (isExec(name)) {
            return execReadOnly(args);
        }
        return isIdempotent(name);
    }

    /**
     * 流式预取仍然对 exec_command 关闭。
     *
     * <p>只读命令虽然安全，但预取会在模型本轮意图还没确定时就启动进程；
     * 判断失误就是一个白跑的子进程（还可能踩到网络）。省下的那点时间不值得这个风险。</p>
     */
    public static boolean isStreamPrefetchSafe(String name, String args) {
        if (isExec(name)) {
            return false;
        }
        return isStreamPrefetchSafe(name);
    }

    /** 只读命令按命令行文本分锁（同一条命令串行，不同命令并行）。 */
    public static String concurrencyKeyArgumentOf(String name, String args) {
        if (isExec(name)) {
            return execReadOnly(args) ? "command" : "";
        }
        return concurrencyKeyArgumentOf(name);
    }

    /** 只读命令用 ARGUMENT 范围，否则维持 GLOBAL（与写类工具共用一把全局锁）。 */
    public static ToolConcurrencyScope concurrencyScopeOf(String name, String args) {
        if (isExec(name)) {
            return execReadOnly(args) ? ToolConcurrencyScope.ARGUMENT : ToolConcurrencyScope.GLOBAL;
        }
        return concurrencyScopeOf(name);
    }

    /**
     * 参数感知的超时：exec_command 按本次声明的 timeout 取闸门值。
     *
     * <p>旧实现在注册期把超时钉成常量，于是「跑 5 分钟的构建」和「跑 30 毫秒的 git status」
     * 拿同一个预算。返回 0 表示「按名字取静态值」。</p>
     */
    public static long timeoutSecondsOf(String name, String args) {
        if (isExec(name)) {
            long adaptive = ExecCommandParams.outerGateSecondsOf(args);
            return adaptive > 0 ? adaptive : timeoutSecondsOf(name);
        }
        return timeoutSecondsOf(name);
    }

    private static boolean isExec(String name) {
        return EXEC_TOOL.equals(name);
    }

    /** 取命令文本并做只读判定；参数残缺时一律按「非只读」处理。 */
    private static boolean execReadOnly(String args) {
        String command = execCommandText(args);
        return !command.isBlank() && CommandReadOnlyJudge.isReadOnly(command);
    }

    private static String execCommandText(String args) {
        if (args == null || args.isBlank()) {
            return "";
        }
        try {
            JsonNode node = JSON.readTree(args);
            JsonNode command = node == null ? null : node.get("command");
            return command == null || command.isNull() ? "" : command.asText("");
        } catch (Exception e) {
            return "";
        }
    }

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
            // 生歌是异步接口（提交后约 3 分钟出结果），闸门必须比工具内部的轮询预算宽：
            // 工具到点会自己返回 PENDING + taskId 交给模型续查，外层先触发则会判成
            // OUTCOME_UNKNOWN 并中止整轮。数字与 SongGenerateParams 同源，避免两处漂移。
            case SongGenerateParams.TOOL_NAME -> SongGenerateParams.outerGateSeconds();
            case "browser_extract_text" -> 300L;
            case "read_file", "list_files", "browser_close" -> 10L;
            case "write_file", "edit_file", "browser_snapshot", "browser_evaluate", "read_package" -> 15L;
            case "browser_screenshot" -> 20L;
            // 点击/输入经常等页面响应，10s 必超时并误伤整条任务
            case "browser_click", "browser_type", "browser_press", "browser_scroll",
                    "web_search", "web_extract", "http_get", "http_post",
                    "browser_navigate", "comfyui_execute" -> 30L;
            // exec_command 的闸门必须比工具自身预算宽（工具自己会强杀进程并返回部分输出，
            // 外层先触发就会变成「终态未知」并中止整轮）。真实值由参数里的 timeout 决定，
            // 见 timeoutSecondsOf(String, String)；这里只是参数残缺时的兜底，与自适应值同源。
            case EXEC_TOOL -> ExecCommandParams.defaultOuterGateSeconds();
            case "render_diagram" -> 90L;
            case "ask_user_question" -> ASK_USER_TIMEOUT_SECONDS;
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
        if (Set.of("todo", "delegate_task", "ask_user_question").contains(name)) {
            return ToolConcurrencyScope.SESSION;
        }
        // 长耗时的云端生成不碰任何本地共享资源：文件名带时间戳，远端任务彼此独立。
        // 但一次调用最长要占几百秒，若按默认落到 GLOBAL，它会长时间霸占 "global" 那把互斥锁
        // （ToolExecutionGuards 的 resourceLocks 每个条纹只有 1 个许可），把别的会话的写类工具
        // 一起堵在锁上 —— 被堵的工具再撞到自己的闸门就是 OUTCOME_UNKNOWN 并中止整轮。
        // 所以这类工具显式声明无并发约束，只受全局并发信号量限制。
        if (name != null && NO_LOCAL_RESOURCE_TOOLS.contains(name)) {
            return ToolConcurrencyScope.NONE;
        }
        if (!key.isEmpty()) {
            return ToolConcurrencyScope.ARGUMENT;
        }
        return isReadOnly(name) ? ToolConcurrencyScope.NONE : ToolConcurrencyScope.GLOBAL;
    }
}
