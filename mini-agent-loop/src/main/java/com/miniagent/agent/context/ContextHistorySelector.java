package com.miniagent.agent.context;

import com.miniagent.common.ChatMessageTexts;
import com.miniagent.common.ChatRole;
import com.miniagent.common.embedding.SharedEmbeddingModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 指代历史精捞：优先会话历史向量库（Milvus/本地持久化），否则扫描窗词重叠回退。
 */
@Slf4j
@Component
public class ContextHistorySelector {

    private static final Pattern TOKEN = Pattern.compile("[\\u4e00-\\u9fff]{2,}|[A-Za-z]{2,}|\\d{2,}");
    private static final double OVERLAP_MIN_SCORE = 0.15;

    @Autowired(required = false)
    private SessionHistoryVectorStore historyVectorStore;
    @Autowired(required = false)
    private SharedEmbeddingModel embeddingModel;

    @Value("${agent.context.history.ref-vector-enabled:true}")
    private boolean vectorEnabled;
    @Value("${agent.context.history.ref-min-score:0.35}")
    private double vectorMinScore;

    /** 测试 / 手动装配 */
    void wireForTest(SessionHistoryVectorStore store, boolean vectorOn, double minScore) {
        this.historyVectorStore = store;
        this.vectorEnabled = vectorOn;
        this.vectorMinScore = minScore;
    }

    public List<ChatMessage> selectRelevant(
            String sessionId,
            List<ChatMessage> all,
            String query,
            ContextReferenceDecision decision,
            int topK,
            int scanMax,
            int pronounAnchor
    ) {
        if (all == null || all.isEmpty() || topK == 0 || decision == null || !decision.shouldLoadPriorHistory()) {
            return List.of();
        }

        String q = buildQuery(query, decision);
        if (vectorEnabled && historyVectorStore != null && historyVectorStore.isEnabled()
                && StringUtils.isNotBlank(sessionId)) {
            List<SessionHistoryVectorStore.Hit> hits =
                    historyVectorStore.search(sessionId, q, topK, vectorMinScore);
            if (hits.isEmpty() && historyVectorStore.isEnabled()) {
                // 上线前旧消息无索引：整窗回填后再搜（embedding 熔断后跳过）
                historyVectorStore.backfill(sessionId, all);
                hits = historyVectorStore.search(
                        sessionId, q, topK, vectorMinScore);
            }
            if (!hits.isEmpty()) {
                List<ChatMessage> fromStore = toMessages(hits);
                if (needsPronounAnchor(decision)) {
                    fromStore = mergeAnchor(fromStore, all, pronounAnchor, topK);
                }
                log.debug("ContextHistorySelector mode=persisted-vector hits={}", hits.size());
                return fromStore;
            }
            log.debug("ContextHistorySelector 持久化向量无命中，回退扫描窗");
        }

        // 扫描窗回退：窗口本身已不够大时直接全给
        if (topK < 0 || topK >= all.size()) {
            return new ArrayList<>(all);
        }

        // 扫描窗：即时 embedding（无持久化库时）或词重叠
        int from = Math.max(0, all.size() - Math.max(topK, scanMax));
        List<ChatMessage> pool = all.subList(from, all.size());
        List<Scored> scored;
        String mode;
        if (vectorEnabled && embeddingModel != null && embeddingModel.isEnabled()) {
            try {
                scored = scoreByVector(pool, from, q);
                mode = "window-vector";
            } catch (Exception e) {
                log.warn("扫描窗向量精排失败，回退词重叠: {}", e.getMessage());
                scored = scoreByOverlap(pool, from, q);
                mode = "overlap";
            }
        } else {
            scored = scoreByOverlap(pool, from, q);
            mode = "overlap";
        }
        double minScore = mode.contains("vector") ? vectorMinScore : OVERLAP_MIN_SCORE;
        List<ChatMessage> out = pick(all, pool, scored, decision, topK, pronounAnchor, minScore);
        log.debug("ContextHistorySelector mode={} picked={}", mode, out.size());
        return out;
    }

    private static String buildQuery(String query, ContextReferenceDecision decision) {
        StringBuilder qb = new StringBuilder(query == null ? "" : query);
        for (String c : decision.candidates()) {
            qb.append(' ').append(c);
        }
        return qb.toString().trim();
    }

    private static boolean needsPronounAnchor(ContextReferenceDecision decision) {
        return decision.candidates().isEmpty() && decision.confidence() < 0.7;
    }

    private static List<ChatMessage> toMessages(List<SessionHistoryVectorStore.Hit> hits) {
        List<ChatMessage> out = new ArrayList<>();
        for (SessionHistoryVectorStore.Hit h : hits) {
            if (ChatRole.ASSISTANT.getValue().equalsIgnoreCase(h.role())) {
                out.add(AiMessage.from(h.text()));
            } else {
                out.add(UserMessage.from(h.text()));
            }
        }
        return out;
    }

    private static List<ChatMessage> mergeAnchor(List<ChatMessage> fromStore, List<ChatMessage> all,
                                                int pronounAnchor, int topK) {
        Set<String> seen = new HashSet<>();
        List<ChatMessage> out = new ArrayList<>();
        for (ChatMessage m : fromStore) {
            String k = textOf(m);
            if (seen.add(k)) out.add(m);
        }
        int anchor = Math.min(pronounAnchor, all.size());
        for (int i = all.size() - anchor; i < all.size(); i++) {
            ChatMessage m = all.get(i);
            if (seen.add(textOf(m))) out.add(m);
        }
        if (out.size() > topK) {
            return new ArrayList<>(out.subList(out.size() - topK, out.size()));
        }
        return out;
    }

    private List<Scored> scoreByVector(List<ChatMessage> pool, int from, String query) {
        float[] qVec = embeddingModel.embed(query);
        if (qVec.length == 0) throw new IllegalStateException("query embedding empty");
        List<String> texts = new ArrayList<>(pool.size());
        for (ChatMessage msg : pool) {
            String t = textOf(msg);
            texts.add(t.length() > 800 ? t.substring(0, 800) : t);
        }
        List<float[]> docVecs = embeddingModel.embedAll(texts);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            if (StringUtils.isBlank(texts.get(i))) continue;
            float[] dv = i < docVecs.size() ? docVecs.get(i) : new float[0];
            double sim = SharedEmbeddingModel.cosine(qVec, dv);
            scored.add(new Scored(from + i, sim + 0.03 * (i + 1) / pool.size()));
        }
        return scored;
    }

    private List<Scored> scoreByOverlap(List<ChatMessage> pool, int from, String query) {
        Set<String> queryTokens = tokens(query);
        List<Scored> scored = new ArrayList<>();
        if (queryTokens.isEmpty()) return scored;
        for (int i = 0; i < pool.size(); i++) {
            String text = textOf(pool.get(i));
            if (StringUtils.isBlank(text)) continue;
            double s = overlapScore(queryTokens, tokens(text));
            scored.add(new Scored(from + i, s + 0.05 * (i + 1) / pool.size()));
        }
        return scored;
    }

    private List<ChatMessage> pick(
            List<ChatMessage> all,
            List<ChatMessage> pool,
            List<Scored> scored,
            ContextReferenceDecision decision,
            int topK,
            int pronounAnchor,
            double minScore
    ) {
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        Set<Integer> picked = new LinkedHashSet<>();
        for (Scored s : scored) {
            if (s.score() < minScore && !picked.isEmpty()) break;
            picked.add(s.index());
            if (picked.size() >= topK) break;
        }
        if (needsPronounAnchor(decision) || picked.isEmpty()) {
            int anchor = Math.min(pronounAnchor, pool.size());
            for (int i = all.size() - anchor; i < all.size(); i++) {
                if (i >= 0) picked.add(i);
            }
        }
        Set<Integer> expanded = new LinkedHashSet<>();
        List<Integer> ordered = new ArrayList<>(picked);
        ordered.sort(Integer::compareTo);
        for (int idx : ordered) {
            if (idx > 0 && all.get(idx) instanceof AiMessage && all.get(idx - 1) instanceof UserMessage) {
                expanded.add(idx - 1);
            }
            expanded.add(idx);
            if (expanded.size() >= topK + 2) break;
        }
        List<Integer> finalIdx = new ArrayList<>(expanded);
        finalIdx.sort(Integer::compareTo);
        if (finalIdx.size() > topK) {
            Map<Integer, Double> scoreMap = new HashMap<>();
            for (Scored s : scored) scoreMap.put(s.index(), s.score());
            finalIdx.sort((a, b) -> Double.compare(scoreMap.getOrDefault(b, 0d), scoreMap.getOrDefault(a, 0d)));
            List<Integer> keep = new ArrayList<>(finalIdx.subList(0, topK));
            keep.sort(Integer::compareTo);
            finalIdx = keep;
        }
        List<ChatMessage> out = new ArrayList<>();
        for (int idx : finalIdx) out.add(all.get(idx));
        if (out.isEmpty() && !pool.isEmpty()) {
            int n = Math.min(topK, Math.max(2, pronounAnchor));
            return new ArrayList<>(pool.subList(Math.max(0, pool.size() - n), pool.size()));
        }
        return out;
    }

    private static double overlapScore(Set<String> queryTokens, Set<String> docTokens) {
        if (queryTokens.isEmpty() || docTokens.isEmpty()) return 0;
        int hit = 0;
        for (String t : queryTokens) {
            if (docTokens.contains(t)) hit++;
        }
        return (double) hit / queryTokens.size();
    }

    static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        if (StringUtils.isBlank(text)) return out;
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) out.add(m.group());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) out.add(String.valueOf(c));
        }
        return out;
    }

    private static String textOf(ChatMessage msg) {
        if (msg instanceof UserMessage um) return ChatMessageTexts.userPlain(um);
        if (msg instanceof AiMessage am) return am.text() == null ? "" : am.text();
        return msg == null ? "" : String.valueOf(msg);
    }

    private record Scored(int index, double score) {}
}
