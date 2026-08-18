package com.miniagent.config;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * OpenAI 兼容模型共享配置。
 * 消除 ChatModelConfig 和 StreamingChatModelConfig 中的重复字段和 HttpClient 构建逻辑。
 */
@Component
public class OpenAiModelProperties {

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String chatApiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String chatBaseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String chatModelName;

    @Value("${langchain4j.open-ai.chat-model.timeout:1200s}")
    private Duration chatTimeout;

    @Value("${langchain4j.open-ai.streaming-chat-model.api-key}")
    private String streamingApiKey;

    @Value("${langchain4j.open-ai.streaming-chat-model.base-url}")
    private String streamingBaseUrl;

    @Value("${langchain4j.open-ai.streaming-chat-model.model-name}")
    private String streamingModelName;

    @Value("${langchain4j.open-ai.streaming-chat-model.timeout:1200s}")
    private Duration streamingTimeout;

    /** 构建共享的 JDK HttpClient，connectTimeout=30s，readTimeout 按需配置 */
    public JdkHttpClientBuilder buildHttpClient(Duration readTimeout) {
        return new JdkHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(readTimeout);
    }

    // === Chat Model getters ===
    public String getChatApiKey() { return chatApiKey; }
    public String getChatBaseUrl() { return chatBaseUrl; }
    public String getChatModelName() { return chatModelName; }
    public Duration getChatTimeout() { return chatTimeout; }

    // === Streaming Model getters ===
    public String getStreamingApiKey() { return streamingApiKey; }
    public String getStreamingBaseUrl() { return streamingBaseUrl; }
    public String getStreamingModelName() { return streamingModelName; }
    public Duration getStreamingTimeout() { return streamingTimeout; }
}
