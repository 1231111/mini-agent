package com.miniagent.agent.planner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "agent.planner")
public class PlannerProperties {

    private boolean enabled = true;
    private List<String> forceForIntents = new ArrayList<>(List.of(
            "NEW_TASK", "RESEARCH", "FILE_DELIVERY", "PUBLISHING", "CONTINUE_TASK"));
    private List<String> skipIntents = new ArrayList<>(List.of("QUESTION", "REVIEW"));
    private int maxRecoveries = 3;
    private int proposalBatchSize = 1;
    private int proposalMaxIterations = 8;
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

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getForceForIntents() { return forceForIntents; }
    public void setForceForIntents(List<String> forceForIntents) {
        this.forceForIntents = forceForIntents == null ? List.of() : forceForIntents;
    }

    public List<String> getSkipIntents() { return skipIntents; }
    public void setSkipIntents(List<String> skipIntents) {
        this.skipIntents = skipIntents == null ? List.of() : skipIntents;
    }

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
}
