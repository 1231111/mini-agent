package com.miniagent.common;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 安全提取 UserMessage 文本：多模态消息禁止调 {@link UserMessage#singleText()}。
 */
public final class ChatMessageTexts {

    private ChatMessageTexts() {}

    /** 拼接全部 TextContent；无文字返回空串。永不抛 Expecting single text。 */
    public static String userPlain(UserMessage um) {
        if (Objects.isNull(um)) return "";
        if (um.hasSingleText())
            return StringUtils.defaultString(um.singleText());
        StringBuilder sb = new StringBuilder();
        for (Content c : um.contents()) {
            if (c instanceof TextContent tc)
                sb.append(StringUtils.defaultString(tc.text()));
        }
        return sb.toString();
    }

    /** 追踪/摘要用：文字 + 媒体占位。 */
    public static String userForTrace(UserMessage um) {
        if (Objects.isNull(um)) return "";
        if (um.hasSingleText())
            return StringUtils.defaultString(um.singleText());
        StringBuilder sb = new StringBuilder();
        for (Content c : um.contents()) {
            if (c instanceof TextContent tc)
                sb.append(StringUtils.defaultString(tc.text()));
            else if (c instanceof ImageContent)
                sb.append("[图片]");
            else if (c instanceof AudioContent)
                sb.append("[音频]");
            else if (c instanceof VideoContent)
                sb.append("[视频]");
        }
        return sb.toString();
    }

    /**
     * 历史送模型前去掉 image_url/audio/video，只留文本占位。
     * 避免文本端点报 unknown variant image_url，也避免旧图撑爆上下文。
     */
    public static List<ChatMessage> textOnlyHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<ChatMessage> out = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            ChatMessage t = textOnly(m);
            if (t != null) out.add(t);
        }
        return out;
    }

    public static ChatMessage textOnly(ChatMessage message) {
        if (message == null) return null;
        if (message instanceof UserMessage um) {
            if (um.hasSingleText()) return um;
            String text = userForTrace(um);
            if (StringUtils.isBlank(text)) text = "[媒体消息]";
            return UserMessage.from(text);
        }
        if (message instanceof AiMessage || message instanceof SystemMessage)
            return message;
        return message;
    }
}
