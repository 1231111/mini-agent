package com.miniagent.agent.todo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

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
        public static Result pass(String hash) { return new Result(true, null, hash == null ? "" : hash); }
        public static Result fail(String error) { return new Result(false, error, ""); }
    }

    /**
     * @param content  子任务描述
     * @param doneWhen 验收标准
     * @param evidence 模型提供的证据
     */
    public static Result validate(String content, String doneWhen, String evidence) {
        String dw = doneWhen == null ? "" : doneWhen.trim();
        String ev = evidence == null ? "" : evidence.trim();
        String goal = content == null ? "" : content;

        if (dw.startsWith("file_exists:")) {
            String path = dw.substring("file_exists:".length()).trim();
            Path p = resolveExisting(path, ev);
            if (p == null) {
                return Result.fail("语义验收失败：找不到可校验文件（done_when/evidence）");
            }
            return validateFile(p, goal);
        }

        if ("media_delivered".equalsIgnoreCase(dw) || "media".equalsIgnoreCase(dw)) {
            if (!IMAGE_MD.matcher(ev).find() && !IMAGE_PATH.matcher(ev).find()) {
                return Result.fail("语义验收失败：media evidence 需含可渲染的 markdown 图片或图片 URL");
            }
            return Result.pass(sha256(ev.getBytes(StandardCharsets.UTF_8)));
        }

        // note_required / 未知：至少禁止空证据；若 evidence 像路径则顺带做文件语义检查
        if (!ev.isBlank()) {
            Path maybe = tryResolve(ev);
            if (maybe != null && Files.isRegularFile(maybe)) {
                return validateFile(maybe, goal);
            }
            return Result.pass(sha256(ev.getBytes(StandardCharsets.UTF_8)));
        }
        return Result.fail("语义验收失败：缺少可校验 evidence");
    }

    private static Result validateFile(Path p, String goal) {
        try {
            long size = Files.size(p);
            if (size <= 0) {
                return Result.fail("语义验收失败：文件为空 " + p);
            }
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            String lowerGoal = goal.toLowerCase(Locale.ROOT);

            if (name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt")) {
                if (size < 40) {
                    return Result.fail("语义验收失败：文档过短（<" + size + " bytes），疑似未写完 " + p);
                }
                String body = Files.readString(p, StandardCharsets.UTF_8);
                if (body.isBlank()) {
                    return Result.fail("语义验收失败：文档无有效文本 " + p);
                }
                boolean needsImage = lowerGoal.contains("图") || lowerGoal.contains("image")
                        || lowerGoal.contains("替换") || lowerGoal.contains("插图")
                        || lowerGoal.contains("结构图") || lowerGoal.contains("架构图");
                if (needsImage && !IMAGE_MD.matcher(body).find() && !IMAGE_PATH.matcher(body).find()) {
                    return Result.fail("语义验收失败：任务要求图片/替换，但文档中无 markdown 图片引用 " + p);
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

    private static Path resolveExisting(String doneWhenPath, String evidence) {
        Path p = tryResolve(doneWhenPath);
        if (p != null && Files.exists(p)) return p;
        if (evidence != null && !evidence.isBlank()) {
            // evidence 可能是路径，或 markdown 里夹路径
            Path e = tryResolve(evidence.trim());
            if (e != null && Files.exists(e)) return e;
            var m = Pattern.compile("(?:workspace|generated-images|static/images)[^\\s)'\"]+")
                    .matcher(evidence.replace('\\', '/'));
            if (m.find()) {
                Path fromEv = tryResolve(m.group());
                if (fromEv != null && Files.exists(fromEv)) return fromEv;
            }
        }
        return null;
    }

    private static Path tryResolve(String path) {
        if (path == null || path.isBlank()) return null;
        String normalized = path.replace('\\', '/').trim();
        // 去掉 markdown 包装
        if (normalized.startsWith("![") && normalized.contains("](")) {
            int a = normalized.indexOf("](");
            int b = normalized.lastIndexOf(')');
            if (a >= 0 && b > a) normalized = normalized.substring(a + 2, b).trim();
        }
        try {
            Path p = Path.of(normalized);
            if (!p.isAbsolute()) {
                p = com.miniagent.agent.tool.BuiltinTools.effectiveWorkspaceRoot().resolve(normalized).normalize();
            }
            return p.normalize();
        } catch (Exception e) {
            return null;
        }
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
