package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.intent.TaskPlan;
import com.miniagent.agent.intent.TaskStep;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 三层规划架构的第三层：子目标分解。
 *
 * <p>架构层次：
 * <ul>
 *   <li><b>Layer 1 - 意图识别</b>（IntentPlanner）：识别用户意图，生成 TaskPlan</li>
 *   <li><b>Layer 2 - 任务规划</b>（GoalCompiler）：将 TaskPlan 编译为 Goal + 粗粒度 TaskGraph</li>
 *   <li><b>Layer 3 - 子目标分解</b>（TaskDecomposer）：将粗粒度节点分解为可执行的子步骤</li>
 * </ul>
 *
 * <p>职责：
 * <ul>
 *   <li>接收 GoalCompiler 产出的粗粒度 TaskGraph</li>
 *   <li>对每个节点进行细粒度分解（如果需要）</li>
 *   <li>生成详细的执行步骤、工具参数、验收标准</li>
 *   <li>保持节点间的依赖关系正确</li>
 * </ul>
 *
 * <p>触发条件：
 * <ul>
 *   <li>节点名称过于笼统（如"实现功能"）</li>
 *   <li>节点缺少 toolHint</li>
 *   <li>节点的 doneWhen 过于简单（如 note_required）</li>
 *   <li>任务复杂度高（由 ComplexityResult 判断）</li>
 * </ul>
 */
@Component
public class TaskDecomposer {

    private static final Logger log = LoggerFactory.getLogger(TaskDecomposer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DECOMPOSER_SYSTEM = """
            你是任务分解器。将粗粒度的任务节点分解为可执行的子步骤。
            只输出 JSON。不执行任务，不编造工具。

            输入：一个粗粒度的任务节点描述
            输出：该节点的详细分解

            输出格式：
            {
              "nodeId": "原节点ID",
              "subSteps": [
                {
                  "id": "子步骤ID",
                  "name": "具体操作",
                  "capability": "能力类别",
                  "toolHint": "推荐工具",
                  "toolArguments": {},
                  "doneWhen": {"type": "验收类型", "criteria": "验收标准"}
                }
              ],
              "dependencies": ["子步骤间的依赖关系"],
              "estimatedMinutes": 5
            }

            【分解原则】
            1. 只分解真正需要拆分的节点，简单节点保持原样
            2. 每个子步骤必须有明确的输入输出
            3. 子步骤间依赖关系要清晰
            4. 工具参数要具体可执行
            5. 验收标准要可量化

            【不分解的情况】
            - 节点已经足够具体（如"读取文件 config.json"）
            - 节点是单步操作（如"执行命令 mvn compile"）
            - 节点有明确的 toolHint 和 toolArguments
            """;

    private final PlannerProperties properties;

    /** 专用分解模型（从配置初始化），为空时 fallback 到主模型 */
    private volatile ChatModel decomposerModel;
    private volatile boolean decomposerProbeFailed = false;

    public TaskDecomposer(PlannerProperties properties) {
        this.properties = properties;
    }

    /**
     * 分解 TaskGraph 中的粗粒度节点。
     *
     * @param graph 原始 TaskGraph
     * @param plan  任务规划（用于判断复杂度）
     * @param chat  主模型（fallback）
     * @return 分解后的 TaskGraph
     */
    public TaskGraph decompose(TaskGraph graph, TaskPlan plan, ChatModel chat) {
        if (graph == null || graph.isEmpty()) {
            return graph;
        }

        // 判断是否需要分解
        if (!shouldDecompose(graph, plan)) {
            log.debug("TaskDecomposer: 跳过分解，节点已足够具体");
            return graph;
        }

        List<TaskNode> decomposedNodes = new ArrayList<>();
        Map<String, TaskNode> originalNodes = graph.nodes().stream()
                .collect(java.util.stream.Collectors.toMap(TaskNode::id, n -> n));

        for (TaskNode node : graph.nodes()) {
            if (needsDecomposition(node)) {
                try {
                    List<TaskNode> subSteps = decomposeNode(node, graph, chat);
                    if (subSteps != null && !subSteps.isEmpty()) {
                        decomposedNodes.addAll(subSteps);
                        log.info("TaskDecomposer: 节点 {} 分解为 {} 个子步骤", node.id(), subSteps.size());
                    } else {
                        decomposedNodes.add(node);
                    }
                } catch (Exception e) {
                    log.warn("TaskDecomposer: 节点 {} 分解失败，保持原样: {}", node.id(), e.getMessage());
                    decomposedNodes.add(node);
                }
            } else {
                decomposedNodes.add(node);
            }
        }

        return new TaskGraph(decomposedNodes);
    }

    /**
     * 判断是否需要分解。
     */
    private boolean shouldDecompose(TaskGraph graph, TaskPlan plan) {
        // 如果节点数已经足够多，不需要分解
        if (graph.nodes().size() >= 5) {
            return false;
        }

        // 如果任务简单，不需要分解
        if (plan != null && !plan.requiresStructuredPlan()) {
            return false;
        }

        // 检查是否有节点需要分解
        return graph.nodes().stream().anyMatch(this::needsDecomposition);
    }

    /**
     * 判断单个节点是否需要分解。
     */
    private boolean needsDecomposition(TaskNode node) {
        // 如果已经有 toolHint 和 toolArguments，不需要分解
        if (StringUtils.isNotBlank(node.toolHint()) && node.toolArguments() != null && !node.toolArguments().isEmpty()) {
            return false;
        }

        // 如果名称过于笼统，需要分解
        String name = node.name() == null ? "" : node.name().toLowerCase();
        String[] vaguePatterns = {"实现", "创建", "开发", "设计", "完成", "处理", "执行", "运行"};
        for (String pattern : vaguePatterns) {
            if (name.contains(pattern) && name.length() < 15) {
                return true;
            }
        }

        // 如果 doneWhen 过于简单，需要分解
        if (node.doneWhen() != null && "note_required".equals(node.doneWhen().type())
                && StringUtils.isBlank(node.toolHint())) {
            return true;
        }

        return false;
    }

    /**
     * 分解单个节点。
     */
    private List<TaskNode> decomposeNode(TaskNode node, TaskGraph graph, ChatModel chat) {
        ChatModel model = resolveDecomposerModel(chat);
        if (model == null) {
            log.debug("TaskDecomposer: 无可用模型，跳过节点 {} 的分解", node.id());
            return null;
        }

        String prompt = buildDecompositionPrompt(node, graph);
        try {
            var response = model.chat(ChatRequest.builder()
                    .messages(List.of(new SystemMessage(DECOMPOSER_SYSTEM), UserMessage.from(prompt)))
                    .build());
            String text = response.aiMessage() == null ? "" : response.aiMessage().text();
            return parseDecomposition(text, node, graph);
        } catch (Exception e) {
            log.warn("TaskDecomposer: 节点 {} 分解失败: {}", node.id(), e.getMessage());
            return null;
        }
    }

    /**
     * 构建分解提示词。
     */
    private String buildDecompositionPrompt(TaskNode node, TaskGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 待分解节点\n");
        sb.append("- ID: ").append(node.id()).append("\n");
        sb.append("- 名称: ").append(node.name()).append("\n");
        sb.append("- 能力: ").append(node.capability()).append("\n");
        sb.append("- 工具提示: ").append(StringUtils.defaultString(node.toolHint(), "无")).append("\n");
        sb.append("- 优先级: ").append(node.priority()).append("\n");

        if (node.inputs() != null && !node.inputs().isEmpty()) {
            sb.append("- 输入: ").append(node.inputs()).append("\n");
        }
        if (node.outputs() != null && !node.outputs().isEmpty()) {
            sb.append("- 输出: ").append(node.outputs()).append("\n");
        }
        if (node.doneWhen() != null) {
            sb.append("- 验收: ").append(node.doneWhen().type()).append("\n");
        }

        // 添加上下文
        sb.append("\n## 任务上下文\n");
        for (TaskNode other : graph.nodes()) {
            if (!other.id().equals(node.id()) && other.status() == TaskNodeStatus.SUCCESS) {
                sb.append("- 已完成: ").append(other.id()).append(" - ").append(other.name()).append("\n");
            }
        }

        sb.append("\n请将该节点分解为可执行的子步骤（如果需要）。如果节点已经足够具体，返回空的 subSteps 数组。");

        return sb.toString();
    }

    /**
     * 解析分解结果。
     */
    private List<TaskNode> parseDecomposition(String text, TaskNode originalNode, TaskGraph graph) {
        if (StringUtils.isBlank(text)) {
            return null;
        }

        try {
            String json = extractJson(text);
            JsonNode root = MAPPER.readTree(json);
            JsonNode subSteps = root.get("subSteps");

            if (subSteps == null || !subSteps.isArray() || subSteps.isEmpty()) {
                return null;
            }

            List<TaskNode> result = new ArrayList<>();
            String parentPrefix = originalNode.id() + "_";

            for (int i = 0; i < subSteps.size(); i++) {
                JsonNode step = subSteps.get(i);
                String subId = parentPrefix + (i + 1);
                String name = textOr(step, "name", "子步骤 " + (i + 1));
                String cap = textOr(step, "capability", originalNode.capability());
                String toolHint = textOr(step, "toolHint", "");

                // 处理依赖
                List<String> dependsOn = new ArrayList<>();
                if (i == 0) {
                    // 第一个子步骤依赖原节点的依赖
                    dependsOn.addAll(originalNode.dependsOn());
                } else {
                    // 后续子步骤依赖前一个
                    dependsOn.add(parentPrefix + i);
                }

                // 处理输入输出
                List<String> inputs = new ArrayList<>();
                List<String> outputs = new ArrayList<>();
                if (i == 0 && originalNode.inputs() != null) {
                    inputs.addAll(originalNode.inputs());
                }
                if (i == subSteps.size() - 1 && originalNode.outputs() != null) {
                    outputs.addAll(originalNode.outputs());
                }

                // 处理验收标准
                DoneWhen doneWhen = DoneWhen.note();
                JsonNode dw = step.get("doneWhen");
                if (dw != null) {
                    String type = textOr(dw, "type", "note_required");
                    String criteria = textOr(dw, "criteria", "");
                    String path = textOr(dw, "path", "");
                    doneWhen = new DoneWhen(type, criteria, path);
                }

                // 处理工具参数
                Map<String, Object> toolArguments = Map.of();
                JsonNode ta = step.get("toolArguments");
                if (ta != null && ta.isObject()) {
                    toolArguments = MAPPER.convertValue(ta, Map.class);
                }

                TaskNode subNode = new TaskNode(
                        subId,
                        name,
                        cap,
                        dependsOn,
                        inputs,
                        outputs,
                        TaskNodeStatus.PENDING,
                        originalNode.priority() - i,
                        doneWhen,
                        toolHint,
                        toolArguments,
                        "",
                        List.of(),
                        "",
                        0,
                        ""
                );
                result.add(subNode);
            }

            return result;
        } catch (Exception e) {
            log.warn("TaskDecomposer: 解析分解结果失败: {}", e.getMessage());
            return null;
        }
    }

    private ChatModel resolveDecomposerModel(ChatModel fallback) {
        // TODO: 从配置初始化 decomposerModel
        return fallback;
    }

    private static String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        String trimmed = text.trim();

        // 处理 markdown 代码块
        if (trimmed.contains("```")) {
            int start = trimmed.indexOf("```");
            int codeStart = trimmed.indexOf('\n', start);
            if (codeStart < 0) {
                codeStart = start + 3;
            } else {
                codeStart++;
            }
            int end = trimmed.indexOf("```", codeStart);
            if (end > codeStart) {
                trimmed = trimmed.substring(codeStart, end).trim();
            }
        }

        int s = trimmed.indexOf('{');
        int e = trimmed.lastIndexOf('}');
        if (s >= 0 && e > s) {
            return trimmed.substring(s, e + 1);
        }
        return "{}";
    }

    private static String textOr(JsonNode n, String field, String def) {
        if (n == null) {
            return def;
        }
        JsonNode v = n.get(field);
        return v != null && !v.isNull() ? v.asText(def) : def;
    }
}
