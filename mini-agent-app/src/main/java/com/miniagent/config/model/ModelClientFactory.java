package com.miniagent.config.model;

import org.springframework.beans.factory.annotation.Autowired;

import com.miniagent.config.service.UserModelConfigService;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 按用户有效配置构建并缓存 Chat / Streaming 客户端（不改动全局 @Primary Bean）。
 */
@Slf4j
@Component
public class ModelClientFactory {

    private static final int MAX_CACHE = 16;

    @Autowired
    private UserModelConfigService userModelConfigService;

    @Value("${langchain4j.open-ai.chat-model.timeout:1200s}")
    private Duration timeout;

    /** 有图/音视频时切换的预设；空=沿用用户当前模型 */
    @Value("${agent.multimodal.vision-preset:default}")
    private String visionPreset;

    private final Map<String, ResolvedModels> cache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ResolvedModels> eldest) {
            return size() > MAX_CACHE;
        }
    };

    public record ResolvedModels(
            ChatModel chat,
            StreamingChatModel streaming,
            EffectiveModelSettings settings
    ) {}

    public synchronized ResolvedModels resolve(Long userId) {
        return build(userModelConfigService.getEffective(userId), true);
    }

    /**
     * 多模态轮次：优先 vision-preset（默认 default / mimo-v2.5）。
     */
    public synchronized ResolvedModels resolveForMultimodal(Long userId) {
        if (org.apache.commons.lang3.StringUtils.isBlank(visionPreset))
            return resolve(userId);
        EffectiveModelSettings vision = userModelConfigService.getEffectivePreset(visionPreset.trim());
        log.info("多模态改用 vision 预设: userId={}, preset={}, model={}",
                userId, vision.presetId(), vision.modelName());
        // 视觉请求关闭 thinking，部分网关在 thinking schema 下只接受 text
        return build(vision, false);
    }

    private ResolvedModels build(EffectiveModelSettings settings, boolean thinking) {
        String key = settings.cacheKey() + "|think=" + thinking;
        ResolvedModels hit = cache.get(key);
        if (Objects.nonNull(hit)) return hit;

        log.info("构建用户模型客户端: preset={}, model={}, baseUrl={}, thinking={}",
                settings.presetId(), settings.modelName(), settings.baseUrl(), thinking);

        JdkHttpClientBuilder http = new JdkHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(timeout);

        ChatModel chat = OpenAiChatModel.builder()
                .httpClientBuilder(http)
                .apiKey(settings.apiKey())
                .baseUrl(settings.baseUrl())
                .modelName(settings.modelName())
                .timeout(timeout)
                .returnThinking(thinking)
                .sendThinking(false)
                .build();

        JdkHttpClientBuilder httpStream = new JdkHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(timeout);

        StreamingChatModel streaming = OpenAiStreamingChatModel.builder()
                .httpClientBuilder(httpStream)
                .apiKey(settings.apiKey())
                .baseUrl(settings.baseUrl())
                .modelName(settings.modelName())
                .timeout(timeout)
                .returnThinking(thinking)
                .sendThinking(false)
                .build();

        ResolvedModels resolved = new ResolvedModels(chat, streaming, settings);
        cache.put(key, resolved);
        return resolved;
    }
}
