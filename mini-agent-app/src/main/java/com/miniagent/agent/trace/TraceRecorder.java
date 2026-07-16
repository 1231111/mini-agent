package com.miniagent.agent.trace;

import com.miniagent.config.entity.AgentTraceStep;
import com.miniagent.config.repository.AgentTraceStepRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 智能体执行轨迹记录器。
 * 在 AgentLoop 的关键节点调用，将每一步执行持久化到数据库。
 * 每次 Agent 执行（即每个用户问题）有唯一的 executionId。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TraceRecorder {

    private final AgentTraceStepRepository repo;

    /** 当前执行上下文（每次 agentLoop.run 开始时设置） */
    private final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
    private final ThreadLocal<String> currentExecutionId = new ThreadLocal<>();
    private final ThreadLocal<String> currentQuestion = new ThreadLocal<>();

    /** 开始一次新的执行，设置 executionId 和用户问题 */
    public void beginExecution(String sessionId, String executionId, String userQuestion) {
        currentSessionId.set(sessionId);
        currentExecutionId.set(executionId);
        currentQuestion.set(userQuestion);
        log.debug("TraceRecorder 开始执行: executionId={}, question={}", executionId, userQuestion);
    }

    /** 结束当前执行，清理上下文 */
    public void endExecution() {
        currentSessionId.remove();
        currentExecutionId.remove();
        currentQuestion.remove();
    }

    /** 记录一轮迭代开始 */
    public void recordTurnStart(String sessionId, int turn, String subGoalText) {
        save(build(sessionId, turn, "PLAN", null, null,
                subGoalText != null ? "子目标: " + subGoalText : "开始第 " + (turn + 1) + " 轮迭代",
                subGoalText, 0, 0, "RUNNING", 0));
    }

    /** 记录工具调用 */
    public void recordToolCall(String sessionId, int turn, String toolName, String args) {
        save(build(sessionId, turn, "TOOL_CALL", toolName, truncate(args, 4000),
                null, null, 0, 0, "RUNNING", 0));
    }

    /** 记录工具执行结果 */
    public void recordToolResult(String sessionId, int turn, String toolName, String result, long durationMs, String status) {
        save(build(sessionId, turn, "TOOL_RESULT", toolName, null,
                truncate(result, 8000), null, 0, 0, status, durationMs));
    }

    /** 记录模型思考 / LLM 请求 / LLM 响应 */
    public void recordThinking(String sessionId, int turn, String text) {
        save(build(sessionId, turn, "THINKING", null, null,
                truncate(text, 8000), null, 0, 0, "SUCCESS", 0));
    }

    /** 记录子目标变化 */
    public void recordSubGoal(String sessionId, int turn, String text, int done, int total) {
        save(build(sessionId, turn, "SUB_GOAL", null, null,
                null, text, done, total, "RUNNING", 0));
    }

    /** 记录最终答案 */
    public void recordAnswer(String sessionId, int turn, String answer) {
        save(build(sessionId, turn, "ANSWER", null, null,
                truncate(answer, 8000), null, 0, 0, "SUCCESS", 0));
    }

    /** 记录循环结束 */
    public void recordLoopEnd(String sessionId, int turn, String status, Long tokenIn, Long tokenOut) {
        AgentTraceStep step = build(sessionId, turn, "LOOP_END", null, null,
                "循环结束: " + status, null, 0, 0, status, 0);
        step.setTokenInput(tokenIn);
        step.setTokenOutput(tokenOut);
        save(step);
    }

    /** 记录错误 */
    public void recordError(String sessionId, int turn, String error) {
        save(build(sessionId, turn, "ERROR", null, null,
                truncate(error, 4000), null, 0, 0, "FAILURE", 0));
    }

    /** 记录 LLM 调用耗时 */
    public void recordLlmLatency(String sessionId, int turn, long durationMs, int inputTokens, int outputTokens) {
        save(build(sessionId, turn, "LLM_CALL", null, null,
                String.format("耗时 %dms, 输入 %d tokens, 输出 %d tokens", durationMs, inputTokens, outputTokens),
                null, 0, 0, "SUCCESS", durationMs));
    }

    /** 记录上下文压缩事件 */
    public void recordCompression(String sessionId, int turn, int beforeMsgCount, int afterMsgCount, int beforeTokens, int afterTokens) {
        String content = String.format("上下文压缩: %d→%d 条消息, %d→%d tokens (节省 %.1f%%)",
                beforeMsgCount, afterMsgCount, beforeTokens, afterTokens,
                beforeTokens > 0 ? (double)(beforeTokens - afterTokens) / beforeTokens * 100 : 0);
        save(build(sessionId, turn, "COMPRESSION", null, null,
                content, null, 0, 0, "SUCCESS", 0));
    }

    /** 记录 agent 决策推理（模型在选择工具前的思考文本） */
    public void recordDecision(String sessionId, int turn, String reasoning) {
        if (reasoning == null || reasoning.isBlank()) return;
        save(build(sessionId, turn, "DECISION", null, null,
                truncate(reasoning, 4000), null, 0, 0, "SUCCESS", 0));
    }

    // ---- internal ----

    private AgentTraceStep build(String sessionId, int turn, String stepType,
                                  String toolName, String toolArgs, String content,
                                  String subGoalText, int subGoalDone, int subGoalTotal,
                                  String status, long durationMs) {
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
        return s;
    }

    private void save(AgentTraceStep step) {
        try {
            repo.save(step);
        } catch (Exception e) {
            log.warn("TraceRecorder 写入失败: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "...(truncated)" : s;
    }
}
