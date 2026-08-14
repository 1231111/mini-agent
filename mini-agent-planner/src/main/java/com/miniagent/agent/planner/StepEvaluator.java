package com.miniagent.agent.planner;

import com.miniagent.agent.todo.LlmJudgeTodoValidator;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.todo.TodoSemanticValidator;
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

    private static final int NOTE_MIN_EVIDENCE = 8;
    private static final Pattern EXIT_CODE =
            Pattern.compile("exit_code\\s*=\\s*(-?\\d+)");

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
        DoneWhen dw = node.doneWhen() == null ? DoneWhen.note() : node.doneWhen();
        String spec = dw.wire();
        String ev = StringUtils.isNotBlank(evidence) ? evidence.trim()
                : (toolResult == null ? "" : toolResult.trim());
        if (!dw.worldCheck() && (looksLikeToolError(ev) || looksLikeLoopAbort(ev)))
            return reject("tool error: " + abbreviate(ev, 200));

        if (dw.isJudge()) return judge(node, dw.criteria(), ev);
        if (dw.isCommand()) return checkCommand(ev);
        if (dw.isValidation()) {
            if (StringUtils.isNotBlank(dw.criteria()))
                return judge(node, dw.criteria(), ev);
            return checkValidation(ev);
        }

        if (dw.isNote()) {
            if (StringUtils.isBlank(ev)) return reject("缺少 evidence");
            if (properties.isStrictEval() && ev.length() < NOTE_MIN_EVIDENCE)
                return reject("evidence 过短（<" + NOTE_MIN_EVIDENCE + "）");
            return EvalResult.pass();
        }

        if (dw.isFile() || dw.isMedia()) {
            TodoSemanticValidator.Result r = TodoSemanticValidator.validate(
                    node.name(), spec, ev);
            if (!r.ok()) return reject(r.error());
            if (dw.isFile() && StringUtils.isNotBlank(dw.criteria())) {
                return judge(node, dw.criteria(),
                        StringUtils.isNotBlank(ev) ? ev : dw.path());
            }
            return EvalResult.pass();
        }

        return reject("未知 doneWhen.type: " + dw.type());
    }

    /** todo 已 completed 时：strict 下仍对世界检查 / 命令 / 校验复验 */
    public EvalResult evaluateAfterLoop(TaskNode node, boolean todoCompleted, String evidence) {
        DoneWhen dw = node == null || node.doneWhen() == null
                ? DoneWhen.note() : node.doneWhen();
        if (!dw.worldCheck() && looksLikeLoopAbort(evidence))
            return reject("loop abort: " + abbreviate(evidence, 120));
        if (todoCompleted && !properties.isStrictEval()) return EvalResult.pass();
        if (todoCompleted && properties.isStrictEval()) {
            if (dw.worldCheck() || dw.isJudge() || dw.isCommand() || dw.isValidation())
                return evaluate(node, evidence, evidence);
            if (StringUtils.isBlank(evidence)) return reject("todo completed 但缺少 evidence");
            return EvalResult.pass();
        }
        return evaluate(node, evidence, evidence);
    }

    private EvalResult judge(TaskNode node, String criteria, String evidence) {
        if (llmJudge == null) return reject("llm_judge 未启用");
        String spec = DoneWhen.JUDGE + ":" + criteria;
        TaskTodoStore.TodoItem item = new TaskTodoStore.TodoItem(
                0, node.name(), TaskTodoStore.Status.in_progress, "",
                spec, evidence, "", List.of());
        String err = llmJudge.validate(item, evidence);
        return err == null ? EvalResult.pass() : reject(err);
    }

    static EvalResult checkCommand(String evidence) {
        if (StringUtils.isBlank(evidence))
            return EvalResult.fail("command_success 缺少 evidence");
        if (looksLikeToolError(evidence) || looksLikeLoopAbort(evidence))
            return EvalResult.fail("command_success 命令失败");
        Matcher m = EXIT_CODE.matcher(evidence);
        if (!m.find())
            return EvalResult.fail("command_success 缺少 exit_code");
        int code = Integer.parseInt(m.group(1));
        if (code != 0)
            return EvalResult.fail("command_success 退出码 " + code);
        return EvalResult.pass();
    }

    static EvalResult checkValidation(String evidence) {
        if (StringUtils.isBlank(evidence))
            return EvalResult.fail("validation_passed 缺少 evidence");
        String t = evidence.toLowerCase();
        if (t.contains("build failure") || t.contains("failures!")
                || t.contains("测试失败") || t.contains("assertionerror")
                || t.contains("\"success\":false"))
            return EvalResult.fail("validation_passed 未通过");
        Matcher m = EXIT_CODE.matcher(evidence);
        if (m.find() && Integer.parseInt(m.group(1)) != 0)
            return EvalResult.fail("validation_passed 退出码非 0");
        if (t.contains("build success") || t.contains("tests run")
                || t.contains("exit_code=0") || t.contains("通过")
                || t.contains("\"success\":true"))
            return EvalResult.pass();
        return EvalResult.fail("validation_passed 证据无法证明校验通过");
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
                || s.contains("MAX_ITERATIONS") || s.contains("任务还没做完");
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
