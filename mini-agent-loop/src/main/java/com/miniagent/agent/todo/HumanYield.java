package com.miniagent.agent.todo;

import java.util.regex.Pattern;

/**
 * 用户发「继续」时从 awaiting_confirm 恢复。
 * 挂起本身只认 todo awaiting_confirm，不从模型正文刮关键词。
 */
public final class HumanYield {

    private HumanYield() {}

    private static final Pattern BARE_CONTINUE = Pattern.compile(
            "(?is)^\\s*(继续|接着(?:做|干)?|确认并继续|confirm)\\s*[。.!！]*\\s*$");

    public static boolean looksLikeBareContinue(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return BARE_CONTINUE.matcher(text).matches();
    }
}
