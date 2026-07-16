package com.miniagent.config.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_trace_steps", indexes = {
    @Index(name = "idx_trace_session_turn", columnList = "sessionId,turnIndex"),
    @Index(name = "idx_trace_execution", columnList = "executionId")
})
public class AgentTraceStep {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;
    /** 每次 Agent 执行的唯一标识，用于区分同一 session 中的不同任务 */
    @Column(name = "execution_id", nullable = false, length = 60)
    private String executionId;
    /** 用户的问题/指令 */
    @Column(name = "user_question", columnDefinition = "TEXT")
    private String userQuestion;
    @Column(name = "turn_index", nullable = false)
    private int turnIndex;
    /** 类型：PLAN / TOOL_CALL / TOOL_RESULT / THINKING / ANSWER / SUB_GOAL / ERROR / LOOP_END */
    @Column(name = "step_type", nullable = false, length = 30)
    private String stepType;
    /** 工具名（仅 TOOL_CALL / TOOL_RESULT） */
    @Column(name = "tool_name", length = 100)
    private String toolName;
    /** 工具参数 JSON（仅 TOOL_CALL） */
    @Column(name = "tool_args", columnDefinition = "TEXT")
    private String toolArgs;
    /** 内容：思考文本 / 工具结果 / 答案文本 */
    @Column(columnDefinition = "LONGTEXT")
    private String content;
    /** 子目标文本（仅 SUB_GOAL） */
    @Column(name = "sub_goal_text", columnDefinition = "TEXT")
    private String subGoalText;
    @Column(name = "sub_goal_done")
    private int subGoalDone;
    @Column(name = "sub_goal_total")
    private int subGoalTotal;
    /** 状态：RUNNING / SUCCESS / FAILURE / TIMEOUT */
    @Column(length = 20)
    private String status;
    /** 耗时毫秒（仅 TOOL_RESULT） */
    @Column(name = "duration_ms")
    private long durationMs;
    @Column(name = "token_input")
    private Long tokenInput;
    @Column(name = "token_output")
    private Long tokenOutput;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public String getUserQuestion() { return userQuestion; }
    public void setUserQuestion(String userQuestion) { this.userQuestion = userQuestion; }
    public int getTurnIndex() { return turnIndex; }
    public void setTurnIndex(int turnIndex) { this.turnIndex = turnIndex; }
    public String getStepType() { return stepType; }
    public void setStepType(String stepType) { this.stepType = stepType; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getToolArgs() { return toolArgs; }
    public void setToolArgs(String toolArgs) { this.toolArgs = toolArgs; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSubGoalText() { return subGoalText; }
    public void setSubGoalText(String subGoalText) { this.subGoalText = subGoalText; }
    public int getSubGoalDone() { return subGoalDone; }
    public void setSubGoalDone(int subGoalDone) { this.subGoalDone = subGoalDone; }
    public int getSubGoalTotal() { return subGoalTotal; }
    public void setSubGoalTotal(int subGoalTotal) { this.subGoalTotal = subGoalTotal; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public Long getTokenInput() { return tokenInput; }
    public void setTokenInput(Long tokenInput) { this.tokenInput = tokenInput; }
    public Long getTokenOutput() { return tokenOutput; }
    public void setTokenOutput(Long tokenOutput) { this.tokenOutput = tokenOutput; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
