package com.miniagent.config;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class ChatModelConfig {

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    /** 阻塞式调用超时。默认 1200s，避免长 prompt / 慢响应被默认超时掐断。 */
    @Value("${langchain4j.open-ai.chat-model.timeout:1200s}")
    private Duration timeout;

    @Bean
    @Primary
    public ChatModel chatModel() {
        JdkHttpClientBuilder httpClientBuilder = new JdkHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(timeout);

        return OpenAiChatModel.builder()
                .httpClientBuilder(httpClientBuilder)
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(timeout)
                .returnThinking(true)
                .sendThinking(false)
                .build();
    }
}