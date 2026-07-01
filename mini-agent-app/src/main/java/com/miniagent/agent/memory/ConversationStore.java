package com.miniagent.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 会话持久化存储 — 每个会话一个 JSON 文件，保存在 ~/.hermes/conversations/ 下
 *
 * 数据模型：
 *   {id, title, createdAt, updatedAt, messages: [{role, content, timestamp}]}
 *
 * 设计参考 ChatGPT WebUI：
 *   - 侧边栏显示历史会话列表（按时间分组：今天/昨天/更早）
 *   - 点击切换会话，恢复完整消息历史
 *   - 新建会话自动保存当前会话
 *   - 支持重命名、删除
 */
public class ConversationStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Path conversationsDir;
    private final Map<String, Conversation> cache = new ConcurrentHashMap<>();

    public ConversationStore(Path conversationsDir) {
        this.conversationsDir = conversationsDir;
    }

    /** 启动时从磁盘加载所有会话到内存缓存 */
    public void loadFromDisk() {
        try {
            Files.createDirectories(conversationsDir);
            try (var stream = Files.list(conversationsDir)) {
                stream.filter(p -> p.toString().endsWith(".json"))
                      .forEach(this::loadOne);
            }
        } catch (IOException e) {
            // 目录不存在等，忽略
        }
    }

    private void loadOne(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            Conversation conv = MAPPER.readValue(bytes, Conversation.class);
            cache.put(conv.id, conv);
        } catch (Exception e) {
            // 损坏的文件跳过
        }
    }

    // =========================================================================
    // CRUD
    // =========================================================================

    /** 创建新会话，返回会话对象 */
    public Conversation create(String id, String title) {
        Conversation conv = new Conversation();
        conv.id = id;
        conv.title = (title == null || title.isBlank()) ? "新对话" : title.trim();
        conv.createdAt = Instant.now().toEpochMilli();
        conv.updatedAt = conv.createdAt;
        conv.messages = new ArrayList<>();
        cache.put(id, conv);
        saveToDisk(conv);
        return conv;
    }

    /** 获取会话（不存在返回 null） */
    public Conversation get(String id) {
        return cache.get(id);
    }

    /** 列表（按 updatedAt 降序） */
    public List<ConversationSummary> list() {
        return cache.values().stream()
                .sorted((a, b) -> Long.compare(b.updatedAt, a.updatedAt))
                .map(c -> new ConversationSummary(c.id, c.title, c.createdAt, c.updatedAt,
                        c.messages == null ? 0 : c.messages.size()))
                .collect(Collectors.toList());
    }

    /** 追加消息 */
    public void addMessage(String id, String role, String content) {
        Conversation conv = cache.get(id);
        if (conv == null) return;
        if (conv.messages == null) conv.messages = new ArrayList<>();
        Message msg = new Message();
        msg.role = role;
        msg.content = content;
        msg.timestamp = Instant.now().toEpochMilli();
        conv.messages.add(msg);
        conv.updatedAt = Instant.now().toEpochMilli();
        // 自动用第一条用户消息作为标题
        if ("user".equals(role) && conv.messages.stream().filter(m -> "user".equals(m.role)).count() == 1) {
            String autoTitle = content.length() > 40 ? content.substring(0, 40) + "…" : content;
            conv.title = autoTitle.replaceAll("[\\r\\n]", " ").trim();
        }
        saveToDisk(conv);
    }

    /** 追加消息（带图片） */
    public void addMessageWithImages(String id, String role, String content, List<String> imagePaths) {
        Conversation conv = cache.get(id);
        if (conv == null) return;
        if (conv.messages == null) conv.messages = new ArrayList<>();
        Message msg = new Message();
        msg.role = role;
        msg.content = content;
        msg.timestamp = Instant.now().toEpochMilli();
        msg.images = (imagePaths == null || imagePaths.isEmpty()) ? null : new ArrayList<>(imagePaths);
        conv.messages.add(msg);
        conv.updatedAt = Instant.now().toEpochMilli();
        if ("user".equals(role) && conv.messages.stream().filter(m -> "user".equals(m.role)).count() == 1) {
            String autoTitle = content.length() > 40 ? content.substring(0, 40) + "…" : content;
            conv.title = autoTitle.replaceAll("[\r\n]", " ").trim();
        }
        saveToDisk(conv);
    }

    /** 重命名 */
    public boolean rename(String id, String newTitle) {
        Conversation conv = cache.get(id);
        if (conv == null) return false;
        conv.title = newTitle.trim();
        conv.updatedAt = Instant.now().toEpochMilli();
        saveToDisk(conv);
        return true;
    }

    /** 删除 */
    public boolean delete(String id) {
        Conversation removed = cache.remove(id);
        if (removed == null) return false;
        try {
            Files.deleteIfExists(conversationsDir.resolve(id + ".json"));
        } catch (IOException ignored) {}
        return true;
    }

    /** 会话是否存在 */
    public boolean exists(String id) {
        return cache.containsKey(id);
    }

    // =========================================================================
    // 持久化
    // =========================================================================

    private void saveToDisk(Conversation conv) {
        try {
            Files.createDirectories(conversationsDir);
            Path tmp = Files.createTempFile(conversationsDir, ".conv_", ".tmp");
            MAPPER.writeValue(tmp.toFile(), conv);
            Files.move(tmp, conversationsDir.resolve(conv.id + ".json"),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {}
    }

    // =========================================================================
    // 数据类
    // =========================================================================

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
        /** 图片路径列表（相对于项目根目录，如 conversation-images/sess/123.png） */
        public List<String> images;
    }

    public static class ConversationSummary {
        public String id;
        public String title;
        public long createdAt;
        public long updatedAt;
        public int messageCount;

        public ConversationSummary(String id, String title, long createdAt, long updatedAt, int messageCount) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.messageCount = messageCount;
        }
    }
}
