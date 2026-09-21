package com.miniagent.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import com.miniagent.agent.tool.impl.SongGenerateParams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * capability → 候选工具。Planner 硬闸门唯一的取工具入口。
 *
 * <p>调用方只有 {@link #toolsFor(String)}（Planner 节点执行）。主链路（AgentLoop）
 * 不再按类别挑工具，因此这里没有「按用户消息裁工具面」的静态入口。
 *
 * <p>key 必须覆盖 {@link #PLANNER_CAPABILITIES}；漂移由 {@link #selfCheck()} 在启动期暴露。
 */
@Component
public class CapabilityRegistry implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRegistry.class);

    public static final String GENERAL = "general";
    public static final String FALLBACK_TOOL = "todo";
    public static final String WRITE_FILE = "write_file";
    public static final String READ_FILE = "read_file";

    /**
     * Planner 节点可用的 capability 词表（含 general 回退包）。
     * 结构化图节点禁止填 general，见 PlanValidator。
     */
    public static final List<String> PLANNER_CAPABILITIES = List.of(
            "file_read", "file_write", "web", "code", "image", "browser", "shell",
            "research", "deliver", "plan", GENERAL);

    private static final List<String> FILE_READ =
            List.of(READ_FILE, "list_files", "read_package");

    /**
     * 纯文本落盘 + Office/HTML 交付。PowerPoint 与 HTML→Word 已收敛进 write_document。
     */
    private static final List<String> FILE_WRITE = List.of(
            WRITE_FILE, "edit_file",
            "write_document", "edit_document",
            "write_docx", "write_xlsx",
            "render_diagram");

    private static final List<String> CODE_SEARCH =
            List.of("search_code", "codebase_search", "ast_search");

    private static final List<String> SHELL = List.of("exec_command");

    private static final List<String> WEB =
            List.of("web_search", "web_extract", "http_get", "http_post");

    private static final List<String> BROWSER = List.of(
            "browser_navigate", "browser_snapshot", "browser_click",
            "browser_type", "browser_press", "browser_scroll",
            "browser_evaluate", "browser_screenshot", "browser_extract_text",
            "browser_close");

    /** 媒体生成能力包（图 / 视频 / 音频统一走这里，Planner 节点按能力取工具）。 */
    private static final List<String> IMAGE = List.of(
            "image_generate", SongGenerateParams.TOOL_NAME, "comfyui_status", "comfyui_models",
            "comfyui_txt2img", "comfyui_img2img", "comfyui_img2video",
            "comfyui_tts", "comfyui_check_quality", "render_diagram");

    private static final List<String> QA =
            List.of("skill_list", "skill_view", "skill_manage");

    /**
     * file / file_read 可被 ToolRouter 直接查；qa 给纯问答节点兜底
     * （见 {@code GoalCompiler.inferFromPlan}）。
     */
    private static final Map<String, List<String>> PACKS = Map.ofEntries(
            Map.entry("file_read", FILE_READ),
            Map.entry("file_write", merge(FILE_WRITE, FILE_READ, SHELL)),
            Map.entry("file", merge(FILE_WRITE, FILE_READ, SHELL)),
            Map.entry("deliver", merge(FILE_WRITE, FILE_READ, SHELL)),
            Map.entry("web", merge(WEB, BROWSER)),
            Map.entry("research", merge(WEB, BROWSER, FILE_READ)),
            Map.entry("browser", BROWSER),
            Map.entry("image", merge(IMAGE, List.of(WRITE_FILE, "edit_file"))),
            Map.entry("code", merge(CODE_SEARCH, FILE_READ, FILE_WRITE, SHELL)),
            Map.entry("shell", merge(SHELL, FILE_READ, FILE_WRITE)),
            Map.entry("memory", List.of("memory")),
            Map.entry("todo", List.of(FALLBACK_TOOL)),
            Map.entry("qa", QA),
            Map.entry("plan", merge(List.of(FALLBACK_TOOL), FILE_READ, WEB)),
            Map.entry(GENERAL, merge(FILE_READ, FILE_WRITE, WEB, SHELL,
                    List.of(FALLBACK_TOOL, "memory")))
    );

    @SafeVarargs
    private static List<String> merge(List<String>... groups) {
        Set<String> out = new LinkedHashSet<>();
        for (List<String> g : groups) {
            out.addAll(g);
        }
        return List.copyOf(out);
    }

    private static String normalize(String capability) {
        return capability == null ? "" : capability.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean knownCapability(String capability) {
        String key = normalize(capability);
        return !key.isEmpty() && PACKS.containsKey(key);
    }

    /**
     * 该能力包是否含写盘工具。file_exists 只能挂在这类节点上。
     */
    public static boolean writesFiles(String capability) {
        List<String> pack = PACKS.get(normalize(capability));
        if (pack == null) {
            return false;
        }
        for (String tool : FILE_WRITE) {
            if (pack.contains(tool)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 以落盘/出图为节点目标的能力。note_required 不能挂在这类节点上。
     * 比 {@link #writesFiles} 窄：code/shell 能写文件，但节点目标不必是交付物。
     */
    public static boolean persistsArtifacts(String capability) {
        String key = normalize(capability);
        return "file_write".equals(key) || "deliver".equals(key)
                || "image".equals(key) || "file".equals(key);
    }

    /**
     * 从外部世界取资料的能力。不能同时挂 file_exists / media_delivered。
     */
    public static boolean acquires(String capability) {
        String key = normalize(capability);
        return "web".equals(key) || "research".equals(key)
                || "browser".equals(key) || "file_read".equals(key);
    }

    private record Snapshot(Set<String> registered, Map<String, List<String>> index) {}

    private final ToolRegistry toolRegistry;
    private volatile Snapshot snapshot;

    public CapabilityRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    private Snapshot snapshot() {
        Set<String> registered = Set.copyOf(toolRegistry.getToolNames());
        Snapshot current = this.snapshot;
        if (current != null && current.registered().equals(registered)) {
            return current;
        }
        Snapshot fresh = build(registered);
        this.snapshot = fresh;
        return fresh;
    }

    private Snapshot build(Set<String> registered) {
        Map<String, Set<String>> draft = new LinkedHashMap<>();
        PACKS.forEach((cap, tools) -> draft.put(cap, new LinkedHashSet<>(tools)));

        Map<String, List<String>> index = new LinkedHashMap<>();
        draft.forEach((cap, tools) -> {
            List<String> usable = tools.stream()
                    .filter(t -> registered.isEmpty() || registered.contains(t))
                    .toList();
            index.put(cap, usable);
        });
        return new Snapshot(registered, Map.copyOf(index));
    }

    public List<String> toolsFor(String capability) {
        Snapshot s = snapshot();
        String key = normalize(capability);
        List<String> hit = key.isEmpty() ? null : s.index().get(key);
        if (hit != null && !hit.isEmpty()) {
            return new ArrayList<>(hit);
        }
        List<String> general = s.index().getOrDefault(GENERAL, List.of());
        if (!general.isEmpty()) {
            return new ArrayList<>(general);
        }
        return new ArrayList<>(List.of(FALLBACK_TOOL));
    }

    public boolean containsTool(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Snapshot s = snapshot();
        if (!s.registered().isEmpty()) {
            return s.registered().contains(name);
        }
        return s.index().values().stream().anyMatch(tools -> tools.contains(name));
    }

    /**
     * 不在 {@link #PLANNER_CAPABILITIES} 里、但会被「非提示词」路径发出的 capability。
     *
     * <p>{@code qa} 是 {@code GoalCompiler.inferFromPlan} 对纯问答轮的出口，模型提示词
     * 不提供它 —— 所以它不能进 PLANNER_CAPABILITIES（那是给模型看的词表）。
     * 但它同样要有工具，坏掉时也必须在启动期报出来。</p>
     */
    static final List<String> INTERNAL_CAPABILITIES = List.of("qa");

    /** 自检覆盖的全部 capability：模型能填的 + 代码兜底会填的。 */
    static List<String> checkedCapabilities() {
        List<String> all = new ArrayList<>(PLANNER_CAPABILITIES);
        all.addAll(INTERNAL_CAPABILITIES);
        return all;
    }

    public List<String> selfCheck() {
        List<String> problems = new ArrayList<>();
        for (String cap : checkedCapabilities()) {
            List<String> tools = toolsFor(cap);
            if (tools.isEmpty() || tools.equals(List.of(FALLBACK_TOOL))) {
                problems.add("capability 无可用工具: " + cap);
            }
        }
        return problems;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = selfCheck();
        if (problems.isEmpty()) {
            log.info("CapabilityRegistry 自检通过，capability 词表 {} 均有可用工具",
                    checkedCapabilities());
            return;
        }
        log.error("CapabilityRegistry 自检失败，硬闸门会锁死对应步骤：{}", problems);
    }
}
