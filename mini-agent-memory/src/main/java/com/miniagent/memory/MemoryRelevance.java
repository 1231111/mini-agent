package com.miniagent.memory;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Query-conditioned lexical fallback for memory sources without vector embeddings. */
public final class MemoryRelevance {

    private static final Pattern CJK_RUN = Pattern.compile("[\\p{IsHan}]+");
    private static final Pattern WORD = Pattern.compile("[\\p{IsLatin}\\p{N}][\\p{IsLatin}\\p{N}_+#.-]*");
    private static final Set<String> STOP_WORDS = Set.of(
            "用户", "偏好", "喜欢", "希望", "请用", "请使用", "使用", "回答", "进行", "内容",
            "当前", "本次", "本轮", "任务", "记忆", "相关", "提供", "帮助", "可以", "需要",
            "一个", "这个", "那个", "怎么", "为什么", "查询", "搜索", "查看", "获取", "最新",
            "the", "and", "for", "with", "this", "that", "user", "prefer", "memory", "please"
    );

    private MemoryRelevance() { }

    /**
     * Rank candidates by query-term overlap. Ties preserve the source's order (usually newest-first).
     * Empty or uninformative queries return no candidates; they never trigger full-memory injection.
     */
    public static <T> List<T> topRelevant(String query, List<T> candidates,
                                          Function<T, String> text, int limit) {
        if (StringUtils.isBlank(query) || candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) return List.of();

        List<Scored<T>> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            T candidate = candidates.get(i);
            if (candidate == null) continue;
            Set<String> candidateTerms = terms(text.apply(candidate));
            if (candidateTerms.isEmpty()) continue;
            int overlap = 0;
            for (String term : queryTerms) {
                if (candidateTerms.contains(term)) overlap++;
            }
            if (overlap == 0) continue;

            double queryCoverage = overlap / (double) queryTerms.size();
            double normalizedOverlap = overlap / Math.sqrt(queryTerms.size() * (double) candidateTerms.size());
            double score = 0.7 * queryCoverage + 0.3 * normalizedOverlap;
            scored.add(new Scored<>(candidate, score, i));
        }
        return scored.stream()
                .sorted(Comparator.<Scored<T>>comparingDouble(Scored::score).reversed()
                        .thenComparingInt(Scored::index))
                .limit(limit)
                .map(Scored::value)
                .toList();
    }

    private static Set<String> terms(String input) {
        if (StringUtils.isBlank(input)) return Set.of();
        String normalized = input.toLowerCase(Locale.ROOT);
        Set<String> result = new HashSet<>();

        Matcher words = WORD.matcher(normalized);
        while (words.find()) addTerm(result, words.group());

        Matcher cjk = CJK_RUN.matcher(normalized);
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() == 1) continue;
            for (int size : new int[]{2, 3}) {
                for (int i = 0; i + size <= run.length(); i++) {
                    addTerm(result, run.substring(i, i + size));
                }
            }
        }
        return result;
    }

    private static void addTerm(Set<String> terms, String term) {
        if (term.length() >= 2 && !STOP_WORDS.contains(term)) terms.add(term);
    }

    private record Scored<T>(T value, double score, int index) { }
}
