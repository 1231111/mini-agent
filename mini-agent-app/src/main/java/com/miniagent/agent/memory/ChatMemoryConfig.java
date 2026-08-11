package com.miniagent.agent.memory;

import com.miniagent.agent.skill.SkillStore;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.storage.MediaStorage;
import com.miniagent.memory.AgentDataPaths;
import com.miniagent.memory.MemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

@Configuration
public class ChatMemoryConfig {

    @Value("${agent.chat-memory.max-messages:24}")
    private int maxMessages;

    private final Map<String, ChatMemory> memories = new ConcurrentHashMap<>();

    @Bean
    public MemoryStore memoryStore(AgentDataPaths dataPaths, VectorMemoryStore vectorMemoryStore) {
        MemoryStore store = new MemoryStore(dataPaths.memory());
        store.setVectorStore(vectorMemoryStore);
        store.loadFromDisk();
        return store;
    }

    @Bean
    public SkillStore skillStore(AgentDataPaths dataPaths) {
        return new SkillStore(dataPaths.skills());
    }

    public List<String> getAllSessionIds() {
        return new ArrayList<>(memories.keySet());
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider(DatabaseConversationStore conversationStore,
                                                 MediaStorage mediaStorage) {
        return sid -> {
            MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(maxMessages);
            DatabaseConversationStore.Conversation conv = conversationStore.get(sid);
            if (Objects.nonNull(conv) && Objects.nonNull(conv.messages) && !conv.messages.isEmpty()) {
                List<DatabaseConversationStore.Message> all = conv.messages;
                int from = Math.max(0, all.size() - maxMessages);
                while (from < all.size() && !"user".equals(all.get(from).role)) {
                    from++;
                }
                if (from >= all.size()) from = Math.max(0, all.size() - maxMessages);
                for (int i = from; i < all.size(); i++) {
                    DatabaseConversationStore.Message msg = all.get(i);
                    if (Objects.isNull(msg) || Objects.isNull(msg.content)) continue;
                    if ("user".equals(msg.role)) {
                        if (Objects.nonNull(msg.images) && !msg.images.isEmpty()) {
                            memory.add(rebuildMultimodalMessage(msg.content, msg.images, mediaStorage));
                        } else {
                            memory.add(UserMessage.from(msg.content));
                        }
                    } else if ("assistant".equals(msg.role)) {
                        memory.add(AiMessage.from(msg.content));
                    }
                }
            }
            memories.put(sid, memory);
            return memory;
        };
    }

    private UserMessage rebuildMultimodalMessage(String textContent, List<String> imagePaths,
                                                 MediaStorage mediaStorage) {
        List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
        if (StringUtils.isNotBlank(textContent)) {
            contents.add(dev.langchain4j.data.message.TextContent.from(textContent));
        }
        for (String imgPath : imagePaths) {
            try {
                java.nio.file.Path absPath = mediaStorage.resolve(imgPath);
                if (java.nio.file.Files.exists(absPath)) {
                    byte[] bytes = java.nio.file.Files.readAllBytes(absPath);
                    String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                    String mimeType = "image/png";
                    String lower = imgPath.toLowerCase();
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) mimeType = "image/jpeg";
                    else if (lower.endsWith(".webp")) mimeType = "image/webp";
                    else if (lower.endsWith(".gif")) mimeType = "image/gif";
                    contents.add(dev.langchain4j.data.message.ImageContent.from(
                            "data:" + mimeType + ";base64," + base64));
                } else {
                    contents.add(dev.langchain4j.data.message.TextContent.from("[图片已丢失: " + imgPath + "]"));
                }
            } catch (Exception e) {
                contents.add(dev.langchain4j.data.message.TextContent.from("[图片读取失败: " + imgPath + "]"));
            }
        }
        return UserMessage.from(contents);
    }

    @FunctionalInterface
    public interface ChatMemoryProvider {
        ChatMemory get(String sessionId);
    }
}
