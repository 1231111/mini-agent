package com.miniagent.agent.trace;

import com.miniagent.config.entity.AgentTraceStep;
import com.miniagent.config.repository.AgentTraceStepRepository;
import lombok.extern.slf4j.Slf4j;
import com.miniagent.common.RunStatus;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;

/**
 * 智能体全节点轨迹。executionId ThreadLocal；子代理用 parentStepId 栈挂树。
 */
@Service
@Slf4j
public class TraceRecorder {

    @Autowired
    private AgentTraceStepRepository repo;

    private final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
    private final ThreadLocal<String> currentExecutionId = new ThreadLocal<>();
    private final ThreadLocal<String> currentQuestion = new ThreadLocal<>();
    /** 嵌套父步骤栈（SUBAGENT_LOOP_START id） */
    private final ThreadLocal<Deque<Long>> parentStepStack = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Deque<String>> parentExecStack = ThreadLocal.withInitial(ArrayDeque::new);

    public void beginExecution(String sessionId, String executionId, String userQuestion) {
        currentSessionId.set(sessionId);
        currentExecutionId.set(executionId);
        currentQuestion.set(userQuestion);
        parentStepStack.get().clear();
        parentExecStack.get().clear();
        recordNode(AgentStepNode.RUN_START.name(),
                "{\"executionId\":\"" + executionId + "\",\"question\":"
                        + jsonStr(truncate(userQuestion, 500)) + "}",
                RunStatus.RUNNING.name(), 0);
    }

    public String ensureExecution(String sessionId, String userQuestion) {
        if (isActive()) return currentExecutionId.get();
        String id = "exec_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().substring(0, 8);
        beginExecution(sessionId, id, userQuestion);
        return id;
    }

    public void endExecution(String status) {
        if (isActive()) {
            recordNode(AgentStepNode.RUN_END.name(),
                    "{\"status\":\"" + (status == null ? "UNKNOWN" : status) + "\"}",
                    status == null ? RunStatus.SUCCESS.name() : status, 0);
        }
        clearTls();
    }

    public void endExecution() {
        endExecution(RunStatus.SUCCESS.name());
    }

    public boolean isActive() {
        return StringUtils.isNotBlank(currentExecutionId.get());
    }

    public String currentExecutionId() { return currentExecutionId.get(); }
    public String currentSessionId() { return currentSessionId.get(); }

    /** 子代理：把后续步骤挂到 parentStep 下 */
    public void enterChildScope(Long parentStepId) {
        if (parentStepId == null) return;
        parentStepStack.get().push(parentStepId);
        String exec = currentExecutionId.get();
        if (StringUtils.isNotBlank(exec)) parentExecStack.get().push(exec);
    }

    public void exitChildScope() {
        Deque<Long> ps = parentStepStack.get();
        Deque<String> pe = parentExecStack.get();
        if (!ps.isEmpty()) ps.pop();
        if (!pe.isEmpty()) pe.pop();
    }

    public void recordTurnStart(String sessionId, int turn, String subGoalText) {
        recordTurnStart(sessionId, turn, subGoalText, 0, 0);
    }

    public void recordTurnStart(String sessionId, int turn, String subGoalText, int done, int total) {
        String content = Objects.nonNull(subGoalText)
                ? "子目标: " + subGoalText
                : "开始第 " + (turn + 1) + " 轮迭代";
        if (total > 0) content += "（进度 " + done + "/" + total + "）";
        save(build(sessionId, turn, AgentStepNode.PLAN.name(), null, null,
                content, subGoalText, done, total, RunStatus.RUNNING.name(), 0));
    }

    public void recordToolCall(String sessionId, int turn, String toolName, String args) {
        save(build(sessionId, turn, AgentStepNode.TOOL_CALL.name(), toolName, truncate(args, 4000),
                null, null, 0, 0, RunStatus.RUNNING.name(), 0));
    }

    public void recordToolResult(String sessionId, int turn, String toolName, String result, long durationMs, String status) {
        boolean fail = RunStatus.FAILURE.name().equalsIgnoreCase(status) || RunStatus.TIMEOUT.name().equalsIgnoreCase(status)
                || (result != null && (result.contains("\"error\"") || result.contains("\"success\":false")));
        String type = fail ? AgentStepNode.TOOL_ERROR.name() : AgentStepNode.TOOL_RESULT.name();
        save(build(sessionId, turn, type, toolName, null,
                truncate(result, 8000), null, 0, 0,
                fail ? RunStatus.FAILURE.name() : (StringUtils.isBlank(status) ? RunStatus.SUCCESS.name() : status), durationMs));
    }

    public void recordToolRetry(String sessionId, int turn, String toolName, String reason, int attempt) {
        save(build(sessionId, turn, AgentStepNode.TOOL_RETRY.name(), toolName, null,
                "{\"attempt\":" + attempt + ",\"reason\":" + jsonStr(truncate(reason, 500)) + "}",
                null, 0, 0, RunStatus.RUNNING.name(), 0));
    }

    public void recordThinking(String sessionId, int turn, String text) {
        save(build(sessionId, turn, AgentStepNode.THINKING.name(), null, null,
                truncate(text, 8000), null, 0, 0, RunStatus.SUCCESS.name(), 0));
    }

    /** 状态指针：不再单独落库刷屏；变化已并入 PLAN 元数据 */
    public void recordSubGoal(String sessionId, int turn, String text, int done, int total) {
        log.debug("skip SUB_GOAL step (non-core state); text={}", truncate(text, 80));
    }

    public void recordAnswer(String sessionId, int turn, String answer) {
        save(build(sessionId, turn, AgentStepNode.ANSWER.name(), null, null,
                truncate(answer, 8000), null, 0, 0, RunStatus.SUCCESS.name(), 0));
    }

    /**
     * 主循环结束（唯一结束节点）。reason 写入 status/content，token 可选。
     * @deprecated 用 {@link #recordAgentLoopEnd(String, int, String, Long, Long)}；保留签名避免外部调用方立刻炸掉。
     */
    public void recordLoopEnd(String sessionId, int turn, String status, Long tokenIn, Long tokenOut) {
        recordAgentLoopEnd(sessionId, turn, status, tokenIn, tokenOut);
    }

    public void recordAgentLoopEnd(String sessionId, int turn, String status) {
        recordAgentLoopEnd(sessionId, turn, status, null, null);
    }

    public void recordAgentLoopEnd(String sessionId, int turn, String status, Long tokenIn, Long tokenOut) {
        String st = StringUtils.isBlank(status) ? RunStatus.SUCCESS.name() : status;
        AgentTraceStep step = build(sessionId, turn, AgentStepNode.AGENT_LOOP_END.name(), null, null,
                "{\"reason\":" + jsonStr(st) + ",\"status\":" + jsonStr(st) + "}",
                null, 0, 0, st, 0);
        step.setTokenInput(tokenIn);
        step.setTokenOutput(tokenOut);
        save(step);
    }

    public void recordCanceled(String sessionId, int turn, String reason) {
        save(build(sessionId, turn, AgentStepNode.CANCELED.name(), null, null,
                truncate(reason, 2000), null, 0, 0, RunStatus.CANCELED.name(), 0));
    }

    public void recordAborted(String sessionId, int turn, String reason) {
        save(build(sessionId, turn, AgentStepNode.ABORTED.name(), null, null,
                truncate(reason, 2000), null, 0, 0, RunStatus.ABORTED.name(), 0));
    }

    public void recordWaitingForHuman(String sessionId, int turn, String reason) {
        save(build(sessionId, turn, AgentStepNode.WAITING_FOR_HUMAN.name(), null, null,
                truncate(reason, 2000), null, 0, 0, RunStatus.WAITING.name(), 0));
    }

    public void recordHumanConfirmRejected(String sessionId, int turn, String reason) {
        save(build(sessionId, turn, AgentStepNode.HUMAN_CONFIRM_REJECTED.name(), null, null,
                truncate(reason, 2000), null, 0, 0, RunStatus.REJECTED.name(), 0));
    }

    public void recordError(String sessionId, int turn, String error) {
        save(build(sessionId, turn, AgentStepNode.ERROR.name(), null, null,
                truncate(error, 4000), null, 0, 0, RunStatus.FAILURE.name(), 0));
    }

    public void recordLlmLatency(String sessionId, int turn, long durationMs, int inputTokens, int outputTokens) {
        save(build(sessionId, turn, AgentStepNode.LLM_CALL.name(), null, null,
                String.format("耗时 %dms, 输入 %d tokens, 输出 %d tokens", durationMs, inputTokens, outputTokens),
                null, 0, 0, RunStatus.SUCCESS.name(), durationMs));
    }

    public void recordLlmCallError(String sessionId, int turn, String reason, int attempt) {
        save(build(sessionId, turn, AgentStepNode.LLM_CALL_ERROR.name(), null, null,
                "{\"attempt\":" + attempt + ",\"reason\":" + jsonStr(truncate(reason, 500)) + "}",
                null, 0, 0, RunStatus.FAILURE.name(), 0));
    }

    public void recordLlmRetry(String sessionId, int turn, String strategy, int attempt) {
        save(build(sessionId, turn, AgentStepNode.LLM_RETRY.name(), null, null,
                "{\"attempt\":" + attempt + ",\"strategy\":" + jsonStr(strategy) + "}",
                null, 0, 0, RunStatus.RUNNING.name(), 0));
    }

    public void recordCompression(String sessionId, int turn, int beforeMsgCount, int afterMsgCount, int beforeTokens, int afterTokens) {
        String content = String.format("上下文压缩: %d→%d 条消息, %d→%d tokens (节省 %.1f%%)",
                beforeMsgCount, afterMsgCount, beforeTokens, afterTokens,
                beforeTokens > 0 ? (double) (beforeTokens - afterTokens) / beforeTokens * 100 : 0);
        save(build(sessionId, turn, AgentStepNode.COMPRESSION.name(), null, null,
                content, null, 0, 0, RunStatus.SUCCESS.name(), 0));
    }

    public void recordDecision(String sessionId, int turn, String reasoning) {
        if (StringUtils.isBlank(reasoning)) return;
        save(build(sessionId, turn, AgentStepNode.DECISION.name(), null, null,
                truncate(reasoning, 4000), null, 0, 0, RunStatus.SUCCESS.name(), 0));
    }

    public AgentTraceStep recordNode(String stepType, String content, String status, long durationMs) {
        String sessionId = currentSessionId.get();
        if (StringUtils.isBlank(sessionId)) return null;
        return save(build(sessionId, 0, stepType, null, null,
                truncate(content, 8000), null, 0, 0,
                StringUtils.isBlank(status) ? RunStatus.SUCCESS.name() : status, durationMs));
    }

    public AgentTraceStep recordNode(String sessionId, int turn, String stepType, String content, String status, long durationMs) {
        return save(build(sessionId, turn, stepType, null, null,
                truncate(content, 8000), null, 0, 0,
                StringUtils.isBlank(status) ? RunStatus.SUCCESS.name() : status, durationMs));
    }

    private AgentTraceStep build(String sessionId, int turn, String stepType,
                                 String toolName, String toolArgs, String content,
                                 String subGoalText, int subGoalDone, int subGoalTotal,
                                 String status, long durationMs) {
        // ofCode 未知已 WARN；已知且 persisted=false 的由 save() 丢弃
        AgentTraceStep s = new AgentTraceStep();
        s.setSessionId(sessionId);
        s.setExecutionId(currentExecutionId.get());
        s.setUserQuestion(currentQuestion.get());
        s.setTurnIndex(turn);
        s.setStepType(stepType);
        s.setToolName(toolName);
        s.setToolArgs(toolArgs);
        s.setContent(content);
        s.setSubGoalText(subGoalText);
        s.setSubGoalDone(subGoalDone);
        s.setSubGoalTotal(subGoalTotal);
        s.setStatus(status);
        s.setDurationMs(durationMs);
        Deque<Long> ps = parentStepStack.get();
        Deque<String> pe = parentExecStack.get();
        if (ps != null && !ps.isEmpty()) s.setParentStepId(ps.peek());
        if (pe != null && !pe.isEmpty()) s.setParentExecutionId(pe.peek());
        return s;
    }

    private AgentTraceStep save(AgentTraceStep step) {
        var node = AgentStepNode.ofCode(step.getStepType());
        if (node.isPresent() && !node.get().isPersisted()) {
            log.debug("skip non-persisted stepType={}", step.getStepType());
            return step;
        }
        try {
            if (StringUtils.isBlank(step.getExecutionId())) {
                step.setExecutionId("orphan_" + System.currentTimeMillis());
            }
            return repo.save(step);
        } catch (Exception e) {
            log.warn("TraceRecorder 写入失败: {}", e.getMessage());
            return step;
        }
    }

    private void clearTls() {
        currentSessionId.remove();
        currentExecutionId.remove();
        currentQuestion.remove();
        parentStepStack.remove();
        parentExecStack.remove();
    }

    private String truncate(String s, int max) {
        if (Objects.isNull(s)) return null;
        return s.length() > max ? s.substring(0, max) + "...(truncated)" : s;
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
