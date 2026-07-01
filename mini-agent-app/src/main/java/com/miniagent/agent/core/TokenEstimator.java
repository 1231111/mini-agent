package com.miniagent.agent.core;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token 估算器：基于字符分类近似估算 token 数。
 * CJK（中日韩）字符信息密度高，约 0.7 token/字；其余（英文/数字/符号）约 0.3 token/字符。
 * 比单一系数更贴近真实分词，避免中文场景严重低估导致压缩滞后。
 */
@Component
public class TokenEstimator {

    /** CJK 字符的 token 系数 */
    private static final double CJK_TOKENS_PER_CHAR = 0.7;
    /** 非 CJK 字符（英文/数字/符号/空白）的 token 系数 */
    private static final double LATIN_TOKENS_PER_CHAR = 0.3;

    /** 消息格式开销（role、分隔符等） */
    private static final int MESSAGE_OVERHEAD_TOKENS = 8;

    /** System prompt 额外开销 */
    private static final int SYSTEM_OVERHEAD_TOKENS = 16;

    /** 单张图片的近似 token 开销（多模态，按中等分辨率保守估） */
    private static final int IMAGE_TOKENS = 700;

    /**
     * 估算文本的 token 数：按 CJK / 非 CJK 分别加权。
     */
    public int estimate(String text) {
        if (text == null || text.isBlank()) return 0;
        int cjk = 0, other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) cjk++;
            else other++;
        }
        return (int) Math.ceil(cjk * CJK_TOKENS_PER_CHAR + other * LATIN_TOKENS_PER_CHAR);
    }

    /** 判断是否为 CJK 表意文字 / 假名 / 韩文等高密度字符 */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   // CJK 统一表意
            || (c >= 0x3400 && c <= 0x4DBF)   // CJK 扩展 A
            || (c >= 0x3040 && c <= 0x30FF)   // 平假名 + 片假名
            || (c >= 0xAC00 && c <= 0xD7AF)   // 韩文音节
            || (c >= 0xF900 && c <= 0xFAFF)   // CJK 兼容表意
            || (c >= 0xFF00 && c <= 0xFFEF);  // 全角符号
    }

    /**
     * 估算单条 ChatMessage 的 token 数
     */
    public int estimate(ChatMessage message) {
        int tokens = MESSAGE_OVERHEAD_TOKENS;

        if (message instanceof dev.langchain4j.data.message.SystemMessage sm) {
            tokens = SYSTEM_OVERHEAD_TOKENS;
            tokens += estimateContent(sm);
        } else if (message instanceof dev.langchain4j.data.message.UserMessage um) {
            tokens += estimateContent(um);
        } else if (message instanceof dev.langchain4j.data.message.AiMessage am) {
            if (am.text() != null) {
                tokens += estimate(am.text());
            }
            if (am.hasToolExecutionRequests()) {
                tokens += am.toolExecutionRequests().size() * 30; // 工具调用开销
            }
        } else if (message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage tr) {
            tokens += estimate(tr.text());
        }

        return tokens;
    }

    /**
     * 估算消息列表的总 token 数
     */
    public int estimateMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        return messages.stream().mapToInt(this::estimate).sum();
    }

    /**
     * 计算消息列表在给定上下文窗口中的占比
     * @param messages 当前消息
     * @param maxContextTokens 最大上下文 token 数
     * @return 占比 (0.0 - 1.0+)
     */
    public double contextRatio(List<ChatMessage> messages, int maxContextTokens) {
        int tokens = estimateMessages(messages);
        return (double) tokens / maxContextTokens;
    }

    /**
     * 提取 Content 的 token 估算
     */
    private int estimateContent(ChatMessage message) {
        int tokens = 0;
        try {
            // 通过反射获取 singleContent 或 contents
            var singleContentMethod = message.getClass().getMethod("singleContent");
            Content content = (Content) singleContentMethod.invoke(message);
            if (content instanceof TextContent tc) {
                tokens += estimate(tc.text());
            } else if (content instanceof ImageContent) {
                tokens += IMAGE_TOKENS; // 图片固定开销（多模态）
            }
        } catch (Exception e) {
            // fallback: 尝试 toString
            tokens += estimate(message.toString());
        }
        return tokens;
    }
}
