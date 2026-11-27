package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic retrieval, not a language-specific task router or an outcome attribution policy. */
final class ProcedureRelevanceRanker {
    private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{N}_]+", Pattern.UNICODE_CHARACTER_CLASS);
    private ProcedureRelevanceRanker() {}

    static List<AgentProcedureEntity> rank(Collection<AgentProcedureEntity> candidates, String query, int limit) {
        Set<String> terms = terms(query);
        record Candidate(AgentProcedureEntity value, double relevance, double capability, double reliability) {}
        return candidates.stream().map(p -> new Candidate(p,
                        overlap(terms, terms(p.getName() + " " + Objects.toString(p.getDescription(), ""))),
                        overlap(terms, terms(Objects.toString(p.getPreconditionsJson(), "") + " "
                                + Objects.toString(p.getStepsJson(), "") + " " + Objects.toString(p.getSuccessConditionsJson(), ""))),
                        reliability(p)))
                .filter(c -> terms.isEmpty() || c.relevance() > 0 || c.capability() > 0)
                .sorted(Comparator.comparingDouble(Candidate::relevance).reversed()
                        .thenComparing(Comparator.comparingDouble(Candidate::capability).reversed())
                        .thenComparing(Comparator.comparingDouble(Candidate::reliability).reversed())
                        .thenComparing(c -> Optional.ofNullable(c.value().getImportance()).orElse(0.5), Comparator.reverseOrder())
                        .thenComparing(c -> Objects.toString(c.value().getName(), ""))
                        .thenComparing(c -> Optional.ofNullable(c.value().getId()).orElse(Long.MAX_VALUE)))
                .limit(Math.max(0, limit)).map(Candidate::value).toList();
    }

    /** Beta(1,1) prior avoids declaring an untried version either perfect or broken. */
    private static double reliability(AgentProcedureEntity procedure) {
        long attempts = Math.max(0, Optional.ofNullable(procedure.getAttemptCount()).orElse(0L));
        long successes = Math.max(0, Math.min(attempts, Optional.ofNullable(procedure.getSuccessCount()).orElse(0L)));
        return (successes + 1.0) / (attempts + 2.0);
    }

    private static double overlap(Set<String> query, Set<String> content) {
        if (query.isEmpty()) return 0;
        long matches = query.stream().filter(content::contains).count();
        return matches / (double) query.size();
    }

    /** Words for alphabetic text, code-point bigrams for scripts without spaces. */
    private static Set<String> terms(String value) {
        if (value == null || value.isBlank()) return Set.of();
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        var matcher = WORDS.matcher(normalized);
        while (matcher.find()) {
            String word = matcher.group();
            int[] chars = word.codePoints().toArray();
            boolean ideographic = word.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
                    || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HIRAGANA
                    || Character.UnicodeScript.of(cp) == Character.UnicodeScript.KATAKANA);
            if (ideographic && chars.length > 1) {
                for (int i = 0; i < chars.length - 1; i++) tokens.add(new String(chars, i, 2));
            } else tokens.add(word);
        }
        return tokens;
    }
}
