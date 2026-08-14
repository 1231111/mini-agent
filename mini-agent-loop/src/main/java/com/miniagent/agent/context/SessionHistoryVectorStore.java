package com.miniagent.agent.context;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/**
 * 会话历史向量：写入时持久化，指代时按 session 检索。
 */
public interface SessionHistoryVectorStore {

    boolean isEnabled();

    void upsertMessage(String sessionId, String role, String text);

    default void upsertTurn(String sessionId, String userText, String assistantText) {
        upsertMessage(sessionId, "user", userText);
        upsertMessage(sessionId, "assistant", assistantText);
    }

    /** 旧会话无索引时整窗回填（同步）。 */
    default void backfill(String sessionId, List<ChatMessage> messages) {
        if (!isEnabled() || sessionId == null || messages == null) return;
        for (ChatMessage m : messages) {
            if (m instanceof UserMessage um) {
                String t = com.miniagent.common.ChatMessageTexts.userPlain(um);
                if (t != null && !t.isBlank()) upsertMessage(sessionId, "user", t);
            } else if (m instanceof AiMessage am) {
                String t = am.text();
                if (t != null && !t.isBlank()) upsertMessage(sessionId, "assistant", t);
            }
        }
    }

    List<Hit> search(String sessionId, String query, int topK, double minScore);

    void deleteSession(String sessionId);

    record Hit(long seq, String role, String text, double score) {}
}
