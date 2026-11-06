package com.miniagent.memoryservice.eval;

import com.miniagent.common.llm.DedicatedChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EvalConfig {

    @Bean
    @ConditionalOnMissingBean
    EvalModels evalModels(EvalWorkerProperties props) {
        return new EvalModels(metered(props.planner()), metered(props.judge()));
    }

    private static MeteredChatModel metered(EvalWorkerProperties.Model model) {
        ChatModel chat = DedicatedChatModel.openAiOrNull(model.model(), model.baseUrl(),
                model.apiKey(), model.timeoutSeconds());
        return chat == null ? null : new MeteredChatModel(chat);
    }
}
