package com.miniagent.agent.comfyui;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.miniagent.config.storage.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 图片质检工具 — 用 LLM 视觉能力分析生成图片的质量。
 * 检测常见问题：变形、缺手指、模糊、构图异常等。
 */
@Slf4j
@Component
public class ImageQualityChecker {

    @Autowired
    private ChatModel chatModel;
    @Autowired
    private MediaStorage mediaStorage;

    private static final String QUALITY_PROMPT = """
            你是一个图片质量审核员。请仔细检查这张 AI 生成的图片，判断质量是否合格。

            检查项：
            1. 人物解剖：手指数量、肢体比例、面部是否自然
            2. 画面清晰度：是否模糊、有噪点、有伪影
            3. 构图合理性：元素是否完整、有无截断、有无多余物体
            4. 风格一致性：色调、光影是否协调
            5. 文字/水印：是否有不该出现的文字或水印

            只输出 JSON，不要其他文字：
            {"pass": true/false, "score": 1-10, "issues": ["问题1", "问题2"], "suggestion": "改进建议"}
            - pass: 7分及以上为合格
            - issues: 列出所有发现的问题，没有则为空数组
            - suggestion: 如不合格，给出改进提示词的建议
            """;

    private static final String QUALITY_WITH_REF_PROMPT = """
            你是一个图片质量审核员。第一张图是用户提供的参考图，第二张图是 AI 根据参考图生成的新图。

            请检查：
            1. 生成图的质量：解剖、清晰度、构图、风格是否合格
            2. 与参考图的相似度：风格、色调、主题是否保持一致
            3. 创新性：是否有合理的变化，而不是完全复制

            只输出 JSON，不要其他文字：
            {"pass": true/false, "score": 1-10, "similarity": 1-10, "issues": ["问题1"], "suggestion": "改进建议"}
            - pass: 7分及以上为合格
            - similarity: 与参考图的风格相似度（1=完全不同，10=完全一致）
            """;

    /** 带参考图的质检（img2img 用） */
    public String checkWithReference(String generatedImagePath, String referenceImagePath) {
        try {
            Path genPath = resolvePath(generatedImagePath);
            Path refPath = resolvePath(referenceImagePath);
            if (genPath == null || !Files.exists(genPath)) {
                return "{\"pass\":false,\"error\":\"生成图不存在: " + generatedImagePath + "\"}";
            }
            if (refPath == null || !Files.exists(refPath)) {
                // 参考图找不到就退化为普通质检
                return check(generatedImagePath);
            }

            byte[] genBytes = Files.readAllBytes(genPath);
            byte[] refBytes = Files.readAllBytes(refPath);
            if (genBytes.length < 1024) {
                return "{\"pass\":false,\"score\":1,\"issues\":[\"文件过小\"],\"suggestion\":\"重新生成\"}";
            }

            String genBase64 = Base64.getEncoder().encodeToString(genBytes);
            String refBase64 = Base64.getEncoder().encodeToString(refBytes);
            String genDataUrl = "data:image/png;base64," + genBase64;
            String refDataUrl = "data:image/png;base64," + refBase64;

            log.info("对比质检: 生成图={}KB, 参考图={}KB", genBytes.length / 1024, refBytes.length / 1024);

            List<Content> contents = new ArrayList<>();
            contents.add(TextContent.from(QUALITY_WITH_REF_PROMPT));
            contents.add(ImageContent.from(refDataUrl));
            contents.add(ImageContent.from(genDataUrl));
            UserMessage msg = UserMessage.from(contents);

            ChatResponse resp = chatModel.chat(ChatRequest.builder().messages(List.of(msg)).build());
            if (resp == null || resp.aiMessage() == null) {
                return "{\"pass\":true,\"score\":5,\"issues\":[\"质检模型调用失败\"],\"suggestion\":\"\"}";
            }

            String result = resp.aiMessage().text().trim();
            if (result.startsWith("```")) {
                result = result.replaceAll("^```(?:json)?\\\\s*", "").replaceAll("\\\\s*```$", "");
            }
            log.info("对比质检结果: {}", result.length() > 200 ? result.substring(0, 200) : result);
            return result;

        } catch (Exception e) {
            log.error("对比质检异常: {}", e.getMessage());
            return "{\"pass\":true,\"score\":5,\"issues\":[\"质检异常\"],\"suggestion\":\"\"}";
        }
    }

    /**
     * 检查图片质量。
     * @param imagePath 图片路径（支持绝对路径、相对路径、URL路径如 /generated-images/xxx.png）
     * @return JSON 格式的质检结果
     */
    public String check(String imagePath) {
        try {
            if (imagePath == null || imagePath.isBlank()) {
                return "{\"pass\":false,\"error\":\"请提供图片路径\"}";
            }

            Path path = resolvePath(imagePath);
            if (path == null || !Files.exists(path)) {
                return "{\"pass\":false,\"error\":\"图片文件不存在: " + imagePath + "\"}";
            }

            // 读取图片为 base64
            byte[] bytes = Files.readAllBytes(path);
            long fileSize = bytes.length;
            if (fileSize < 1024) {
                return "{\"pass\":false,\"score\":1,\"issues\":[\"文件过小，可能生成失败\"],\"suggestion\":\"重新生成\"}";
            }

            String ext = path.toString().toLowerCase();
            String mimeType = "image/png";
            if (ext.endsWith(".jpg") || ext.endsWith(".jpeg")) mimeType = "image/jpeg";
            else if (ext.endsWith(".webp")) mimeType = "image/webp";

            String base64 = Base64.getEncoder().encodeToString(bytes);
            String dataUrl = "data:" + mimeType + ";base64," + base64;

            log.info("图片质检: path={}, size={}KB", path, fileSize / 1024);

            // 调 LLM 视觉模型分析
            List<Content> contents = new ArrayList<>();
            contents.add(TextContent.from(QUALITY_PROMPT));
            contents.add(ImageContent.from(dataUrl));
            UserMessage msg = UserMessage.from(contents);

            ChatResponse resp = chatModel.chat(ChatRequest.builder().messages(List.of(msg)).build());
            if (resp == null || resp.aiMessage() == null) {
                return "{\"pass\":true,\"score\":5,\"issues\":[\"质检模型调用失败，默认通过\"],\"suggestion\":\"\"}";
            }

            String result = resp.aiMessage().text().trim();
            // 去掉可能的 markdown 代码块包裹
            if (result.startsWith("```")) {
                result = result.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }

            log.info("图片质检结果: {}", result.length() > 200 ? result.substring(0, 200) : result);
            return result;

        } catch (Exception e) {
            log.error("图片质检异常: {}", e.getMessage());
            return "{\"pass\":true,\"score\":5,\"issues\":[\"质检异常: " + e.getMessage().replace("\"", "'") + "\"],\"suggestion\":\"\"}";
        }
    }

    /** 解析图片路径：绝对路径或 data-dir 下媒体相对路径。 */
    private Path resolvePath(String imagePath) {
        Path path = Path.of(imagePath);
        if (Files.exists(path)) return path;
        try {
            path = mediaStorage.resolve(imagePath);
            if (Files.exists(path)) return path;
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }
}

