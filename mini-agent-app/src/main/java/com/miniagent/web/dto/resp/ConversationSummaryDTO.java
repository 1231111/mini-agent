package com.miniagent.web.dto.resp;

/** GET /api/conversations 的一条 */
public record ConversationSummaryDTO(String sessionId, String title, String updatedAt) {
}
