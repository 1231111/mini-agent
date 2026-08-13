package com.miniagent.application;

import com.miniagent.agent.web.MultimodalMedia;
import com.miniagent.common.MessageConstants;
import com.miniagent.config.storage.MediaStorage;
import com.miniagent.web.dto.MediaRef;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 多模态消息构建：图片/音视频/文件 → UserMessage。
 * 从 AgentChatApplicationService 提取而来。
 */
@Component
@Slf4j
public class MultimodalMessageBuilder {

    @Autowired
    private MediaStorage mediaStorage;

    @Value("${agent.multimodal.audio-max-bytes:36700160}")
    private long audioMaxBytes;
    @Value("${agent.multimodal.video-max-bytes:36700160}")
    private long videoMaxBytes;

    public List<String> saveImagesToDisk(String sessionId, List<String> imageDataUrls) {
        return mediaStorage.saveConversationImages(sessionId, imageDataUrls);
    }

    public List<String> copyMediaToConversation(Long userId, String sessionId, List<MediaRef> media) {
        List<String> keys = new ArrayList<>();
        for (MediaRef ref : media) {
            try {
                Path src = resolveOwnedUpload(userId, ref.getFilePath());
                String key = mediaStorage.copyUploadToConversation(sessionId, src, ref.getFilename());
                if (StringUtils.isNotBlank(key)) keys.add(key);
            } catch (Exception e) {
                log.warn("复制音视频到会话失败: {}", e.getMessage());
            }
        }
        return keys;
    }

    public UserMessage buildMultimodalUserMessage(Long userId, String userMessage, List<String> images,
                                                   List<String> savedImagePaths,
                                                   List<MediaRef> mediaRefs,
                                                   List<String> savedMediaPaths) {
        List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        if (StringUtils.isNotBlank(userMessage)) text.append(userMessage);
        Optional.ofNullable(savedImagePaths).filter(paths -> !paths.isEmpty()).ifPresent(paths -> {
            text.append(String.format(MessageConstants.CHAT_USER_UPLOADED_IMAGES, paths.size()));
            for (int i = 0; i < paths.size(); i++) {
                text.append(String.format(MessageConstants.CHAT_IMAGE_LOCAL_PATH, i + 1, paths.get(i)))
                        .append('\n');
            }
        });
        if (Objects.nonNull(mediaRefs) && !mediaRefs.isEmpty()) {
            text.append(MessageConstants.CHAT_USER_UPLOADED_MEDIA);
            for (int i = 0; i < mediaRefs.size(); i++) {
                MediaRef r = mediaRefs.get(i);
                String pathNote = (Objects.nonNull(savedMediaPaths) && i < savedMediaPaths.size())
                        ? savedMediaPaths.get(i)
                        : r.getFilePath();
                String label = MultimodalMedia.KIND_VIDEO.equals(r.getKind()) ? "视频" : "音频";
                text.append(String.format(MessageConstants.CHAT_MEDIA_LOCAL_PATH, label, pathNote))
                        .append('\n');
            }
        }
        contents.add(TextContent.from(text.toString()));
        if (Objects.nonNull(images))
            images.forEach(img -> contents.add(ImageContent.from(img)));
        if (Objects.nonNull(mediaRefs)) {
            for (MediaRef ref : mediaRefs) {
                try {
                    appendMediaContent(contents, userId, ref);
                } catch (Exception e) {
                    log.warn("加载音视频失败 {}: {}", ref.getFilename(), e.getMessage());
                    contents.add(TextContent.from("[媒体读取失败: " + ref.getFilename() + "]"));
                }
            }
        }
        return UserMessage.from(contents);
    }

    public void appendMediaContent(List<dev.langchain4j.data.message.Content> contents,
                                    Long userId, MediaRef ref) throws IOException {
        Path path = Objects.nonNull(userId)
                ? resolveOwnedUpload(userId, ref.getFilePath())
                : mediaStorage.resolve(ref.getFilePath()).normalize();
        if (!Files.isRegularFile(path))
            throw new IOException("not found");
        long size = Files.size(path);
        String kind = Optional.ofNullable(ref.getKind())
                .orElseGet(() -> MultimodalMedia.kindOf(ref.getFilename(), ref.getMimeType()));
        long limit = MultimodalMedia.KIND_VIDEO.equals(kind) ? videoMaxBytes : audioMaxBytes;
        if (size > limit)
            throw new IOException("媒体超过上限 " + (limit / 1024 / 1024) + "MB");
        byte[] bytes = Files.readAllBytes(path);
        String b64 = Base64.getEncoder().encodeToString(bytes);
        String mime = MultimodalMedia.mimeOf(ref.getFilename(), ref.getMimeType(), kind);
        if (MultimodalMedia.KIND_VIDEO.equals(kind))
            contents.add(VideoContent.from(b64, mime));
        else
            contents.add(AudioContent.from(b64, mime));
    }

    public Path resolveOwnedUpload(Long userId, String filePath) throws IOException {
        Path p = mediaStorage.resolve(filePath).normalize();
        if (!Files.isRegularFile(p))
            throw new IOException("媒体文件不存在: " + filePath);
        Path userRoot = mediaStorage.uploadsDir().resolve(String.valueOf(userId)).normalize();
        Path convRoot = mediaStorage.conversationsDir().normalize();
        if (!p.startsWith(userRoot) && !p.startsWith(convRoot))
            throw new IOException("非法媒体路径");
        return p;
    }

    public static String buildDisplayQuestion(String userMessage, int imageCount, int mediaCount) {
        String base = StringUtils.isNotBlank(userMessage)
                ? userMessage
                : (imageCount + mediaCount > 0
                ? MessageConstants.CHAT_MEDIA_PLACEHOLDER
                : "");
        StringBuilder sb = new StringBuilder(base);
        if (imageCount > 0) sb.append(" 📷x").append(imageCount);
        if (mediaCount > 0) sb.append(" 🎙x").append(mediaCount);
        return sb.toString();
    }
}
