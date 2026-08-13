package com.miniagent.agent.planner;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * capability 匹配 + 可解释加权（toolHint / 历史成功率 / 失败降权）。
 */
@Component
public class ToolRouter {

    private static final int SCORE_BASE = 10;
    private static final int SCORE_CAP_MATCH = 5;
    private static final int SCORE_NAME_MATCH = 3;
    private static final int SCORE_HINT_PENALTY = 8;
    /** 成功率相对中性 0.5 的满分加权幅度 */
    private static final int SCORE_RATE_SPAN = 10;

    private final ToolCapabilityIndex capabilityIndex;
    private final ToolSuccessStats successStats;
    private final ReadyTaskSelector readySelector = new ReadyTaskSelector();

    public ToolRouter(ToolCapabilityIndex capabilityIndex, ToolSuccessStats successStats) {
        this.capabilityIndex = capabilityIndex;
        this.successStats = successStats;
    }

    public ActionProposal propose(StateSnapshot snap, int batchSize) {
        List<TaskNode> ready = readySelector.select(snap.graph());
        return propose(snap, ready, batchSize);
    }

    public ActionProposal propose(StateSnapshot snap, List<TaskNode> ready, int batchSize) {
        int n = Math.max(1, batchSize);
        List<ActionSpec> actions = new ArrayList<>();
        int taken = 0;
        for (TaskNode node : ready) {
            if (taken >= n) break;
            String tool = pickTool(node);
            actions.add(new ActionSpec(
                    "act_" + UUID.randomUUID().toString().substring(0, 8),
                    node.id(),
                    tool,
                    Mapish.empty(),
                    node.doneWhen(),
                    ""));
            taken++;
        }
        return new ActionProposal(
                "prop_" + UUID.randomUUID().toString().substring(0, 8),
                snap.version(),
                snap.executionId(),
                actions);
    }

    public String pickTool(TaskNode node) {
        List<String> candidates = capabilityIndex.toolsFor(node.capability());
        String hint = node.toolHint() == null ? "" : node.toolHint().trim();
        if (!hint.isEmpty() && capabilityIndex.containsTool(hint))
            return hint;
        if (!hint.isEmpty()) {
            String mapped = mapAlias(hint, node);
            if (mapped != null) return mapped;
        }
        if (candidates.isEmpty()) return "todo";
        String best = candidates.get(0);
        int bestScore = score(node, best);
        for (int i = 1; i < candidates.size(); i++) {
            String t = candidates.get(i);
            int s = score(node, t);
            if (s > bestScore) {
                bestScore = s;
                best = t;
            }
        }
        return best;
    }

    /** 编译器常编造 feishu_browser 等未注册名，映射到真实工具。 */
    private String mapAlias(String hint, TaskNode node) {
        String h = hint.toLowerCase(Locale.ROOT);
        String cap = node.capability() == null ? "" : node.capability().toLowerCase(Locale.ROOT);
        if (h.contains("feishu") || h.contains("lark") || h.contains("browser")
                || h.contains("password") || h.contains("wiki")
                || h.contains("parser") || h.contains("document")
                || cap.contains("browser")) {
            List<String> browser = capabilityIndex.toolsFor("browser");
            if (!browser.isEmpty()) return browser.get(0);
        }
        if (h.contains("write") || h.contains("markdown") || h.contains("md")
                || cap.contains("write") || cap.contains("deliver")) {
            List<String> write = capabilityIndex.toolsFor("file_write");
            if (!write.isEmpty()) return write.get(0);
        }
        return null;
    }

    private int score(TaskNode node, String tool) {
        int s = SCORE_BASE;
        String name = node.name() == null ? "" : node.name().toLowerCase(Locale.ROOT);
        String cap = node.capability() == null ? "" : node.capability().toLowerCase(Locale.ROOT);
        String t = tool.toLowerCase(Locale.ROOT);
        if (cap.contains("write") && t.contains("write")) s += SCORE_CAP_MATCH;
        if (cap.contains("web") && (t.contains("web") || t.contains("http"))) s += SCORE_CAP_MATCH;
        if (cap.contains("image") && (t.contains("image") || t.contains("comfy"))) s += SCORE_CAP_MATCH;
        if (cap.contains("code") && (t.contains("code") || t.contains("search") || t.contains("ast")))
            s += SCORE_CAP_MATCH;
        if (name.contains("写") && t.contains("write")) s += SCORE_NAME_MATCH;
        if (name.contains("搜索") && t.contains("search")) s += SCORE_NAME_MATCH;
        if (node.retryCount() > 0 && t.equalsIgnoreCase(node.toolHint()))
            s -= SCORE_HINT_PENALTY;
        if (successStats != null) {
            double rate = successStats.rate(tool);
            s += (int) Math.round((rate - 0.5d) * 2 * SCORE_RATE_SPAN);
        }
        return s;
    }

    /** 提案内工具 + todo（硬闸门下不含 memory） */
    public static List<String> allowedTools(ActionProposal proposal) {
        return allowedTools(proposal, true);
    }

    public static List<String> allowedTools(ActionProposal proposal, boolean hardGate) {
        Set<String> set = new LinkedHashSet<>();
        for (ActionSpec a : proposal.actions())
            if (a.tool() != null && !a.tool().isBlank()) set.add(a.tool());
        set.add("todo");
        if (!hardGate) set.add("memory");
        return new ArrayList<>(set);
    }

    /**
     * 解析未注册 hint，并按能力展开同族工具（browser_navigate 同时放行 snapshot/click/type）。
     */
    public List<String> allowedFor(ActionProposal proposal, TaskGraph graph, boolean hardGate) {
        Set<String> set = new LinkedHashSet<>();
        for (ActionSpec a : proposal.actions()) {
            if (a.tool() == null || a.tool().isBlank()) continue;
            TaskNode node = graph == null ? null : graph.byId(a.taskId());
            String picked = node != null ? pickTool(node) : mapOrKeep(a.tool());
            set.add(picked);
            expandFamily(set, picked, node == null ? "" : node.capability());
        }
        expandFromGraph(set, graph);
        set.add("todo");
        if (!hardGate) set.add("memory");
        return new ArrayList<>(set);
    }

    private String mapOrKeep(String tool) {
        if (capabilityIndex.containsTool(tool)) return tool;
        TaskNode fake = new TaskNode("x", "", "general", List.of(),
                TaskNodeStatus.READY, 1, "", tool, "", 0);
        return pickTool(fake);
    }

    private void expandFamily(Set<String> set, String tool, String capability) {
        String t = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        String cap = capability == null ? "" : capability.toLowerCase(Locale.ROOT);
        if (t.startsWith("browser_") || t.contains("feishu") || cap.contains("browser"))
            set.addAll(capabilityIndex.toolsFor("browser"));
        if (t.contains("write") || cap.contains("write") || cap.contains("deliver")
                || t.contains("markdown")) {
            set.addAll(capabilityIndex.toolsFor("file_write"));
            set.addAll(capabilityIndex.toolsFor("file_read"));
        }
    }

    /** 读链接写文件：写盘节点仍保留浏览器，避免拆步后丢掉正文。 */
    private void expandFromGraph(Set<String> set, TaskGraph graph) {
        if (graph == null) return;
        boolean browser = false;
        boolean write = false;
        for (TaskNode n : graph.nodes()) {
            String cap = n.capability().toLowerCase(Locale.ROOT);
            String name = n.name().toLowerCase(Locale.ROOT);
            String hint = n.toolHint().toLowerCase(Locale.ROOT);
            if (cap.contains("browser") || cap.contains("web")
                    || hint.startsWith("browser_") || hint.contains("feishu")
                    || name.contains("飞书") || name.contains("网页")
                    || name.contains("打开"))
                browser = true;
            if (cap.contains("write") || cap.contains("deliver")
                    || hint.contains("write") || name.contains("写")
                    || name.contains("md"))
                write = true;
        }
        if (browser) set.addAll(capabilityIndex.toolsFor("browser"));
        if (write) {
            set.addAll(capabilityIndex.toolsFor("file_write"));
            set.addAll(capabilityIndex.toolsFor("file_read"));
        }
    }

    /** 避免 ActionSpec 依赖 Map 工厂散落 */
    private static final class Mapish {
        static java.util.Map<String, Object> empty() { return java.util.Map.of(); }
    }
}
