package com.miniagent.agent.comfyui;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.config.storage.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.apache.commons.lang3.StringUtils;

/**
 * ComfyUI API 服务 — 统一封装所有 ComfyUI 交互。
 *
 * 集成能力来源（ClawHub skills）：
 *   - HuangYuChuh/ComfyUI_Skills_OpenClaw: 工作流执行、状态查询、依赖管理
 *   - salmonrk/openclaw-comfyui: 模板映射、图生视频(LTX-2)、角色LoRA注入
 *   - xtopher86/comfyui-runner: 实例启停管理
 *   - qqliaoxin/Comfyui-Api: 中文提示词翻译
 *   - yhsi5358/ComfyUI TTS: 语音合成
 *   - zeron-g/ComfyUI Painter: CivitAI 模型管理
 *
 * ComfyUI API 文档: https://docs.comfy.org/
 */
@Slf4j
@Service
public class ComfyUIService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    @Lazy
    @Autowired

    private ImageQualityChecker qualityChecker;
    @Autowired

    private MediaStorage mediaStorage;

    

    /** 默认反向提示词 — 覆盖常见质量问题 */
    private static final String DEFAULT_NEGATIVE_PROMPT =
            "low quality, worst quality, blurry, jpeg artifacts, watermark, text, logo, signature, "
            + "deformed, disfigured, ugly, bad anatomy, bad hands, extra fingers, missing fingers, "
            + "cropped, out of frame, duplicate, morbid, mutilated, poorly drawn face, mutation, "
            + "extra limbs, bad proportions, gross proportions, malformed limbs, fused fingers, "
            + "too many fingers, long neck, poorly drawn hands, cloned face";

    @Value("${comfyui.host:127.0.0.1}")
    private String host;

    @Value("${comfyui.port:8188}")
    private int port;

    private String baseUrl() {
        return "http://" + host + ":" + port;
    }

    // ==================== 1. 实例状态 (来自 comfyui-runner) ====================

    /**
     * 检查 ComfyUI 是否在线，返回系统状态。
     * 对应 comfyui-runner 的 start/stop/status 功能。
     */
    public String getStatus() {
        try {
            String body = httpGet(baseUrl() + "/system_stats");
            if (Objects.isNull(body)) {
                return toJson(Map.of(
                        "online", false,
                        "message", "ComfyUI 未运行或无法连接 (" + baseUrl() + ")",
                        "hint", "请先启动 ComfyUI：python main.py --listen 0.0.0.0 --port 8188"
                ));
            }
            JsonNode stats = mapper.readTree(body);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("online", true);
            result.put("url", baseUrl());
            if (stats.has("system")) {
                JsonNode sys = stats.get("system");
                result.put("os", sys.path("os").asText(""));
                result.put("python_version", sys.path("python_version").asText(""));
            }
            if (stats.has("devices")) {
                List<Map<String, Object>> devices = new ArrayList<>();
                for (JsonNode dev : stats.get("devices")) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("name", dev.path("name").asText(""));
                    d.put("type", dev.path("type").asText(""));
                    d.put("vram_total", formatBytes(dev.path("vram_total").asLong(0)));
                    d.put("vram_free", formatBytes(dev.path("vram_free").asLong(0)));
                    d.put("torch_vram_total", formatBytes(dev.path("torch_vram_total").asLong(0)));
                    d.put("torch_vram_free", formatBytes(dev.path("torch_vram_free").asLong(0)));
                    devices.add(d);
                }
                result.put("devices", devices);
            }
            return toJson(result);
        } catch (Exception e) {
            return toJson(Map.of("online", false, "error", e.getMessage()));
        }
    }

    // ==================== 2. 工作流管理 (来自 comfyui-skill-cli) ====================

    /**
     * 列出 ComfyUI 中已加载的工作流（通过 /object_info 获取节点信息）。
     */
    public String listWorkflows() {
        try {
            String body = httpGet(baseUrl() + "/object_info");
            if (Objects.isNull(body)) return error("无法获取工作流信息，ComfyUI 可能未启动");

            JsonNode nodes = mapper.readTree(body);
            List<String> nodeNames = new ArrayList<>();
            nodes.fieldNames().forEachRemaining(nodeNames::add);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total_nodes", nodeNames.size());
            result.put("categories", extractCategories(nodes));
            result.put("sampling_nodes", nodeNames.stream()
                    .filter(n -> n.contains("Sampler") || n.contains("KSampler"))
                    .toList());
            result.put("checkpoint_loaders", nodeNames.stream()
                    .filter(n -> n.contains("CheckpointLoader") || n.contains("checkpoint"))
                    .toList());
            result.put("hint", "使用 comfyui_execute 传入工作流 JSON 执行，或用 comfyui_txt2img 快捷文生图");
            return toJson(result);
        } catch (Exception e) {
            return error("获取工作流失败: " + e.getMessage());
        }
    }

    /**
     * 获取 ComfyUI 中已安装的 checkpoint 模型列表。
     * 先查询 /object_info/CheckpointLoaderSimple 获取可用模型，
     * 如果没有该节点则回退到 /object_info 的 ckpt_name 字段。
     */
    public String getCheckpointModels() {
        try {
            String body = httpGet(baseUrl() + "/object_info/CheckpointLoaderSimple");
            if (Objects.isNull(body)) {
                body = httpGet(baseUrl() + "/object_info");
                if (Objects.isNull(body)) return error("无法获取模型信息，ComfyUI 可能未启动");
            }

            JsonNode root = mapper.readTree(body);
            List<String> models = new ArrayList<>();

            // 从 CheckpointLoaderSimple 节点获取 input.ckpt_name 的值列表
            JsonNode checkpointNode = root.path("CheckpointLoaderSimple");
            if (checkpointNode.isObject()) {
                JsonNode ckptInput = checkpointNode.path("input").path("required").path("ckpt_name");
                if (ckptInput.isArray() && ckptInput.size() > 0) {
                    JsonNode nameList = ckptInput.get(0); // 第一个元素是选项数组
                    if (nameList.isArray()) {
                        for (JsonNode name : nameList) {
                            models.add(name.asText());
                        }
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            if (models.isEmpty()) {
                result.put("message", "未找到 checkpoint 模型。请在 ComfyUI 中安装至少一个 checkpoint 模型。");
                result.put("hint", "下载模型放到 ComfyUI/models/checkpoints/ 目录");
            } else {
                result.put("models", models);
                result.put("count", models.size());
                // 根据模型名推断风格标签，帮助 LLM 选择
                List<Map<String, String>> modelInfo = new ArrayList<>();
                for (String m : models) {
                    Map<String, String> info = new LinkedHashMap<>();
                    info.put("name", m);
                    info.put("style", inferStyle(m));
                    modelInfo.add(info);
                }
                result.put("model_details", modelInfo);
                result.put("hint", "根据用户要求的画风选择合适的 checkpoint，传给 comfyui_txt2img(checkpoint=\"模型名\")");
            }
            return toJson(result);
        } catch (Exception e) {
            return error("获取模型列表失败: " + e.getMessage());
        }
    }

    // ==================== 3. 执行工作流 (来自 comfyui-skill-cli) ====================

    /**
     * 提交工作流到 ComfyUI 执行（非阻塞），返回 prompt_id。
     * 工作流格式为 ComfyUI API 格式 JSON。
     */
    public String submitWorkflow(String workflowJson) {
        try {
            // ComfyUI API 要求 {"prompt": {...}} 格式
            String wrappedJson = workflowJson.trim().startsWith("{\"prompt\"")
                    ? workflowJson
                    : "{\"prompt\":" + workflowJson + "}";
            String body = httpPost(baseUrl() + "/prompt", wrappedJson);
            if (Objects.isNull(body)) return error("提交工作流失败，ComfyUI 可能未启动");

            JsonNode result = mapper.readTree(body);
            String promptId = result.path("prompt_id").asText("");
            return toJson(Map.of(
                    "status", "submitted",
                    "prompt_id", promptId,
                    "message", "工作流已提交，使用 comfyui_execute(action=status, prompt_id=\"" + promptId + "\") 查询进度"
            ));
        } catch (Exception e) {
            return error("提交工作流失败: " + e.getMessage());
        }
    }

    /**
     * 提交工作流并阻塞等待完成，返回最终结果（含图片链接）。
     * 内部轮询 ComfyUI history API，直到任务完成或超时。
     */
    private String submitAndWait(String workflowJson, int timeoutSeconds) {
        // 1. 提交
        String submitResult = submitWorkflow(workflowJson);
        if (submitResult.contains("\"error\"")) return submitResult;

        try {
            JsonNode submitted = mapper.readTree(submitResult);
            String promptId = submitted.path("prompt_id").asText("");
            if (promptId.isEmpty()) return error("提交成功但未获取到 prompt_id");

            log.info("ComfyUI 任务已提交: prompt_id={}, 等待完成(超时{}秒)", promptId, timeoutSeconds);

            // 2. 轮询等待
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            int pollInterval = 2000;  // 初始 2 秒

            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(pollInterval);

                String body = httpGet(baseUrl() + "/history/" + promptId);
                if (Objects.nonNull(body)) {
                    JsonNode history = mapper.readTree(body);
                    if (history.has(promptId)) {
                        JsonNode entry = history.get(promptId);
                        JsonNode status = entry.path("status");
                        String statusStr = status.path("status_str").asText("");

                        if ("error".equals(statusStr)) {
                            return error("ComfyUI 执行出错: " + status.toString());
                        }

                        // 有 outputs 就算完成
                        JsonNode outputs = entry.path("outputs");
                        if (outputs.isObject() && !outputs.isEmpty()) {
                            return buildImageResult(promptId, outputs);
                        }
                    }
                }

                // 自适应轮询间隔：前 30 秒 2 秒一次，之后 5 秒一次
                if (System.currentTimeMillis() - (deadline - timeoutSeconds * 1000L) > 30000) {
                    pollInterval = 5000;
                }
            }

            return toJson(Map.of(
                    "status", "timeout",
                    "prompt_id", promptId,
                    "message", "图片生成超时(" + timeoutSeconds + "秒)，可能仍在处理中。用 comfyui_execute(action=status, prompt_id=\"" + promptId + "\") 手动查询。"
            ));
        } catch (Exception e) {
            return error("等待 ComfyUI 完成失败: " + e.getMessage());
        }
    }

    /** 从 ComfyUI outputs 构建最终结果（下载图片到本地 + markdown 链接） */
    private String buildImageResult(String promptId, JsonNode outputs) {
        Path imageDir = mediaStorage.generatedDir();
        imageDir.toFile().mkdirs();

        List<String> images = new ArrayList<>();
        outputs.fields().forEachRemaining(nodeEntry -> {
            JsonNode nodeOutput = nodeEntry.getValue();
            if (nodeOutput.has("images")) {
                for (JsonNode img : nodeOutput.get("images")) {
                    String filename = img.path("filename").asText("");
                    String subfolder = img.path("subfolder").asText("");
                    String type = img.path("type").asText("output");
                    if (!filename.isEmpty()) {
                        // 1. 从 ComfyUI 下载图片到本地
                        String localFile = downloadFromComfyUI(filename, subfolder, type, imageDir);
                        if (Objects.nonNull(localFile)) {
                            // 2. 返回 Spring Boot 的 URL（前端可访问）
                            images.add("/generated-images/" + localFile);
                        }
                    }
                }
            }
            // 音频输出（TTS）
            if (nodeOutput.has("audio")) {
                for (JsonNode audio : nodeOutput.get("audio")) {
                    String filename = audio.path("filename").asText("");
                    if (!filename.isEmpty()) {
                        String localFile = downloadFromComfyUI(filename, "", "output", imageDir);
                        if (Objects.nonNull(localFile)) {
                            images.add("/generated-images/" + localFile);
                        }
                    }
                }
            }
        });

        if (images.isEmpty()) {
            return "{\"status\":\"error\",\"message\":\"任务完成但未找到输出文件\"}";
        }

        // 直接返回 markdown 图片链接（前端可直接渲染，AgentLoop 可识别为媒体交付）
        StringBuilder md = new StringBuilder();
        for (String imgUrl : images) {
            md.append("\n![生成的图片](").append(imgUrl).append(")");
        }
        return md.toString().trim();
    }

    /** 从 ComfyUI 下载文件到本地目录，返回本地文件名（不含路径），失败返回 null */
    private String downloadFromComfyUI(String filename, String subfolder, String type, Path saveDir) {
        try {
            String url = baseUrl() + "/view?filename=" + filename;
            if (Objects.nonNull(subfolder) && !subfolder.isEmpty()) url += "&subfolder=" + subfolder;
            if (Objects.nonNull(type) && !type.isEmpty()) url += "&type=" + type;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                log.warn("下载 ComfyUI 文件失败: {} 返回 {}", url, resp.statusCode());
                return null;
            }

            // 文件名加时间戳避免冲突
            String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : ".png";
            String localName = "comfyui_" + System.currentTimeMillis() + ext;
            Path savePath = saveDir.resolve(localName);
            Files.write(savePath, resp.body());
            log.info("ComfyUI 图片已保存: {}", savePath);
            return localName;
        } catch (Exception e) {
            log.error("下载 ComfyUI 文件失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 查询工作流执行状态。
     */
    public String checkStatus(String promptId) {
        try {
            String body = httpGet(baseUrl() + "/history/" + promptId);
            if (Objects.isNull(body)) return error("查询状态失败");

            JsonNode history = mapper.readTree(body);
            if (history.has(promptId)) {
                JsonNode entry = history.get(promptId);
                JsonNode outputs = entry.path("outputs");
                JsonNode status = entry.path("status");

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("prompt_id", promptId);
                result.put("status", status.path("status_str").asText("unknown"));
                result.put("completed", status.path("completed").asBoolean(false));

                // 提取输出图片 — 复用 buildImageResult 逻辑（下载到本地）
                if (outputs.isObject() && !outputs.isEmpty()) {
                    return buildImageResult(promptId, outputs);
                }
                return toJson(result);
            } else {
                return toJson(Map.of(
                        "prompt_id", promptId,
                        "status", "pending",
                        "message", "任务可能还在队列中或正在执行"
                ));
            }
        } catch (Exception e) {
            return error("查询状态失败: " + e.getMessage());
        }
    }

    // ==================== 4. 文生图快捷方式 ====================

    /**
     * 文生图（txt2img）— 构建标准工作流 JSON 并提交。
     * 集成自 comfyui-skill-cli 的 txt2img 工作流 + salmonrk 的模板映射。
     */
    public String txt2img(String prompt, String negativePrompt, int width, int height,
                           String checkpoint, int steps, double cfg, Long seed) {
        try {
            if (Objects.isNull(seed) || seed < 0) seed = new Random().nextLong() & 0xFFFFFFFFL;
            if (StringUtils.isBlank(negativePrompt)) {
                negativePrompt = DEFAULT_NEGATIVE_PROMPT;
            }
            if (StringUtils.isBlank(checkpoint)) {
                // 自动获取第一个可用模型
                String modelsJson = getCheckpointModels();
                try {
                    JsonNode modelsNode = mapper.readTree(modelsJson);
                    if (modelsNode.has("models") && modelsNode.get("models").isArray()
                            && modelsNode.get("models").size() > 0) {
                         checkpoint = modelsNode.get("models").get(0).asText();
                        log.info("自动选择 checkpoint: {}", checkpoint);
                    } else {
                        return error("ComfyUI 中没有可用的 checkpoint 模型。请先在 ComfyUI/models/checkpoints/ 中放置模型文件。");
                    }
                } catch (Exception e) {
                    return error("获取模型列表失败: " + e.getMessage());
                }
            }

            // 构建 ComfyUI API 格式的工作流
            String workflow = String.format("""
                    {
                      "3": {
                        "class_type": "KSampler",
                        "inputs": {
                          "seed": %d, "steps": %d, "cfg": %.1f,
                          "sampler_name": "dpmpp_2m", "scheduler": "karras",
                          "denoise": 1.0, "model": ["4", 0],
                          "positive": ["6", 0], "negative": ["7", 0],
                          "latent_image": ["5", 0]
                        }
                      },
                      "4": {
                        "class_type": "CheckpointLoaderSimple",
                        "inputs": {"ckpt_name": "%s"}
                      },
                      "5": {
                        "class_type": "EmptyLatentImage",
                        "inputs": {"width": %d, "height": %d, "batch_size": 1}
                      },
                      "6": {
                        "class_type": "CLIPTextEncode",
                        "inputs": {"text": "%s", "clip": ["4", 1]}
                      },
                      "7": {
                        "class_type": "CLIPTextEncode",
                        "inputs": {"text": "%s", "clip": ["4", 1]}
                      },
                      "8": {
                        "class_type": "VAEDecode",
                        "inputs": {"samples": ["3", 0], "vae": ["4", 2]}
                      },
                      "9": {
                        "class_type": "SaveImage",
                        "inputs": {"filename_prefix": "miniagent", "images": ["8", 0]}
                      }
                    }""",
                    seed, steps, cfg,
                    escapeJson(checkpoint),
                    width, height,
                    escapeJson(prompt),
                    escapeJson(negativePrompt));
            String result = submitAndWait(workflow, 180);
            return autoQualityCheck(result, prompt, negativePrompt, checkpoint,
                    "txt2img", width, height, 1.0, steps, cfg, null);
        } catch (Exception e) {
            return error("文生图失败: " + e.getMessage());
        }
    }

    /**
     * 图生图（img2img）— 上传参考图 + 构建工作流 + 提交等待。
     *
     * @param imagePath   本地图片路径（用户上传的参考图）
     * @param prompt      正向提示词（描述想要的效果）
     * @param negativePrompt 反向提示词
     * @param checkpoint  checkpoint 模型名
     * @param denoise     去噪强度 0.0~1.0（0=完全保留原图，1=完全重绘，推荐 0.4~0.7）
     * @param steps       采样步数
     * @param cfg         CFG 引导系数
     * @param seed        随机种子
     */
    public String img2img(String imagePath, String prompt, String negativePrompt,
                          String checkpoint, double denoise, int steps, double cfg, Long seed) {
        try {
            if (StringUtils.isBlank(imagePath)) return error("请提供参考图片路径");
            if (Objects.isNull(seed) || seed < 0) seed = new Random().nextLong() & 0xFFFFFFFFL;
            if (StringUtils.isBlank(negativePrompt)) negativePrompt = DEFAULT_NEGATIVE_PROMPT;
            if (denoise < 0 || denoise > 1) denoise = 0.6;

            // 1. 上传图片到 ComfyUI
            String uploadResult = uploadImage(imagePath);
            JsonNode uploadJson = mapper.readTree(uploadResult);
            if (!uploadJson.path("success").asBoolean(false)) {
                return uploadResult; // 上传失败
            }
            String filename = uploadJson.path("filename").asText("");
            log.info("参考图已上传: {}", filename);

            // 2. 自动选模型
            if (StringUtils.isBlank(checkpoint)) {
                String modelsJson = getCheckpointModels();
                try {
                    JsonNode modelsNode = mapper.readTree(modelsJson);
                    if (modelsNode.has("models") && modelsNode.get("models").isArray()
                            && modelsNode.get("models").size() > 0) {
                        checkpoint = modelsNode.get("models").get(0).asText();
                    } else {
                        return error("ComfyUI 中没有可用的 checkpoint 模型");
                    }
                } catch (Exception e) {
                    return error("获取模型列表失败: " + e.getMessage());
                }
            }

            // 3. 构建 img2img 工作流
            //    LoadImage → VAE Encode → KSampler(denoise<1) → VAEDecode → SaveImage
            String workflow = String.format("""
                    {
                      "1": {
                        "class_type": "LoadImage",
                        "inputs": {"image": "%s"}
                      },
                      "2": {
                        "class_type": "CheckpointLoaderSimple",
                        "inputs": {"ckpt_name": "%s"}
                      },
                      "3": {
                        "class_type": "CLIPTextEncode",
                        "inputs": {"text": "%s", "clip": ["2", 1]}
                      },
                      "4": {
                        "class_type": "CLIPTextEncode",
                        "inputs": {"text": "%s", "clip": ["2", 1]}
                      },
                      "5": {
                        "class_type": "VAEEncode",
                        "inputs": {"pixels": ["1", 0], "vae": ["2", 2]}
                      },
                      "6": {
                        "class_type": "KSampler",
                        "inputs": {
                          "seed": %d, "steps": %d, "cfg": %.1f,
                          "sampler_name": "dpmpp_2m", "scheduler": "karras",
                          "denoise": %.2f, "model": ["2", 0],
                          "positive": ["3", 0], "negative": ["4", 0],
                          "latent_image": ["5", 0]
                        }
                      },
                      "7": {
                        "class_type": "VAEDecode",
                        "inputs": {"samples": ["6", 0], "vae": ["2", 2]}
                      },
                      "8": {
                        "class_type": "SaveImage",
                        "inputs": {"filename_prefix": "miniagent_img2img", "images": ["7", 0]}
                      }
                    }""",
                    escapeJson(filename),
                    escapeJson(checkpoint),
                    escapeJson(prompt),
                    escapeJson(negativePrompt),
                    seed, steps, cfg, denoise);

            // 4. 提交并等待
            String result = submitAndWait(workflow, 180);
            return autoQualityCheck(result, prompt, negativePrompt, checkpoint,
                    "img2img", 0, 0, denoise, steps, cfg, imagePath);
        } catch (Exception e) {
            return error("图生图失败: " + e.getMessage());
        }
    }

    // ==================== 5. 图生图/编辑 (来自 salmonrk) ====================

    /**
     * 上传图片到 ComfyUI，返回文件名。
     * 用于 img2img、inpainting 等需要输入图片的工作流。
     */
    public String uploadImage(String imagePath) {
        try {
            java.io.File file = new java.io.File(imagePath);
            if (!file.exists()) return error("图片文件不存在: " + imagePath);

            // multipart upload
            String boundary = "----MiniAgent" + System.currentTimeMillis();
            byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());

            String header = "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"image\"; filename=\"" + file.getName() + "\"\r\n" +
                    "Content-Type: image/png\r\n\r\n";
            String footer = "\r\n--" + boundary + "--\r\n";

            byte[] body = concatBytes(
                    header.getBytes(StandardCharsets.UTF_8),
                    fileBytes,
                    footer.getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + "/upload/image"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = mapper.readTree(resp.body());
            return toJson(Map.of(
                    "success", true,
                    "filename", result.path("name").asText(""),
                    "subfolder", result.path("subfolder").asText(""),
                    "message", "图片已上传，可用于 img2img 工作流"
            ));
        } catch (Exception e) {
            return error("上传图片失败: " + e.getMessage());
        }
    }

    // ==================== 6. 图生视频 (来自 salmonrk LTX-2) ====================

    /**
     * 图生视频 — 上传图片 + LTX-2 工作流。
     * 这是一个长时间任务（5-10分钟），返回 prompt_id 供轮询。
     */
    public String img2video(String imagePath, String movementPrompt) {
        try {
            // 先上传图片
            String uploadResult = uploadImage(imagePath);
            JsonNode uploadJson = mapper.readTree(uploadResult);
            if (!uploadJson.path("success").asBoolean(false)) {
                return uploadResult; // 上传失败
            }
            String filename = uploadJson.path("filename").asText("");

            // 构建 LTX-2 图生视频工作流
            String workflow = String.format("""
                    {
                      "1": {
                        "class_type": "LoadImage",
                        "inputs": {"image": "%s"}
                      },
                      "2": {
                        "class_type": "LTXVImgToVideo",
                        "inputs": {
                          "image": ["1", 0],
                          "prompt": "%s",
                          "negative_prompt": "low quality, blurry",
                          "width": 720, "height": 1280,
                          "num_frames": 97, "fps": 24,
                          "steps": 30, "cfg": 3.0,
                          "seed": %d
                        }
                      },
                      "3": {
                        "class_type": "SaveVideo",
                        "inputs": {"filename_prefix": "miniagent_video", "video": ["2", 0]}
                      }
                    }""",
                    escapeJson(filename),
                    escapeJson(Optional.ofNullable(movementPrompt).orElse("gentle movement")),
                    new Random().nextLong() & 0xFFFFFFFFL);

            return submitAndWait(workflow, 600);  // 视频生成 5-10 分钟
        } catch (Exception e) {
            return error("图生视频失败: " + e.getMessage());
        }
    }

    // ==================== 7. TTS 语音合成 (来自 yhsi5358) ====================

    /**
     * 文本转语音 — 通过 ComfyUI 的 Qwen-TTS 节点。
     */
    public String tts(String text, String voice) {
        try {
            if (StringUtils.isBlank(voice)) voice = "default";

            String workflow = String.format("""
                    {
                      "1": {
                        "class_type": "QwenTTSNode",
                        "inputs": {
                          "text": "%s",
                          "voice": "%s"
                        }
                      },
                      "2": {
                        "class_type": "SaveAudio",
                        "inputs": {
                          "filename_prefix": "miniagent_tts",
                          "audio": ["1", 0]
                        }
                      }
                    }""",
                    escapeJson(text),
                    escapeJson(voice));

            return submitAndWait(workflow, 120);  // TTS 通常较快
        } catch (Exception e) {
            return error("TTS 失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    // ==================== 自动质检 + 重试 ====================

    private static final int MAX_QUALITY_RETRIES = 1;

    @Value("${comfyui.quality-check.enabled:true}")
    private boolean qualityCheckEnabled;

    /**
     * 自动质检：生成成功后调用 ImageQualityChecker 检查图片质量，
     * 不合格则根据建议调整提示词重试，最多重试 MAX_QUALITY_RETRIES 次。
     */
    private String autoQualityCheck(String result, String prompt, String negativePrompt,
                                     String checkpoint, String mode,
                                     int width, int height, double denoise, int steps, double cfg,
                                     String referenceImagePath) {
        if (!qualityCheckEnabled) {
            return result;
        }
        // 只对成功的结果做质检
        if (Objects.isNull(result) || result.contains("\"error\"") || !result.contains("\"status\":\"success\"")) {
            return result;
        }

        // 提取图片路径
        String imagePath = extractFirstImagePath(result);
        if (Objects.isNull(imagePath)) {
            log.info("自动质检跳过：未找到图片路径");
            return result;
        }

        String currentPrompt = prompt;
        String currentNegative = negativePrompt;

        for (int attempt = 1; attempt <= MAX_QUALITY_RETRIES; attempt++) {
            log.info("自动质检第 {}/{} 次: {}", attempt, MAX_QUALITY_RETRIES, imagePath);
            // img2img 有参考图时用对比质检
            String checkResult;
            if ("img2img".equals(mode) && StringUtils.isNotBlank(referenceImagePath)) {
                checkResult = qualityChecker.checkWithReference(imagePath, referenceImagePath);
            } else {
                checkResult = qualityChecker.check(imagePath);
            }

            // 判断是否通过
            boolean passed = checkResult.contains("\"pass\":true") || checkResult.contains("\"pass\": true");
            if (passed) {
                log.info("图片质检通过 (第{}次)", attempt);
                // 在结果中附加质检信息
                return appendQualityInfo(result, checkResult, attempt);
            }

            log.info("图片质检不通过 (第{}次): {}", attempt, checkResult);

            // 最后一次重试仍不通过，返回原结果+质检信息
            if (attempt >= MAX_QUALITY_RETRIES) {
                log.info("已达最大重试次数，返回当前结果");
                return appendQualityInfo(result, checkResult, attempt);
            }

            // 根据质检建议调整提示词重试
            String suggestion = extractSuggestion(checkResult);
            if (StringUtils.isNotBlank(suggestion)) {
                // 把建议追加到反向提示词
                currentNegative = (Optional.ofNullable(currentNegative).orElse(DEFAULT_NEGATIVE_PROMPT)
                        + ", " + suggestion);
                log.info("根据质检建议调整反向提示词: {}", suggestion);
            }

            // 重新生成
            String retryResult;
            if ("txt2img".equals(mode)) {
                retryResult = txt2img(currentPrompt, currentNegative, width, height, checkpoint, steps, cfg, null);
            } else {
                // img2img: 无法重试（需要原始参考图），直接返回
                log.info("img2img 模式不支持自动重试，返回当前结果");
                return appendQualityInfo(result, checkResult, attempt);
            }

            if (Objects.nonNull(retryResult) && retryResult.contains("\"status\":\"success\"")) {
                result = retryResult;
                imagePath = extractFirstImagePath(result);
            } else {
                log.warn("重试生成失败，返回上一次结果");
                return appendQualityInfo(result, checkResult, attempt);
            }
        }

        return result;
    }

    /** 从结果 JSON 中提取第一张图片的路径 */
    private String extractFirstImagePath(String result) {
        try {
            JsonNode root = mapper.readTree(result);
            JsonNode images = root.path("images");
            if (images.isArray() && images.size() > 0) {
                return images.get(0).asText();
            }
        } catch (Exception e) {
            // 尝试正则提取
            var m = java.util.regex.Pattern.compile("\"(/generated-images/[^\"]+)\"").matcher(result);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** 从质检结果中提取 suggestion */
    private String extractSuggestion(String checkResult) {
        try {
            JsonNode root = mapper.readTree(checkResult);
            return root.path("suggestion").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** 在结果 JSON 中附加质检信息 */
    private String appendQualityInfo(String result, String checkResult, int attempts) {
        try {
            JsonNode root = mapper.readTree(result);
            Map<String, Object> map = mapper.convertValue(root, Map.class);
            JsonNode check = mapper.readTree(checkResult);
            map.put("quality_check", Map.of(
                    "passed", check.path("pass").asBoolean(false),
                    "score", check.path("score").asInt(0),
                    "attempts", attempts,
                    "issues", check.path("issues").toString(),
                    "suggestion", check.path("suggestion").asText("")
            ));
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return result;
        }
    }

    private String httpGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return resp.body();
            log.warn("ComfyUI HTTP {} {}: {}", resp.statusCode(), url, resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body());
            return null;
        } catch (Exception e) {
            log.debug("ComfyUI 连接失败 {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String httpPost(String url, String jsonBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return resp.body();
            log.warn("ComfyUI POST {} 返回 {}: {}", url, resp.statusCode(), resp.body());
            return null;
        } catch (Exception e) {
            log.error("ComfyUI POST 失败 {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String toJson(Map<String, Object> map) {
        try { return mapper.writeValueAsString(map); }
        catch (Exception e) { return "{\"error\":\"序列化失败\"}"; }
    }

    private String error(String msg) {
        return "{\"error\":\"" + msg.replace("\"", "'") + "\"}";
    }

    /** 根据 checkpoint 文件名推断画风标签 */
    private static String inferStyle(String modelName) {
        String lower = modelName.toLowerCase();
        if (lower.contains("anime") || lower.contains("动漫") || lower.contains("manga")) return "动漫/二次元";
        if (lower.contains("realistic") || lower.contains("写实") || lower.contains("photo")) return "写实摄影";
        if (lower.contains("2d") || lower.contains("flat")) return "2D扁平";
        if (lower.contains("2.5d") || lower.contains("25d")) return "2.5D";
        if (lower.contains("xl") || lower.contains("sdxl")) return "SDXL高清";
        if (lower.contains("ink") || lower.contains("prt") || lower.contains("watercolor")) return "水墨/手绘";
        if (lower.contains("fantasy") || lower.contains("奇幻")) return "奇幻幻想";
        if (lower.contains("cute") || lower.contains("kawaii") || lower.contains("可爱")) return "可爱Q版";
        if (lower.contains("cyber") || lower.contains("punk")) return "赛博朋克";
        if (lower.contains("sd1.5") || lower.contains("sd15")) return "SD1.5通用";
        if (lower.contains("ouka") || lower.contains("star")) return "星空/唯美";
        return "通用";
    }

    private String escapeJson(String s) {
        if (Objects.isNull(s)) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int idx = (int) (Math.log(bytes) / Math.log(1024));
        return String.format("%.1f %s", bytes / Math.pow(1024, idx), units[Math.min(idx, units.length - 1)]);
    }

    private List<String> extractCategories(JsonNode nodes) {
        Set<String> cats = new TreeSet<>();
        nodes.fields().forEachRemaining(entry -> {
            String cat = entry.getValue().path("category").asText("");
            if (!cat.isEmpty()) cats.add(cat);
        });
        return new ArrayList<>(cats);
    }

    private byte[] concatBytes(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }
}
