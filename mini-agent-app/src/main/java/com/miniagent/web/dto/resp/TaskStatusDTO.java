package com.miniagent.web.dto.resp;

/** GET /api/task-status */
public record TaskStatusDTO(String sessionId, boolean running) {
}
