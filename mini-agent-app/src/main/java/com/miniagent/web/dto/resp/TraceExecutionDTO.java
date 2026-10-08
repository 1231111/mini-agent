package com.miniagent.web.dto.resp;

import java.time.LocalDateTime;

/** GET /api/traces/executions 的一条 */
public record TraceExecutionDTO(
        String executionId,
        String sessionId,
        String userQuestion,
        String answerSummary,
        int stepCount,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String status) {
}
