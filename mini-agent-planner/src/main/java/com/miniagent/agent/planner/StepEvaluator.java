package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
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
        if (!dw.worldCheck() && (looksLikeToolError(ev) || looksLikeLoopAbort(ev))) {
            return reject("tool error: " + abbreviate(ev, 200));
        }

        if (dw.isJudge()) {
            return judge(node, dw.criteria(), ev);
        }
        if (dw.isCommand()) {
            return checkCommand(ev);
        }
        if (dw.isValidation()) {
            if (StringUtils.isNotBlank(dw.criteria()))
                return judge(node, dw.criteria(), ev);
            return checkValidation(ev);
        }

        if (dw.isNote() && looksLikeFileDelivery(node)) {
            return evaluateFile(node, ev, dw.path());
        }
        if (dw.isNote()) {
            if (StringUtils.isBlank(ev)) {
                return reject("缺少 evidence");
            }
            if (properties.isStrictEval() && ev.length() < NOTE_MIN_EVIDENCE) {
                return reject("evidence 过短（<" + NOTE_MIN_EVIDENCE + "）");
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

    /** 写文件/出图/交代码节点不能靠 note_required 放行。 */
    static boolean looksLikeFileDelivery(TaskNode node) {
        if (node == null) {
            return false;
        }
        DoneWhen dw = node.doneWhen();
        if (dw != null && (dw.isFile() || dw.isMedia())) {
            return true;
        }
        if (node.outputs() != null && !node.outputs().isEmpty()) {
            return true;
        }
        String cap = node.capability() == null ? "" : node.capability();
        if (cap.equals("file_write") || cap.equals("deliver")
                || cap.equals("image") || cap.equals("code")) {
            return true;
        }
        String n = node.name() == null ? "" : node.name().toLowerCase();
        return n.contains(".md") || n.contains(".png") || n.contains(".java")
                || n.contains(".docx") || n.contains(".xlsx") || n.contains(".pptx")
                || n.contains(".mmd") || n.contains(".py") || n.contains(".html")
                || n.contains("架构图") || n.contains("流程图") || n.contains("时序图");
    }

    /** todo 已 completed 时：strict 下仍对世界检查 / 命令 / 校验复验 */
    public EvalResult evaluateAfterLoop(TaskNode node, boolean todoCompleted, String evidence) {
        DoneWhen dw = node == null || node.doneWhen() == null
                ? DoneWhen.note() : node.doneWhen();
        if (!dw.worldCheck() && looksLikeLoopAbort(evidence)) {
            return reject("loop abort: " + abbreviate(evidence, 120));
        }
        if (todoCompleted && !properties.isStrictEval() && !looksLikeFileDelivery(node)) {
            return EvalResult.pass();
        }
        if (todoCompleted && properties.isStrictEval()) {
            if (dw.worldCheck() || dw.isJudge() || dw.isCommand() || dw.isValidation()
                    || looksLikeFileDelivery(node)) {
                return evaluate(node, evidence, evidence);
            }
            if (StringUtils.isBlank(evidence)) {
                return reject("todo completed 但缺少 evidence");
            }
            return EvalResult.pass();
        }
        return evaluate(node, evidence, evidence);
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
        if (s == null || s.isBlank()) {
            return false;
        }
        String t = s.toLowerCase();
        return t.contains("\"error\"") || t.startsWith("错误") || t.startsWith("未知工具")
                || t.contains("tool execution error") || t.contains("timeout")
                || t.contains("planner 硬闸门");
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

    static boolean looksLikeLoopAbort(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        return s.contains("达到最大迭代") || s.contains("任务轮次已达上限")
                || s.contains("MAX_ITERATIONS") || s.contains("任务还没做完");
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
