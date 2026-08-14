package com.miniagent.common.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 共享 Embedding（与 codebase / 记忆向量同一套配置）。
 * 供应商报模型不存在等致命错误时熔断，避免每条消息打一次 HTTP。
 */
@Slf4j
@Component
public class SharedEmbeddingModel {

    @Value("${agent.codebase.embedding-enabled:true}")
    private boolean embeddingEnabled;
    @Value("${agent.codebase.embedding-api-key:}")
    private String apiKey;
    @Value("${agent.codebase.embedding-base-url:https://api.siliconflow.cn/v1}")
    private String baseUrl;
    @Value("${agent.codebase.embedding-model:BAAI/bge-m3}")
    private String modelName;

    private volatile EmbeddingModel model;
    private final AtomicBoolean tripped = new AtomicBoolean(false);

    public boolean isEnabled() {
        return embeddingEnabled
                && StringUtils.isNotBlank(apiKey)
                && !tripped.get();
    }

    public float[] embed(String text) {
        if (!isEnabled() || StringUtils.isBlank(text))
            return new float[0];
        try {
            Embedding emb = model().embed(TextSegment.from(text)).content();
            return emb.vector();
        } catch (Exception e) {
            tripIfFatal(e);
            if (!tripped.get())
                log.warn("embedding 失败: {}", shortMsg(e));
            return new float[0];
        }
    }

    /** 批量嵌入；失败返回空列表（不逐条重打致命错误） */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        if (!isEnabled() || texts == null || texts.isEmpty())
            return out;
        try {
            List<TextSegment> segs = new ArrayList<>(texts.size());
            for (String t : texts)
                segs.add(TextSegment.from(t == null ? "" : t));
            List<Embedding> embeddings = model().embedAll(segs).content();
            for (Embedding e : embeddings)
                out.add(e.vector());
            return out;
        } catch (Exception e) {
            tripIfFatal(e);
            if (tripped.get())
                return out;
            log.warn("批量 embedding 失败，回退逐条: {}", shortMsg(e));
            for (String t : texts)
                out.add(embed(t));
            return out;
        }
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length)
            return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0)
            return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private void tripIfFatal(Exception e) {
        String m = shortMsg(e).toLowerCase();
        boolean fatal = m.contains("404")
                || m.contains("not_found")
                || m.contains("model is not supported")
                || m.contains("模型在当前分组内不支持")
                || m.contains("模型不存在")
                || m.contains("invalid_api_key")
                || m.contains("incorrect api key");
        if (!fatal || !tripped.compareAndSet(false, true))
            return;
        log.error(
                "Embedding 已熔断（{} / {}）：{}。将回退词重叠/关闭向量写入，重启或改配置后恢复",
                baseUrl, modelName, shortMsg(e));
    }

    private static String shortMsg(Throwable e) {
        String m = e.getMessage();
        if (m == null)
            return e.getClass().getSimpleName();
        return m.length() > 240 ? m.substring(0, 240) + "…" : m;
    }

    private EmbeddingModel model() {
        if (model == null) {
            synchronized (this) {
                if (model == null) {
                    model = OpenAiEmbeddingModel.builder()
                            .httpClientBuilder(http1ClientBuilder())
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .modelName(modelName)
                            .build();
                }
            }
        }
        return model;
    }

    /**
     * 明文 HTTP 上 JDK 默认试 h2c Upgrade，uvicorn 拒掉后 body 丢失→422。
     */
    public static JdkHttpClientBuilder http1ClientBuilder() {
        HttpClient.Builder jdk = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15));
        return new JdkHttpClientBuilder()
                .httpClientBuilder(jdk)
                .readTimeout(Duration.ofSeconds(60));
    }
}
