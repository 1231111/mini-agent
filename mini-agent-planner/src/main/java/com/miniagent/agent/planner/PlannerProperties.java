package com.miniagent.agent.planner;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.planner")
@Data
public class PlannerProperties {

    private boolean enabled = true;

    // ─── 专用规划模型（GoalCompiler DAG 编译用） ───
    /** 专用规划模型名称。为空时 fallback 到主对话模型。 */
    private String plannerModelName;
    /** 专用规划模型 base-url。为空时 fallback 到主对话模型。 */
    private String plannerBaseUrl;
    /** 专用规划模型 api-key。为空时 fallback 到主对话模型。 */
    private String plannerApiKey;
    /** 专用规划模型超时（秒）。默认 60s，比主模型短避免拖慢。 */
    private int plannerTimeoutSeconds = 60;

    private int maxRecoveries = 3;
    private int proposalBatchSize = 1;
    private int proposalMaxIterations = 8;
    /** 本步工具含 browser_* 时的每段轮次上限 */
    private int proposalBrowserMaxIterations = 16;
    /** 同一节点配额用尽后最多续跑几段（含第一段） */
    private int proposalMaxChunks = 6;
    private int compilerRetry = 1;
    private int maxOuterRounds = 24;
    /** Proposal 硬闸门：锁定工具面 + 禁止改其它 todo */
    private boolean hardProposal = true;
    /** 步骤验收收紧：禁止宽松放行 */
    private boolean strictEval = true;
    private int maxLocalRepair = 3;
    private int maxReplaceTool = 2;
    private int maxRewriteGraph = 2;
    private int maxReviseGoal = 1;
    /** 验证失败后 replan 重试次数 */
    private int maxReplanRetries = 2;
    /** 节点级工具超时上限（秒）。0=不额外封顶，沿用工具自身 timeout。 */
    private int actionTimeoutSeconds = 0;
    /** 专用评判模型。为空时 llm_judge 走主对话模型。 */
    private String judgeModelName;
    private String judgeBaseUrl;
    private String judgeApiKey;
    private int judgeTimeoutSeconds = 60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxRecoveries() { return maxRecoveries; }
    public void setMaxRecoveries(int maxRecoveries) { this.maxRecoveries = maxRecoveries; }

    public int getProposalBatchSize() { return proposalBatchSize; }
    public void setProposalBatchSize(int proposalBatchSize) {
        this.proposalBatchSize = proposalBatchSize;
    }

    public int getProposalMaxIterations() { return proposalMaxIterations; }
    public void setProposalMaxIterations(int proposalMaxIterations) {
        this.proposalMaxIterations = proposalMaxIterations;
    }

    public int getProposalBrowserMaxIterations() { return proposalBrowserMaxIterations; }
    public void setProposalBrowserMaxIterations(int proposalBrowserMaxIterations) {
        this.proposalBrowserMaxIterations = proposalBrowserMaxIterations;
    }

    public int getProposalMaxChunks() { return proposalMaxChunks; }
    public void setProposalMaxChunks(int proposalMaxChunks) {
        this.proposalMaxChunks = proposalMaxChunks;
    }

    public int getCompilerRetry() { return compilerRetry; }
    public void setCompilerRetry(int compilerRetry) { this.compilerRetry = compilerRetry; }

    public int getMaxOuterRounds() { return maxOuterRounds; }
    public void setMaxOuterRounds(int maxOuterRounds) { this.maxOuterRounds = maxOuterRounds; }

    public boolean isHardProposal() { return hardProposal; }
    public void setHardProposal(boolean hardProposal) { this.hardProposal = hardProposal; }

    public boolean isStrictEval() { return strictEval; }
    public void setStrictEval(boolean strictEval) { this.strictEval = strictEval; }

    public int getMaxLocalRepair() { return maxLocalRepair; }
    public void setMaxLocalRepair(int maxLocalRepair) { this.maxLocalRepair = maxLocalRepair; }

    public int getMaxReplaceTool() { return maxReplaceTool; }
    public void setMaxReplaceTool(int maxReplaceTool) { this.maxReplaceTool = maxReplaceTool; }

    public int getMaxRewriteGraph() { return maxRewriteGraph; }
    public void setMaxRewriteGraph(int maxRewriteGraph) { this.maxRewriteGraph = maxRewriteGraph; }

    public int getMaxReviseGoal() { return maxReviseGoal; }
    public void setMaxReviseGoal(int maxReviseGoal) { this.maxReviseGoal = maxReviseGoal; }

    public int getMaxReplanRetries() { return maxReplanRetries; }
    public void setMaxReplanRetries(int maxReplanRetries) { this.maxReplanRetries = maxReplanRetries; }

    public int getActionTimeoutSeconds() { return actionTimeoutSeconds; }
    public void setActionTimeoutSeconds(int actionTimeoutSeconds) {
        this.actionTimeoutSeconds = Math.max(0, actionTimeoutSeconds);
    }

    public String getJudgeModelName() { return judgeModelName; }
    public void setJudgeModelName(String judgeModelName) {
        this.judgeModelName = judgeModelName;
    }

    public String getJudgeBaseUrl() { return judgeBaseUrl; }
    public void setJudgeBaseUrl(String judgeBaseUrl) { this.judgeBaseUrl = judgeBaseUrl; }

    public String getJudgeApiKey() { return judgeApiKey; }
    public void setJudgeApiKey(String judgeApiKey) { this.judgeApiKey = judgeApiKey; }

    public int getJudgeTimeoutSeconds() { return judgeTimeoutSeconds; }
    public void setJudgeTimeoutSeconds(int judgeTimeoutSeconds) {
        this.judgeTimeoutSeconds = judgeTimeoutSeconds;
    }
}
