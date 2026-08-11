package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * codebase_search：基于 embedding 的语义代码检索（Cursor 风格的「补充手段」）。
 *
 * 设计取舍（见与用户的讨论）：grep/ast 是主力，本工具用于「概念性、不知道精确关键词」的查询。
 * - 分块：JavaParser 按方法/类粒度切（语义边界，不是按行瞎切）。
 * - 嵌入：siliconflow 的 OpenAI 兼容 embedding 接口（bge-m3）。
 * - 存储：langchain4j InMemoryEmbeddingStore，持久化到 JSON 文件。
 * - 增量：按文件 mtime 判断是否需要重新索引，首次查询时惰性构建。
 *
 * 未配置 key（embedding-enabled=false）时优雅降级：直接提示改用 search_code/ast_search。
 */
@Slf4j
@Component
public class CodebaseSearchTool {

    private final ToolRegistry toolRegistry;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${agent.codebase.embedding-enabled:false}")
    private boolean enabled;
    @Value("${agent.codebase.embedding-api-key:}")
    private String apiKey;
    @Value("${agent.codebase.embedding-base-url:https://api.siliconflow.cn/v1}")
    private String baseUrl;
    @Value("${agent.codebase.embedding-model:BAAI/bge-m3}")
    private String embeddingModelName;
    @Value("${agent.codebase.index-file:./workspace/.codebase-index.json}")
    private String indexFile;

    private EmbeddingModel embeddingModel;
    private InMemoryEmbeddingStore<TextSegment> store;
    private final AtomicBoolean indexed = new AtomicBoolean(false);
    /** 已索引文件 mtime 记录，用于增量判断。 */
    private final Map<String, Long> indexedMtime = new LinkedHashMap<>();

    public CodebaseSearchTool(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void register() {
        toolRegistry.register(Tool.builder()
                .name("codebase_search")
                .description("""
                        语义检索代码库：用自然语言描述你要找的功能/逻辑，返回最相关的代码片段。
                        适合「不知道精确关键词、按意图找」的查询，例如「处理用户登录鉴权的逻辑」「重试机制在哪实现」。
                        若你已知道确切的符号名/关键字，优先用 search_code（文本）或 ast_search（结构），更快更准。
                        首次调用会自动索引项目 Java 源码（可能稍慢），之后增量更新。
                        """)
                .parameters(buildSchema())
                .handler(this::handle)
                .build());
    }

    private Map<String, Object> buildSchema() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", Map.of("type", "string",
                "description", "自然语言描述要找的代码功能/逻辑", "required", true));
        params.put("path", Map.of("type", "string",
                "description", "索引根目录，默认项目根目录"));
        params.put("top_k", Map.of("type", "integer",
                "description", "返回最相关的前 N 个片段，默认 5"));
        return params;
    }

    @SuppressWarnings("unchecked")
    private String handle(String json) {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            return "{\"error\":\"codebase_search 未启用（缺少 embedding key）。请改用 search_code 或 ast_search。\"}";
        }
        try {
            Map<String, Object> args = MAPPER.readValue(json == null ? "{}" : json, Map.class);
            String query = String.valueOf(args.getOrDefault("query", "")).trim();
            if (query.isEmpty()) return err("query 不能为空");
            String pathArg = args.get("path") == null ? null : String.valueOf(args.get("path")).trim();
            int topK = args.containsKey("top_k") ? ((Number) args.get("top_k")).intValue() : 5;

            Path root = (pathArg == null || pathArg.isBlank())
                    ? Path.of(System.getProperty("user.dir")).toAbsolutePath()
                    : resolvePath(pathArg);
            if (!Files.exists(root)) return err("路径不存在: " + pathArg);

            ensureIndexed(root);
            if (store == null) return err("索引尚未就绪");

            Embedding queryEmbedding = embeddingModel().embed(query).content();
            EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(Math.max(1, topK))
                    .minScore(0.3)
                    .build();
            List<EmbeddingMatch<TextSegment>> matches = store.search(req).matches();

            if (matches.isEmpty()) return "（无相关片段）query=" + query;
            StringBuilder sb = new StringBuilder("语义检索 top " + matches.size() + "：\n\n");
            for (EmbeddingMatch<TextSegment> m : matches) {
                TextSegment seg = m.embedded();
                String loc = seg.metadata().getString("location");
                sb.append("─── ").append(loc != null ? loc : "?")
                  .append("  (score=").append(String.format("%.3f", m.score())).append(")\n");
                sb.append(truncate(seg.text(), 800)).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("codebase_search 失败", e);
            return err("codebase_search 执行失败: " + e.getMessage());
        }
    }

    /** 惰性 + 增量索引。首次或有文件变更时重建。 */
    private synchronized void ensureIndexed(Path root) {
        try {
            // 收集当前 Java 文件及 mtime
            List<Path> files;
            try (var stream = Files.walk(root)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> { String s = p.toString().replace('\\', '/');
                                       return !s.contains("/target/") && !s.contains("/build/"); })
                        .toList();
            }
            Map<String, Long> currentMtime = new LinkedHashMap<>();
            for (Path f : files) {
                try { currentMtime.put(f.toString(), Files.getLastModifiedTime(f).toMillis()); }
                catch (Exception ignored) {}
            }

            // 已索引且无变更 → 直接用
            if (indexed.get() && store != null && currentMtime.equals(indexedMtime)) {
                return;
            }

            // 尝试从磁盘加载已有索引（仅首次进程启动后）
            Path idx = resolvePath(indexFile);
            if (!indexed.get() && store == null && Files.exists(idx)) {
                try {
                    store = InMemoryEmbeddingStore.fromFile(idx);
                    log.info("已加载代码库索引: {}", idx);
                } catch (Exception e) {
                    log.warn("加载索引失败，将重建: {}", e.getMessage());
                    store = null;
                }
            }

            // 变更检测：文件集或 mtime 不同就全量重建（实现简单可靠；量大时可优化为单文件增量）
            boolean needRebuild = (store == null) || !currentMtime.equals(indexedMtime);
            if (!needRebuild) { indexed.set(true); return; }

            log.info("开始索引代码库（{} 个 Java 文件）...", files.size());
            InMemoryEmbeddingStore<TextSegment> fresh = new InMemoryEmbeddingStore<>();
            int chunks = 0;
            for (Path f : files) {
                List<TextSegment> segs = chunkJavaFile(f, root);
                for (TextSegment seg : segs) {
                    try {
                        Embedding emb = embeddingModel().embed(seg).content();
                        fresh.add(emb, seg);
                        chunks++;
                    } catch (Exception e) {
                        log.warn("嵌入失败（跳过一段）: {}", e.getMessage());
                    }
                }
            }
            store = fresh;
            indexedMtime.clear();
            indexedMtime.putAll(currentMtime);
            indexed.set(true);

            // 持久化
            try {
                Files.createDirectories(idx.getParent());
                store.serializeToFile(idx);
                log.info("代码库索引完成：{} 段，已持久化到 {}", chunks, idx);
            } catch (Exception e) {
                log.warn("索引持久化失败（不影响本次查询）: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("索引构建失败", e);
        }
    }

    /** 用 JavaParser 按方法/类粒度切块；解析失败的文件整体作为一段。 */
    private List<TextSegment> chunkJavaFile(Path file, Path root) {
        List<TextSegment> segs = new ArrayList<>();
        String rel = root.relativize(file).toString().replace('\\', '/');
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            CompilationUnit cu = StaticJavaParser.parse(content);

            cu.findAll(MethodDeclaration.class).forEach(m -> {
                int line = m.getBegin().map(p -> p.line).orElse(0);
                String cls = m.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(ClassOrInterfaceDeclaration::getNameAsString).orElse("?");
                String text = cls + "#" + m.getNameAsString() + "\n" + m.toString();
                segs.add(TextSegment.from(truncate(text, 4000),
                        dev.langchain4j.data.document.Metadata.from("location", rel + ":" + line)));
            });

            // 没有方法的文件（接口常量、枚举等）整体入一段
            if (segs.isEmpty()) {
                segs.add(TextSegment.from(truncate(content, 4000),
                        dev.langchain4j.data.document.Metadata.from("location", rel + ":1")));
            }
        } catch (Exception e) {
            // 解析失败：整文件作为一段，仍可被语义检索命中
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                segs.add(TextSegment.from(truncate(content, 4000),
                        dev.langchain4j.data.document.Metadata.from("location", rel + ":1")));
            } catch (Exception ignored) {}
        }
        return segs;
    }

    private EmbeddingModel embeddingModel() {
        if (embeddingModel == null) {
            JdkHttpClientBuilder http = new JdkHttpClientBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .readTimeout(java.time.Duration.ofSeconds(60));
            embeddingModel = OpenAiEmbeddingModel.builder()
                    .httpClientBuilder(http)
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(embeddingModelName)
                    .build();
        }
        return embeddingModel;
    }

    private Path resolvePath(String path) {
        Path p = Path.of(path.replace('\\', '/').trim());
        if (p.isAbsolute()) return p.normalize();
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().resolve(p).normalize();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String err(String msg) {
        try { return MAPPER.writeValueAsString(Map.of("success", false, "error", msg)); }
        catch (Exception e) { return "{\"success\":false,\"error\":\"" + msg.replace("\"", "'") + "\"}"; }
    }
}
