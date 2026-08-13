package com.miniagent.agent.planner;

import com.miniagent.agent.todo.LlmJudgeTodoValidator;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.todo.TodoSemanticValidator;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 步骤验收。strictEval=true 时禁止宽松放行，并走语义 / llm_judge。
 */
@Component
public class StepEvaluator {

    private static final int NOTE_MIN_EVIDENCE = 8;

    public record EvalResult(boolean ok, String reason) {
        public static EvalResult pass() { return new EvalResult(true, ""); }
        public static EvalResult fail(String r) { return new EvalResult(false, r == null ? "" : r); }
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
        if (node == null) return reject("missing node");
        String doneWhen = node.doneWhen() == null ? "" : node.doneWhen().trim();
        String ev = StringUtils.isNotBlank(evidence) ? evidence.trim()
                : (toolResult == null ? "" : toolResult.trim());
        boolean worldCheck = doneWhen.regionMatches(true, 0, "file_exists:", 0, 12)
                || "media_delivered".equalsIgnoreCase(doneWhen);
        if (!worldCheck && (looksLikeToolError(ev) || looksLikeLoopAbort(ev)))
            return reject("tool error: " + abbreviate(ev, 200));

        if (doneWhen.regionMatches(true, 0, "llm_judge:", 0, "llm_judge:".length())) {
            if (llmJudge == null) return reject("llm_judge 未启用");
            TaskTodoStore.TodoItem item = new TaskTodoStore.TodoItem(
                    0, node.name(), TaskTodoStore.Status.in_progress, "",
                    doneWhen, ev, "", List.of());
            String err = llmJudge.validate(item, ev);
            return err == null ? EvalResult.pass() : reject(err);
        }

        if (StringUtils.isBlank(doneWhen) || "note_required".equalsIgnoreCase(doneWhen)) {
            if (StringUtils.isBlank(ev)) return reject("缺少 evidence");
            if (properties.isStrictEval() && ev.length() < NOTE_MIN_EVIDENCE)
                return reject("evidence 过短（<" + NOTE_MIN_EVIDENCE + "）");
            return EvalResult.pass();
        }

        TodoSemanticValidator.Result r = TodoSemanticValidator.validate(
                node.name(), doneWhen, ev);
        if (r.ok()) return EvalResult.pass();
        return reject(r.error());
    }

    /** todo 已 completed 时：strict 下仍对 file_exists/media/llm_judge 复验 evidence */
    public EvalResult evaluateAfterLoop(TaskNode node, boolean todoCompleted, String evidence) {
        String dw = node == null || node.doneWhen() == null ? "" : node.doneWhen().trim();
        boolean worldCheck = dw.startsWith("file_exists:")
                || "media_delivered".equalsIgnoreCase(dw);
        if (!worldCheck && looksLikeLoopAbort(evidence))
            return reject("loop abort: " + abbreviate(evidence, 120));
        if (todoCompleted && !properties.isStrictEval()) return EvalResult.pass();
        if (todoCompleted && properties.isStrictEval()) {
            if (dw.startsWith("file_exists:") || "media_delivered".equalsIgnoreCase(dw)
                    || dw.regionMatches(true, 0, "llm_judge:", 0, "llm_judge:".length()))
                return evaluate(node, evidence, evidence);
            if (StringUtils.isBlank(evidence)) return reject("todo completed 但缺少 evidence");
            return EvalResult.pass();
        }
        return evaluate(node, evidence, evidence);
    }

    private EvalResult reject(String reason) {
        metrics.evalReject();
        return EvalResult.fail(reason);
    }

    static boolean looksLikeToolError(String s) {
        if (s == null || s.isBlank()) return false;
        String t = s.toLowerCase();
        return t.contains("\"error\"") || t.startsWith("错误") || t.startsWith("未知工具")
                || t.contains("tool execution error") || t.contains("timeout")
                || t.contains("planner 硬闸门");
    }

    static boolean looksLikeLoopAbort(String s) {
        if (s == null || s.isBlank()) return false;
        return s.contains("达到最大迭代") || s.contains("任务轮次已达上限")
                || s.contains("MAX_ITERATIONS");
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
