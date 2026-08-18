package com.miniagent.common;

/**
 * 通用字符串工具，消除各模块中的重复 truncate 方法。
 */
public final class StringUtils {

    private StringUtils() {}

    /**
     * 截断字符串到指定长度，超长部分用 "..." 替代。
     * null 输入返回空字符串。
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * 截断字符串，null 输入返回 null（适合可选字段）。
     */
    public static String truncateOrNull(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
