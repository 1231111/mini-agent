package com.miniagent.common.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 共享 Embedding（与 codebase / 记忆向量同一套配置）。
 * <p>
 * 两种后端，由 {@code agent.codebase.embedding-provider} 选：
 * <ul>
 *   <li>{@code remote}（默认）—— OpenAI 兼容 HTTP。供应商报模型不存在等致命错误时熔断，
 *       避免每条消息打一次 HTTP。</li>
 *   <li>{@code local-onnx} —— 进程内 ONNX 推理，见 {@link LocalOnnxEmbeddingModel}。
 *       桌面档用它，从而不再需要 embedding-server 那个 Python sidecar，
 *       也不依赖外部 API。</li>
 * </ul>
 * 两者对外是同一个 {@link #embed} / {@link #embedAll}，调用方不感知。
 */
@Slf4j
@Component
public class SharedEmbeddingModel {

    @Value("${agent.codebase.embedding-enabled:true}")
    private boolean embeddingEnabled;

    /** {@code remote} 或 {@code local-onnx}。默认 remote —— 保持既有部署行为不变。 */
    @Value("${agent.codebase.embedding-provider:remote}")
    private String provider;

    @Value("${agent.codebase.embedding-api-key:}")
    private String apiKey;
    @Value("${agent.codebase.embedding-base-url:https://api.siliconflow.cn/v1}")
    private String baseUrl;
    @Value("${agent.codebase.embedding-model:BAAI/bge-m3}")
    private String modelName;

    /** local-onnx 的 ONNX 文件路径。伴生 {@code .data} 必须与它同目录，否则权重加载不全。 */
    @Value("${agent.codebase.embedding-local-model:}")
    private String localModelPath;
    /** local-onnx 的 tokenizer.json 路径。 */
    @Value("${agent.codebase.embedding-local-tokenizer:}")
    private String localTokenizerPath;
    /**
     * local-onnx 的推理线程数。ORT 用的是进程内线程池，不是请求级隔离 ——
     * 给太大时多路并发会互相抢核，反而更慢。
     */
    @Value("${agent.codebase.embedding-local-threads:4}")
    private int localThreads;

    private volatile EmbeddingModel model;
    private final AtomicBoolean tripped = new AtomicBoolean(false);

    private volatile LocalOnnxEmbeddingModel localModel;

    public boolean isEnabled() {
        if (!embeddingEnabled) {
            return false;
        }
        if (isLocalProvider()) {
            return local().isAvailable();
        }
        return StringUtils.isNotBlank(apiKey) && !tripped.get();
    }

    public float[] embed(String text) {
        if (!isEnabled() || StringUtils.isBlank(text)) {
            return new float[0];
        }
        if (isLocalProvider()) {
            return local().embed(text);
        }
        try {
            Embedding emb = model().embed(TextSegment.from(text)).content();
            return emb.vector();
        } catch (Exception e) {
            tripIfFatal(e);
            if (!tripped.get()) {
                log.warn("embedding 失败: {}", shortMsg(e));
            }
            return new float[0];
        }
    }

    /** 批量嵌入；失败返回空列表（不逐条重打致命错误） */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        if (!isEnabled() || texts == null || texts.isEmpty()) {
            return out;
        }
        if (isLocalProvider()) {
            return local().embedAll(texts);
        }
        try {
            List<TextSegment> segs = new ArrayList<>(texts.size());
            for (String t : texts) {
                segs.add(TextSegment.from(t == null ? "" : t));
            }
            List<Embedding> embeddings = model().embedAll(segs).content();
            for (Embedding e : embeddings) {
                out.add(e.vector());
            }
            return out;
        } catch (Exception e) {
            tripIfFatal(e);
            if (tripped.get()) {
                return out;
            }
            log.warn("批量 embedding 失败，回退逐条: {}", shortMsg(e));
            for (String t : texts) {
                out.add(embed(t));
            }
            return out;
        }
    }

    /** 当前是不是走进程内 ONNX。 */
    public boolean isLocalProvider() {
        return "local-onnx".equalsIgnoreCase(StringUtils.trimToEmpty(provider));
    }

    /** 当前生效的后端描述，用于诊断日志 / 健康检查。 */
    public String providerDescription() {
        if (isLocalProvider()) {
            int dim = localModel == null ? -1 : localModel.dimension();
            return "local-onnx(" + localModelPath + ", dim=" + dim + ")";
        }
        return "remote(" + baseUrl + ", model=" + modelName + ")";
    }

    /**
     * 启动期预检内联模型（异步）。
     *
     * <p>为什么必须在启动时做，而不是等第一次检索：{@link #isEnabled()} 是懒的，
     * 模型缺失要等到有人真的用到检索才暴露，表现为「检索结果不准」——
     * 那时很难联想到「安装包少了个文件」。构建脚本已经会拦截缺料，但用户也可能
     * 手工删掉模型省空间，所以日志里必须有一条明确的结论。
     *
     * <p>为什么放后台线程：建会话在 Java 侧实测要 4.7~5.3 秒（见下），同步做会把这
     * 段时间直接加到窗口出现之前。这里是纯预热 + 告警，不参与任何就绪判断 ——
     * 桌面壳等的是 {@code /actuator/health}，与模型无关，所以异步是安全的。
     *
     * <p><b>Java 侧与 Python 侧的建会话耗时不能互相推算。</b>同一份 int8 文件：
     * Java（出厂路径）4.7s，Python 0.53s，差 9 倍；而且三档在 Java 侧只差 11%
     * （int8 4746ms / fp16 4883ms / fp32 5290ms），说明这里是 ORT 的一份固定初始化
     * 开销在主导，与模型大小基本无关。所以「int8 建会话快 4 倍」这个结论只在
     * Python 口径下成立，不要拿它当出厂依据。
     *
     * <p>只判 {@code local-onnx}。{@code remote} 档的可用性取决于外部服务，
     * 启动时打一次 HTTP 探测既慢又不可靠，仍交给调用时的熔断逻辑。
     */
    @PostConstruct
    void preflight() {
        if (!isLocalProvider()) {
            return;
        }
        Thread t = new Thread(this::runPreflight, "embedding-preflight");
        t.setDaemon(true);
        t.start();
    }

    private void runPreflight() {
        LocalOnnxEmbeddingModel m = local();
        if (m.isAvailable()) {
            log.info("内联 embedding 预检通过: dim={}, {}", m.dimension(), providerDescription());
        } else {
            log.error("内联 embedding 预检失败，本次运行没有语义检索：{}", m.initError());
            log.error("  模型路径：{}", localModelPath);
            log.error("  tokenizer：{}", localTokenizerPath);
            log.error("  出厂包应指向安装目录 resources/models 下的文件；"
                    + "缺料时重跑 scripts/build-desktop.mjs（不要带 --skip-models）");
        }
    }

    @PreDestroy
    public void shutdown() {
        LocalOnnxEmbeddingModel m = localModel;
        if (m != null) {
            m.close();
        }
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
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
        if (!fatal || !tripped.compareAndSet(false, true)) {
            return;
        }
        log.error(
                "Embedding 已熔断（{} / {}）：{}。将回退词重叠/关闭向量写入，重启或改配置后恢复",
                baseUrl, modelName, shortMsg(e));
    }

    private static String shortMsg(Throwable e) {
        String m = e.getMessage();
        if (m == null) {
            return e.getClass().getSimpleName();
        }
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
     * 懒建本地模型。路径没配时交给 {@link LocalOnnxEmbeddingModel#isAvailable()} 判 false，
     * 不在这里抛 —— 配置缺失应该表现为"该功能不可用"，而不是启动就崩。
     */
    private LocalOnnxEmbeddingModel local() {
        if (localModel == null) {
            synchronized (this) {
                if (localModel == null) {
                    localModel = new LocalOnnxEmbeddingModel(
                            localModelPath == null ? Path.of("") : Path.of(localModelPath.trim()),
                            localTokenizerPath == null ? Path.of("") : Path.of(localTokenizerPath.trim()),
                            localThreads);
                    log.info("已选择内联 embedding 后端: provider=local-onnx, model={}, tokenizer={}",
                            localModelPath, localTokenizerPath);
                }
            }
        }
        return localModel;
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
