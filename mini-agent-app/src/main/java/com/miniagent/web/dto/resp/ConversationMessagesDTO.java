package com.miniagent.web.dto.resp;

import java.util.List;

/** GET /api/conversation/messages */
public record ConversationMessagesDTO(List<Task> tasks, boolean hasMore) {

    public record Task(
            Long id,
            String question,
            String answer,
            String createdAt,
            List<String> images) {
    }
}
