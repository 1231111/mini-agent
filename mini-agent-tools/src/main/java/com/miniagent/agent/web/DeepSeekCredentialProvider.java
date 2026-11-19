package com.miniagent.agent.web;

import java.util.Optional;

/** Resolves a request owner's DeepSeek credential without exposing app/database dependencies to tools. */
public interface DeepSeekCredentialProvider {

    Optional<Credential> currentUserCredential();

    record Credential(String apiKey, String baseUrl) {
        public Credential {
            if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("DeepSeek API key is blank");
            if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("DeepSeek base URL is blank");
            apiKey = apiKey.trim();
            baseUrl = baseUrl.trim();
        }
    }
}
