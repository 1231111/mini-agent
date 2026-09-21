package com.miniagent.agent.planner;

import com.miniagent.agent.tool.CapabilityRegistry;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调度时把 path / url / 前置产出绑到 ActionSpec。
 * 参数齐的写文件、读文件锁定主工具，其余仍走能力面。
 */
final class ActionBinder {

    static final String ARG_PATH = "path";
    static final String ARG_CONTENT = "content";
    static final String ARG_URL = "url";
    private static final String CAP_FILE_READ = "file_read";
    private static final String CAP_IMAGE = "image";

    record Bound(String tool, Map<String, Object> arguments) {
        Bound {
            tool = tool == null ? "" : tool;
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    private ActionBinder() {}

    static Bound bind(TaskNode node, TaskGraph graph) {
        if (node == null) {
            return new Bound("", Map.of());
        }
        Map<String, Object> args = new LinkedHashMap<>();
        if (node.toolArguments() != null) {
            args.putAll(node.toolArguments());
        }
        putIfAbsent(args, ARG_PATH, pathOf(node));
        putIfAbsent(args, ARG_URL, urlOf(node));
        if (writesPlainFile(node)) {
            putIfAbsent(args, ARG_CONTENT, predecessorContent(node, graph));
        }
        return new Bound(toolOf(node, args), args);
    }

    static boolean canDirect(ActionProposal proposal) {
        return proposal != null
                && proposal.actions() != null
                && proposal.actions().size() == 1
                && canDirect(proposal.actions().get(0));
    }

    static boolean canDirect(ActionSpec action) {
        if (action == null) {
            return false;
        }
        return canDirect(action.tool(), action.arguments());
    }

    static boolean canDirect(String tool, Map<String, Object> arguments) {
        if (CapabilityRegistry.WRITE_FILE.equals(tool)) {
            return hasText(arguments, ARG_PATH) && hasText(arguments, ARG_CONTENT);
        }
        if (CapabilityRegistry.READ_FILE.equals(tool)) {
            return hasText(arguments, ARG_PATH);
        }
        return false;
    }

    private static String toolOf(TaskNode node, Map<String, Object> args) {
        if (writesPlainFile(node)
                && hasText(args, ARG_PATH)
                && hasText(args, ARG_CONTENT)) {
            return CapabilityRegistry.WRITE_FILE;
        }
        if (CAP_FILE_READ.equalsIgnoreCase(node.capability())
                && hasText(args, ARG_PATH)) {
            return CapabilityRegistry.READ_FILE;
        }
        return node.capability() == null ? "" : node.capability();
    }

    private static boolean writesPlainFile(TaskNode node) {
        if (node == null) {
            return false;
        }
        String cap = node.capability();
        if (CAP_IMAGE.equalsIgnoreCase(cap)) {
            return false;
        }
        return CapabilityRegistry.persistsArtifacts(cap);
    }

    private static String pathOf(TaskNode node) {
        DoneWhen dw = node.doneWhen();
        if (dw != null && StringUtils.isNotBlank(dw.path())) {
            return dw.path();
        }
        return DataflowNormalizer.pathFromName(node.name());
    }

    private static String urlOf(TaskNode node) {
        return DecompositionPolicy.firstUrl(node.name());
    }

    static String predecessorContent(TaskNode node, TaskGraph graph) {
        if (node == null || graph == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!node.inputs().isEmpty()) {
            for (String in : node.inputs()) {
                TaskNode prod = PlanningLoop.producerOf(graph, node, in);
                appendPredecessor(sb, prod, in);
            }
        } else {
            for (String d : node.dependsOn()) {
                appendPredecessor(sb, graph.byId(d), "");
            }
        }
        return sb.toString();
    }

    private static void appendPredecessor(StringBuilder sb, TaskNode prod, String port) {
        if (prod == null) {
            return;
        }
        String named = port.isEmpty() ? "" : prod.outputBindings().get(port);
        String text = StringUtils.isNotBlank(prod.output()) ? prod.output() : named;
        if (StringUtils.isBlank(text)) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(text);
    }

    private static void putIfAbsent(Map<String, Object> args, String key, String value) {
        if (StringUtils.isBlank(value) || args == null || StringUtils.isBlank(key)) {
            return;
        }
        Object cur = args.get(key);
        if (cur != null && StringUtils.isNotBlank(String.valueOf(cur))) {
            return;
        }
        args.put(key, value);
    }

    static boolean hasText(Map<String, Object> arguments, String key) {
        if (arguments == null || key == null) {
            return false;
        }
        Object v = arguments.get(key);
        return v != null && StringUtils.isNotBlank(String.valueOf(v));
    }
}
