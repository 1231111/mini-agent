package com.miniagent.agent.tool;

import com.miniagent.common.SafeSegments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件工具（{@code read_file} / {@code list_files} / {@code search_code} / {@code edit_file} /
 * {@code write_file}）的根目录约束。
 *
 * <p><b>为什么需要它</b>：这些工具此前对绝对路径直通 —— 解析器里就是
 * {@code if (p.isAbsolute()) return p.normalize();}。后果是模型（或被提示注入的模型）
 * 可以直接读写主机上的任意文件：{@code ~/.ssh/authorized_keys}、{@code ~/.aws/credentials}、
 * 应用自己的 {@code .env} 与 {@code application.yml}。而 {@code read_file} 属于
 * {@code PLAN_SAFE_TOOLS}，在最严格的只读模式下**无需任何批准**即可调用，
 * 于是"读任意文件"成了最好用的一条越权通道；{@code edit_file} 的写侧同理，
 * 一次调用就能把公钥写进 authorized_keys，从此主机长期驻留。</p>
 *
 * <p><b>允许的根</b>（其余一律拒绝，消息里给出放行方式）：</p>
 * <ol>
 *   <li>当前 workspace（子 Agent 会覆盖成自己的隔离目录）；</li>
 *   <li>数据根 {@code miniagent.data.dir}（媒体、上传附件、记忆、技能都在这里，
 *       上传文档的 {@code .extracted.txt} 侧车读取依赖它）；</li>
 *   <li>项目根（{@code user.dir}）—— 编码任务要读真实源码，这是产品能力不是漏洞；</li>
 *   <li>{@code agent.tools.allowed-read-roots} 显式列出的额外根（逗号分隔）。</li>
 * </ol>
 * 常见密钥库、凭证文件和私钥文件在所有根下默认拒绝；添加额外根不会放宽此 deny rule。
 * 普通源码、配置模板和公开证书不按名称中的 secret/credential 子串拒绝。
 *
 * <p><b>符号链接 / junction</b>：词法前缀比较挡不住"workspace 里放一个指向 C:\Users 的
 * junction"这类绕过（Windows 上建 junction 不需要管理员权限）。所以判定取
 * {@code toRealPath()}：目标存在时按真实路径比较；目标不存在时，向上找到最近的存在祖先
 * 再拼回未存在的尾部 —— 这样"通过 junction 新建文件"也落在真实根之外。</p>
 */
public final class PathGuard {

    private static final Set<String> SENSITIVE_DIRECTORY_NAMES = Set.of(
            ".ssh", ".aws", ".azure", ".kube", ".gnupg", ".docker");
    private static final Set<String> SENSITIVE_FILE_NAMES = Set.of(
            ".netrc", ".npmrc", ".pypirc", ".htpasswd", ".git-credentials",
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "kubeconfig",
            "credentials", "credentials.json", "secrets", "secret", "token", "token.json", "access_token", "refresh_token",
            "auth.json", "service-account.json", "service_account.json");
    // PEM is a shared public/private container. Keep it denied without inspecting
    // file contents; public certificates can be supplied as .crt/.cer instead.
    private static final Set<String> PRIVATE_KEY_EXTENSIONS = Set.of(
            ".pem", ".key", ".p12", ".pfx", ".p8", ".jks", ".keystore");
    private static final Set<String> TEMPLATE_SUFFIXES = Set.of(".example", ".sample", ".template", ".dist");
    private static final Set<String> CREDENTIAL_DATA_EXTENSIONS = Set.of(
            ".json", ".yaml", ".yml", ".toml", ".ini", ".conf", ".properties");

    /** 由配置额外放行的根（{@code agent.tools.allowed-read-roots}）。 */
    private static final Set<Path> EXTRA_ROOTS = ConcurrentHashMap.newKeySet();

    private PathGuard() {
    }

    /** 放行额外的根目录。由 {@link PathGuardProperties} 在启动时按配置调用。 */
    public static void allowRoots(String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (String raw : csv.split("[,;]")) {
            String value = raw.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                EXTRA_ROOTS.add(Path.of(value).toAbsolutePath().normalize());
            } catch (RuntimeException ignored) {
                // 配置写错不该让启动失败，但要留下痕迹：调用方会记日志
            }
        }
    }

    /** 当前允许的根（供日志与自检）。 */
    public static List<Path> allowedRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(BuiltinTools.effectiveWorkspaceRoot());
        Path dataRoot = dataRoot();
        if (dataRoot != null) {
            roots.add(dataRoot);
        }
        Path project = projectRoot();
        if (project != null) {
            roots.add(project);
        }
        roots.addAll(EXTRA_ROOTS);
        return List.copyOf(roots);
    }

    private static Path dataRoot() {
        String data = System.getProperty("miniagent.data.dir");
        if (data != null && !data.isBlank()) {
            return Path.of(data).toAbsolutePath().normalize();
        }
        return projectRoot();
    }

    private static Path projectRoot() {
        String dir = System.getProperty("user.dir");
        return dir == null || dir.isBlank() ? null : Path.of(dir).toAbsolutePath().normalize();
    }

    /**
     * 目标是否落在允许范围内。不做异常，供需要"判断"而非"拒绝"的调用点使用。
     *
     * <p><b>只按真实路径比较，不能"或"上词法前缀。</b>这里踩过一次：
     * 早先写成 {@code real.startsWith(realRoot) || lexical.startsWith(realRoot)}，
     * 于是 workspace 里一个指向外部的 junction 直接通过 —— 词法上
     * {@code workspace\escape\secret.txt} 确实以 workspace 开头，而真实落点在根之外。
     * 词法分支看着像"为不存在的文件兜底"，实际是把刚补上的防线又拆了；
     * 不存在的目标由 {@link #realPathOfNearestExisting} 自己处理（回溯到最近的存在祖先），
     * 不需要词法兜底。</p>
     */
    public static boolean isAllowed(Path candidate) {
        if (candidate == null) {
            return false;
        }
        Path lexical = candidate.toAbsolutePath().normalize();
        // Check the caller-visible spelling before resolving links. A safe alias must
        // not make a credential-named path look harmless, and a link to a credential
        // must not make a harmless-looking path readable either.
        if (isSensitivePath(lexical)) {
            return false;
        }
        Path effective = realPathOfNearestExisting(candidate);
        if (isSensitivePath(effective)) {
            return false;
        }
        for (Path root : allowedRoots()) {
            if (effective.startsWith(realPathOfNearestExisting(root))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 断言目标在允许范围内，否则抛 {@link SecurityException}。
     *
     * @param purpose 出现在错误消息里的用途（read / write / edit / search），
     *                让模型能立刻知道是哪一类操作被拒
     */
    public static void assertAllowed(Path candidate, String purpose) {
        if (isAllowed(candidate)) {
            return;
        }
        Path normalized = candidate == null ? null : candidate.toAbsolutePath().normalize();
        Path effective = normalized == null ? null : realPathOfNearestExisting(normalized);
        if (isSensitivePath(normalized) || isSensitivePath(effective)) {
            throw new SecurityException("拒绝访问敏感凭证或密钥路径（" + purpose + "）："
                    + (normalized == null ? "(null)" : normalized)
                    + "。如确需访问，请由运维将脱敏副本放入工作区；不支持直接放行密钥文件。");
        }
        List<String> roots = new ArrayList<>();
        for (Path root : allowedRoots()) {
            roots.add(root.toString());
        }
        throw new SecurityException("拒绝访问工作区外的路径（" + purpose + "）："
                + (candidate == null ? "(null)" : candidate.toAbsolutePath().normalize())
                + "。允许的根目录: " + String.join(" | ", roots)
                + "。确实需要访问其他目录时，由运维在 agent.tools.allowed-read-roots 中显式放行。");
    }

    /**
     * Default-deny explicit credential stores and terminal credential/private-key files.
     * File-name rules apply only to the leaf: a business package named credentials
     * or a SecretsService.java source must remain usable by coding tools.
     * This is intentionally applied to both the normalized lexical path and its
     * nearest-existing-real-path counterpart in {@link #isAllowed(Path)}.
     */
    private static boolean isSensitivePath(Path path) {
        if (path == null) return false;
        Path normalized = path.toAbsolutePath().normalize();
        boolean previousWasConfig = false;
        for (int index = 0; index < normalized.getNameCount(); index++) {
            Path part = normalized.getName(index);
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (SENSITIVE_DIRECTORY_NAMES.contains(name)) return true;
            if (previousWasConfig && "gcloud".equals(name)) return true;
            previousWasConfig = ".config".equals(name);
            if (index != normalized.getNameCount() - 1 || Files.isDirectory(normalized)) continue;
            if (isCredentialFileName(name)) return true;
            if (PRIVATE_KEY_EXTENSIONS.stream().anyMatch(name::endsWith)) return true;
        }
        return false;
    }

    private static boolean isCredentialFileName(String name) {
        boolean template = TEMPLATE_SUFFIXES.stream().anyMatch(name::endsWith);
        if (template) return false;
        if (name.equals(".env") || name.startsWith(".env.")) return true;
        if (SENSITIVE_FILE_NAMES.contains(name) || name.endsWith(".token") || name.endsWith(".secret")) return true;
        for (String extension : CREDENTIAL_DATA_EXTENSIONS) {
            if (!name.endsWith(extension)) continue;
            String stem = name.substring(0, name.length() - extension.length());
            if (Set.of("credential", "credentials", "secret", "secrets", "token", "access_token", "refresh_token")
                    .contains(stem) || stem.endsWith(".credentials") || stem.endsWith(".secrets")) return true;
            if (stem.equals("service-account") || stem.startsWith("service-account-")
                    || stem.equals("service_account") || stem.startsWith("service_account_")) return true;
        }
        return false;
    }

    /**
     * 目标（或它最近的已存在祖先）的真实路径。
     *
     * <p>存在的目标直接 {@code toRealPath()}（解析符号链接/junction）；
     * 不存在的目标向上回溯到最近的存在祖先，把未存在的尾部拼回去。
     * 这样"通过 junction 写一个新文件"也会被判定在真实根之外。</p>
     */
    static Path realPathOfNearestExisting(Path target) {
        Path absolute = target.toAbsolutePath().normalize();
        Path cursor = absolute;
        while (cursor != null) {
            try {
                Path real = cursor.toRealPath();
                if (cursor.equals(absolute)) {
                    return real;
                }
                Path tail = absolute.subpath(cursor.getNameCount(), absolute.getNameCount());
                return real.resolve(tail).normalize();
            } catch (IOException | RuntimeException e) {
                cursor = cursor.getParent();
            }
        }
        return absolute;
    }

    /**
     * 把若干段拼到 {@code root} 下，并保证结果仍在 {@code root} 内。
     *
     * <p><b>与 {@link #isAllowed} 的分工</b>：{@code isAllowed} 回答"哪些根可以访问"
     * （工作区、数据根、项目根一律放行，比较宽）；而像 skills 目录这类东西有自己的边界，
     * 需要"只允许在这一个根里面"这种更严的判定 —— 否则 {@code ../../} 一拼就写到别处去了。</p>
     *
     * <p><b>两道防线</b>：每一段先过 {@link SafeSegments}（拒绝 {@code .}/{@code ..}、
     * 路径分隔符、绝对路径），拼完再按<b>真实路径</b>比较一次（挡住通过 junction/符号链接
     * 逃出根目录）。两道都跑，是因为"没写死分隔符"不等于"拼出来一定在根里"。</p>
     *
     * @param segments 允许传 null 或空串，表示这一段省略（例如技能没有分类）
     */
    public static Path resolveInside(Path root, String purpose, String... segments) {
        if (root == null) {
            throw new SecurityException("目录根未配置，拒绝访问（" + purpose + "）");
        }
        Path base = root.toAbsolutePath().normalize();
        Path candidate = base;
        for (String segment : segments) {
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            SafeSegments.requireValid(segment, "路径段");
            candidate = candidate.resolve(segment);
        }
        Path realCandidate = realPathOfNearestExisting(candidate);
        if (!realCandidate.startsWith(realPathOfNearestExisting(base))) {
            throw new SecurityException("拒绝越出目录的路径（" + purpose + "）："
                    + candidate + "，允许范围: " + base);
        }
        return candidate.normalize();
    }

    /**
     * 解析"根目录下的相对子路径"，允许多级（例如 {@code references/spec.md}）。
     *
     * <p>与 {@link #resolveInside} 的分工：那个只接受单段（每段是一个目录名或文件名），
     * 这个接受用 {@code /} 或 {@code \} 分隔的多级相对路径，但会**逐段**过
     * {@link SafeSegments} —— 所以 {@code ..}、盘符、路径回退都进不来，
     * 同时保留了"技能包里读 references/ 子文件"这个正当能力。</p>
     */
    public static Path resolveRelative(Path root, String purpose, String relativePath) {
        if (root == null) {
            throw new SecurityException("目录根未配置，拒绝访问（" + purpose + "）");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("相对路径不能为空");
        }
        return resolveInside(root, purpose, relativePath.trim().split("[/\\\\]+"));
    }

    /** 供测试重置放行清单。 */
    static void clearExtraRoots() {
        EXTRA_ROOTS.clear();
    }
}
