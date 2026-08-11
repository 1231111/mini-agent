package com.miniagent.agent.trace;

import org.springframework.beans.factory.annotation.Autowired;

import com.miniagent.config.entity.AgentTraceStep;
import com.miniagent.config.repository.AgentTraceStepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 智能体全节点轨迹：意图漏斗 / TaskPlan / 权限 / Todo / 工具 / LLM / 收尾。
 * 每次用户问题一个 executionId（ThreadLocal，主路径 begin，AgentLoop 复用）。
 */
@Service
@Slf4j
public class TraceRecorder {

    @Autowired
    private AgentTraceStepRepository repo;

    private final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
    private final ThreadLocal<String> currentExecutionId = new ThreadLocal<>();
    private final ThreadLocal<String> currentQuestion = new ThreadLocal<>();

    public void beginExecution(String sessionId, String executionId, String userQuestion) {
        currentSessionId.set(sessionId);
        currentExecutionId.set(executionId);
        currentQuestion.set(userQuestion);
        log.debug("TraceRecorder 开始执行: executionId={}, question={}", executionId, userQuestion);
        recordNode("RUN_START",
                "{\"executionId\":\"" + executionId + "\",\"question\":"
                        + jsonStr(truncate(userQuestion, 500)) + "}",
                "RUNNING", 0);
    }

    /** 若当前线程尚无 execution，则创建；否则复用（意图漏斗与 AgentLoop 同 ID） */
    public String ensureExecution(String sessionId, String userQuestion) {
        if (isActive()) return currentExecutionId.get();
        String id = "exec_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().substring(0, 8);
        beginExecution(sessionId, id, userQuestion);
        return id;
    }

    public void endExecution(String status) {
        if (isActive()) {
            recordNode("RUN_END", "{\"status\":\"" + (status == null ? "UNKNOWN" : status) + "\"}",
                    status == null ? "SUCCESS" : status, 0);
        }
        currentSessionId.remove();
        currentExecutionId.remove();
        currentQuestion.remove();
    }

    public void endExecution() {
        endExecution("SUCCESS");
    }

    public boolean isActive() {
        return StringUtils.isNotBlank(currentExecutionId.get());
    }

    public String currentExecutionId() { return currentExecutionId.get(); }
    public String currentSessionId() { return currentSessionId.get(); }

    public void recordTurnStart(String sessionId, int turn, String subGoalText) {
        save(build(sessionId, turn, "PLAN", null, null,
                Objects.nonNull(subGoalText) ? "子目标: " + subGoalText : "开始第 " + (turn + 1) + " 轮迭代",
                subGoalText, 0, 0, "RUNNING", 0));
    }

    public void recordToolCall(String sessionId, int turn, String toolName, String args) {
        save(build(sessionId, turn, "TOOL_CALL", toolName, truncate(args, 4000),
                null, null, 0, 0, "RUNNING", 0));
    }

    public void recordToolResult(String sessionId, int turn, String toolName, String result, long durationMs, String status) {
        save(build(sessionId, turn, "TOOL_RESULT", toolName, null,
                truncate(result, 8000), null, 0, 0, status, durationMs));
    }

    public void recordThinking(String sessionId, int turn, String text) {
        save(build(sessionId, turn, "THINKING", null, null,
                truncate(text, 8000), null, 0, 0, "SUCCESS", 0));
    }

    public void recordSubGoal(String sessionId, int turn, String text, int done, int total) {
        save(build(sessionId, turn, "SUB_GOAL", null, null,
                null, text, done, total, "RUNNING", 0));
    }

    public void recordAnswer(String sessionId, int turn, String answer) {
        save(build(sessionId, turn, "ANSWER", null, null,
                truncate(answer, 8000), null, 0, 0, "SUCCESS", 0));
    }

    public void recordLoopEnd(String sessionId, int turn, String status, Long tokenIn, Long tokenOut) {
        AgentTraceStep step = build(sessionId, turn, "LOOP_END", null, null,
                "循环结束: " + status, null, 0, 0, status, 0);
        step.setTokenInput(tokenIn);
        step.setTokenOutput(tokenOut);
        save(step);
    }

    public void recordError(String sessionId, int turn, String error) {
        save(build(sessionId, turn, "ERROR", null, null,
                truncate(error, 4000), null, 0, 0, "FAILURE", 0));
    }

    public void recordLlmLatency(String sessionId, int turn, long durationMs, int inputTokens, int outputTokens) {
        save(build(sessionId, turn, "LLM_CALL", null, null,
                String.format("耗时 %dms, 输入 %d tokens, 输出 %d tokens", durationMs, inputTokens, outputTokens),
                null, 0, 0, "SUCCESS", durationMs));
    }

    public void recordCompression(String sessionId, int turn, int beforeMsgCount, int afterMsgCount, int beforeTokens, int afterTokens) {
        String content = String.format("上下文压缩: %d→%d 条消息, %d→%d tokens (节省 %.1f%%)",
                beforeMsgCount, afterMsgCount, beforeTokens, afterTokens,
                beforeTokens > 0 ? (double)(beforeTokens - afterTokens) / beforeTokens * 100 : 0);
        save(build(sessionId, turn, "COMPRESSION", null, null,
                content, null, 0, 0, "SUCCESS", 0));
    }

    public void recordDecision(String sessionId, int turn, String reasoning) {
        if (StringUtils.isBlank(reasoning)) return;
        save(build(sessionId, turn, "DECISION", null, null,
                truncate(reasoning, 4000), null, 0, 0, "SUCCESS", 0));
    }

    /** 通用节点：INTENT_L0/L1/L2、TASK_PLAN、PERM_DENY、TODO_SEED、SUBAGENT_*、RUN_* */
    public void recordNode(String stepType, String content, String status, long durationMs) {
        String sessionId = currentSessionId.get();
        if (StringUtils.isBlank(sessionId)) return;
        save(build(sessionId, 0, stepType, null, null,
                truncate(content, 8000), null, 0, 0,
                StringUtils.isBlank(status) ? "SUCCESS" : status, durationMs));
    }

    public void recordNode(String sessionId, int turn, String stepType, String content, String status, long durationMs) {
        save(build(sessionId, turn, stepType, null, null,
                truncate(content, 8000), null, 0, 0,
                StringUtils.isBlank(status) ? "SUCCESS" : status, durationMs));
    }

    private AgentTraceStep build(String sessionId, int turn, String stepType,
                                  String toolName, String toolArgs, String content,
                                  String subGoalText, int subGoalDone, int subGoalTotal,
                                  String status, long durationMs) {
        if (!TraceStepType.isKnownPersisted(stepType)) {
            log.warn("未知 Trace 节点码（未登记在 TraceStepType）: {}", stepType);
        }
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
            if (StringUtils.isBlank(step.getExecutionId())) {
                // 无 execution 上下文时仍写 session，便于排查
                step.setExecutionId("orphan_" + System.currentTimeMillis());
            }
            repo.save(step);
        } catch (Exception e) {
            log.warn("TraceRecorder 写入失败: {}", e.getMessage());
        }
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
