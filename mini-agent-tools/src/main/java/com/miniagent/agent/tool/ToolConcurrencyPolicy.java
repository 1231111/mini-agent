package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.ExecCommandParams;
import com.miniagent.agent.tool.impl.SongGenerateParams;

import java.util.Set;

/**
 * 工具执行契约的<b>唯一声明点</b>。
 *
 * <h2>改造前的结构问题</h2>
 *
 * <p>「跑多久、抢什么资源、超时了怎么办」这四件事散在四处，加一个长耗时工具要同时改四处：</p>
 * <ul>
 *   <li>内层预算 → 各工具参数类（{@code ExecCommandParams.DEFAULT_TIMEOUT_SECONDS} 等）</li>
 *   <li>外层闸门 → {@code timeoutSecondsOf} 的 switch（一堆魔法数）</li>
 *   <li>并发范围 → {@code concurrencyScopeOf} 的 Set</li>
 *   <li>超时处置 → {@code AgentLoop.timeoutToolResult} 的 if-else</li>
 * </ul>
 *
 * <p>漏掉超时那一处，工具就在 60s 默认闸门被砍。现在四件事收敛成一个
 * {@link ToolExecutionProfile}，{@link #profileOf(String, String)} 是唯一声明点，
 * 注册期（{@code ToolRegistry}）、闸门（{@code ToolExecutionGuards}）、
 * 超时恢复（{@code AgentLoop.timeoutToolResult}）三处都从这里取。</p>
 *
 * <h2>为什么有些档位余量是 0</h2>
 *
 * <p>{@code outerGateMarginSeconds == 0} 表示「工具自己不做超时强杀，外层闸门就是唯一防线」。
 * 只有<b>确实有内层强杀</b>的工具（{@code exec_command} 杀进程、{@code song_generate} 停轮询、
 * {@code image_generate} 到总预算强杀返回）才留正余量：外层一旦先触发，超时会被判成
 * 「终态未知」并中止整轮，而工具自己处理超时是可控终态 —— 所以外层必须比内层宽，
 * 否则内部那套精细处理永远是死代码。</p>
 */
public final class ToolConcurrencyPolicy {

    /** 需要按命令行内容判定的工具：只有它一条命令的只读性会随参数变化。 */
    public static final String EXEC_TOOL = "exec_command";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> READ_ONLY = Set.of(
            "skill_list", "skill_view", "read_file", "list_files", "read_package",
            "search_code", "ast_search", "codebase_search", "web_search", "web_extract",
            "http_get", "browser_snapshot", "comfyui_status", "comfyui_workflows",
            "comfyui_models", "ask_user_question");

    /** 写进本地状态但能靠一次只读观察核验终态的工具，超时后共用这句提示。 */
    private static final String VERIFY_LOCAL_HINT =
            "，操作可能已部分生效；先用只读工具核验目标状态，再决定重试还是修复";

    /** 浏览器工具超时后共用这句提示：页面还活着，拍一次快照就知道点没点上。 */
    private static final String VERIFY_BROWSER_HINT =
            "，页面终态未知；先 browser_snapshot 核验当前页面，再决定重试还是换策略";

    // ════════════════════════════════════════════════════════════════════
    //  执行契约档位：同族工具复用同一档，预算特例用 withBudget 派生
    //
    //  字段顺序（见 ToolExecutionProfile）：
    //    内层预算, 外层余量, 锁等待, 并发范围, 分锁参数键, 共享资源名, 超时处置, 恢复提示, 自动重试
    // ════════════════════════════════════════════════════════════════════

    /** 只读且不抢任何共享资源：超时可直接重试。 */
    private static final ToolExecutionProfile READ_FREE = new ToolExecutionProfile(
            60, 0, 3, ToolConcurrencyScope.NONE, "", "",
            ToolTimeoutRecovery.RETRY, "，可安全重试", 0);

    /** 只读、按目标路径分锁（同一条路径串行，不同路径并行）。 */
    private static final ToolExecutionProfile READ_BY_PATH = new ToolExecutionProfile(
            60, 0, 3, ToolConcurrencyScope.ARGUMENT, "path", "",
            ToolTimeoutRecovery.RETRY, "，可安全重试", 0);

    /** 同上，但允许框架自动重试一次（网络抖动）。要求工具幂等。 */
    private static final ToolExecutionProfile READ_BY_PATH_RETRYABLE = new ToolExecutionProfile(
            60, 0, 3, ToolConcurrencyScope.ARGUMENT, "path", "",
            ToolTimeoutRecovery.RETRY, "，可安全重试", 1);

    /** 只读、按查询串分锁，可自动重试一次（网络抖动）。 */
    private static final ToolExecutionProfile READ_BY_QUERY = new ToolExecutionProfile(
            30, 0, 3, ToolConcurrencyScope.ARGUMENT, "query", "",
            ToolTimeoutRecovery.RETRY, "，可安全重试", 1);

    /** 只读、按 URL 分锁，可自动重试一次（网络抖动）。 */
    private static final ToolExecutionProfile READ_BY_URL = new ToolExecutionProfile(
            30, 0, 3, ToolConcurrencyScope.ARGUMENT, "url", "",
            ToolTimeoutRecovery.RETRY, "，可安全重试", 1);

    /** 远程抓取：无本地副作用，不需要锁，超时可直接重试。 */
    private static final ToolExecutionProfile REMOTE_FETCH = new ToolExecutionProfile(
            60, 0, 3, ToolConcurrencyScope.NONE, "", "",
            ToolTimeoutRecovery.RETRY, "，可安全重试", 0);

    /** 写本地文件、按目标路径分锁。超时读一次就知道写没写上。 */
    private static final ToolExecutionProfile WRITE_BY_PATH = new ToolExecutionProfile(
            15, 0, 10, ToolConcurrencyScope.ARGUMENT, "path", "",
            ToolTimeoutRecovery.VERIFY, VERIFY_LOCAL_HINT, 0);

    /** 写本地状态、按目标名分锁（{@code memory} 的 target 是记忆名，不是文件路径）。 */
    private static final ToolExecutionProfile WRITE_BY_TARGET = new ToolExecutionProfile(
            60, 0, 10, ToolConcurrencyScope.ARGUMENT, "target", "",
            ToolTimeoutRecovery.VERIFY, VERIFY_LOCAL_HINT, 0);

    /** 发起远程写、按 URL 分锁。超时后先查目标状态再决定重试还是补偿。 */
    private static final ToolExecutionProfile WRITE_BY_URL = new ToolExecutionProfile(
            30, 0, 10, ToolConcurrencyScope.ARGUMENT, "url", "",
            ToolTimeoutRecovery.VERIFY,
            "，请求可能已达远端；先用 http_get 查一次目标状态，再决定重试还是补偿", 0);

    /** 写本地状态、无分锁键（delete_path / move_file 有多个路径参数，无法单键分锁）。 */
    private static final ToolExecutionProfile WRITE_GLOBAL = new ToolExecutionProfile(
            60, 0, 10, ToolConcurrencyScope.GLOBAL, "", "",
            ToolTimeoutRecovery.VERIFY, VERIFY_LOCAL_HINT, 0);

    /** 幂等的本地写（重跑一次结果一样），故超时可直接重试而不必先核验。 */
    private static final ToolExecutionProfile WRITE_GLOBAL_IDEMPOTENT = new ToolExecutionProfile(
            90, 0, 10, ToolConcurrencyScope.GLOBAL, "", "",
            ToolTimeoutRecovery.RETRY, "，可安全重试", 0);

    /** 给用户发消息：会话内互斥，超时先查会话记录确认发没发出去。 */
    private static final ToolExecutionProfile NOTIFY_USER = new ToolExecutionProfile(
            60, 0, 10, ToolConcurrencyScope.SESSION, "", "",
            ToolTimeoutRecovery.VERIFY,
            "，消息可能已发出；先查一次会话记录确认，再决定是否重试（重试会重复发）", 0);

    /** 派发子任务：子 Agent 可能已在跑，终态无法靠一次观察核验，只能中止整轮。 */
    private static final ToolExecutionProfile DELEGATE = new ToolExecutionProfile(
            60, 0, 10, ToolConcurrencyScope.SESSION, "", "",
            ToolTimeoutRecovery.ABORT,
            "，子任务可能已在后台运行；必须先核验其状态，禁止直接重派（会起两个子任务）", 0);

    /** 浏览器工具基档：会话内互斥（同一个页面），超时拍快照核验。 */
    private static final ToolExecutionProfile BROWSER = new ToolExecutionProfile(
            30, 0, 10, ToolConcurrencyScope.SESSION, "", "",
            ToolTimeoutRecovery.VERIFY, VERIFY_BROWSER_HINT, 0);

    /** 远程生成、抢本地同一块 GPU（ComfyUI 系列）：与本地文件系统<b>不</b>共用锁。 */
    private static final ToolExecutionProfile COMFYUI_GPU = new ToolExecutionProfile(
            200, 0, 10, ToolConcurrencyScope.SHARED_RESOURCE, "", "comfyui-gpu",
            ToolTimeoutRecovery.VERIFY,
            "，生成任务可能仍在本地 GPU 上跑；先 comfyui_status 核验，不要重复提交", 0);

    /**
     * 远程生成、抢远程生成 API 配额：与本地文件系统<b>不</b>共用锁。
     *
     * <p>内层预算 540s 的构成：image_generate 现在有自己的总预算，到点自己强杀并返回
     * 「哪些后端试过、各自什么错」（可控终态）。540s = 前两个后端竞速段
     * （chatanywhere 慢模型单次等待可达 500s）+ 串行降级尾巴 + 落盘。外层再宽 30s 覆盖收尾。</p>
     *
     * <p>这里的 540 只是<b>未配置时的默认值</b>（与 {@code ImageGenerationService} 的
     * {@code DEFAULT_TOTAL_BUDGET_SECONDS} 同源，由测试钉住）：注册期 {@code BuiltinTools}
     * 用 {@code image.gen.total-budget-seconds} 的实际值 {@code withBudget} 派生，
     * 配置调大时闸门自动跟着走 —— 改造前是「内层等 500s、外层闸门 150s」的倒挂，
     * 外层先触发就把超时升级成「终态未知」并中止整轮。</p>
     */
    private static final ToolExecutionProfile REMOTE_GENERATION = new ToolExecutionProfile(
            540, 30, 10, ToolConcurrencyScope.SHARED_RESOURCE, "", "image-api",
            ToolTimeoutRecovery.VERIFY,
            "，生成任务可能仍在远端跑；等一会儿再查，不要重复提交（重复提交会再花一次钱）", 0);

    /**
     * {@code exec_command} 只读命令档：按命令行文本分锁。
     * <p>内层预算来自调用方声明的 {@code timeout} 参数（见 {@code ExecCommandParams}），
     * 这里的 120 只是未声明时的兜底。外层必须比内层宽 15s，
     * 让工具自己的「杀进程 + 返回部分输出」先于外层闸门生效。</p>
     */
    private static final ToolExecutionProfile EXEC_READ = new ToolExecutionProfile(
            ExecCommandParams.DEFAULT_TIMEOUT_SECONDS,
            ExecCommandParams.OUTER_GATE_MARGIN_SECONDS,
            10, ToolConcurrencyScope.ARGUMENT, "command", "",
            ToolTimeoutRecovery.VERIFY,
            "，命令进程可能仍在后台运行。先跑一条只读命令核验"
                    + "（如 tasklist / pgrep -a java），确认没有残留进程再重试，"
                    + "并把 timeout 参数调大（上限 " + ExecCommandParams.MAX_TIMEOUT_SECONDS + "s）", 0);

    /** {@code exec_command} 写命令档：与写文件等本地写工具共用全局互斥锁。 */
    private static final ToolExecutionProfile EXEC_WRITE = new ToolExecutionProfile(
            ExecCommandParams.DEFAULT_TIMEOUT_SECONDS,
            ExecCommandParams.OUTER_GATE_MARGIN_SECONDS,
            10, ToolConcurrencyScope.GLOBAL, "", "",
            ToolTimeoutRecovery.VERIFY,
            "，命令进程可能仍在后台运行。先跑一条只读命令核验"
                    + "（如 tasklist / pgrep -a java），确认没有残留进程再重试，"
                    + "并把 timeout 参数调大（上限 " + ExecCommandParams.MAX_TIMEOUT_SECONDS + "s）", 0);

    /**
     * 云端异步任务档（{@code song_generate}）：不碰本地共享资源，无锁。
     * <p>外层必须比内层轮询预算宽 30s，余量覆盖「最后一次查询 + 下载整首歌 + 落盘」。
     * 超时不等于副作用去向不明 —— 任务还在云端，拿同一个 taskId 查一次就能确定终态。</p>
     */
    private static final ToolExecutionProfile ASYNC_TASK = new ToolExecutionProfile(
            SongGenerateParams.DEFAULT_POLL_BUDGET_SECONDS,
            SongGenerateParams.OUTER_GATE_MARGIN_SECONDS,
            3, ToolConcurrencyScope.NONE, "", "",
            ToolTimeoutRecovery.RESUME,
            "，任务仍在云端生成中。先告诉用户还在生成，"
                    + "下一轮用响应里那个续查令牌再调一次 " + SongGenerateParams.TOOL_NAME
                    + " 续查，不要重新提交", 0);

    /** 等待用户回答：真正的等待由 AgentLoop 让出，超时只是防止拦截失效。 */
    private static final ToolExecutionProfile AWAIT_USER = new ToolExecutionProfile(
            3600, 0, 3, ToolConcurrencyScope.SESSION, "", "",
            ToolTimeoutRecovery.AWAIT_USER, "", 0);

    private ToolConcurrencyPolicy() {}

    // ════════════════════════════════════════════════════════════════════
    //  唯一声明点
    // ════════════════════════════════════════════════════════════════════

    /**
     * 工具执行契约的唯一声明点（静态版）。
     *
     * <p>未声明的工具（含 MCP 动态注册）落到 {@link ToolExecutionProfile#DEFAULT}：
     * 60s 闸门、全局互斥、超时即中止 —— 与改造前的兜底行为一致。</p>
     */
    public static ToolExecutionProfile profileOf(String name) {
        if (name == null) {
            return ToolExecutionProfile.DEFAULT;
        }
        return switch (name) {
            // ── 只读 ──
            case "skill_list", "skill_view", "comfyui_status", "comfyui_workflows",
                 "comfyui_models" -> READ_FREE.withBudget(60);
            case "read_file", "list_files" -> READ_BY_PATH.withBudget(10);
            case "read_package" -> READ_BY_PATH.withBudget(15);
            case "search_code", "ast_search", "codebase_search" -> READ_BY_PATH;
            case "web_search" -> READ_BY_QUERY;
            case "web_extract", "http_get" -> READ_BY_URL;

            // ── 本地写 ──
            case "write_file", "edit_file" -> WRITE_BY_PATH;
            case "todo", "memory", "skill_manage" -> WRITE_GLOBAL;
            case "delete_path", "move_file", "create_directory", "process" -> WRITE_GLOBAL;
            case "render_diagram" -> WRITE_GLOBAL.withBudget(90);
            case "send_user_message", "delegate_task" -> WRITE_GLOBAL;

            // ── 浏览器（同一个页面必须串行） ──
            case "browser_close" -> BROWSER.withBudget(10);
            case "browser_snapshot", "browser_evaluate" -> BROWSER.withBudget(15);
            case "browser_screenshot" -> BROWSER.withBudget(20);
            case "browser_extract_text" -> BROWSER.withBudget(300);

            // ── 远程生成 ──
            case "image_generate" -> REMOTE_GENERATION;
            case "comfyui_tts" -> COMFYUI_GPU.withBudget(140);
            case "comfyui_txt2img", "comfyui_img2img" -> COMFYUI_GPU;
            case "comfyui_img2video" -> COMFYUI_GPU.withBudget(620);
            case "comfyui_execute" -> COMFYUI_GPU.withBudget(30);
            case "tavily_extract" -> READ_BY_URL.withBudget(60);

            // ── 异步任务 ──
            case SongGenerateParams.TOOL_NAME -> ASYNC_TASK;

            // ── 等待用户 ──
            case "ask_user_question" -> AWAIT_USER;

            // ── 参数化预算（内层预算随调用方声明变化） ──
            case EXEC_TOOL -> EXEC_WRITE;

            // ── 浏览器动作族（click / type / press / scroll / navigate） ──
            default -> name.startsWith("browser_") ? BROWSER : ToolExecutionProfile.DEFAULT;
        };
    }

    /**
     * 工具执行契约的唯一声明点（参数感知版）。
     *
     * <p>只有 {@code exec_command} 的预算随参数变化：{@code git status} 与
     * {@code mvnw package} 不该共用同一个数。其余工具直接退回静态版。</p>
     */
    public static ToolExecutionProfile profileOf(String name, String argumentsJson) {
        if (isExec(name)) {
            return (execReadOnly(argumentsJson) ? EXEC_READ : EXEC_WRITE)
                    .withBudget(ExecCommandParams.requestedTimeoutSecondsOf(argumentsJson));
        }
        return profileOf(name);
    }

    // ════════════════════════════════════════════════════════════════════
    //  派生视图：旧签名保持不变，实现改为从 profileOf 取
    // ════════════════════════════════════════════════════════════════════

    /** 外层闸门秒数（静态版）。 */
    public static long timeoutSecondsOf(String name) {
        return profileOf(name).outerGateSeconds();
    }

    /** 外层闸门秒数（参数感知版）：exec_command 预算随 timeout 参数变化。 */
    public static long timeoutSecondsOf(String name, String argumentsJson) {
        return profileOf(name, argumentsJson).outerGateSeconds();
    }

    /** 串行化范围（静态版）。 */
    public static ToolConcurrencyScope concurrencyScopeOf(String name) {
        return profileOf(name).concurrencyScope();
    }

    /** 串行化范围（参数感知版）：exec_command 只读命令按命令行文本分锁。 */
    public static ToolConcurrencyScope concurrencyScopeOf(String name, String argumentsJson) {
        return profileOf(name, argumentsJson).concurrencyScope();
    }

    /** {@code ARGUMENT} 范围的分锁参数键。 */
    public static String concurrencyKeyArgumentOf(String name) {
        return profileOf(name).concurrencyKeyArgument();
    }

    /** {@code ARGUMENT} 范围的分锁参数键（参数感知版）。 */
    public static String concurrencyKeyArgumentOf(String name, String argumentsJson) {
        return profileOf(name, argumentsJson).concurrencyKeyArgument();
    }

    /** {@code SHARED_RESOURCE} 范围的共享资源名。 */
    public static String sharedResourceKeyOf(String name) {
        return profileOf(name).sharedResourceKey();
    }

    /** 自动重试次数。 */
    public static int maxRetriesOf(String name) {
        return profileOf(name).maxRetries();
    }

    /** 外层闸门超时后的处置策略。 */
    public static ToolTimeoutRecovery timeoutRecoveryOf(String name) {
        return profileOf(name).timeoutRecovery();
    }

    /** 资源锁等待上限（秒）。 */
    public static long lockWaitSecondsOf(String name) {
        return profileOf(name).lockWaitSeconds();
    }

    // ════════════════════════════════════════════════════════════════════
    //  副作用判定（与执行契约正交，故不进 profile）
    // ════════════════════════════════════════════════════════════════════

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

    /**
     * 超时后能靠一次只读观察确定真实终态的工具。
     *
     * <p>改造后由 {@link ToolTimeoutRecovery#VERIFY} 表达，这里保留为派生视图。
     * 判定口径从「仅浏览器」放宽到「所有声明 VERIFY 的工具」——
     * {@code exec_command}（跑 tasklist 查残留进程）、{@code write_file}（read_file 看写没写上）
     * 同样符合「一次只读观察即可核验」。</p>
     */
    public static boolean isOutcomeVerifiable(String name) {
        return timeoutRecoveryOf(name) == ToolTimeoutRecovery.VERIFY;
    }

    public static boolean isIdempotent(String name) {
        return isReadOnly(name) || "render_diagram".equals(name);
    }

    public static boolean isStreamPrefetchSafe(String name) { return isReadOnly(name); }
}
