package com.miniagent.config;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class StreamingChatModelConfig {

    private final OpenAiModelProperties props;

    public StreamingChatModelConfig(OpenAiModelProperties props) {
        this.props = props;
    }

    @Bean
    @Primary
    public StreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .httpClientBuilder(props.buildHttpClient(props.getStreamingTimeout()))
                .apiKey(props.getStreamingApiKey())
                .baseUrl(props.getStreamingBaseUrl())
                .modelName(props.getStreamingModelName())
                .timeout(props.getStreamingTimeout())
                .returnThinking(true)
                .sendThinking(false)
                .build();
    }
}
