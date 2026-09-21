package com.miniagent.agent.planner;

import com.miniagent.agent.tool.CapabilityRegistry;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 补全 Compiler 常漏的端口、能力和落盘验收。LLM 经常只写 dependsOn。
 */
final class DataflowNormalizer {

    private static final String OUTPUT_SUFFIX = "_out";
    private static final Pattern FILE_IN_NAME = Pattern.compile(
            "([A-Za-z0-9_./-]+\\.(md|txt|json|png|mmd|java|py|html"
                    + "|docx|xlsx|pptx|csv|pdf))",
            Pattern.CASE_INSENSITIVE);

    private DataflowNormalizer() {}

    static TaskGraph normalize(TaskGraph graph) {
        return wire(fixAcceptance(fixCapabilities(graph)));
    }

    static TaskGraph wire(TaskGraph graph) {
        if (graph == null || graph.isEmpty()) {
            return graph;
        }
        List<TaskNode> withOutputs = new ArrayList<>();
        for (TaskNode n : graph.nodes()) {
            if (n.outputs().isEmpty()) {
                withOutputs.add(n.withPorts(n.inputs(), List.of(defaultOutput(n))));
            } else {
                withOutputs.add(n);
            }
        }
        TaskGraph g1 = new TaskGraph(withOutputs);
        List<TaskNode> withInputs = new ArrayList<>();
        for (TaskNode n : g1.nodes()) {
            if (!n.inputs().isEmpty() || n.dependsOn().isEmpty()) {
                withInputs.add(n);
                continue;
            }
            Set<String> ins = new LinkedHashSet<>();
            for (String d : n.dependsOn()) {
                TaskNode dep = g1.byId(d);
                if (dep != null) {
                    ins.addAll(dep.outputs());
                }
            }
            if (ins.isEmpty()) {
                withInputs.add(n);
            } else {
                withInputs.add(n.withPorts(List.copyOf(ins), n.outputs()));
            }
        }
        return new TaskGraph(withInputs);
    }

    static String defaultOutput(TaskNode n) {
        if (n != null && n.doneWhen() != null && n.doneWhen().isFile()
                && StringUtils.isNotBlank(n.doneWhen().path())) {
            return n.doneWhen().path();
        }
        return (n == null || n.id().isBlank() ? "n" : n.id()) + OUTPUT_SUFFIX;
    }

    static TaskGraph fixCapabilities(TaskGraph graph) {
        if (graph == null || graph.isEmpty()) {
            return graph;
        }
        List<TaskNode> next = new ArrayList<>();
        for (TaskNode n : graph.nodes()) {
            String cap = n.capability() == null ? "" : n.capability().trim();
            if (!cap.isEmpty()
                    && !CapabilityRegistry.GENERAL.equalsIgnoreCase(cap)) {
                next.add(n);
                continue;
            }
            String inferred = inferCapability(n);
            if (inferred.isBlank() || inferred.equalsIgnoreCase(cap)) {
                next.add(n);
            } else {
                next.add(n.withCapability(inferred));
            }
        }
        return new TaskGraph(next);
    }

    static TaskGraph fixAcceptance(TaskGraph graph) {
        if (graph == null || graph.isEmpty()) {
            return graph;
        }
        List<TaskNode> next = new ArrayList<>();
        for (TaskNode n : graph.nodes()) {
            if (!needsFileAcceptance(n) || !n.doneWhen().isNote()) {
                next.add(n);
                continue;
            }
            String path = pathFromName(n.name());
            if (!path.isBlank()) {
                next.add(n.withDoneWhen(DoneWhen.file(path)));
                continue;
            }
            if ("image".equalsIgnoreCase(n.capability())) {
                next.add(n.withDoneWhen(DoneWhen.media()));
            } else {
                next.add(n);
            }
        }
        return new TaskGraph(next);
    }

    static boolean needsFileAcceptance(TaskNode n) {
        return n != null && CapabilityRegistry.persistsArtifacts(n.capability());
    }

    /**
     * 空/general 只认冻结的 doneWhen 或节点名里的文件名，不刮中文动词。
     */
    static String inferCapability(TaskNode n) {
        if (n == null) {
            return CapabilityRegistry.GENERAL;
        }
        String cap = n.capability() == null ? "" : n.capability().trim();
        if (!cap.isEmpty()
                && !CapabilityRegistry.GENERAL.equalsIgnoreCase(cap)
                && CapabilityRegistry.knownCapability(cap)) {
            return cap;
        }
        DoneWhen dw = n.doneWhen();
        if (dw != null && dw.isFile()) {
            return "file_write";
        }
        if (dw != null && dw.isMedia()) {
            return "image";
        }
        if (dw != null && dw.isCommand()) {
            return "shell";
        }
        if (dw != null && dw.isValidation()) {
            return "code";
        }
        if (!pathFromName(n.name()).isBlank()) {
            return "file_write";
        }
        if (!cap.isEmpty()) {
            return cap;
        }
        return CapabilityRegistry.GENERAL;
    }

    static String pathFromName(String name) {
        if (StringUtils.isBlank(name)) {
            return "";
        }
        Matcher m = FILE_IN_NAME.matcher(name);
        return m.find() ? m.group(1) : "";
    }
}
