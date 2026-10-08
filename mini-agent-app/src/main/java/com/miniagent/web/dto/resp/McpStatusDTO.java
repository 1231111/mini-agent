package com.miniagent.web.dto.resp;

import java.util.List;

/** GET /api/mcp/status */
public record McpStatusDTO(
        boolean enabled,
        int serverCount,
        List<String> registeredTools,
        int toolCount) {
}
