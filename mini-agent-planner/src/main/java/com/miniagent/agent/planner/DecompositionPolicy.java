package com.miniagent.agent.planner;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts URLs from action descriptions for ActionBinder. */
final class DecompositionPolicy {

    private static final Pattern HTTP_URL =
            Pattern.compile("https?://[^\\s)\"'<>\\]]+");

    private DecompositionPolicy() {}

    static String firstUrl(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher m = HTTP_URL.matcher(text);
        if (!m.find()) {
            return "";
        }
        String url = m.group();
        int end = url.length();
        while (end > 0) {
            char c = url.charAt(end - 1);
            if (c == '.' || c == ',' || c == ';' || c == '。' || c == '、') {
                end--;
            } else {
                break;
            }
        }
        return url.substring(0, end);
    }

    static String firstPath(String text) {
        return DataflowNormalizer.pathFromName(text);
    }

}
