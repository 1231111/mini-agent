package com.miniagent.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 图像生成服务 — 对标 hermes-agent image_generate
 *
 * hermes-agent 实现：
 *   模型: FAL.ai FLUX 2 Pro (fal-ai/flux-2-pro)
 *   放大: FAL.ai Clarity Upscaler (fal-ai/clarity-upscaler) 2x
 *   认证: FAL_KEY 环境变量 → Authorization: Key <key>
 *   流程: 提交生成请求(POST /queue.fal.run/flux-2/pro) → 轮询结果 → 提交放大 → 轮询 → 返回URL
 *
 * 我们的实现：
 *   后端路由: FAL.ai (同hermes) / SiliconFlow (免费额度) / 智谱CogView (国产)
 *   输出格式: {"success": true, "image": "url"} (与hermes完全一致)
 *
 * 环境变量：
 *   FAL_KEY              — FAL.ai API 密钥（首选）
 *   CHATANYWHERE_API_KEY — ChatAnywhere 图片生成 API 密钥（OpenAI 兼容）
 *   SILICONFLOW_API_KEY  — SiliconFlow API 密钥（免费FLUX.1-schnell）
 *   ZHIPU_API_KEY        — 智谱AI API密钥（CogView-3）
 */
@Slf4j
@Service
public class ImageGenerationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ========== 环境变量配置 ==========

    @Value("${image.gen.fal-key:#{null}}")
    private String falKey;

    @Value("${image.gen.chatanywhere-key:#{null}}")
    private String chatAnywhereKey;

    @Value("${image.gen.chatanywhere-base-url:https://api.chatanywhere.tech/v1}")
    private String chatAnywhereBaseUrl;

    @Value("${image.gen.chatanywhere-model:dall-e-3}")
    private String chatAnywhereModel;

    @Value("${image.gen.siliconflow-api-key:#{null}}")
    private String siliconflowKey;

    @Value("${image.gen.zhipu-api-key:#{null}}")
    private String zhipuKey;

    @Value("${image.gen.backend:}")
    private String configuredBackend;

    @Value("${image.gen.mimo-api-key:${langchain4j.open-ai.chat-model.api-key:#{null}}}")
    private String mimoKey;

    @Value("${image.gen.mimo-base-url:${langchain4j.open-ai.chat-model.base-url:https://token-plan-cn.xiaomimimo.com/v1}}")
    private String mimoBaseUrl;

    @Value("${image.gen.mimo-model:mimo-v2-image}")
    private String mimoImageModel;

    /**
     * 生成图片 — 对标 hermes-agent image_generate
     *
     * @param prompt      图片描述（英文效果最好）
     * @param aspectRatio 比例: landscape / square / portrait
     * @return 标准化 JSON: {"success": true, "image": "url"}
     */
    public String generate(String prompt, String aspectRatio) {
        if (prompt == null || prompt.isBlank()) {
            return errorJson("prompt 不能为空");
        }
        if (aspectRatio == null || aspectRatio.isBlank()) {
            aspectRatio = "landscape";
        }

        String primaryBackend = resolveBackend();
        String imageSize = mapAspectRatio(aspectRatio);
        log.info("图像生成: backend={} aspect={} prompt='{}'", primaryBackend, aspectRatio,
                prompt.substring(0, Math.min(80, prompt.length())));

        // 按优先级构建 fallback 链（跳过未配置 key 的 provider）
        List<String> chain = buildFallbackChain(primaryBackend);
        String lastError = "无可用后端";

        for (String backend : chain) {
            try {
                log.info("尝试后端: {}", backend);
                String imageUrl = switch (backend) {
                    case "chatanywhere" -> generateViaChatAnywhere(prompt, imageSize);
                    case "mimo", "xiaomi" -> generateViaMimo(prompt, imageSize);
                    case "fal"         -> generateViaFal(prompt, imageSize);
                    case "siliconflow" -> generateViaSiliconFlow(prompt, imageSize);
                    case "zhipu"       -> generateViaCogView(prompt, imageSize);
                    default            -> null;
                };
                if (imageUrl != null && !imageUrl.isBlank()) {
                    // 统一返回 markdown 图片链接（前端可直接渲染，AgentLoop 可识别为媒体交付）
                    if (imageUrl.startsWith("![")) return imageUrl;  // 已是 markdown
                    if (imageUrl.startsWith("http")) return "![生成的图片](" + imageUrl + ")";  // URL → markdown
                    // 本地路径（如 /static/images/xxx.png）→ markdown
                    return "![生成的图片](" + imageUrl + ")";
                }
                lastError = "后端 " + backend + " 未返回图片URL";
                log.warn("后端 {} 未返回图片，尝试下一个", backend);
            } catch (Exception e) {
                lastError = "后端 " + backend + " 失败: " + e.getMessage();
                log.warn("后端 {} 异常，尝试下一个: {}", backend, e.getMessage());
            }
        }

        log.error("所有图像生成后端均失败: {}", lastError);
        return errorJson("所有后端均失败: " + lastError);
    }

    // ========== ChatAnywhere 图片后端（OpenAI Images 兼容） ==========

    /**
     * ChatAnywhere 图片生成接口：
     * POST /v1/images/generations
     * 文档：https://chatanywhere.apifox.cn/api-92222078
     */
    private String generateViaChatAnywhere(String prompt, String imageSize) throws Exception {
        String key = resolveKey(chatAnywhereKey, "CHATANYWHERE_API_KEY");

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", prompt);
        payload.put("n", 1);
        payload.put("model", chatAnywhereModel);
        payload.put("size", mapToOpenAiImageSize(imageSize));
        if ("dall-e-3".equalsIgnoreCase(chatAnywhereModel)) {
            payload.put("quality", "hd");
        }

        if ("gpt-image-2-ca".equalsIgnoreCase(chatAnywhereModel)) {
           return generateImageBy2ca(prompt, imageSize);
        }

        String base = (chatAnywhereBaseUrl == null || chatAnywhereBaseUrl.isBlank())
                ? "https://api.chatanywhere.tech/v1"
                : chatAnywhereBaseUrl.replaceAll("/+$", "");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/images/generations"))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode data = root.path("data");
        if (response.statusCode() >= 200 && response.statusCode() < 300 && data.isArray() && !data.isEmpty()) {
            String url = data.get(0).path("url").asText("");
            if (!url.isBlank()) return url;
        }

        String msg = root.path("error").path("message")
                .asText(root.path("message").asText(response.body()));
        log.error("ChatAnywhere 图像生成响应异常: HTTP {} {}", response.statusCode(), msg);
        throw new RuntimeException("ChatAnywhere: " + msg);
    }
    // 在类中定义常量，指向项目根目录下的 generated-images
    private static final String GENERATED_IMAGES_DIR = "generated-images";

    private String generateImageBy2ca(String prompt, String imageSize) {
        String key = resolveKey(chatAnywhereKey, "CHATANYWHERE_API_KEY");
        String base = (chatAnywhereBaseUrl == null || chatAnywhereBaseUrl.isBlank())
                ? "https://api.chatanywhere.tech/v1"
                : chatAnywhereBaseUrl.replaceAll("/+$", "");

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", prompt);
        payload.put("n", 1);
        payload.put("model", chatAnywhereModel);
        payload.put("size", mapToOpenAiImageSize(imageSize));

        // 图片保存目录（声明在 try 外，catch 中也需要访问）
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path imageDir = projectRoot.resolve(GENERATED_IMAGES_DIR);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/images/generations"))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(500))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                log.error("API 请求失败，状态码: {}, 响应: {}", response.statusCode(), response.body());
                return null;
            }

            JsonNode root = MAPPER.readTree(response.body());

            if (root.has("error")) {
                log.error("API 返回错误: {}", root.path("error").path("message").asText());
                return null;
            }

            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() == 0) {
                log.error("返回数据为空");
                return null;
            }

            String b64Json = data.get(0).path("b64_json").asText();
            if (b64Json == null || b64Json.isBlank()) {
                log.error("未找到 b64_json 字段");
                return null;
            }

            // 解码 Base64
            byte[] imageBytes = Base64.getDecoder().decode(b64Json);

            // 确保目录存在
            if (!Files.exists(imageDir)) {
                Files.createDirectories(imageDir);
                log.info("创建图片目录: {}", imageDir.toAbsolutePath());
            }

            // 生成唯一文件名（格式：img_时间戳_随机数.png）
            String fileName = String.format("img_%d_%d.png",
                    System.currentTimeMillis(),
                    ThreadLocalRandom.current().nextInt(10000));
            Path outputPath = imageDir.resolve(fileName);

            // 保存图片
            Files.write(outputPath, imageBytes);

            log.info("图片已保存到: {}", outputPath.toAbsolutePath());

            // 返回 markdown 图片链接（前端可直接渲染，AgentLoop 可识别为媒体交付）
            return "![生成的图片](/static/images/" + fileName + ")";

        } catch (Exception e) {
            log.error("ChatAnywhere 2CA 图像生成失败: {}", e.getMessage(), e);
            // 安全网：HTTP 失败但图片可能已保存到磁盘（竞态条件），检查最近 60 秒内生成的图片
            String recentImage = findRecentGeneratedImage(imageDir, 60);
            if (recentImage != null) {
                log.info("HTTP 失败但发现最近生成的图片: {}", recentImage);
                return "![生成的图片](/static/images/" + recentImage + ")";
            }
            return null;
        }
    }

    // ========== 小米 MiMo / OpenAI-compatible images 后端 ==========

    /**
     * 小米 MiMo 网关按 OpenAI 兼容图片接口调用。
     * 如果当前账号/模型不支持 /images/generations，会把服务端错误透传给上层，
     * 避免 Agent 继续无限重试。
     */
    private String generateViaMimo(String prompt, String imageSize) throws Exception {
        String key = resolveKey(mimoKey, "MIMO_API_KEY");
        Map<String, Integer> size = parseImageSize(imageSize);

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", mimoImageModel);
        payload.put("prompt", prompt);
        payload.put("n", 1);
        payload.put("size", size.get("width") + "x" + size.get("height"));
        payload.put("response_format", "url");

        String base = (mimoBaseUrl == null || mimoBaseUrl.isBlank())
                ? "https://token-plan-cn.xiaomimimo.com/v1"
                : mimoBaseUrl.replaceAll("/+$", "");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/images/generations"))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode data = root.path("data");
        if (response.statusCode() >= 200 && response.statusCode() < 300 && data.isArray() && !data.isEmpty()) {
            JsonNode first = data.get(0);
            String url = first.path("url").asText("");
            if (!url.isBlank()) return url;
            String b64 = first.path("b64_json").asText("");
            if (!b64.isBlank()) return "data:image/png;base64," + b64;
        }

        String msg = root.path("error").path("message")
                .asText(root.path("message").asText(response.body()));
        log.error("MiMo 图像生成响应异常: HTTP {} {}", response.statusCode(), msg);
        throw new RuntimeException("MiMo: " + msg);
    }

    // ========== FAL.ai 后端（同 hermes-agent） ==========

    /**
     * FAL.ai FLUX 2 Pro — hermes-agent 完全一致的模型
     *
     * REST API 流程：
     *   1. POST https://queue.fal.run/fal-ai/flux-2/pro → 得到 request_id
     *   2. GET  https://queue.fal.run/fal-ai/flux-2/pro/requests/{id} → 轮询直到完成
     *   3. 返回 images[0].url
     *
     * 可选：用 Clarity Upscaler 放大（hermes-agent 会自动做）
     */
    private String generateViaFal(String prompt, String imageSize) throws Exception {
        String key = resolveKey(falKey, "FAL_KEY");

        // 1. 提交生成请求
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", prompt);
        payload.put("image_size", imageSize);
        payload.put("num_inference_steps", 50);
        payload.put("guidance_scale", 4.5);
        payload.put("num_images", 1);
        payload.put("output_format", "png");
        payload.put("sync_mode", true); // 同步模式，直接返回结果

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://queue.fal.run/fal-ai/flux-2/pro"))
                .header("Authorization", "Key " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(response.body());

        // 同步模式直接返回 images
        JsonNode images = root.path("images");
        if (images.isArray() && !images.isEmpty()) {
            return images.get(0).path("url").asText(null);
        }

        // 异步模式：需要轮询
        String requestId = root.path("request_id").asText("");
        if (!requestId.isBlank()) {
            return pollFalResult(key, requestId);
        }

        log.error("FAL.ai 响应异常: {}", response.body());
        return null;
    }

    /**
     * 轮询 FAL.ai 异步结果
     */
    private String pollFalResult(String key, String requestId) throws Exception {
        for (int i = 0; i < 30; i++) { // 最多轮询30次，每次2秒
            Thread.sleep(2000);

            HttpRequest pollReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://queue.fal.run/fal-ai/flux-2/pro/requests/" + requestId))
                    .header("Authorization", "Key " + key)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> pollResp = HTTP.send(pollReq, HttpResponse.BodyHandlers.ofString());
            JsonNode pollRoot = MAPPER.readTree(pollResp.body());
            String status = pollRoot.path("status").asText("");

            if ("COMPLETED".equals(status)) {
                JsonNode images = pollRoot.path("images");
                if (images.isArray() && !images.isEmpty()) {
                    return images.get(0).path("url").asText(null);
                }
            } else if ("FAILED".equals(status)) {
                log.error("FAL.ai 生成失败: {}", pollResp.body());
                return null;
            }
            log.info("FAL.ai 轮询: status={} ({}/30)", status, i + 1);
        }
        log.error("FAL.ai 轮询超时");
        return null;
    }

    // ========== SiliconFlow 后端（免费额度） ==========

    /**
     * SiliconFlow — 提供免费的 FLUX.1-schnell 模型
     * 文档: https://docs.siliconflow.cn/api-reference/images/images-generations
     */
    private String generateViaSiliconFlow(String prompt, String imageSize) throws Exception {
        String key = resolveKey(siliconflowKey, "SILICONFLOW_API_KEY");

        // SiliconFlow 要求 "WxH" 格式，如 "1024x1024"
        // 使用官方推荐的 Kolors 模型尺寸
        String sizeStr = toSiliconFlowSize(imageSize);

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", "Kwai-Kolors/Kolors");
        payload.put("prompt", prompt);
        payload.put("image_size", sizeStr);
        payload.put("batch_size", 1);
        payload.put("num_inference_steps", 20);  // 官方默认 20
        payload.put("guidance_scale", 7.5);       // 官方默认 7.5

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.siliconflow.cn/v1/images/generations"))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode images = root.path("images");

        if (images.isArray() && !images.isEmpty()) {
            return images.get(0).path("url").asText(null);
        }

        // 解析具体错误原因抛出，让上层 fallback 链记录并继续或终止
        String errBody = response.body();
        log.error("SiliconFlow 响应异常: {}", errBody);
        // 提取 message 字段（SiliconFlow 错误格式：{"message":"Api key is invalid"}）
        String apiMsg = root.path("message").asText(root.path("error").asText("未知错误"));
        throw new RuntimeException("SiliconFlow: " + apiMsg);
    }

    // ========== 智谱CogView后端（国产备选） ==========

    /**
     * 智谱 CogView-3 — 国产模型，中文prompt支持好
     */
    private String generateViaCogView(String prompt, String imageSize) throws Exception {
        String key = resolveKey(zhipuKey, "ZHIPU_API_KEY");

        // CogView API 格式
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", "cogview-3-flash");
        payload.put("prompt", prompt);
        payload.put("size", mapToCogViewSize(imageSize));
        payload.put("n", 1);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://open.bigmodel.cn/api/paas/v4/images/generations"))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode data = root.path("data");

        if (data.isArray() && !data.isEmpty()) {
            return data.get(0).path("url").asText(null);
        }

        String errBody = response.body();
        log.error("CogView 响应异常: {}", errBody);
        String apiMsg = root.path("error").path("message").asText(root.path("message").asText("未知错误"));
        throw new RuntimeException("CogView: " + apiMsg);
    }

    // ========== 后端选择 ==========

    private String resolveBackend() {
        if (configuredBackend != null && !configuredBackend.isBlank()) {
            return configuredBackend.toLowerCase().trim();
        }
        // 自动检测优先级: ChatAnywhere > MiMo > FAL > SiliconFlow > 智谱
        if (chatAnywhereKey != null && !chatAnywhereKey.isBlank()) return "chatanywhere";
        if (mimoKey != null && !mimoKey.isBlank()) return "mimo";
        if (falKey != null && !falKey.isBlank()) return "fal";
        if (siliconflowKey != null && !siliconflowKey.isBlank()) return "siliconflow";
        if (zhipuKey != null && !zhipuKey.isBlank()) return "zhipu";
        return "mimo"; // 默认使用项目主模型网关
    }

    /**
     * 构建 fallback 链：只包含已配置了有效 key 的后端，按优先级排列。
     * primaryBackend 排首位，其余有效后端依次追加。
     */
    private List<String> buildFallbackChain(String primaryBackend) {
        List<String> chain = new ArrayList<>();
        // 哪些后端有 key 就加入
        Map<String, String> keyMap = Map.of(
                "chatanywhere", chatAnywhereKey != null ? chatAnywhereKey : "",
                "mimo",        mimoKey         != null ? mimoKey        : "",
                "fal",         falKey          != null ? falKey         : "",
                "siliconflow", siliconflowKey  != null ? siliconflowKey : "",
                "zhipu",       zhipuKey        != null ? zhipuKey       : ""
        );
        List<String> allBackends = List.of("chatanywhere", "mimo", "fal", "siliconflow", "zhipu");

        // 先放 primary
        if (keyMap.getOrDefault(primaryBackend, "").isBlank()) {
            // primary 没 key，尝试从环境变量检测
            String envKey = switch (primaryBackend) {
                case "chatanywhere" -> System.getenv("CHATANYWHERE_API_KEY");
                case "mimo", "xiaomi" -> System.getenv("MIMO_API_KEY");
                case "fal"         -> System.getenv("FAL_KEY");
                case "siliconflow" -> System.getenv("SILICONFLOW_API_KEY");
                case "zhipu"       -> System.getenv("ZHIPU_API_KEY");
                default -> null;
            };
            if (envKey != null && !envKey.isBlank()) chain.add(primaryBackend);
        } else {
            chain.add(primaryBackend);
        }

        // 再追加其他有 key 的
        for (String b : allBackends) {
            if (!b.equals(primaryBackend)) {
                String k = keyMap.getOrDefault(b, "");
                if (!k.isBlank()) chain.add(b);
                else {
                    String envKey = switch (b) {
                        case "chatanywhere" -> System.getenv("CHATANYWHERE_API_KEY");
                        case "mimo", "xiaomi" -> System.getenv("MIMO_API_KEY");
                        case "fal"         -> System.getenv("FAL_KEY");
                        case "siliconflow" -> System.getenv("SILICONFLOW_API_KEY");
                        case "zhipu"       -> System.getenv("ZHIPU_API_KEY");
                        default -> null;
                    };
                    if (envKey != null && !envKey.isBlank()) chain.add(b);
                }
            }
        }

        if (chain.isEmpty()) {
            // 没有任何有效 key，仍然返回 primary，让它报错给上层
            chain.add(primaryBackend);
        }
        return chain;
    }

    private String resolveKey(String value, String envName) {
        if (value != null && !value.isBlank()) return value;
        String envVal = System.getenv(envName);
        if (envVal != null && !envVal.isBlank()) return envVal;
        throw new IllegalStateException("未配置 " + envName + "，无法使用该图像生成后端");
    }

    // ========== 工具方法 ==========

    /**
     * 将简单比例参数映射为 FAL.ai 的 image_size
     * 与 hermes-agent ASPECT_RATIO_MAP 完全一致
     */
    private static String mapAspectRatio(String aspectRatio) {
        return switch (aspectRatio.toLowerCase().trim()) {
            case "landscape" -> "landscape_16_9";
            case "square" -> "square_hd";
            case "portrait" -> "portrait_16_9";
            default -> "landscape_16_9";
        };
    }

    /** 将 image_size 解析为 width/height */
    private static Map<String, Integer> parseImageSize(String imageSize) {
        return switch (imageSize) {
            case "landscape_16_9" -> Map.of("width", 1344, "height", 768);
            case "landscape_4_3" -> Map.of("width", 1024, "height", 768);
            case "square_hd" -> Map.of("width", 1024, "height", 1024);
            case "square" -> Map.of("width", 512, "height", 512);
            case "portrait_4_3" -> Map.of("width", 768, "height", 1024);
            case "portrait_16_9" -> Map.of("width", 768, "height", 1344);
            default -> Map.of("width", 1024, "height", 768);
        };
    }

    /** 转换为 SiliconFlow Kolors 推荐的 "WxH" 格式 */
    private static String toSiliconFlowSize(String imageSize) {
        // Kolors 官方推荐尺寸：
        //   "1024x1024" (1:1)  "960x1280" (3:4)  "768x1024" (3:4)
        //   "720x1440" (1:2)   "720x1280" (9:16)
        // 注意：Kolors 不支持真正的横版（如16:9 landscape），landscape 映射为方形
        return switch (imageSize) {
            case "landscape_16_9" -> "1024x1024"; // 无横版，用方形代替
            case "landscape_4_3"  -> "1024x1024"; // 无横版，用方形代替
            case "square_hd"      -> "1024x1024"; // 1:1
            case "square"         -> "1024x1024"; // 1:1
            case "portrait_4_3"   -> "960x1280";  // 3:4 竖版
            case "portrait_16_9"  -> "720x1280";  // 9:16 竖版
            default               -> "1024x1024"; // 默认方形
        };
    }

    /** 映射为 CogView 的 size 格式 */
    private static String mapToCogViewSize(String imageSize) {
        Map<String, Integer> size = parseImageSize(imageSize);
        return size.get("width") + "x" + size.get("height");
    }

    /** 映射为 OpenAI/ChatAnywhere 图片接口支持的 size。 */
    private static String mapToOpenAiImageSize(String imageSize) {
        return switch (imageSize) {
            case "portrait_16_9", "portrait_4_3" -> "1024x1792";
            case "landscape_16_9", "landscape_4_3" -> "1792x1024";
            case "square_4K" -> "3840x2160";
            default -> "1024x1024";
        };
    }

    /**
     * 安全网：在 generated-images 目录中查找最近 N 秒内生成的图片。
     * 用于处理 HTTP 超时但图片已保存到磁盘的竞态条件。
     */
    private String findRecentGeneratedImage(Path imageDir, int withinSeconds) {
        if (!Files.exists(imageDir)) return null;
        long cutoff = System.currentTimeMillis() - withinSeconds * 1000L;
        try (var stream = Files.list(imageDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".png") || p.toString().endsWith(".jpg"))
                    .filter(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis() > cutoff; }
                        catch (Exception e) { return false; }
                    })
                    .max(java.util.Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (Exception e) { return 0; }
                    }))
                    .map(p -> p.getFileName().toString())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String errorJson(String message) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("success", false);
            root.put("error", message);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }
}
