package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板 fallback 的结构信号：URL、文件名、文本事实。
 * 结构化图是否拆够由 capability × doneWhen 判定，不刮节点名。
 */
final class DecompositionPolicy {

    private static final Pattern HTTP_URL =
            Pattern.compile("https?://[^\\s)\"'<>\\]]+");

    private DecompositionPolicy() {}

    static String blob(String userMessage, TaskPlan plan) {
        String goal = plan == null || plan.taskGoal() == null ? "" : plan.taskGoal();
        return ((userMessage == null ? "" : userMessage) + " " + goal)
                .toLowerCase(Locale.ROOT);
    }

    static String firstUrl(String text) {
        if (StringUtils.isBlank(text)) {
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

    static boolean isSpreadsheet(String path) {
        if (StringUtils.isBlank(path)) {
            return false;
        }
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".xlsx") || p.endsWith(".csv");
    }

    static boolean isDiagramFile(String path) {
        if (StringUtils.isBlank(path)) {
            return false;
        }
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".mmd") || p.endsWith(".png");
    }

    /** 某个信号是否命中；plan 为空一律按未命中。 */
    private static boolean hit(TaskPlan plan, Predicate<TaskSignals> test) {
        return plan != null && test.test(plan.signals());
    }

    /** 有 URL 且要落盘：抓页面 + 写文件两段。 */
    static boolean fetchWrite(String userMessage, TaskPlan plan) {
        String t = blob(userMessage, plan);
        if (firstUrl(t).isBlank()) {
            return false;
        }
        return !firstPath(t).isBlank() || hit(plan, TaskSignals::needsFiles);
    }

    /** 联网取资料且有落盘文件名：获取 + 写入两段。 */
    static boolean researchThenFile(String userMessage, TaskPlan plan) {
        if (!hit(plan, TaskSignals::needsWeb)) {
            return false;
        }
        return !firstPath(blob(userMessage, plan)).isBlank();
    }

    /**
     * 目标是表格文件、且这轮是读它：读取 + 统计两段。
     *
     * <p>「生成 sales.xlsx」也要落盘、也有 xlsx 路径，但它不是读 —— 光看路径
     * 会把生成动作拆成「读一个还不存在的文件 + 统计」，第一步必然失败。
     * 所以这里额外要求命中读类动词（{@link TaskSignals#readsFile()}）。</p>
     */
    static boolean readThenAnalyze(String userMessage, TaskPlan plan) {
        return isSpreadsheet(firstPath(blob(userMessage, plan)))
                && hit(plan, TaskSignals::readsFile);
    }

    static boolean looksLikeDiagram(String userMessage, TaskPlan plan) {
        return isDiagramFile(firstPath(blob(userMessage, plan)));
    }

    /** 有 URL 或文件名时，短任务也可以编成可调度图。 */
    static boolean hasGraphSignal(String userMessage, TaskPlan plan) {
        String t = blob(userMessage, plan);
        return !firstPath(t).isBlank() || !firstUrl(t).isBlank();
    }
}
