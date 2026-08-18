package com.miniagent.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全工具：敏感信息脱敏。统一各模块中的 redactSensitive 实现。
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    // URL-param 风格：key=value
    private static final Pattern URL_PARAM = Pattern.compile(
            "(access_token|secret|api_key|api-key|password|token|authorization)=([^&\\s]+)",
            Pattern.CASE_INSENSITIVE);

    // JSON 风格："key": "value"
    private static final Pattern JSON_PARAM = Pattern.compile(
            "\"(access_token|secret|api_key|api-key|password|token|authorization)\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    /**
     * 脱敏字符串中的敏感字段（API key、token、密码等）。
     */
    public static String redactSensitive(String s) {
        if (s == null || s.isEmpty()) return s;
        String result = URL_PARAM.matcher(s).replaceAll("$1=***");
        result = JSON_PARAM.matcher(result).replaceAll("\"$1\":\"***\"");
        return result;
    }
}
