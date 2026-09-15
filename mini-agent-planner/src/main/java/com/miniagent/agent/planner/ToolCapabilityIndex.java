package com.miniagent.agent.planner;

import com.miniagent.agent.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * capability → 候选工具。
 *
 * <p>本表是硬闸门（agent.planner.hard-proposal=true）的唯一依据：
 * {@code AgentLoop.buildToolSpecsForTurn} 会按这里返回的工具名裁剪模型可见的工具，
 * {@code ProposalTurnPolicy.denyTool} 会拒掉表外的调用。
 * 所以某个 capability 漏列一个工具，等于该步骤永远做不成 —— 必须显式列全，
 * 不能依赖工具描述里的中文关键词碰运气命中。
 *
 * <p>key 必须与 {@code GoalCompiler.COMPILER_SYSTEM} 里告诉模型的 capability 词表一一对应，
 * 两边漂移由 {@link #selfCheck()} 兜底暴露。
 */
@Component
public class ToolCapabilityIndex implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ToolCapabilityIndex.class);

    private static final String GENERAL = "general";
    private static final String FALLBACK_TOOL = "todo";

    private static final List<String> FILE_READ =
            List.of("read_file", "list_files", "read_package");

    /**
     * 纯文本落盘 + Office/HTML 交付。缺任何一类就交付不了对应格式的产出物。
     * PowerPoint 与 HTML→Word 已收敛进 write_document（按其分派），故不再单列工具名。
     */
    private static final List<String> FILE_WRITE = List.of(
            "write_file", "edit_file",
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

    private static final List<String> IMAGE = List.of(
            "image_generate", "comfyui_models", "comfyui_txt2img",
            "comfyui_img2img", "comfyui_check_quality", "render_diagram");

    /**
     * capability 词表与 GoalCompiler 对齐：
     * file_write / web / code / image / browser / shell / research / deliver / plan / general。
     * file / file_read 是内部细分，ToolRouter.expandFamily 会直接查。
     */
    private static final Map<String, List<String>> STATIC = Map.ofEntries(
            Map.entry("file_read", FILE_READ),
            // 写之前常要读回原文校验，写完要能改，两者必须同时在场
            Map.entry("file_write", merge(FILE_WRITE, FILE_READ, SHELL)),
            Map.entry("file", merge(FILE_WRITE, FILE_READ, SHELL)),
            Map.entry("deliver", merge(FILE_WRITE, FILE_READ, SHELL)),
            Map.entry("web", merge(WEB, BROWSER)),
            Map.entry("research", merge(WEB, BROWSER, FILE_READ, FILE_WRITE)),
            // 抽取结果要能落盘，否则整步产出只能停在上下文里
            Map.entry("browser", merge(BROWSER, FILE_WRITE)),
            // 出图：写 mermaid + 渲染。不要挂 read_package，否则模型会去翻仓库而不是画图
            Map.entry("image", merge(IMAGE, List.of("write_file", "edit_file"))),
            // 写代码 = 检索 + 读 + 写 + 跑；只给检索工具等于这一步做不了事
            Map.entry("code", merge(CODE_SEARCH, FILE_READ, FILE_WRITE, SHELL)),
            Map.entry("shell", merge(SHELL, FILE_READ, FILE_WRITE)),
            Map.entry("memory", List.of("memory")),
            Map.entry("todo", List.of(FALLBACK_TOOL)),
            // 规划步要能读、能查资料才制定得出方案；只给 todo 会变成死步
            // （硬闸门还会拒掉 todo.set，任务图由 Planner 投影）
            Map.entry("plan", merge(List.of(FALLBACK_TOOL), FILE_READ, WEB)),
            // 兜底桶：capability 未知时落这里，硬闸门下太窄会直接把步骤做死，
            // 所以读/写/搜/跑都要在场（exec 仍受 PermissionPolicy 二次把关）
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

    /** 按当前注册表算好的一份不可变视图；registered 变了才重算。 */
    private record Snapshot(Set<String> registered, Map<String, List<String>> index) {}

    private final ToolRegistry toolRegistry;
    private volatile Snapshot snapshot;

    public ToolCapabilityIndex(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 原子换页而不是原地 clear+put：并发会话同时查询时，
     * 旧实现会让别的线程读到半构建的 map，进而把整步工具面缩成 todo。
     */
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
        STATIC.forEach((cap, tools) -> draft.put(cap, new LinkedHashSet<>(tools)));
        // 描述关键词只做增补，用于 MCP 等运行期注册、STATIC 里没有的工具
        enrichFromDescriptions(draft);

        Map<String, List<String>> index = new LinkedHashMap<>();
        draft.forEach((cap, tools) -> {
            List<String> usable = tools.stream()
                    .filter(t -> registered.isEmpty() || registered.contains(t))
                    .toList();
            index.put(cap, usable);
        });
        return new Snapshot(registered, Map.copyOf(index));
    }

    private void enrichFromDescriptions(Map<String, Set<String>> draft) {
        for (ToolSpecification spec : toolRegistry.getSpecifications()) {
            String name = spec.name();
            String desc = (spec.description() == null ? "" : spec.description())
                    .toLowerCase(Locale.ROOT);
            if (desc.contains("文件") || desc.contains("file") || desc.contains("写入")) {
                add(draft, "file", name);
            }
            if (desc.contains("搜索") || desc.contains("网页") || desc.contains("web")) {
                add(draft, "web", name);
            }
            if (desc.contains("浏览器") || desc.contains("browser")) {
                add(draft, "browser", name);
            }
            // 禁止单字「图」：会把「片段」「试图」误打进 image，出图步骤就会去搜代码
            if (desc.contains("图片") || desc.contains("生图") || desc.contains("image")
                    || desc.contains("comfy") || desc.contains("mermaid")
                    || desc.contains("diagram") || desc.contains("架构图")
                    || desc.contains("流程图")) {
                add(draft, "image", name);
            }
            if (desc.contains("代码") || desc.contains("code") || desc.contains("ast")) {
                add(draft, "code", name);
            }
            if (desc.contains("命令") || desc.contains("exec") || desc.contains("shell")) {
                add(draft, "shell", name);
            }
        }
    }

    private void add(Map<String, Set<String>> draft, String cap, String tool) {
        draft.computeIfAbsent(cap, k -> new LinkedHashSet<>()).add(tool);
    }

    public List<String> toolsFor(String capability) {
        Snapshot s = snapshot();
        String key = capability == null ? "" : capability.trim().toLowerCase(Locale.ROOT);
        List<String> hit = key.isEmpty() ? null : s.index().get(key);
        if (hit != null && !hit.isEmpty()) {
            return new ArrayList<>(hit);
        }
        // 未知 capability 退到 general（能读能写能搜），而不是退到只剩 todo 的死步
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
     * 自检：GoalCompiler 承诺给模型的 capability 必须都能解析出工具面。
     * 返回问题描述列表，空列表代表一致。
     */
    public List<String> selfCheck() {
        List<String> problems = new ArrayList<>();
        for (String cap : GoalCompiler.CAPABILITIES) {
            List<String> tools = toolsFor(cap);
            if (tools.isEmpty() || tools.equals(List.of(FALLBACK_TOOL))) {
                problems.add("capability 无可用工具: " + cap);
            }
        }
        return problems;
    }

    /** 工具全部注册完后跑一次自检；漂移会让硬闸门锁死步骤，必须在启动期就暴露。 */
    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = selfCheck();
        if (problems.isEmpty()) {
            log.info("ToolCapabilityIndex 自检通过，capability 词表 {} 均有可用工具",
                    GoalCompiler.CAPABILITIES);
            return;
        }
        log.error("ToolCapabilityIndex 自检失败，硬闸门会锁死对应步骤：{}", problems);
    }
}
