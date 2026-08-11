package com.miniagent.eval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * 断言执行器：对一条 {@link EvalCheck} 求值，返回是否通过 + 失败原因。
 * 只读「可观测副作用」（回复文本 + 文件系统），不接触 Agent 内部。
 */
public class EvalChecker {

    /** 判定「翻车」的默认错误短语；no_error 未指定 value 时使用。 */
    private static final List<String> DEFAULT_ERROR_PHRASES = List.of(
            "达到最大迭代次数", "模型连接异常", "模型未返回内容",
            "执行失败", "Exception", "stacktrace", "我做不到"
    );

    private final Path projectRoot;

    public EvalChecker(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public CheckResult check(EvalCheck c, String response) {
        String resp = Optional.ofNullable(response).orElse("");
        boolean raw = switch (Optional.ofNullable(c.type).orElse("")) {
            case "response_contains" -> resp.toLowerCase().contains(safe(c.value).toLowerCase());
            case "response_regex" -> Pattern.compile(safe(c.value), Pattern.DOTALL).matcher(resp).find();
            case "response_min_length" -> resp.strip().length() >= c.number;
            case "no_error" -> noError(resp, c.value);
            case "file_exists" -> fileExists(c.path);
            case "file_contains" -> fileContains(c.path, c.value);
            default -> false;
        };
        if (StringUtils.isBlank(c.type) || !KNOWN.contains(c.type)) {
            return CheckResult.fail(c.type, "未知断言类型: " + c.type);
        }
        boolean pass = c.negate != raw; // negate 时取反
        String desc = describe(c);
        return pass ? CheckResult.pass(desc) : CheckResult.fail(desc, failReason(c, resp));
    }

    private static final List<String> KNOWN = List.of(
            "response_contains", "response_regex", "response_min_length",
            "no_error", "file_exists", "file_contains");

    private boolean noError(String resp, String value) {
        if (StringUtils.isNotBlank(value)) return !resp.contains(value);
        String low = resp.toLowerCase();
        return DEFAULT_ERROR_PHRASES.stream().noneMatch(p -> low.contains(p.toLowerCase()));
    }

    private boolean fileExists(String path) {
        Path p = resolve(path);
        return Objects.nonNull(p) && Files.exists(p);
    }

    private boolean fileContains(String path, String value) {
        Path p = resolve(path);
        if (Objects.isNull(p) || !Files.exists(p)) return false;
        try {
            String content = Files.readString(p, StandardCharsets.UTF_8);
            return Objects.isNull(value) || content.contains(value);
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析用例里的相对路径：相对项目根；绝对路径直接用。 */
    private Path resolve(String path) {
        if (StringUtils.isBlank(path)) return null;
        String norm = path.replace('\\', '/').trim();
        Path p = Path.of(norm);
        return p.isAbsolute() ? p.normalize() : projectRoot.resolve(norm).normalize();
    }

    private String describe(EvalCheck c) {
        String not = c.negate ? "[非]" : "";
        return switch (c.type) {
            case "response_contains" -> not + "回复包含「" + safe(c.value) + "」";
            case "response_regex" -> not + "回复匹配 /" + safe(c.value) + "/";
            case "response_min_length" -> not + "回复长度>=" + c.number;
            case "no_error" -> not + "回复无错误短语";
            case "file_exists" -> not + "文件存在 " + c.path;
            case "file_contains" -> not + "文件含「" + safe(c.value) + "」@" + c.path;
            default -> c.type;
        };
    }

    private String failReason(EvalCheck c, String resp) {
        return switch (c.type) {
            case "file_exists", "file_contains" -> "实际路径=" + resolve(c.path);
            default -> "实际回复(前120字)=" + resp.strip()
                    .substring(0, Math.min(120, resp.strip().length()));
        };
    }

    private static String safe(String s) { return Optional.ofNullable(s).orElse(""); }
}
