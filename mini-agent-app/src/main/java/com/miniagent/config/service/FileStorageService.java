package com.miniagent.config.service;

import com.miniagent.config.entity.FileUpload;
import com.miniagent.config.repository.FileUploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final FileUploadRepository fileUploadRepository;
    private final Path baseDir;

    public FileStorageService(
            FileUploadRepository fileUploadRepository,
            @Value("${file.upload.base-dir:./user-uploads}") String baseDir) {
        this.fileUploadRepository = fileUploadRepository;
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            log.error("Failed to create base upload directory: {}", this.baseDir, e);
        }
    }

    public FileUpload saveFile(Long userId, String sessionId, String originalFilename,
                               String mimeType, String base64Content) {
        try {
            Path userDir = baseDir.resolve(String.valueOf(userId));
            Files.createDirectories(userDir);

            String ext = "";
            int dotIdx = originalFilename.lastIndexOf('.');
            if (dotIdx > 0) {
                ext = originalFilename.substring(dotIdx);
            }
            String storedFilename = UUID.randomUUID().toString().substring(0, 8) + "_" + System.currentTimeMillis() + ext;

            byte[] data = Base64.getDecoder().decode(base64Content);

            // For text files, detect encoding and convert to UTF-8
            if (isTextFile(originalFilename, mimeType)) {
                data = toUtf8(data);
                if (!storedFilename.endsWith(".txt")) {
                    storedFilename = storedFilename.substring(0, storedFilename.lastIndexOf('.')) + ".txt";
                }
            }

            Path filePath = userDir.resolve(storedFilename);
            Files.write(filePath, data);

            FileUpload fileUpload = new FileUpload();
            fileUpload.setUserId(userId);
            fileUpload.setOriginalFilename(originalFilename);
            fileUpload.setStoredFilename(storedFilename);
            fileUpload.setMimeType(mimeType);
            fileUpload.setFileSize((long) data.length);
            fileUpload.setFilePath(filePath.toString());
            fileUpload.setSessionId(sessionId);

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
        if (mimeType != null && (mimeType.startsWith("text/") || mimeType.contains("json")
                || mimeType.contains("xml") || mimeType.contains("javascript"))) {
            return true;
        }
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        String[] exts = {".txt", ".csv", ".json", ".md", ".xml", ".html", ".htm",
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
