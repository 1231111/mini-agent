package com.miniagent.web.dto.resp;

import java.util.List;

/** GET /api/traces/summary */
public record TraceSummaryDTO(
        long totalSteps,
        long totalTurns,
        long totalDurationMs,
        List<ToolStat> tools,
        List<SlowStep> slowestSteps) {

    public record ToolStat(String name, long count, long avgDurationMs) {
    }

    public record SlowStep(String stepType, String toolName, Number durationMs) {
    }
}
