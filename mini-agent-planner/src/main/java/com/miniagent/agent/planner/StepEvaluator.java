package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.todo.LlmJudgeTodoValidator;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.todo.TodoSemanticValidator;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.CommandSemantics;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 步骤验收。strictEval=true 时禁止宽松放行，并走语义 / llm_judge。
 */
@Component
public class StepEvaluator {

    private static final Pattern EXIT_CODE =
            Pattern.compile("^\\s*exit_code\\s*=\\s*(-?\\d+)(?:\\s|$)", Pattern.CASE_INSENSITIVE);

    public record EvalResult(boolean ok, String reason) {
        public static EvalResult pass() { return new EvalResult(true, ""); }
        public static EvalResult fail(String r) { return new EvalResult(false, r == null ? "" : r); }
    }

    /**
     * 图终验：不看节点 SUCCESS 戳，重跑冻结的 doneWhen；Goal.successCriteria
     * 只认文件名或与节点 doneWhen.criteria 对齐的评判句。
     * ponytail: 其余开放文本要隔离评判模型，现在拒绝而不是跳过。
     */
    public record GraphEval(boolean ok, String nodeId, String reason) {
        public static GraphEval pass() {
            return new GraphEval(true, "", "");
        }

        public static GraphEval fail(String nodeId, String reason) {
            return new GraphEval(false, nodeId == null ? "" : nodeId,
                    reason == null ? "" : reason);
        }
    }

    private final PlannerProperties properties;
    private final PlannerMetrics metrics;
    private final LlmJudgeTodoValidator llmJudge;

    public StepEvaluator(PlannerProperties properties,
                         PlannerMetrics metrics,
                         @Autowired(required = false) LlmJudgeTodoValidator llmJudge) {
        this.properties = properties;
        this.metrics = metrics;
        this.llmJudge = llmJudge;
    }

    public EvalResult evaluate(TaskNode node, String toolResult, String evidence) {
        return evaluate(node, toolResult, evidence, false);
    }

    /** A concrete ToolResult outranks heuristics over its human-readable payload. */
    public EvalResult evaluate(TaskNode node, ToolResult structuredResult, String evidence) {
        if (structuredResult == null) {
            return evaluate(node, "", evidence, false);
        }
        if (!structuredResult.isSuccess()) {
            return reject("tool error: " + abbreviate(
                    StringUtils.defaultIfBlank(structuredResult.message(), structuredResult.legacyText()), 400));
        }
        return evaluate(node, structuredResult.legacyText(), evidence, true);
    }

    private EvalResult evaluate(TaskNode node, String toolResult, String evidence,
                                boolean structuredSuccess) {
        if (node == null) {
            return reject("missing node");
        }
        DoneWhen dw = node.doneWhen() == null ? DoneWhen.note() : node.doneWhen();
        String spec = dw.wire();
        String ev = StringUtils.isNotBlank(evidence) ? evidence.trim()
                : (toolResult == null ? "" : toolResult.trim());
        if (looksLikeFileDelivery(node) && isHollowEvidence(ev)) {
            return reject("file delivery 节点不能用空洞 evidence 放行");
        }
        if (looksLikeAcquire(node) && isHollowEvidence(ev)) {
            return reject("获取类节点不能用空洞 evidence 放行");
        }
        if (!structuredSuccess && (looksLikeToolError(toolResult) || looksLikeToolError(ev))) {
            // 400 而不是 200：这条 reason 会被 RecoveryEngine 装进 FailureDiagnosis.reason，
            // 再原样拼进 tryLlmReplan 的重规划提示词 —— 它是模型唯一能看到的失败细节。
            // 200 字刚好卡掉服务端返回里靠后的关键字段（SongGenerationService 的
            // hint 自带 300 字响应体片段，加上 error 文案就超过 200 了）。
            return reject("tool error: " + abbreviate(ev, 400));
        }

        if (dw.isJudge()) {
            return judge(node, dw.criteria(), ev);
        }
        if (dw.isCommand()) {
            return checkCommand(ev, structuredSuccess);
        }
        if (dw.isValidation()) {
            if (StringUtils.isNotBlank(dw.criteria()))
                return judge(node, dw.criteria(), ev);
            return checkValidation(ev, structuredSuccess);
        }

        if (dw.isNote() && looksLikeFileDelivery(node)) {
            return evaluateFile(node, ev, dw.path());
        }
        if (dw.isNote()) {
            if (StringUtils.isBlank(ev) || isHollowEvidence(ev)) {
                return reject("缺少 evidence");
            }
            return EvalResult.pass();
        }

        if (dw.isFile() || dw.isMedia()) {
            TodoSemanticValidator.Result r = TodoSemanticValidator.validate(
                    node.name(), spec, ev);
            if (!r.ok()) {
                return reject(r.error());
            }
            return EvalResult.pass();
        }

        return reject("未知 doneWhen.type: " + dw.type());
    }

    private EvalResult evaluateFile(TaskNode node, String evidence, String pathHint) {
        String path = StringUtils.isNotBlank(pathHint) ? pathHint.trim() : "";
        String spec = DoneWhen.FILE + ":" + path;
        TodoSemanticValidator.Result r = TodoSemanticValidator.validate(
                node.name(), spec, evidence);
        if (!r.ok()) {
            return reject(r.error());
        }
        return EvalResult.pass();
    }

    /** 落盘/出图由 capability 或冻结的 worldCheck 决定，不刮节点名。 */
    static boolean looksLikeFileDelivery(TaskNode node) {
        if (node == null) {
            return false;
        }
        DoneWhen dw = node.doneWhen();
        if (dw != null && dw.worldCheck()) {
            return true;
        }
        return DataflowNormalizer.needsFileAcceptance(node);
    }

    static boolean looksLikeAcquire(TaskNode node) {
        return node != null && CapabilityRegistry.acquires(node.capability());
    }

    /** 循环结束后验收：不看 todo 勾选，只认证据与 doneWhen。 */
    public EvalResult evaluateAfterLoop(TaskNode node, String evidence) {
        return evaluate(node, evidence, evidence);
    }

    public GraphEval evaluateGraph(Goal goal, TaskGraph graph) {
        if (graph == null || graph.isEmpty()) {
            return GraphEval.fail("", "eval: empty graph");
        }
        for (TaskNode n : graph.nodes()) {
            if (n.status() != TaskNodeStatus.SUCCESS) {
                return GraphEval.fail(n.id(), "eval: unfinished node " + n.id());
            }
            if (n.toolStatus() != null && n.toolStatus() != ToolStatus.SUCCESS) {
                return GraphEval.fail(n.id(), "eval: unsuccessful tool status " + n.toolStatus());
            }
            if (!NodeOutputBinder.ofNode(n).complete(n)) {
                return GraphEval.fail(n.id(), "eval: outputs 未绑定");
            }
            EvalResult ev = evaluate(n, n.output(), n.output(), n.toolStatus() == ToolStatus.SUCCESS);
            if (!ev.ok()) {
                return GraphEval.fail(n.id(), ev.reason());
            }
        }
        if (goal == null || goal.successCriteria() == null) {
            return GraphEval.pass();
        }
        for (String c : goal.successCriteria()) {
            if (Goal.isPlaceholderCriterion(c)) {
                continue;
            }
            String path = DataflowNormalizer.pathFromName(c);
            if (path.isBlank()) {
                if (!hasMatchingJudge(graph, c)) {
                    return GraphEval.fail("", "eval: successCriteria 无法验收 " + c);
                }
                continue;
            }
            if (!hasFileDeliverable(graph, path)) {
                return GraphEval.fail("", "eval: successCriteria 缺少产物 " + path);
            }
        }
        return GraphEval.pass();
    }

    static boolean hasFileDeliverable(TaskGraph graph, String path) {
        if (graph == null || StringUtils.isBlank(path)) {
            return false;
        }
        String want = path.replace('\\', '/');
        int slash = want.lastIndexOf('/');
        String fileName = slash >= 0 ? want.substring(slash + 1) : want;
        for (TaskNode n : graph.nodes()) {
            DoneWhen dw = n.doneWhen();
            if (dw == null || !dw.isFile() || StringUtils.isBlank(dw.path())) {
                continue;
            }
            String got = dw.path().replace('\\', '/');
            if (want.equalsIgnoreCase(got) || got.endsWith("/" + fileName)
                    || fileName.equalsIgnoreCase(pathFileName(got))) {
                return true;
            }
        }
        return false;
    }

    static boolean hasMatchingJudge(TaskGraph graph, String criteria) {
        if (graph == null || StringUtils.isBlank(criteria)) {
            return false;
        }
        String want = criteria.trim();
        for (TaskNode n : graph.nodes()) {
            DoneWhen dw = n.doneWhen();
            if (dw == null || StringUtils.isBlank(dw.criteria())) {
                continue;
            }
            if ((dw.isJudge() || dw.isValidation())
                    && want.equals(dw.criteria().trim())) {
                return true;
            }
        }
        return false;
    }

    private static String pathFileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private EvalResult judge(TaskNode node, String criteria, String evidence) {
        if (llmJudge == null) {
            return reject("llm_judge 未启用");
        }
        String spec = DoneWhen.JUDGE + ":" + criteria;
        TaskTodoStore.TodoItem item = new TaskTodoStore.TodoItem(
                0, node.name(), TaskTodoStore.Status.in_progress, "",
                spec, evidence, "", List.of());
        String err = llmJudge.validate(item, evidence);
        return err == null ? EvalResult.pass() : reject(err);
    }

    static EvalResult checkCommand(String evidence) {
        return checkCommand(evidence, false);
    }

    private static EvalResult checkCommand(String evidence, boolean structuredSuccess) {
        if (StringUtils.isBlank(evidence))
            return EvalResult.fail("command_success 缺少 evidence");
        if (!structuredSuccess && looksLikeToolError(evidence))
            return EvalResult.fail("command_success 命令失败");
        Integer code = exitCode(evidence);
        if (code == null)
            return EvalResult.fail("command_success 缺少 exit_code");
        if (code != 0 && !CommandSemantics.isToleratedExitText(evidence))
            return EvalResult.fail("command_success 退出码 " + code);
        return EvalResult.pass();
    }

    static EvalResult checkValidation(String evidence) {
        return checkValidation(evidence, false);
    }

    private static EvalResult checkValidation(String evidence, boolean structuredSuccess) {
        if (StringUtils.isBlank(evidence)) {
            return EvalResult.fail("validation_passed 缺少 evidence");
        }
        if (!structuredSuccess && looksLikeToolError(evidence)) {
            return EvalResult.fail("validation_passed 命令失败");
        }
        Integer code = exitCode(evidence);
        if (code != null) {
            if (code != 0 && !CommandSemantics.isToleratedExitText(evidence)) {
                return EvalResult.fail("validation_passed 退出码 " + code);
            }
            return EvalResult.pass();
        }
        JsonNode root = jsonObject(evidence);
        if (root != null && root.path("success").isBoolean() && root.path("success").asBoolean()) {
            return EvalResult.pass();
        }
        return EvalResult.fail("validation_passed 证据无法证明校验通过");
    }

    private EvalResult reject(String reason) {
        metrics.evalReject();
        return EvalResult.fail(reason);
    }

    static boolean looksLikeToolError(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        String text = s.trim();
        JsonNode root = jsonObject(text);
        if (root != null) {
            String status = root.path("status").asText("");
            if ("failed".equalsIgnoreCase(status) || "failure".equalsIgnoreCase(status)
                    || "error".equalsIgnoreCase(status)) {
                return true;
            }
            JsonNode code = root.get("exit_code");
            if (code != null && code.canConvertToInt() && code.asInt() != 0) {
                return true;
            }
        }
        Integer code = exitCode(text);
        if (code != null && code != 0 && !CommandSemantics.isToleratedExitText(text)) {
            return true;
        }
        // Shared legacy protocol reads only top-level execution fields or known
        // error-response prefixes. Nested data and command stdout are ordinary content.
        return !ToolResult.fromLegacy(text).isSuccess()
                || text.startsWith("未知工具")
                || text.regionMatches(true, 0, "tool execution error:", 0, "tool execution error:".length());
    }

    private static Integer exitCode(String evidence) {
        if (StringUtils.isBlank(evidence)) {
            return null;
        }
        JsonNode root = jsonObject(evidence);
        if (root != null) {
            JsonNode code = root.get("exit_code");
            return code != null && code.canConvertToInt() ? code.asInt() : null;
        }
        Matcher matcher = EXIT_CODE.matcher(evidence);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException invalid) {
            return Integer.MIN_VALUE;
        }
    }

    private static JsonNode jsonObject(String text) {
        try {
            JsonNode root = PlannerStateJson.MAPPER.readTree(text);
            return root != null && root.isObject() ? root : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean isHollowEvidence(String s) {
        if (StringUtils.isBlank(s)) {
            return true;
        }
        String t = s.trim();
        return t.equals(AgentLoop.STEP_SEGMENT_DONE)
                || t.equals("本步已完成")
                || t.startsWith("已按规划图推进任务");
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
