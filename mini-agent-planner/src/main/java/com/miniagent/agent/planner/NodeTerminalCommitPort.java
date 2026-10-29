package com.miniagent.agent.planner;

public interface NodeTerminalCommitPort {
    void commit(String sessionId, long taskId, String actionId, TaskNode node, Result result);

    enum Result { SUCCESS, FAILURE }
}
