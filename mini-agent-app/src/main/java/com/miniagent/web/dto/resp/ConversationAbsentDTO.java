package com.miniagent.web.dto.resp;

/** GET /api/conversation 查无此会话 */
public record ConversationAbsentDTO(boolean exists, String sessionId) {
}
