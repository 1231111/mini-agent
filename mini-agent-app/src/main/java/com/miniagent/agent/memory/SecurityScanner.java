package com.miniagent.agent.memory;

import java.util.regex.Pattern;

/**
 * 写入记忆前的安全扫描 — 防止 prompt 注入 / 凭据外泄 / 隐形字符
 * 参考 hermes-agent 的 _scan_memory_content()
 */
public class SecurityScanner {

    // Prompt 注入 / 角色劫持
    private static final Pattern[] THREAT_PATTERNS = {
            Pattern.compile("ignore\\s+(previous|all|above|prior)\\s+instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("do\\s+not\\s+tell\\s+the\\s+user", Pattern.CASE_INSENSITIVE),
            Pattern.compile("system\\s+prompt\\s+override", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(your|all|any)\\s+(instructions|rules|guidelines)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("act\\s+as\\s+(if|though)\\s+you\\s+(have\\s+no|don'?t\\s+have)\\s+(restrictions|limits|rules)", Pattern.CASE_INSENSITIVE),
            // 凭据外泄
            Pattern.compile("curl\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)"),
            Pattern.compile("wget\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)"),
            Pattern.compile("cat\\s+[^\\n]*(\\.env|credentials|\\.netrc|\\.pgpass|\\.npmrc|\\.pypirc)"),
            // SSH 后门
            Pattern.compile("authorized_keys"),
            Pattern.compile("\\$HOME/\\.ssh|~/\\.ssh"),
            Pattern.compile("\\$HOME/\\.hermes/\\.env|~/\\.hermes/\\.env"),
    };

    // 隐形 unicode 字符（常用于注入攻击）
    private static final char[] INVISIBLE_CHARS = {
            '\u200b', '\u200c', '\u200d', '\u2060', '\ufeff',
            '\u202a', '\u202b', '\u202c', '\u202d', '\u202e',
    };

    /**
     * 扫描内容。返回 null 表示安全，返回字符串表示被拦截的原因。
     */
    public static String scan(String content) {
        // 检查隐形 unicode
        for (char c : INVISIBLE_CHARS) {
            if (content.indexOf(c) >= 0) {
                return String.format("拦截：内容包含隐形 unicode 字符 U+%04X（可能是注入攻击）。", (int) c);
            }
        }

        // 检查威胁模式
        for (Pattern p : THREAT_PATTERNS) {
            if (p.matcher(content).find()) {
                return "拦截：内容匹配安全威胁模式。记忆条目会注入系统提示，不能包含注入或外泄载荷。";
            }
        }

        return null;
    }
}
