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

    /**
     * 计划审批只认明确的短答复。带任务词的句子不算批准 ——
     * 否则「开始做另一个任务」会被当成对上一张图的授权。
     */
    private static final Pattern PLAN_APPROVAL = Pattern.compile(
            "(?is)^\\s*(开始|执行|继续|接着(?:做|干)?|可以|同意|批准|确认(?:执行)?|"
                    + "就这么做|按这个(?:来|做)?|confirm|approve|ok|go)\\s*[。.!！]*\\s*$");

    public static boolean looksLikeBareContinue(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return BARE_CONTINUE.matcher(text).matches();
    }

    public static boolean looksLikePlanApproval(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return PLAN_APPROVAL.matcher(text.trim()).matches();
    }
}
