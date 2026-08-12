package com.miniagent.config.service;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;

import com.miniagent.agent.web.UploadedDocumentService;
import com.miniagent.agent.web.MultimodalMedia;
import com.miniagent.config.entity.FileUpload;
import com.miniagent.config.repository.FileUploadRepository;
import com.miniagent.config.storage.MediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.Objects;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    @Autowired
    private FileUploadRepository fileUploadRepository;
    @Lazy
    @Autowired
    private UploadedDocumentService uploadedDocumentService;
    @Autowired
    private MediaStorage mediaStorage;
    private Path baseDir;

    @PostConstruct
    private void initAutowiredComputed() {
        this.baseDir = mediaStorage.uploadsDir();
    }

    public FileUpload saveFile(Long userId, String sessionId, String originalFilename,
                               String mimeType, String base64Content) {
        try {
            Path userDir = baseDir.resolve(String.valueOf(userId));
            Files.createDirectories(userDir);

            String ext = "";
            int dotIdx = Objects.isNull(originalFilename) ? -1 : originalFilename.lastIndexOf('.');
            if (dotIdx > 0) {
                ext = originalFilename.substring(dotIdx);
            }
            String storedFilename = UUID.randomUUID().toString().substring(0, 8)
                    + "_" + System.currentTimeMillis() + ext;

            byte[] data = Base64.getDecoder().decode(base64Content);

            // 文本类只做编码归一到 UTF-8，保留原扩展名（不再强行改成 .txt）
            if (isTextFile(originalFilename, mimeType)) {
                data = toUtf8(data);
            }

            Path filePath = userDir.resolve(storedFilename);
            Files.write(filePath, data);

            FileUpload fileUpload = new FileUpload();
            fileUpload.setUserId(userId);
            fileUpload.setOriginalFilename(originalFilename);
            fileUpload.setStoredFilename(storedFilename);
            fileUpload.setMimeType(mimeType);
            fileUpload.setFileSize((long) data.length);
            fileUpload.setFilePath(filePath.toAbsolutePath().toString());
            fileUpload.setSessionId(sessionId);

            // 上传即提取：Office/PDF/大文本写侧车；音视频走原生多模态，跳过抽文本
            if (!MultimodalMedia.isNativeMedia(originalFilename, mimeType)) {
                try {
                    String sidecar = uploadedDocumentService.extractAndWriteSidecar(
                            userId, filePath, originalFilename, mimeType);
                    fileUpload.setExtractedTextPath(sidecar);
                } catch (Exception e) {
                    log.warn("上传后文本提取失败（文件已保存）: {}", originalFilename, e);
                }
            }

            return fileUploadRepository.save(fileUpload);
        } catch (Exception e) {
            log.error("Failed to save file: {} for user: {}", originalFilename, userId, e);
            throw new RuntimeException("File save failed: " + e.getMessage(), e);
        }
    }

    public List<FileUpload> getUserFiles(Long userId) {
        return fileUploadRepository.findByUserId(userId);
    }

    public List<FileUpload> getUserSessionFiles(Long userId, String sessionId) {
        return fileUploadRepository.findByUserIdAndSessionId(userId, sessionId);
    }

    public Path getBaseDir() {
        return baseDir;
    }

    private boolean isTextFile(String filename, String mimeType) {
        if (Objects.nonNull(mimeType) && (mimeType.startsWith("text/") || mimeType.contains("json")
                || mimeType.contains("xml") || mimeType.contains("javascript")
                || mimeType.contains("markdown"))) {
            return true;
        }
        if (Objects.isNull(filename)) return false;
        String lower = filename.toLowerCase();
        String[] exts = {".txt", ".csv", ".json", ".md", ".markdown", ".xml", ".html", ".htm",
                ".css", ".js", ".ts", ".py", ".java", ".sql", ".sh", ".bat",
                ".yml", ".yaml", ".toml", ".ini", ".cfg", ".conf", ".log",
                ".c", ".cpp", ".h", ".hpp", ".go", ".rs", ".rb", ".php"};
        for (String e : exts) {
            if (lower.endsWith(e)) return true;
        }
        return false;
    }

    private byte[] toUtf8(byte[] data) {
        String utf8 = new String(data, StandardCharsets.UTF_8);
        if (!utf8.contains("\ufffd")) {
            return data;
        }
        try {
            String gbk = new String(data, Charset.forName("GBK"));
            if (!gbk.contains("\ufffd")) {
                return gbk.getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        return data;
    }
}
