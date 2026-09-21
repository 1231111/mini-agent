package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskSignals;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Planner 断点恢复门禁。
 *
 * <p>旧图存在并不代表下一条消息就是旧任务的答复。恢复必须有明确的续跑/授权信号，
 * 或者<b>此刻</b>图里确实有一个悬而未决的问题、而用户这句话看起来就是在回答它。</p>
 *
 * <h2>为什么删掉了 resumeRequested</h2>
 *
 * <p>旧实现的判据是 {@code (resumeRequested || awaitingConfirm) && looksLikeHumanReply && !TASK_MARKER}。
 * 其中 {@code resumeRequested} 是 {@code PlannerStateStore} 里一个进程内的 {@code Set<String>}，
 * 只在「恢复」发生时才被清掉 —— 换句话说，<b>一旦系统在该会话里等过一次用户，
 * 这个会话此后任何一句不含任务词、不超过 300 字的话，都会被当成对那张旧图的回复</b>。
 * 这正是「历史任务污染当前任务」的直接入口：用户开始做新事情，却被旧图的节点调度接管。</p>
 *
 * <p>删掉它不需要新的判据，因为另一个条件已经足够：{@code awaitingConfirm} 就是
 * 「此刻有悬而未决的问题」。而 {@code resumeRequested} 的每一个写入点
 * （{@code PlanningLoop} 的 {@code humanWait} 分支、{@code hasAwaitingConfirm()} 分支、
 * 页面确认按钮）在写入时都已经把节点置为 {@code AWAITING_CONFIRM}，
 * 也就是说凡是它能表达的，{@code awaitingConfirm} 都能表达，而且表达的是「现在」。</p>
 *
 * <p>判据仍然是文本事实（{@link TaskSignals} + 正则），不读任何分类结果。</p>
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
                    + "请问|如何|怎么|能否|为什么).*");

    private static final Pattern TASK_MARKER = Pattern.compile(
            "(?is).*(?:生成|创建|编写|写一份|修改|编辑|替换|删除|执行|运行|部署|"
                    + "搜索|打开|下载|实现|开发|搭建|设计|应用|文件|标书|投标|代码|"
                    + "项目|文档|接口|页面|方案|系统).*");

    private PlannerResumePolicy() {
    }

    /**
     * 判断本轮是否可以复用未完成的 Planner 图。
     *
     * @param signals         本轮从用户消息里观察到的事实
     * @param userMessage     用户消息原文
     * @param awaitingConfirm 图里<b>此刻</b>存在 AWAITING_CONFIRM 节点（即系统正在等用户）
     * @param incomplete      图未全部终态成功
     */
    public static boolean shouldResume(TaskSignals signals, String userMessage,
                                       boolean awaitingConfirm, boolean incomplete) {
        if (!incomplete) {
            return false;
        }
        TaskSignals s = signals == null ? TaskSignals.NONE : signals;
        // 纯问答轮不接管旧图：「你好」不该被上一轮的任务图吃进去
        if (s.lightTurn()) {
            return false;
        }
        String text = normalize(userMessage);
        if (isClearlyNewTopic(text)) {
            return false;
        }
        // 命中续跑词表，或文本本身是明确的续跑/授权语句
        if (s.continueTask() || isExplicitContinuation(text)) {
            return true;
        }
        // 续跑词表只覆盖有限写法，不能单独覆盖消息边界；
        // 只有「此刻正在等用户答复」时的短答复才允许恢复，且仍不许吞掉明摆着的新任务。
        return awaitingConfirm && looksLikeHumanReply(text);
    }

    private static boolean isExplicitContinuation(String text) {
        return !text.isEmpty()
                && !isClearlyNewTopic(text)
                && (EXPLICIT_CONTINUE.matcher(text).matches()
                || APPROVAL.matcher(text).matches());
    }

    static boolean isClearlyNewTopic(String text) {
        if (text.isEmpty()) {
            return false;
        }
        return NEW_TOPIC.matcher(text).matches()
                || text.endsWith("?") || text.endsWith("？");
    }

    /** 等待人工输入时，允许凭据/确认类短答复继续，但不吞掉新任务。 */
    static boolean looksLikeHumanReply(String text) {
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
