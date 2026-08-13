package com.miniagent.memory.model;

/**
 * Agent 上下文：传给 MemoryManager.buildContext() 的请求参数。
 */
public class AgentContext {
    private String tenantId;
    private String userId;
    private String projectId;
    private String sessionId;
    private String goal;
    private String currentState;

    public AgentContext() {}

    // --- getters/setters ---

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }

    public String getCurrentState() { return currentState; }
    public void setCurrentState(String currentState) { this.currentState = currentState; }
}
