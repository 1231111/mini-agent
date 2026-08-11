package com.miniagent.agent.web;

import com.miniagent.config.storage.MediaStorage;
import com.miniagent.web.dto.FileRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户上传文档 → 安全校验 → 文本提取 → 注入对话上下文。
 * <ul>
 *   <li>路径必须落在该用户的 upload 目录内（防路径穿越）</li>
 *   <li>Office/PDF 走 {@link FileContentExtractor}，禁止二进制当 UTF-8 硬读</li>
 *   <li>总注入字符有预算；Office/大文本写 .extracted.txt 侧车供 read_file</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadedDocumentService {

    private final FileContentExtractor extractor;
    private final MediaStorage mediaStorage;

    @Value("${file.extract.max-context-chars:120000}")
    private int maxContextChars;

    @Value("${file.extract.sidecar-threshold-chars:20000}")
    private int sidecarThresholdChars;

    public String buildMessageContext(Long userId, List<FileRef> refs) {
        if (userId == null || refs == null || refs.isEmpty()) return "";

        StringBuilder out = new StringBuilder();
        int budgetLeft = maxContextChars;
        int index = 0;

        for (FileRef ref : refs) {
            if (ref == null || ref.getFilePath() == null || ref.getFilePath().isBlank()) continue;
            index++;
            Path path = Paths.get(ref.getFilePath()).toAbsolutePath().normalize();
            if (!isOwnedPath(userId, path)) {
                log.warn("拒绝越权文件路径 userId={}, path={}", userId, path);
                out.append("\n\n[附件#").append(index).append(" 拒绝: 非法路径或不属于当前用户]");
                continue;
            }
            if (!Files.isRegularFile(path)) {
                out.append("\n\n[附件#").append(index).append(" 拒绝: 文件不存在 ")
                        .append(ref.getFilename()).append("]");
                continue;
            }

            String filename = ref.getFilename() != null ? ref.getFilename() : path.getFileName().toString();
            String mime = ref.getMimeType();

            ExtractResult full = extractFull(path, filename, mime);
            out.append("\n\n===== 附件#").append(index).append(": ").append(filename);
            out.append(" | format=").append(full.format());
            out.append(" | size=").append(ref.getFileSize()).append(" bytes =====\n");

            if (!full.success()) {
                out.append("[提取失败] ").append(full.error()).append('\n');
                out.append("原始文件路径: ").append(path).append('\n');
                continue;
            }

            Path sidecar = maybeWriteSidecar(path, filename, mime, full);
            if (sidecar != null) {
                out.append("完整提取文本: ").append(sidecar.toAbsolutePath()).append('\n');
                out.append("请优先用 read_file 对该 .extracted.txt 分段阅读（offset/limit）。\n");
            }
            out.append("原始文件路径: ").append(path).append('\n');

            if (budgetLeft <= 0) {
                out.append("[上下文预算已满，正文未再内联，请 read_file 上述提取文本]\n");
                out.append("===== 附件#").append(index).append(" 结束 =====\n");
                continue;
            }

            String inline = full.text();
            // 内联再按单文件预算与总预算截断
            int perFileCap = Math.min(budgetLeft, Math.max(4000, maxContextChars / Math.max(1, refs.size())));
            if (inline.length() > perFileCap) {
                inline = inline.substring(0, perFileCap)
                        + "\n…[内联已截断，完整内容见侧车或 read_file]";
            }
            budgetLeft -= inline.length();
            out.append(inline).append('\n');
            out.append("===== 附件#").append(index).append(" 结束 =====\n");
        }
        return out.toString();
    }

    /**
     * 上传完成后：为可提取文档写侧车，返回侧车绝对路径（失败返回 null）。
     */
    public String extractAndWriteSidecar(Long userId, Path originalPath, String filename, String mimeType) {
        if (userId == null || originalPath == null) return null;
        Path path = originalPath.toAbsolutePath().normalize();
        if (!isOwnedPath(userId, path)) {
            log.warn("侧车提取拒绝越权路径 userId={}, path={}", userId, path);
            return null;
        }
        ExtractResult full = extractFull(path, filename, mimeType);
        if (!full.success()) {
            log.info("上传后提取跳过/失败: {} — {}", filename, full.error());
            return null;
        }
        Path sidecar = maybeWriteSidecar(path, filename, mimeType, full);
        // 小文本也可选不写；上传阶段对 Office/PDF 强制写
        if (sidecar == null && full.hasText()) {
            FileContentExtractor.DocKind kind = extractor.resolveKind(filename, mimeType);
            if (kind != FileContentExtractor.DocKind.TEXT) {
                sidecar = writeSidecar(path, full.text());
            }
        }
        return sidecar == null ? null : sidecar.toAbsolutePath().toString();
    }

    public boolean isOwnedPath(Long userId, Path path) {
        try {
            Path root = mediaStorage.uploadsDir().resolve(String.valueOf(userId)).normalize();
            return path.toAbsolutePath().normalize().startsWith(root);
        } catch (Exception e) {
            return false;
        }
    }

    public List<String> validateRefs(Long userId, List<FileRef> refs) {
        List<String> errors = new ArrayList<>();
        if (refs == null) return errors;
        for (FileRef ref : refs) {
            if (ref == null || ref.getFilePath() == null) {
                errors.add("空文件引用");
                continue;
            }
            Path p = Paths.get(ref.getFilePath()).toAbsolutePath().normalize();
            if (!isOwnedPath(userId, p)) errors.add("非法路径: " + ref.getFilename());
        }
        return errors;
    }

    private ExtractResult extractFull(Path path, String filename, String mime) {
        try {
            byte[] data = Files.readAllBytes(path);
            return extractor.extractFullRaw(filename, mime, data);
        } catch (Exception e) {
            return ExtractResult.fail(null, "读取失败: " + e.getMessage());
        }
    }

    private Path maybeWriteSidecar(Path original, String filename, String mime, ExtractResult full) {
        if (!full.success() || !full.hasText()) return null;
        FileContentExtractor.DocKind kind = extractor.resolveKind(filename, mime);
        boolean officeOrPdf = kind == FileContentExtractor.DocKind.PDF
                || kind == FileContentExtractor.DocKind.DOCX
                || kind == FileContentExtractor.DocKind.PPTX
                || kind == FileContentExtractor.DocKind.XLSX;
        boolean largeText = full.originalChars() >= sidecarThresholdChars;
        if (!officeOrPdf && !largeText) return null;

        Path existing = sidecarPathFor(original);
        try {
            if (Files.isRegularFile(existing) && Files.size(existing) > 0) {
                return existing;
            }
        } catch (Exception ignored) {}
        return writeSidecar(original, full.text());
    }

    private Path writeSidecar(Path original, String fullText) {
        try {
            Path sidecar = sidecarPathFor(original);
            Files.writeString(sidecar, fullText, StandardCharsets.UTF_8);
            log.info("已写提取侧车: {} ({} chars)", sidecar, fullText.length());
            return sidecar;
        } catch (Exception e) {
            log.warn("写侧车失败: {}", original, e);
            return null;
        }
    }

    public static Path sidecarPathFor(Path original) {
        return original.getParent().resolve(original.getFileName().toString() + ".extracted.txt");
    }
}
