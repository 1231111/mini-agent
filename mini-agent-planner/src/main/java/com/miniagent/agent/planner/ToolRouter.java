package com.miniagent.agent.planner;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Executor 调工具前按 capability 展开工具面。hint 优先但不独占。
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

    public ToolRouter(ToolCapabilityIndex capabilityIndex, ToolSuccessStats successStats) {
        this.capabilityIndex = capabilityIndex;
        this.successStats = successStats;
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

    /**
     * 按节点 capability 展开同族工具；toolHint 若已注册则排在前面，不锁死单工具。
     */
    public List<String> allowedFor(ActionProposal proposal, TaskGraph graph, boolean hardGate) {
        Set<String> set = new LinkedHashSet<>();
        for (ActionSpec a : proposal.actions()) {
            TaskNode node = graph == null ? null : graph.byId(a.taskId());
            String cap = node != null ? node.capability()
                    : (a.tool() == null ? "" : a.tool());
            String hint = node != null ? node.toolHint() : "";
            addPreferred(set, hint, node, cap);
            set.addAll(capabilityIndex.toolsFor(cap));
            expandFamily(set, hint, cap);
            expandFileToolsIfNeeded(set, node, a);
            expandExternalIoIfNeeded(set, node);
        }
        set.add("todo");
        if (!hardGate) set.add("memory");
        return new ArrayList<>(set);
    }

    private void addPreferred(Set<String> set, String hint, TaskNode node, String cap) {
        if (hint == null || hint.isBlank()) return;
        if (capabilityIndex.containsTool(hint)) {
            set.add(hint);
            return;
        }
        TaskNode forAlias = node != null ? node
                : new TaskNode("x", "", cap, List.of(), List.of(), List.of(),
                TaskNodeStatus.READY, 1, DoneWhen.note(), hint, "", 0, "");
        String mapped = mapAlias(hint, forAlias);
        if (mapped != null) set.add(mapped);
    }

    private void expandFamily(Set<String> set, String tool, String capability) {
        String t = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        String cap = capability == null ? "" : capability.toLowerCase(Locale.ROOT);
        if (t.startsWith("browser_") || t.contains("feishu") || cap.contains("browser"))
            set.addAll(capabilityIndex.toolsFor("browser"));
        if (t.contains("write") || t.contains("read") || cap.contains("write")
                || cap.contains("deliver") || t.contains("markdown")) {
            set.addAll(capabilityIndex.toolsFor("file_write"));
            set.addAll(capabilityIndex.toolsFor("file_read"));
        }
    }

    /**
     * 本步要落盘或有产出物时放行写文件。图节点缺失时看 expectedResult（file_exists:…）。
     */
    private void expandFileToolsIfNeeded(Set<String> set, TaskNode node, ActionSpec a) {
        boolean file = node != null && node.doneWhen().isFile();
        if (!file && a != null && a.expectedResult() != null
                && a.expectedResult().startsWith(DoneWhen.FILE)) {
            file = true;
        }
        boolean hasOut = node != null && !node.outputs().isEmpty();
        if (!file && !hasOut) {
            return;
        }
        set.add("write_file");
        set.add("edit_file");
        set.addAll(capabilityIndex.toolsFor("file_write"));
        if (node == null || !"browser".equals(node.capability())) {
            set.add("read_file");
        }
    }

    /**
     * 发布/调外部 API 不能只给文件工具，否则硬闸门会拒掉 http_get / exec_command。
     */
    private void expandExternalIoIfNeeded(Set<String> set, TaskNode node) {
        if (!looksLikeExternalPublish(node)) {
            return;
        }
        set.addAll(capabilityIndex.toolsFor("web"));
        set.addAll(capabilityIndex.toolsFor("shell"));
        set.addAll(capabilityIndex.toolsFor("file_read"));
        set.addAll(capabilityIndex.toolsFor("file_write"));
    }

    static boolean looksLikeExternalPublish(TaskNode node) {
        if (node == null) {
            return false;
        }
        if (node.doneWhen().isMedia() || node.doneWhen().isCommand()) {
            return true;
        }
        String n = node.name().toLowerCase(Locale.ROOT);
        String c = node.capability().toLowerCase(Locale.ROOT);
        if ("shell".equals(c)) {
            return true;
        }
        return n.contains("发布") || n.contains("草稿")
                || n.contains("publish") || n.contains("draft")
                || n.contains("wechat");
    }
}
