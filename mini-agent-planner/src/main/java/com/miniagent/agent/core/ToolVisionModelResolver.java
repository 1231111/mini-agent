package com.miniagent.agent.core;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.Objects;

/** Resolves the configured vision client only when a tool actually supplies an image observation. */
@FunctionalInterface
public interface ToolVisionModelResolver {
    Models resolve();

    record Models(ChatModel chat, StreamingChatModel streaming, int contextWindowTokens) {
        public Models {
            Objects.requireNonNull(chat, "A configured vision chat model is required");
        }
    }
}
