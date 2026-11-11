package com.miniagent.web.dto.resp;

/** GET /api/conversations/{sessionId}/task */
public record TaskStatusDTO(
        String sessionId,
        boolean running,
        boolean pauseRequested,
        boolean pausedAtCheckpoint) {

    public TaskStatusDTO(String sessionId, boolean running) {
        this(sessionId, running, false, false);
    }
}
