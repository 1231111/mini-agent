package com.miniagent.agent.context;

import com.miniagent.common.embedding.SharedEmbeddingModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextHistorySelectorTest {

    @Test
    void overlapPrefersReportOverRecentChitchat() {
        ContextHistorySelector selector = new ContextHistorySelector();
        List<ChatMessage> all = new ArrayList<>();
        all.add(UserMessage.from("这是季度销售报告，营收同比增长12%"));
        all.add(AiMessage.from("已记录报告要点：营收+12%。"));
        for (int i = 0; i < 10; i++) {
            all.add(UserMessage.from("嗯嗯好的确认一下" + i));
            all.add(AiMessage.from("好的。"));
        }
        ContextReferenceDecision d = ContextReference.detect("刚才那份报告的营收增长多少？");
        List<ChatMessage> picked = selector.selectRelevant(
                "s1", all, "刚才那份报告的营收增长多少？", d, 4, 48, 4);
        String joined = picked.toString();
        assertTrue(joined.contains("销售报告") || joined.contains("营收"), joined);
        assertFalse(picked.isEmpty());
    }

    @Test
    void pronounKeepsRecentTurn() {
        ContextHistorySelector selector = new ContextHistorySelector();
        List<ChatMessage> mem = List.of(
                UserMessage.from("画一只橘猫"),
                AiMessage.from("已生成橘猫图")
        );
        ContextReferenceDecision d = ContextReference.detect("把它改成蓝色");
        List<ChatMessage> picked = selector.selectRelevant("s1", mem, "把它改成蓝色", d, 4, 48, 4);
        assertFalse(picked.isEmpty());
        assertTrue(picked.toString().contains("猫") || picked.toString().contains("橘"));
    }

    @Test
    void cosineRanksSimilarVectors() {
        float[] q = {1f, 0f, 0f};
        float[] near = {0.9f, 0.1f, 0f};
        float[] far = {0f, 1f, 0f};
        assertTrue(SharedEmbeddingModel.cosine(q, near) > SharedEmbeddingModel.cosine(q, far));
        assertEquals(1.0, SharedEmbeddingModel.cosine(q, q), 1e-6);
    }

    @Test
    void persistedHitsBecomeMessages() {
        ContextHistorySelector selector = new ContextHistorySelector();
        SessionHistoryVectorStore fake = new SessionHistoryVectorStore() {
            @Override public boolean isEnabled() { return true; }
            @Override public void upsertMessage(String s, String r, String t) {}
            @Override public void deleteSession(String s) {}
            @Override
            public List<Hit> search(String sessionId, String query, int topK, double minScore) {
                return List.of(
                        new Hit(1, "user", "季度销售报告营收增长12%", 0.9),
                        new Hit(2, "assistant", "营收同比增长12%", 0.85)
                );
            }
        };
        selector.wireForTest(fake, true, 0.3);
        List<ChatMessage> window = List.of(UserMessage.from("嗯"), AiMessage.from("好"));
        ContextReferenceDecision d = ContextReference.detect("刚才那份报告营收多少");
        assertTrue(d.shouldLoadPriorHistory());
        List<ChatMessage> picked = selector.selectRelevant("s1", window, "刚才那份报告营收多少", d, 4, 48, 4);
        assertEquals(2, picked.size());
        assertTrue(picked.get(0) instanceof UserMessage);
        assertTrue(((UserMessage) picked.get(0)).singleText().contains("营收"));
    }
}
