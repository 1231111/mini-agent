package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.miniagent.agent.tool.CapabilityRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 todo evidence / 本步工具结果 / file path 抽出契约产出。
 * 不用整轮模型散文当 SUCCESS 证据。
 */
final class NodeOutputBinder {

    private NodeOutputBinder() {}

    record Bound(String evidence, Map<String, String> bindings) {
        Bound {
            evidence = evidence == null ? "" : evidence;
            bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
        }

        boolean complete(TaskNode node) {
            if (node == null) {
                return false;
            }
            if (node.outputs() == null || node.outputs().isEmpty()) {
                return StringUtils.isNotBlank(evidence);
            }
            for (String o : node.outputs()) {
                if (StringUtils.isBlank(bindings.get(o))) {
                    return false;
                }
            }
            return true;
        }
    }

    static Bound ofNode(TaskNode node) {
        if (node == null) {
            return new Bound("", Map.of());
        }
        return new Bound(node.output(), node.outputBindings());
    }

    static Bound bind(TaskNode node, String todoEvidence,
                      List<ChatMessage> messages, String judgeAnswer) {
        if (node == null) {
            return new Bound("", Map.of());
        }
        String todo = usable(todoEvidence);
        String tools = lastUsefulToolText(messages);
        String evidence = firstNonBlank(todo, tools);
        Map<String, String> bindings = new LinkedHashMap<>();
        mergeJsonPorts(bindings, node, todo);
        mergeJsonPorts(bindings, node, tools);
        mergeFilePath(bindings, node);
        DoneWhen dw = node.doneWhen() == null ? DoneWhen.note() : node.doneWhen();
        if (dw.isFile() && StringUtils.isBlank(evidence)
                && StringUtils.isNotBlank(dw.path())) {
            evidence = dw.path();
        }
        if (dw.isJudge()) {
            String ans = usable(judgeAnswer);
            if (StringUtils.isBlank(evidence)) {
                evidence = ans;
            }
            fillMissing(bindings, node, ans);
        }
        fillMissing(bindings, node, evidence);
        if (StringUtils.isBlank(evidence) && !bindings.isEmpty()) {
            evidence = firstBinding(bindings, node);
        }
        return new Bound(evidence, bindings);
    }

    private static String usable(String s) {
        if (StringUtils.isBlank(s) || StepEvaluator.isHollowEvidence(s)) {
            return "";
        }
        return s.trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.isNotBlank(a)) {
            return a;
        }
        return b == null ? "" : b;
    }

    private static String lastUsefulToolText(List<ChatMessage> messages) {
        String last = "";
        if (messages == null) {
            return last;
        }
        for (ChatMessage m : messages) {
            if (!(m instanceof ToolExecutionResultMessage tr)) {
                continue;
            }
            if (CapabilityRegistry.FALLBACK_TOOL.equals(tr.toolName())) {
                continue;
            }
            String t = usable(tr.text());
            if (t.isEmpty() || StepEvaluator.looksLikeToolError(t)) {
                continue;
            }
            last = t;
        }
        return last;
    }

    private static void mergeJsonPorts(Map<String, String> bindings,
                                       TaskNode node, String json) {
        if (node == null || StringUtils.isBlank(json) || node.outputs().isEmpty()) {
            return;
        }
        JsonNode root = parseObject(json);
        if (root == null) {
            return;
        }
        JsonNode bag = portBag(root);
        for (String declared : node.outputs()) {
            JsonNode value = bag.get(declared);
            if (value == null || value.isNull()) {
                continue;
            }
            if (StringUtils.isNotBlank(bindings.get(declared))) {
                continue;
            }
            bindings.put(declared,
                    value.isValueNode() ? value.asText() : value.toString());
        }
        JsonNode path = root.get("path");
        if (path != null && path.isValueNode()
                && StringUtils.isNotBlank(path.asText())) {
            mergePath(bindings, node, path.asText());
        }
    }

    private static JsonNode portBag(JsonNode root) {
        if (root.has("outputs") && root.get("outputs").isObject()) {
            return root.get("outputs");
        }
        if (root.has("artifacts") && root.get("artifacts").isObject()) {
            return root.get("artifacts");
        }
        return root;
    }

    private static void mergeFilePath(Map<String, String> bindings, TaskNode node) {
        DoneWhen dw = node.doneWhen();
        if (dw == null || !dw.isFile() || StringUtils.isBlank(dw.path())) {
            return;
        }
        mergePath(bindings, node, dw.path());
    }

    private static void mergePath(Map<String, String> bindings,
                                  TaskNode node, String path) {
        if (StringUtils.isBlank(path) || node.outputs().isEmpty()) {
            return;
        }
        String norm = path.replace('\\', '/');
        for (String o : node.outputs()) {
            if (StringUtils.isNotBlank(bindings.get(o))) {
                continue;
            }
            bindings.put(o, norm);
        }
    }

    private static void fillMissing(Map<String, String> bindings,
                                    TaskNode node, String value) {
        if (StringUtils.isBlank(value) || node.outputs().isEmpty()) {
            return;
        }
        for (String o : node.outputs()) {
            if (StringUtils.isBlank(bindings.get(o))) {
                bindings.put(o, value);
            }
        }
    }

    private static String firstBinding(Map<String, String> bindings, TaskNode node) {
        for (String o : node.outputs()) {
            String v = bindings.get(o);
            if (StringUtils.isNotBlank(v)) {
                return v;
            }
        }
        return "";
    }

    private static JsonNode parseObject(String json) {
        try {
            JsonNode root = PlannerStateJson.MAPPER.readTree(json);
            if (root != null && root.isObject()) {
                return root;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
