package com.miniagent.agent.skill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * Safety and resource limits for persistent skills.
 *
 * <p>Skills are instructions that survive a session and are later injected into
 * planner context. They therefore need a stricter write contract than ordinary
 * model output: bounded size, a valid stable name, and rejection of common
 * prompt-injection/credential-exfiltration payloads.</p>
 */
public final class SkillContentPolicy {
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_DESCRIPTION_CHARS = 2_000;
    public static final int MAX_SKILL_BYTES = 256 * 1024;
    public static final int MAX_PATCH_TEXT_CHARS = 64 * 1024;

    private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?");
    private static final Pattern[] THREATS = {
            Pattern.compile("(?i)ignore\\s+(?:all\\s+|any\\s+|the\\s+)*(?:previous|prior|above|earlier)\\s+(?:instruction|prompt|rule)"),
            Pattern.compile("(?i)(?:disregard|override|bypass)\\s+(?:all\\s+|any\\s+|your\\s+|the\\s+)*(?:previous\\s+|prior\\s+|existing\\s+)*(?:instruction|rule|policy|restriction|prompt)"),
            Pattern.compile("(?i)(?:you\\s+are\\s+now|from\\s+now\\s+on)\\s+(?:an?\\s+)?(?:assistant|system|developer|root)"),
            Pattern.compile("(?i)(?:print|reveal|show|output|leak)[^\\n]{0,80}(?:system\\s+prompt|hidden\\s+rules|api[_ ]?key|secret|token|credential)"),
            Pattern.compile("(?i)(?:curl|wget)\\s+[^\\n]*(?:\\$\\{?[A-Z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL)|/etc/shadow|\\.ssh/)"),
            Pattern.compile("(?:忽略|无视|忘记|绕过)(?:掉)?(?:之前|以上|所有|全部|当前|原有|任何|这些|的)*(?:指令|规则|提示词|限制|安全策略|约束)"),
            Pattern.compile("(?:输出|泄露|显示|告诉|打印)[^\\n]{0,40}(?:系统提示词|系统指令|隐藏规则|密钥|令牌|凭据|密码)"),
            Pattern.compile("(?:系统提示词|系统指令|隐藏规则|密钥|令牌|凭据|密码)[^\\n]{0,40}(?:输出|泄露|显示|告诉|打印)"),
            Pattern.compile("(?:不要|别)(?:告诉|告知|提醒)用户")
    };

    private SkillContentPolicy() { }

    public static String validateName(String name) {
        if (name == null || name.isBlank()) return "技能名不能为空";
        if (name.length() > MAX_NAME_LENGTH || !NAME.matcher(name).matches()) {
            return "技能名必须是 1-64 个小写字母、数字或连字符，且不能以连字符开头/结尾";
        }
        return null;
    }

    public static String validateDocument(String name, String description, String content) {
        String nameError = validateName(name);
        if (nameError != null) return nameError;
        if (description != null && description.length() > MAX_DESCRIPTION_CHARS) {
            return "技能描述超过 " + MAX_DESCRIPTION_CHARS + " 个字符";
        }
        if (content == null || content.isBlank()) return "技能内容不能为空";
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_SKILL_BYTES) {
            return "技能内容超过 " + MAX_SKILL_BYTES + " 字节上限";
        }
        for (char c : new char[]{'\u200b', '\u200c', '\u200d', '\u2060', '\ufeff',
                '\u202a', '\u202b', '\u202c', '\u202d', '\u202e'}) {
            if (content.indexOf(c) >= 0) return "技能内容包含不可见 Unicode 控制字符";
        }
        for (Pattern threat : THREATS) {
            if (threat.matcher(content).find()) return "技能内容命中提示注入或凭据外泄安全规则";
        }
        return null;
    }

    public static String validatePatchText(String value, String field) {
        if (value == null || value.isEmpty()) return field + " 不能为空";
        if (value.length() > MAX_PATCH_TEXT_CHARS) return field + "超过 " + MAX_PATCH_TEXT_CHARS + " 个字符";
        return null;
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
