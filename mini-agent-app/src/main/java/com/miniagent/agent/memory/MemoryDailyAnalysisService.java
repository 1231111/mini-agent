package com.miniagent.agent.memory;

import org.springframework.beans.factory.annotation.Autowired;

import com.miniagent.common.ChatRole;
import com.miniagent.common.model.EffectiveModelContext;
import com.miniagent.config.model.UserModelBinder;
import com.miniagent.memory.MemoryKeys;
import com.miniagent.memory.MemoryService;
import com.miniagent.memory.MemoryStore;
import com.miniagent.memory.model.MemoryReadPolicy;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.commons.lang3.StringUtils;

/**
 * 每天 00:00 自动分析用户对话，提取偏好写入语义事实（用户画像）。
 */
@Slf4j
@Service
public class MemoryDailyAnalysisService {

    @Autowired
    private ChatModel chatModel;
    @Autowired
    private MemoryStore memoryStore;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private com.miniagent.config.service.DatabaseConversationStore conversationStore;
    /**
     * 定时任务跑在 {@code scheduling} 线程池上，没有继承任何用户上下文，
     * 必须显式绑定该用户的模型与归属，否则画像分析会走全局 Bean。
     */
    @Autowired
    private UserModelBinder userModelBinder;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 每天 00:00:00 执行分析：按用户遍历持久化会话，分别提取画像写入各自的 USER.md */
    @Scheduled(cron = "0 0 0 * * *")
    public void dailyAnalysis() {
        log.info("[MemoryAnalysis] 开始每日用户画像分析...");
        try {
            List<Long> userIds = conversationStore.listUserIds();
            if (userIds.isEmpty()) {
                log.info("[MemoryAnalysis] 没有任何用户会话，跳过。");
                return;
            }
            // 分析过去 24 小时内有更新的会话
            long since = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
            int analyzed = 0;
            for (Long userId : userIds) {
                if (Objects.isNull(userId)) {
                    continue;
                }
                try {
                    analyzed += analyzeUser(userId, since);
                } catch (Exception e) {
                    log.warn("[MemoryAnalysis] 用户 {} 分析失败: {}", userId, e.getMessage());
                }
            }
            log.info("[MemoryAnalysis] 分析完成，共处理 {} 个用户。", analyzed);
        } catch (Exception e) {
            log.error("[MemoryAnalysis] 每日分析失败", e);
        }
    }

    /** 分析单个用户当天活跃会话，结果写入该用户的 USER.md。返回 1 表示已分析、0 表示无内容跳过。 */
    private int analyzeUser(Long userId, long since) {
        List<String> allMessages = collectUserMessages(userId, since);
        if (allMessages.isEmpty()) {
            return 0;
        }

        String transcript = String.join("\n", allMessages);
        if (transcript.length() > 20000) {
            transcript = transcript.substring(transcript.length() - 20000); // 截取最近2万字符
        }

        // 绑定归属与模型：归属让快照读取与写入都落到该用户的目录，
        // 模型让分析走该用户自己配置的那套（全局 Bean 仅在解析失败时兜底）。
        try (var ignored = userModelBinder.bindUser(userId)) {
            String analysis = analyzeWithLLM(transcript);
            if (StringUtils.isBlank(analysis)) {
                return 0;
            }
            writeAnalysisResults(analysis);
            return 1;
        }
    }

    /** 收集某用户当天活跃会话的对话消息（从持久化存储，覆盖重启后/未打开的会话）。 */
    private List<String> collectUserMessages(Long userId, long since) {
        List<String> allMessages = new ArrayList<>();
        var conversations = conversationStore.activeConversationsSince(userId, since);
        for (var conv : conversations) {
            if (Objects.isNull(conv.messages)) {
                continue;
            }
            for (var msg : conv.messages) {
                if (Objects.isNull(msg) || StringUtils.isBlank(msg.content)) {
                    continue;
                }
                String role = ChatRole.USER.getValue().equals(msg.role) ? "用户"
                        : ChatRole.ASSISTANT.getValue().equals(msg.role) ? "助手" : "其它";
                allMessages.add(role + ": " + msg.content);
            }
        }
        return allMessages;
    }

    /**
     * 基于现有 memory 文件内容调用 LLM 分析
     */
    private String analyzeWithLLM(String transcript) {
        // 构造分析提示
        String systemPrompt = """
你是用户画像分析师。根据以下对话记录，提取用户的偏好信息。

要求：
1. 提取沟通风格偏好（如：喜欢简洁/详细/技术深挖等）
2. 提取技术领域和工具偏好
3. 提取工作习惯和项目特点
4. 每条不超过50字
5. 输出格式：每行一条，用 § 分隔

已有记忆（可能为空）：
""" + memoryService.retrieveForPrompt(
                null, "用户偏好",
                new MemoryReadPolicy(false, false, true, false, 0)) + """

对话记录：
""" + transcript + """

请分析并输出用户偏好条目（用 § 分隔）：""";

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            dev.langchain4j.data.message.SystemMessage.from(systemPrompt),
                            UserMessage.from("请分析以上对话，提取用户偏好。")
                    ))
                    .build();

            var response = EffectiveModelContext.chatOr(chatModel).chat(request);
            return response.aiMessage().text();
        } catch (Exception e) {
            log.error("[MemoryAnalysis] LLM 调用失败", e);
            return null;
        }
    }

    /** 解析 LLM 结果，写入用户偏好事实（不重复写画像 blob）。 */
    private void writeAnalysisResults(String analysis) {
        String[] lines = analysis.split("[§\n]");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.length() > 200) {
                continue;
            }

            trimmed = trimmed.replaceFirst("^[\\d]+[.、]\\s*", "").replaceFirst("^[-*]\\s*", "");
            memoryService.add(MemoryKeys.TARGET_USER, trimmed);
        }
    }
}
