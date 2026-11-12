package com.miniagent.web.dto.resp;

import java.util.List;
import com.miniagent.application.TaskProgressSnapshot;

/** GET /api/conversation/messages */
public record ConversationMessagesDTO(List<Task> tasks, boolean hasMore) {

    public record Task(
            Long id,
            String question,
            String answer,
            String createdAt,
            List<String> images,
            TaskProgressSnapshot progress) {
        public Task(Long id, String question, String answer, String createdAt, List<String> images) {
            this(id, question, answer, createdAt, images, null);
        }
    }
}
