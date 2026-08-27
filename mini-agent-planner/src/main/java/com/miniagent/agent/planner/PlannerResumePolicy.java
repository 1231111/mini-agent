package com.miniagent.agent.planner;

import com.miniagent.agent.intent.IntentType;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Planner 断点恢复门禁。
 *
 * <p>旧图存在并不代表下一条消息就是旧任务的答复。恢复必须有明确的
 * 续跑/授权信号，或是在等待人工输入时看起来像凭据、确认等短答复；
 * 明显的新问题必须开启新的规划上下文。</p>
 */
public final class PlannerResumePolicy {

    private static final Pattern EXPLICIT_CONTINUE = Pattern.compile(
            "(?is)^(?:继续(?:帮我)?(?:执行|推进|完成|做|写|改|处理)(?:.*)?"
                    + "|继续(?:帮我)?"
                    + "|接着(?:做|干|写|改|处理)(?:.*)?"
                    + "|接着"
                    + "|按刚才(?:的)?(?:继续)?"
                    + "|在上次基础上(?:继续)?"
                    + "|基于(?:刚才|上一步|之前)(?:继续)?"
                    + "|按照上一步(?:继续)?"
                    + "|恢复(?:任务|执行)"
                    + "|确认并继续|confirm)"
                    + "(?:[：:，,。.!！;；\\s].*)?$");

    private static final Pattern APPROVAL = Pattern.compile(
            "(?is).*(?:已批准|已经批准|批准了|授权|允许).*(?:继续|执行|推进).*|"
                    + ".*(?:继续执行已确认|已确认的步骤).*");

    private static final Pattern NEW_TOPIC = Pattern.compile(
            "(?is).*(?:我问的是|我的问题是|我想问(?:的是)?|"
                    + "换个问题|另一个问题|新问题|重新问|其实(?:我)?(?:想问|要问)?|"
                    + "请问|如何|怎么|能否|为什么).*" );

    private static final Pattern TASK_MARKER = Pattern.compile(
            "(?is).*(?:生成|创建|编写|写一份|修改|编辑|替换|删除|执行|运行|部署|"
                    + "搜索|打开|下载|实现|开发|搭建|设计|应用|文件|标书|投标|代码|"
                    + "项目|文档|接口|页面|方案|系统).*" );

    private PlannerResumePolicy() {
    }

    /**
     * 判断本轮是否可以复用未完成的 Planner 图。
     */
    public static boolean shouldResume(IntentType intent,
                                       String userMessage,
                                       boolean resumeRequested,
                                       boolean awaitingConfirm,
                                       boolean incomplete) {
        if (!incomplete || intent == null
                || intent == IntentType.QUESTION
                || intent == IntentType.REVIEW) {
            return false;
        }

        String text = normalize(userMessage);
        if (isClearlyNewTopic(text)) {
            return false;
        }
        if (isExplicitContinuation(text)) {
            return true;
        }
        // 分类器的 CONTINUE_TASK 只是语义提示，不能单独覆盖消息边界；
        // 只有已有断点标记或人工确认状态下的短答复才允许恢复。
        if ((resumeRequested || awaitingConfirm) && looksLikeHumanReply(text)
                && !TASK_MARKER.matcher(text).matches()) {
            return true;
        }
        return false;
    }

    public static boolean isExplicitContinuation(String message) {
        String text = normalize(message);
        return !text.isEmpty()
                && !isClearlyNewTopic(text)
                && (EXPLICIT_CONTINUE.matcher(text).matches()
                || APPROVAL.matcher(text).matches());
    }

    public static boolean isClearlyNewTopic(String message) {
        String text = normalize(message);
        if (text.isEmpty()) {
            return false;
        }
        return NEW_TOPIC.matcher(text).matches()
                || text.endsWith("?") || text.endsWith("？");
    }

    /** 等待人工输入时，允许凭据/确认类短答复继续，但不吞掉新任务。 */
    public static boolean looksLikeHumanReply(String message) {
        String text = normalize(message);
        if (text.isEmpty() || text.length() > 300
                || isClearlyNewTopic(text)
                || TASK_MARKER.matcher(text).matches()) {
            return false;
        }
        return true;
    }

    private static String normalize(String message) {
        return message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
    }
}
