package com.miniagent.agent.context;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 指代粗筛：词法/否定/候选实体。精排由 ContextLoader 按相关度捞历史，本类不决定条数。
 */
public final class ContextReference {
    private ContextReference() {}

    /** 否定/撤销：命中则即使有指代词也不加载旧历史 */
    private static final Pattern NEGATION = Pattern.compile(
            "(不要参考|别参考|无需参考|不用参考|不要看|别看|忘掉|忘记|忽略|无视|"
                    + "重新开始|换个话题|聊点别的|聊別的|不重要了|无关了|無關了|"
                    + "don't\\s+refer|do\\s+not\\s+refer|forget\\s+(that|the|it)|ignore\\s+(that|the|above))",
            Pattern.CASE_INSENSITIVE);

    /** 强指代（含实体线索或明确回指短语） */
    private static final Pattern STRONG_REF = Pattern.compile(
            "(刚才|剛剛|刚刚|上面|之前|前述|上述|前文|上次|上一步|前面那个|前面那個|刚说的|剛說的|"
                    + "那份|那篇|这篇|這篇|那段|这段|這段|上述内容|上面说|上面說|刚才说|剛才說|"
                    + "那份报告|那份報告|那份文|按刚才|按剛才|按上面|继续刚才|繼續剛才|接着刚才|接著剛才|"
                    + "the\\s+(report|doc|document|file|image|message)\\s+above|"
                    + "above\\s+(report|doc|document)|"
                    + "that\\s+(report|doc|document|file|image|one))",
            Pattern.CASE_INSENSITIVE);

    /** 弱指代：代词/指示，需配合精排或最近轮保底 */
    private static final Pattern WEAK_REF = Pattern.compile(
            "(把它|将它|將它|这个|這個|那个|那個|这份|這份|那份|这些|這些|那些|"
                    + "它(?=[的地得]?[改画畫变變成成加拉剪涂塗])|"
                    + "\\bit\\b|\\bthat\\b|\\bthis\\b|\\bthose\\b|\\bthese\\b)",
            Pattern.CASE_INSENSITIVE);

    /** 指代词后的名词候选（简繁 + 常见英文） */
    private static final Pattern CANDIDATE_AFTER_REF = Pattern.compile(
            "(?:刚才|剛剛|刚刚|上面|之前|那份|那篇|这篇|這篇|那个|那個|这份|這份|前述|上述|上次|刚说的|剛說的|"
                    + "the\\s+above|above|that|this)\\s*([\\u4e00-\\u9fffA-Za-z]{1,16})");

    public static ContextReferenceDecision detect(String text) {
        if (StringUtils.isBlank(text)) {
            return ContextReferenceDecision.none();
        }
        String q = text.trim();
        boolean negated = NEGATION.matcher(q).find();
        boolean strong = STRONG_REF.matcher(q).find();
        boolean weak = WEAK_REF.matcher(q).find();
        List<String> candidates = extractCandidates(q);

        if (!strong && !weak && candidates.isEmpty()) {
            return ContextReferenceDecision.none();
        }

        double confidence;
        if (strong && !candidates.isEmpty()) {
            confidence = 0.9;
        } else if (strong) {
            confidence = 0.7;
        } else if (weak && !candidates.isEmpty()) {
            confidence = 0.65;
        } else {
            confidence = 0.45; // 纯代词
        }
        if (negated) {
            confidence = Math.min(confidence, 0.2);
        }

        return new ContextReferenceDecision(true, confidence, candidates, negated);
    }

    /** @deprecated 仅兼容旧调用；请用 {@link #detect(String)} */
    @Deprecated
    public static boolean mentionsPrior(String text) {
        return detect(text).shouldLoadPriorHistory();
    }

    private static List<String> extractCandidates(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = CANDIDATE_AFTER_REF.matcher(text);
        while (m.find()) {
            String c = m.group(1);
            if (c == null) continue;
            c = c.trim();
            if (c.length() < 1 || isStop(c)) continue;
            out.add(c.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(out);
    }

    private static boolean isStop(String w) {
        return Set.of("的", "了", "吗", "嗎", "呢", "吧", "啊", "是", "在", "和", "与", "與",
                "里", "裡", "中", "上", "下", "说", "說", "看", "用", "把", "被",
                "report", "above", "that", "this", "the", "a", "an").contains(w.toLowerCase(Locale.ROOT));
    }
}
