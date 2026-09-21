package com.miniagent.agent.llm;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;

/**
 * 规划 / 评判共用的独立 ChatModel 构建。缺任一端点配置则返回 null。
 */
public final class DedicatedChatModel {

    private DedicatedChatModel() {}

    public static ChatModel openAiOrNull(String name, String baseUrl, String apiKey,
                                         int timeoutSeconds) {
        if (StringUtils.isAnyBlank(name, baseUrl, apiKey)) {
            return null;
        }
        Duration timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        return OpenAiChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .readTimeout(timeout))
                .apiKey(apiKey.trim())
                .baseUrl(baseUrl.trim())
                .modelName(name.trim())
                .timeout(timeout)
                .maxRetries(1)
                .temperature(0.0)
                .returnThinking(false)
                .sendThinking(false)
                .build();
    }
}
