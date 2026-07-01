package com.miniagent.agent.memory;

import com.miniagent.agent.skill.SkillStore;
import com.miniagent.config.service.DatabaseConversationStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class ChatMemoryConfig {

    @Value("${agent.chat-memory.max-messages:24}")
    private int maxMessages;

    private final Map<String, ChatMemory> memories = new ConcurrentHashMap<>();

    // 项目根目录：优先从环境变量 MINI_AGENT_HOME 读取，否则用 user.dir（jar 所在目录）
    private static final Path PROJECT_ROOT = detectProjectRoot();
    private static final String APP_DIR = ".mini-agent";

    private static Path detectProjectRoot() {
        String env = System.getenv("MINI_AGENT_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    @Bean
    public MemoryStore memoryStore(VectorMemoryStore vectorMemoryStore) {
        Path memoryDir = PROJECT_ROOT.resolve("memory");
        MemoryStore store = new MemoryStore(memoryDir);
        store.setVectorStore(vectorMemoryStore);
        store.loadFromDisk();
        return store;
    }

    @Bean
    public SkillStore skillStore() {
        Path skillsDir = PROJECT_ROOT.resolve("skills");
        return new SkillStore(skillsDir);
    }

    // ConversationStore bean removed - using DatabaseConversationStore (Spring @Service) instead

    public List<String> getAllSessionIds() {
        return new ArrayList<>(memories.keySet());
    }

    /**
     * 会话级 ChatMemory：每次拿都从 {@link ConversationStore} 重建“最近 maxMessages 条”滑动窗口。
     *
     * 之前的实现只在 session 第一次创建时一次性回填历史，
     * 之后再没和 ConversationStore 同步，导致旧会话被打开时拿不到历史，
     * 模型只能调 memory 工具瞎找上下文，最终命中死循环。
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(DatabaseConversationStore conversationStore) {
        return sid -> {
            MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(maxMessages);
            DatabaseConversationStore.Conversation conv = conversationStore.get(sid);
            if (conv != null && conv.messages != null && !conv.messages.isEmpty()) {
                List<DatabaseConversationStore.Message> all = conv.messages;
                // 取最近 maxMessages 条，但对齐到完整对话轮次边界：
                // 从候选起点向后找第一条 user 消息作为窗口起点，避免以 assistant 半截开头。
                int from = Math.max(0, all.size() - maxMessages);
                while (from < all.size() && !"user".equals(all.get(from).role)) {
                    from++;
                }
                if (from >= all.size()) from = Math.max(0, all.size() - maxMessages); // 兜底：无 user 边界则退回原起点
                for (int i = from; i < all.size(); i++) {
                    DatabaseConversationStore.Message msg = all.get(i);
                    if (msg == null || msg.content == null) continue;
                    if ("user".equals(msg.role)) {
                        // 多模态历史：有图片路径时恢复为带图片的 UserMessage
                        if (msg.images != null && !msg.images.isEmpty()) {
                            memory.add(rebuildMultimodalMessage(msg.content, msg.images));
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

    /**
     * 从磁盘图片路径重建多模态 UserMessage。
     * 图片文件读取为 base64 data URL，注入 ImageContent。
     */
    private UserMessage rebuildMultimodalMessage(String textContent, List<String> imagePaths) {
        List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
        if (textContent != null && !textContent.isBlank()) {
            contents.add(dev.langchain4j.data.message.TextContent.from(textContent));
        }
        for (String imgPath : imagePaths) {
            try {
                java.nio.file.Path absPath = java.nio.file.Path.of(System.getProperty("user.dir")).resolve(imgPath);
                if (java.nio.file.Files.exists(absPath)) {
                    byte[] bytes = java.nio.file.Files.readAllBytes(absPath);
                    String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                    // 推断 MIME 类型
                    String mimeType = "image/png";
                    String lower = imgPath.toLowerCase();
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) mimeType = "image/jpeg";
                    else if (lower.endsWith(".webp")) mimeType = "image/webp";
                    else if (lower.endsWith(".gif")) mimeType = "image/gif";
                    String dataUrl = "data:" + mimeType + ";base64," + base64;
                    contents.add(dev.langchain4j.data.message.ImageContent.from(dataUrl));
                } else {
                    // 图片文件已删除，用文本占位
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
