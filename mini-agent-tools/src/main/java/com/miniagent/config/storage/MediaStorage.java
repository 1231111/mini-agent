package com.miniagent.config.storage;

import org.springframework.beans.factory.annotation.Autowired;

import com.miniagent.common.MessageConstants;
import com.miniagent.memory.AgentDataPaths;
import com.miniagent.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 统一媒体存储：generated / conversations / uploads 三桶，均在 {@link AgentDataPaths} 下。
 * 生成媒体按当前用户分目录保存，只能通过带认证的 /api/generated-media/** 读取。
 */
@Component
public class MediaStorage {

    private static final Logger log = LoggerFactory.getLogger(MediaStorage.class);

    @Autowired

    private AgentDataPaths paths;

    

    /** 生成媒体的物理根目录；不能直接注册成 Spring 静态资源。 */
    public Path generatedDir() { return paths.mediaGenerated(); }
    public Path generatedRoot() { return generatedDir(); }

    /** 当前执行 owner 的生成目录。缺少 owner 时拒绝写入，避免回退到共享公共目录。 */
    public Path generatedOwnerDir() {
        Long owner = MemoryStore.getCurrentUser();
        if (owner == null || owner <= 0) {
            throw new IllegalStateException("Generated media requires an authenticated owner context");
        }
        Path root = generatedRoot().toAbsolutePath().normalize();
        Path ownerDir = root.resolve(String.valueOf(owner)).normalize();
        if (!ownerDir.startsWith(root)) {
            throw new IllegalStateException("Invalid generated-media owner directory");
        }
        return ownerDir;
    }

    /** 把 owner 目录中的文件转换成受保护 URL。 */
    public String generatedUrl(String filename) {
        String safeName = safeFilename(filename);
        Long owner = MemoryStore.getCurrentUser();
        if (owner == null || owner <= 0) {
            throw new IllegalStateException("Generated media requires an authenticated owner context");
        }
        return "/api/generated-media/" + owner + "/" + safeName;
    }
    public Path conversationsDir() { return paths.mediaConversations(); }
    public Path uploadsDir() { return paths.mediaUploads(); }

    public Path resolve(String relativeOrUrl) {
        return paths.resolveMedia(relativeOrUrl);
    }

    /** 保存生成图并返回带 owner 的受保护 URL。 */
    public String saveGenerated(byte[] bytes, String filename) throws IOException {
        Path dir = generatedOwnerDir();
        Files.createDirectories(dir);
        String safeName = safeFilename(filename);
        Path file = dir.resolve(safeName).normalize();
        if (!file.startsWith(dir)) {
            throw new IOException(String.format(MessageConstants.FILE_INVALID_NAME, filename));
        }
        Files.write(file, bytes);
        return generatedUrl(safeName);
    }

    private static String safeFilename(String filename) {
        String value = Objects.requireNonNullElse(filename, "").trim();
        if (value.isEmpty() || value.length() > 180
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                || value.contains("..")) {
            throw new IllegalArgumentException(String.format(MessageConstants.FILE_INVALID_NAME, filename));
        }
        return value;
    }

    /** 对话附图：返回相对键 conversation-images/{sessionId}/{file}（兼容旧库字段） */
    public List<String> saveConversationImages(String sessionId, List<String> imageDataUrls) {
        List<String> out = new ArrayList<>();
        if (Objects.isNull(imageDataUrls) || imageDataUrls.isEmpty()) {
            return out;
        }
        try {
            Path imgDir = conversationsDir().resolve(Optional.ofNullable(sessionId).orElse("_default"));
            Files.createDirectories(imgDir);
            long ts = System.currentTimeMillis();
            for (int i = 0; i < imageDataUrls.size(); i++) {
                String dataUrl = imageDataUrls.get(i);
                String base64 = dataUrl;
                String ext = "png";
                if (Objects.nonNull(dataUrl) && dataUrl.startsWith("data:")) {
                    int commaIdx = dataUrl.indexOf(',');
                    if (commaIdx > 0) {
                        String header = dataUrl.substring(0, commaIdx);
                        base64 = dataUrl.substring(commaIdx + 1);
                        if (header.contains("jpeg") || header.contains("jpg")) {
                            ext = "jpg";
                        }
                        else if (header.contains("webp")) {
                            ext = "webp";
                        }
                        else if (header.contains("gif")) {
                            ext = "gif";
                        }
                    }
                }
                String filename = ts + "_" + i + "." + ext;
                Files.write(imgDir.resolve(filename), Base64.getDecoder().decode(base64));
                out.add("conversation-images/" + sessionId + "/" + filename);
            }
        } catch (Exception e) {
            log.warn("保存对话图片失败: {}", e.getMessage());
        }
        return out;
    }

    /** 将已上传音/视频复制到会话目录，返回 conversation-images/{sessionId}/{file} 键 */
    public String copyUploadToConversation(String sessionId, Path source, String originalFilename) {
        try {
            Path dir = conversationsDir().resolve(Optional.ofNullable(sessionId).orElse("_default"));
            Files.createDirectories(dir);
            String ext = "";
            if (Objects.nonNull(originalFilename)) {
                int dot = originalFilename.lastIndexOf('.');
                if (dot > 0) {
                    ext = originalFilename.substring(dot);
                }
            }
            String filename = System.currentTimeMillis() + "_"
                    + UUID.randomUUID().toString().substring(0, 6) + ext;
            Path dest = dir.resolve(filename).normalize();
            if (!dest.startsWith(dir))
                throw new IOException(String.format(MessageConstants.FILE_INVALID_NAME, filename));
            Files.copy(source, dest);
            return "conversation-images/" + sessionId + "/" + filename;
        } catch (Exception e) {
            log.warn("复制会话媒体失败: {}", e.getMessage());
            return null;
        }
    }
}
