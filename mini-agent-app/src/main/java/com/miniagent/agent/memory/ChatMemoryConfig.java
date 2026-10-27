package com.miniagent.agent.memory;

import com.miniagent.agent.skill.SkillStore;
import com.miniagent.agent.web.MultimodalMedia;
import com.miniagent.common.ChatRole;
import com.miniagent.common.event.SessionEvictedEvent;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.storage.MediaStorage;
import com.miniagent.common.io.AgentDataPaths;
import com.miniagent.memory.MemoryBlobStore;
import com.miniagent.memory.MemoryStore;
import com.miniagent.memory.MemoryVectorIndex;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

@Configuration
public class ChatMemoryConfig {

    @Value("${agent.chat-memory.max-messages:64}")
    private int maxMessages;

    private final Map<String, ChatMemory> memories = new ConcurrentHashMap<>();
    /** 最后一次取用时间：判定"这个会话还用不用得上"，是空闲回收的唯一依据。 */
    private final Map<String, Long> lastAccess = new ConcurrentHashMap<>();

    @Bean
    public MemoryStore memoryStore(AgentDataPaths dataPaths,
                                   @Autowired(required = false) MemoryVectorIndex vectorIndex,
                                   @Autowired(required = false) MemoryBlobStore blobStore) {
        MemoryStore store = new MemoryStore(dataPaths.memory());
        if (vectorIndex != null) {
            store.setVectorStore(vectorIndex);
        }
        if (blobStore != null) {
            store.setBlobStore(blobStore);
        }
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

    /**
     * 释放某个会话的进程内状态。
     *
     * <p><b>为什么需要它</b>：{@code memories} 只在 chatMemoryProvider 里 {@code put}，
     * 从来没有 {@code remove} —— 桌面客户端点 × 只是隐藏到托盘、进程常年不重启，
     * 于是每聊过一个会话就多留一份消息窗口，内存单调上涨直到被系统杀掉。</p>
     *
     * <p>释放是安全的：记忆窗口本来就在 chatMemoryProvider 里按需从
     * {@code DatabaseConversationStore} 重建，丢的是缓存不是内容。</p>
     */
    public boolean evict(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return false;
        }
        String sid = sessionId.trim();
        lastAccess.remove(sid);
        return memories.remove(sid) != null;
    }

    /** 空闲达到 {@code ttlMillis} 的会话；没有任何活动记录的按"从未取用"处理，同样算空闲。 */
    public List<String> idleSessions(long nowMillis, long ttlMillis) {
        List<String> idle = new ArrayList<>();
        for (String sid : memories.keySet()) {
            Long last = lastAccess.get(sid);
            if (last == null || nowMillis - last >= ttlMillis) {
                idle.add(sid);
            }
        }
        return idle;
    }

    /** 会话被删除或判定空闲时，清掉它的记忆窗口。 */
    @EventListener
    public void onSessionEvicted(SessionEvictedEvent event) {
        evict(event.sessionId());
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
                while (from < all.size() && !ChatRole.USER.getValue().equals(all.get(from).role)) {
                    from++;
                }
                if (from >= all.size()) {
                    from = Math.max(0, all.size() - maxMessages);
                }
                for (int i = from; i < all.size(); i++) {
                    DatabaseConversationStore.Message msg = all.get(i);
                    if (Objects.isNull(msg) || Objects.isNull(msg.content)) {
                        continue;
                    }
                    if (ChatRole.USER.getValue().equals(msg.role)) {
                        if (Objects.nonNull(msg.images) && !msg.images.isEmpty()) {
                            memory.add(rebuildMultimodalMessage(msg.content, msg.images, mediaStorage));
                        } else {
                            memory.add(UserMessage.from(msg.content));
                        }
                    } else if (ChatRole.ASSISTANT.getValue().equals(msg.role)) {
                        memory.add(AiMessage.from(msg.content));
                    } else if (ChatRole.SYSTEM.getValue().equals(msg.role)) {
                        memory.add(SystemMessage.from(msg.content));
                    }
                }
            }
            memories.put(sid, memory);
            lastAccess.put(sid, System.currentTimeMillis());
            return memory;
        };
    }

    private UserMessage rebuildMultimodalMessage(String textContent, List<String> imagePaths,
                                                 MediaStorage mediaStorage) {
        List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
        if (StringUtils.isNotBlank(textContent)) {
            contents.add(dev.langchain4j.data.message.TextContent.from(textContent));
        }
        for (String mediaPath : imagePaths) {
            try {
                java.nio.file.Path absPath = mediaStorage.resolve(mediaPath);
                if (!java.nio.file.Files.exists(absPath)) {
                    contents.add(dev.langchain4j.data.message.TextContent.from(
                            "[媒体已丢失: " + mediaPath + "]"));
                    continue;
                }
                byte[] bytes = java.nio.file.Files.readAllBytes(absPath);
                String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                String lower = mediaPath.toLowerCase();
                String kind = MultimodalMedia.kindOf(mediaPath, null);
                if (MultimodalMedia.KIND_AUDIO.equals(kind)) {
                    String mime = MultimodalMedia.mimeOf(mediaPath, null, kind);
                    contents.add(dev.langchain4j.data.message.AudioContent.from(base64, mime));
                } else if (MultimodalMedia.KIND_VIDEO.equals(kind)) {
                    String mime = MultimodalMedia.mimeOf(mediaPath, null, kind);
                    contents.add(dev.langchain4j.data.message.VideoContent.from(base64, mime));
                } else {
                    String mimeType = "image/png";
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                        mimeType = "image/jpeg";
                    }
                    else if (lower.endsWith(".webp")) {
                        mimeType = "image/webp";
                    }
                    else if (lower.endsWith(".gif")) {
                        mimeType = "image/gif";
                    }
                    contents.add(dev.langchain4j.data.message.ImageContent.from(
                            "data:" + mimeType + ";base64," + base64));
                }
            } catch (Exception e) {
                contents.add(dev.langchain4j.data.message.TextContent.from(
                        "[媒体读取失败: " + mediaPath + "]"));
            }
        }
        return UserMessage.from(contents);
    }

    @FunctionalInterface
    public interface ChatMemoryProvider {
        ChatMemory get(String sessionId);
    }
}
