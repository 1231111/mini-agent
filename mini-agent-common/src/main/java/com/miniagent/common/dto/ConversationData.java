package com.miniagent.common.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话数据共享 DTO。消除 ConversationStore 和 DatabaseConversationStore 中的重复内部类。
 */
public final class ConversationData {

    private ConversationData() {}

    public static class Conversation {
        public String id;
        public String title;
        public long createdAt;
        public long updatedAt;
        public List<Message> messages = new ArrayList<>();
    }

    public static class Message {
        public String role;      // "user" | "assistant"
        public String content;
        public long timestamp;
        /** 图片路径列表 */
        public List<String> images;
    }

    public static class ConversationSummary {
        public String id;
        public String title;
        public long createdAt;
        public long updatedAt;
        public long messageCount;

        public ConversationSummary() {}

        public ConversationSummary(String id, String title, long createdAt, long updatedAt, long messageCount) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.messageCount = messageCount;
        }
    }
}
