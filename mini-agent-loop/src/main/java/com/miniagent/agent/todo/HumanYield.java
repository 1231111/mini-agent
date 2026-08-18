package com.miniagent.agent.todo;

import java.util.regex.Pattern;

/**
 * 把「向用户要密钥/登录」从收尾拦截里拆出来，走已有 awaiting_confirm。
 * ponytail: 关键词启发式；误判堆积后再做成模型显式 yield 工具。
 */
public final class HumanYield {

    private HumanYield() {}

    /** 必须是「向用户索要」；回执里提到 AppID/密钥本身不算。 */
    private static final Pattern NEED_HUMAN = Pattern.compile(
            "(?i)(请提供|请发给我|请告诉|请告知|请贴上|请回复"
                    + "|需要你(?:的)?(?:提供|发给|告诉|补充)"
                    + "|缺少.{0,16}(?:AppID|AppSecret|app[_-]?id|app[_-]?secret"
                    + "|密钥|凭据|口令|access[_-]?token|api[_-]?key)"
                    + "|请登录|扫码登录)");

    private static final Pattern BARE_CONTINUE = Pattern.compile(
            "(?is)^\\s*(继续|接着(?:做|干)?|确认并继续|confirm)\\s*[。.!！]*\\s*$");

    public static boolean looksLikeNeedHuman(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return NEED_HUMAN.matcher(text).find();
    }

    public static boolean looksLikeBareContinue(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return BARE_CONTINUE.matcher(text).matches();
    }
}
