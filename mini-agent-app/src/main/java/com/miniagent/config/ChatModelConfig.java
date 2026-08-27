package com.miniagent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ChatModelConfig {

    private final OpenAiModelProperties props;

    public ChatModelConfig(OpenAiModelProperties props) {
        this.props = props;
    }

    @Bean
    @Primary
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .httpClientBuilder(props.buildHttpClient(props.getChatTimeout()))
                .apiKey(props.getChatApiKey())
                .baseUrl(props.getChatBaseUrl())
                .modelName(props.getChatModelName())
                .timeout(props.getChatTimeout())
                .returnThinking(true)
                .sendThinking(false)
                .build();
    }
}
