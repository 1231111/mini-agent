package com.miniagent.config;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class StreamingChatModelConfig {

    @Value("${langchain4j.open-ai.streaming-chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.streaming-chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.streaming-chat-model.model-name}")
    private String modelName;

    /** 流式调用超时。默认 1200s，配合长任务慢首包，避免 socket 被默认超时掐断导致 SSE 流中途 closed。 */
    @Value("${langchain4j.open-ai.streaming-chat-model.timeout:1200s}")
    private Duration timeout;

    @Bean
    @Primary
    public StreamingChatModel streamingChatModel() {
        // 显式构建 JDK HttpClient SSE 后端（稳定版）：readTimeout 放宽到与流式超时一致，
        // 否则底层 socket 在长流（思考链 + 工具循环）下被默认 readTimeout 掐断，SSE 抛 "closed"。
        // connectTimeout 用较短值（建连本应很快），readTimeout 才是长流的关键。
        JdkHttpClientBuilder httpClientBuilder = new JdkHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(timeout);

        return OpenAiStreamingChatModel.builder()
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