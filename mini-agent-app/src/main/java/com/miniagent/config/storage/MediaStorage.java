package com.miniagent.config.storage;

import com.miniagent.memory.AgentDataPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 统一媒体存储：generated / conversations / uploads 三桶，均在 {@link AgentDataPaths} 下。
 * HTTP 仍暴露 /generated-images/**（兼容前端），物理路径为 media/generated。
 */
@Component
public class MediaStorage {

    private static final Logger log = LoggerFactory.getLogger(MediaStorage.class);

    private final AgentDataPaths paths;

    public MediaStorage(AgentDataPaths paths) {
        this.paths = paths;
    }

    public Path generatedDir() { return paths.mediaGenerated(); }
    public Path conversationsDir() { return paths.mediaConversations(); }
    public Path uploadsDir() { return paths.mediaUploads(); }

    public Path resolve(String relativeOrUrl) {
        return paths.resolveMedia(relativeOrUrl);
    }

    /** 保存生成图，返回对外 URL 路径 /generated-images/{filename} */
    public String saveGenerated(byte[] bytes, String filename) throws IOException {
        Path dir = generatedDir();
        Files.createDirectories(dir);
        Path file = dir.resolve(filename).normalize();
        if (!file.startsWith(dir)) throw new IOException("非法文件名: " + filename);
        Files.write(file, bytes);
        return "/generated-images/" + filename;
    }

    /** 对话附图：返回相对键 conversation-images/{sessionId}/{file}（兼容旧库字段） */
    public List<String> saveConversationImages(String sessionId, List<String> imageDataUrls) {
        List<String> out = new ArrayList<>();
        if (imageDataUrls == null || imageDataUrls.isEmpty()) return out;
        try {
            Path imgDir = conversationsDir().resolve(sessionId == null ? "_default" : sessionId);
            Files.createDirectories(imgDir);
            long ts = System.currentTimeMillis();
            for (int i = 0; i < imageDataUrls.size(); i++) {
                String dataUrl = imageDataUrls.get(i);
                String base64 = dataUrl;
                String ext = "png";
                if (dataUrl != null && dataUrl.startsWith("data:")) {
                    int commaIdx = dataUrl.indexOf(',');
                    if (commaIdx > 0) {
                        String header = dataUrl.substring(0, commaIdx);
                        base64 = dataUrl.substring(commaIdx + 1);
                        if (header.contains("jpeg") || header.contains("jpg")) ext = "jpg";
                        else if (header.contains("webp")) ext = "webp";
                        else if (header.contains("gif")) ext = "gif";
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
}
