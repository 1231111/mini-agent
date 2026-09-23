package com.miniagent.agent.todo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import com.miniagent.agent.tool.BuiltinTools;

/**
 * 语义层验收（双轨制第二轨）：在 file_exists / media 等存在性检查通过后，
 * 再校验产物是否「像做对了」，失败则禁止 todo completed。
 */
public final class TodoSemanticValidator {

    private static final Pattern IMAGE_MD = Pattern.compile("!\\[[^\\]]*\\]\\([^)]+\\)");
    private static final Pattern IMAGE_PATH = Pattern.compile("(/static/images/|/generated-images/|https?://\\S+\\.(png|jpg|jpeg|webp|gif))",
            Pattern.CASE_INSENSITIVE);

    private TodoSemanticValidator() {}

    public record Result(boolean ok, String error, String contentHash) {
        public static Result pass(String hash) { return new Result(true, null, Optional.ofNullable(hash).orElse("")); }
        public static Result fail(String error) { return new Result(false, error, ""); }
    }

    /**
     * @param content  子任务描述
     * @param doneWhen 验收标准
     * @param evidence 模型提供的证据
     */
    public static Result validate(String content, String doneWhen, String evidence) {
        String dw = Objects.isNull(doneWhen) ? "" : doneWhen.trim();
        String ev = Objects.isNull(evidence) ? "" : evidence.trim();

        if (dw.startsWith("file_exists:")) {
            String path = dw.substring("file_exists:".length()).trim();
            Path p = resolveExisting(path, ev);
            if (Objects.isNull(p)) {
                return Result.fail("语义验收失败：找不到可校验文件（done_when/evidence）");
            }
            return validateFile(p);
        }

        if ("media_delivered".equalsIgnoreCase(dw) || "media".equalsIgnoreCase(dw)) {
            Path image = existingImage(ev);
            if (image != null) {
                return validateFile(image);
            }
            if (IMAGE_MD.matcher(ev).find() || IMAGE_PATH.matcher(ev).find()) {
                return Result.pass(sha256(ev.getBytes(StandardCharsets.UTF_8)));
            }
            return Result.fail("语义验收失败：media evidence 需含可渲染的 markdown 图片或图片 URL");
        }

        // note_required / 未知：至少禁止空证据；若 evidence 像路径则顺带做文件语义检查
        if (!StringUtils.isBlank(ev)) {
            Path maybe = tryResolve(ev);
            if (Objects.nonNull(maybe) && Files.isRegularFile(maybe)) {
                return validateFile(maybe);
            }
            return Result.pass(sha256(ev.getBytes(StandardCharsets.UTF_8)));
        }
        return Result.fail("语义验收失败：缺少可校验 evidence");
    }

    private static Result validateFile(Path p) {
        try {
            long size = Files.size(p);
            if (size <= 0) {
                return Result.fail("语义验收失败：文件为空 " + p);
            }
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);

            if (name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt")) {
                if (size < 40) {
                    return Result.fail("语义验收失败：文档过短（<" + size + " bytes），疑似未写完 " + p);
                }
                String body = Files.readString(p, StandardCharsets.UTF_8);
                if (StringUtils.isBlank(body)) {
                    return Result.fail("语义验收失败：文档无有效文本 " + p);
                }
                return Result.pass(sha256(body.getBytes(StandardCharsets.UTF_8)));
            }

            if (name.endsWith(".sql")) {
                if (size < 20) {
                    return Result.fail("语义验收失败：SQL 文件过短 " + p);
                }
                String body = Files.readString(p, StandardCharsets.UTF_8);
                String u = body.toUpperCase(Locale.ROOT);
                if (!u.contains("CREATE") && !u.contains("INSERT") && !u.contains("ALTER")) {
                    return Result.fail("语义验收失败：SQL 文件缺少 CREATE/INSERT/ALTER 语句 " + p);
                }
                return Result.pass(sha256(body.getBytes(StandardCharsets.UTF_8)));
            }

            if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".webp") || name.endsWith(".gif")) {
                if (size < 100) {
                    return Result.fail("语义验收失败：图片文件过小，疑似损坏 " + p);
                }
                return Result.pass(sha256(Files.readAllBytes(p)));
            }

            // 其它文件：非空即可
            if (size < 8) {
                return Result.fail("语义验收失败：产物过小 " + p);
            }
            return Result.pass(sha256(Files.readAllBytes(p)));
        } catch (Exception e) {
            return Result.fail("语义验收异常: " + e.getMessage());
        }
    }

    /** 本地已生成的图片（含 render_diagram 的绝对路径和工具 JSON 里的 path）算交付。 */
    public static boolean acceptsMediaEvidence(String evidence) {
        if (StringUtils.isBlank(evidence)) {
            return false;
        }
        if (IMAGE_MD.matcher(evidence).find() || IMAGE_PATH.matcher(evidence).find()) {
            return true;
        }
        return existingImage(evidence) != null;
    }

    private static Path existingImage(String evidence) {
        Path direct = tryResolve(evidence);
        if (isImageFile(direct)) {
            return direct;
        }
        var jsonPath = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(evidence);
        if (jsonPath.find()) {
            Path fromJson = tryResolve(jsonPath.group(1).replace("\\\\", "\\"));
            if (isImageFile(fromJson)) {
                return fromJson;
            }
        }
        var bare = Pattern.compile(
                "(?i)(?:[A-Za-z]:)?[^\\s\"']+\\.(?:png|jpe?g|webp|gif|svg)\\b")
                .matcher(evidence);
        if (bare.find()) {
            Path fromText = tryResolve(bare.group());
            if (isImageFile(fromText)) {
                return fromText;
            }
        }
        return null;
    }

    private static boolean isImageFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".svg");
    }

    private static Path resolveExisting(String doneWhenPath, String evidence) {
        Path p = tryResolve(doneWhenPath);
        if (Objects.nonNull(p) && Files.exists(p)) {
            return p;
        }
        if (StringUtils.isNotBlank(evidence)) {
            // evidence 可能是路径，或 markdown 里夹路径
            Path e = tryResolve(evidence.trim());
            if (Objects.nonNull(e) && Files.exists(e)) {
                return e;
            }
            var m = Pattern.compile("(?:workspace|generated-images|static/images)[^\\s)'\"]+")
                    .matcher(evidence.replace('\\', '/'));
            if (m.find()) {
                Path fromEv = tryResolve(m.group());
                if (Objects.nonNull(fromEv) && Files.exists(fromEv)) {
                    return fromEv;
                }
            }
        }
        return null;
    }

    private static Path tryResolve(String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        // 与 TaskTodoStore 一致：剥 file_exists:、截说明尾巴
        String normalized = TaskTodoStore.extractPathSpec(path);
        if (normalized.isBlank()) {
            return null;
        }
        // 去掉 markdown 包装
        if (normalized.startsWith("![") && normalized.contains("](")) {
            int a = normalized.indexOf("](");
            int b = normalized.lastIndexOf(')');
            if (a >= 0 && b > a) {
                normalized = normalized.substring(a + 2, b).trim();
            }
        }
        try {
            Path p = Path.of(normalized);
            if (!p.isAbsolute()) {
                String rel = BuiltinTools.stripWorkspaceAlias(normalized);
                if (rel.isBlank()) {
                    return BuiltinTools.effectiveWorkspaceRoot();
                }
                Path root = BuiltinTools.effectiveWorkspaceRoot();
                Path atRoot = root.resolve(rel).normalize();
                if (Files.exists(atRoot)) {
                    return atRoot;
                }
                Path underTask = root.resolve(BuiltinTools.currentTaskName())
                        .resolve(rel).normalize();
                if (Files.exists(underTask)) {
                    return underTask;
                }
                String baseName = Path.of(rel).getFileName().toString();
                Path byName = findByFileName(baseName);
                if (Objects.nonNull(byName)) {
                    return byName;
                }
                p = atRoot;
            } else if (!Files.exists(p)) {
                Path byName = findByFileName(p.getFileName().toString());
                if (Objects.nonNull(byName)) {
                    return byName;
                }
            }
            return Files.exists(p) ? p.normalize() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Path findByFileName(String fileName) {
        if (StringUtils.isBlank(fileName) || fileName.contains("/") || fileName.contains("\\")) {
            return null;
        }
        try {
            Path root = BuiltinTools.effectiveWorkspaceRoot();
            Path hit = searchFileName(root, fileName, 3);
            if (Objects.nonNull(hit)) {
                return hit;
            }
            String task = BuiltinTools.currentTaskName();
            if (StringUtils.isNotBlank(task) && !"default".equals(task)) {
                return searchFileName(root.resolve(task), fileName, 2);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static Path searchFileName(Path dir, String fileName, int depth) {
        if (depth < 0 || !Files.isDirectory(dir)) {
            return null;
        }
        try (var entries = Files.list(dir)) {
            java.util.List<Path> listed = entries.toList();
            for (Path p : listed) {
                if (Files.isRegularFile(p) && fileName.equals(p.getFileName().toString())) {
                    return p;
                }
            }
            if (depth > 0) {
                for (Path sub : listed) {
                    if (!Files.isDirectory(sub)) {
                        continue;
                    }
                    Path hit = searchFileName(sub, fileName, depth - 1);
                    if (Objects.nonNull(hit)) {
                        return hit;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private static String sha256(byte[] data) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(dig).substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }
}
