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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import java.util.Locale;
import java.util.regex.Pattern;

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
    @Autowired
    private ObjectProvider<UploadMalwareScanner> malwareScanners;
    private Path baseDir;

    @org.springframework.beans.factory.annotation.Value("${file.upload.max-size:734003200}")
    private long maxUploadSizeBytes = 734_003_200L;
    @org.springframework.beans.factory.annotation.Value("${file.extract.max-bytes:52428800}")
    private long maxExtractBytes = 52_428_800L;
    @org.springframework.beans.factory.annotation.Value("${file.upload.antivirus.required:false}")
    private boolean antivirusRequired;

    private static final Pattern SAFE_EXTENSION = Pattern.compile("\\.[a-z0-9]{1,10}");

    @PostConstruct
    private void initAutowiredComputed() {
        this.baseDir = mediaStorage.uploadsDir();
    }

    public FileUpload saveFile(Long userId, String sessionId, String originalFilename,
                               String mimeType, String base64Content) {
        if (base64Content == null) {
            throw new IllegalArgumentException("file content required");
        }
        long maxEncodedChars = ((maxUploadSizeBytes + 2) / 3) * 4;
        if (base64Content.length() > maxEncodedChars) {
            throw new IllegalArgumentException("file exceeds configured size limit");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Content);
            if (decoded.length > maxUploadSizeBytes) {
                throw new IllegalArgumentException("file exceeds configured size limit");
            }
            return saveFile(userId, sessionId, originalFilename, mimeType, decoded);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to save file for user {}", userId, e);
            throw new RuntimeException("File save failed: " + e.getMessage(), e);
        }
    }

    /**
     * 上传落盘。按流写入，不把整个文件再编码成 Base64。
     * 大于 {@code file.extract.max-bytes} 的文档只保存，不抽全文进上下文。
     */
    public FileUpload saveUploaded(Long userId, String sessionId, String originalFilename,
                                   String mimeType, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file content required");
        }
        long size = file.getSize();
        if (size > maxUploadSizeBytes) {
            throw new IllegalArgumentException("file exceeds configured size limit");
        }
        try {
            UploadContentInspector.Inspection inspection;
            try (InputStream probe = file.getInputStream()) {
                inspection = UploadContentInspector.inspect(originalFilename, mimeType, readHead(probe));
            }
            requireAccepted(inspection);
            scanIfRequired(file.getInputStream());
            Path filePath = prepareStoredPath(userId, originalFilename);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
            return persistUpload(userId, sessionId, originalFilename, mimeType,
                    filePath, size);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to save file for user {}", userId, e);
            throw new RuntimeException("File save failed: " + e.getMessage(), e);
        }
    }

    /** Byte-oriented overload used by internal callers and tests; avoids needless base64 copies. */
    public FileUpload saveFile(Long userId, String sessionId, String originalFilename,
                               String mimeType, byte[] data) {
        try {
            if (userId == null || userId <= 0) {
                throw new IllegalArgumentException("userId required");
            }
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("file content required");
            }
            if (data.length > maxUploadSizeBytes) {
                throw new IllegalArgumentException("file exceeds configured size limit");
            }
            boolean withinExtractionLimit = data.length <= maxExtractBytes;
            UploadContentInspector.Inspection inspection = UploadContentInspector.inspect(
                    originalFilename, mimeType, data);
            requireAccepted(inspection);
            scanIfRequired(new ByteArrayInputStream(data));
            if (baseDir == null) {
                throw new IllegalStateException("file storage is not initialized");
            }
            Path userDir = baseDir.resolve(String.valueOf(userId));
            userDir = userDir.toAbsolutePath().normalize();
            Path storageRoot = baseDir.toAbsolutePath().normalize();
            if (!userDir.startsWith(storageRoot)) {
                throw new IllegalArgumentException("invalid user storage path");
            }
            Files.createDirectories(userDir);

            String ext = safeExtension(originalFilename);
            String storedFilename = UUID.randomUUID().toString().substring(0, 8)
                    + "_" + System.currentTimeMillis() + ext;

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
            if (!MultimodalMedia.isNativeMedia(originalFilename, mimeType) && withinExtractionLimit) {
                try {
                    String sidecar = uploadedDocumentService.extractAndWriteSidecar(
                            userId, filePath, originalFilename, mimeType);
                    fileUpload.setExtractedTextPath(sidecar);
                } catch (Exception e) {
                    log.warn("上传后文本提取失败（文件已保存）: {}", originalFilename, e);
                }
            }

            return fileUploadRepository.save(fileUpload);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to save file for user {}", userId, e);
            throw new RuntimeException("File save failed: " + e.getMessage(), e);
        }
    }

    private Path prepareStoredPath(Long userId, String originalFilename) throws IOException {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId required");
        }
        if (baseDir == null) {
            throw new IllegalStateException("file storage is not initialized");
        }
        Path userDir = baseDir.resolve(String.valueOf(userId)).toAbsolutePath().normalize();
        Path storageRoot = baseDir.toAbsolutePath().normalize();
        if (!userDir.startsWith(storageRoot)) {
            throw new IllegalArgumentException("invalid user storage path");
        }
        Files.createDirectories(userDir);
        String storedFilename = UUID.randomUUID().toString().substring(0, 8)
                + "_" + System.currentTimeMillis() + safeExtension(originalFilename);
        return userDir.resolve(storedFilename);
    }

    private FileUpload persistUpload(Long userId, String sessionId, String originalFilename,
                                     String mimeType, Path filePath, long size) {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setUserId(userId);
        fileUpload.setOriginalFilename(originalFilename);
        fileUpload.setStoredFilename(filePath.getFileName().toString());
        fileUpload.setMimeType(mimeType);
        fileUpload.setFileSize(size);
        fileUpload.setFilePath(filePath.toAbsolutePath().toString());
        fileUpload.setSessionId(sessionId);
        boolean nativeMedia = MultimodalMedia.isNativeMedia(originalFilename, mimeType);
        if (!nativeMedia && size <= maxExtractBytes) {
            try {
                String sidecar = uploadedDocumentService.extractAndWriteSidecar(
                        userId, filePath, originalFilename, mimeType);
                fileUpload.setExtractedTextPath(sidecar);
            } catch (Exception e) {
                log.warn("上传后文本提取失败（文件已保存）: {}", originalFilename, e);
            }
        } else if (!nativeMedia) {
            log.info("文件超过提取上限，仅落盘不抽全文: {} bytes={}", originalFilename, size);
        }
        return fileUploadRepository.save(fileUpload);
    }

    /** Keep only a short, single, alphanumeric extension from a client filename. */
    public static String safeExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()
                || originalFilename.indexOf('/') >= 0 || originalFilename.indexOf('\\') >= 0) {
            return "";
        }
        String name = originalFilename.trim();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot).toLowerCase(Locale.ROOT);
        return SAFE_EXTENSION.matcher(ext).matches() ? ext : "";
    }

    public List<FileUpload> getUserFiles(Long userId) {
        return fileUploadRepository.findByUserId(userId);
    }

    public List<FileUpload> getUserSessionFiles(Long userId, String sessionId) {
        return fileUploadRepository.findByUserIdAndSessionId(userId, sessionId);
    }

    /**
     * Erase all durable upload bytes owned by one user. Paths from the database are
     * treated as untrusted metadata and must remain below the configured media root;
     * an unexpected legacy path fails closed instead of deleting an arbitrary file.
     */
    @org.springframework.transaction.annotation.Transactional
    public void eraseUserUploads(long userId) {
        if (userId <= 0 || baseDir == null) {
            throw new IllegalArgumentException("有效用户与上传存储根是擦除的必要条件");
        }
        Path root = baseDir.toAbsolutePath().normalize();
        List<FileUpload> uploads = fileUploadRepository.findByUserId(userId);
        for (FileUpload upload : uploads) {
            deleteOwnedUploadPath(root, upload.getFilePath());
            deleteOwnedUploadPath(root, upload.getExtractedTextPath());
        }
        fileUploadRepository.deleteAll(uploads);
        Path userDir = root.resolve(String.valueOf(userId)).normalize();
        if (userDir.startsWith(root) && !userDir.equals(root)) {
            try {
                Files.deleteIfExists(userDir);
            } catch (IOException e) {
                throw new IllegalStateException("用户上传目录仍有未清理文件", e);
            }
        }
    }

    private static void deleteOwnedUploadPath(Path root, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return;
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalStateException("上传记录路径越过媒体存储根，已拒绝自动删除");
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException("无法删除用户上传文件", e);
        }
    }

    public Path getBaseDir() {
        return baseDir;
    }

    private void requireAccepted(UploadContentInspector.Inspection inspection) {
        if (inspection == null || !inspection.accepted()) {
            throw new IllegalArgumentException(inspection == null
                    ? "无法确认上传内容类型" : inspection.reason());
        }
    }

    private void scanIfRequired(InputStream input) throws IOException {
        try (InputStream stream = input) {
            UploadMalwareScanner scanner = malwareScanners == null
                    ? null : malwareScanners.getIfAvailable();
            if (scanner == null) {
                if (antivirusRequired) {
                    throw new IllegalStateException(
                            "上传病毒扫描器未配置；生产环境必须启用 ClamAV 或其它受信扫描器");
                }
                return;
            }
            UploadMalwareScanner.ScanResult result = scanner.scan(stream);
            if (result == null || !result.clean()) {
                throw new IllegalArgumentException("上传被恶意软件扫描拒绝");
            }
        }
    }

    private static byte[] readHead(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        byte[] buffer = new byte[512];
        int remaining = buffer.length;
        while (remaining > 0) {
            int read = input.read(buffer, buffer.length - remaining, remaining);
            if (read < 0) break;
            if (read == 0) continue;
            out.write(buffer, buffer.length - remaining, read);
            remaining -= read;
        }
        return out.toByteArray();
    }

    private boolean isTextFile(String filename, String mimeType) {
        if (Objects.nonNull(mimeType) && (mimeType.startsWith("text/") || mimeType.contains("json")
                || mimeType.contains("xml") || mimeType.contains("javascript")
                || mimeType.contains("markdown"))) {
            return true;
        }
        if (Objects.isNull(filename)) {
            return false;
        }
        String lower = filename.toLowerCase();
        String[] exts = {".txt", ".text", ".csv", ".tsv", ".json", ".jsonl", ".md", ".markdown", ".rst",
                ".xml", ".xhtml", ".xsd", ".xsl", ".xslt", ".html", ".htm", ".svg",
                ".css", ".scss", ".less", ".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx", ".vue",
                ".py", ".ipynb", ".java", ".kt", ".kts", ".go", ".rs", ".c", ".h", ".cc", ".cpp", ".hpp",
                ".cs", ".php", ".rb", ".swift", ".sql", ".sh", ".bash", ".zsh", ".bat", ".cmd", ".ps1",
                ".yml", ".yaml", ".toml", ".ini", ".cfg", ".conf", ".properties", ".env", ".log"};
        for (String e : exts) {
            if (lower.endsWith(e)) {
                return true;
            }
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
