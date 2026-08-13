package com.miniagent.agent.memory.retriever;

import com.miniagent.memory.model.ScoredMemory;
import com.miniagent.memory.retriever.Reranker;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于评分的重排序器（规则，不调 LLM）。
 * 按 HybridScore.finalScore 排序，返回 top-K。
 */
@Component
public class ScoreBasedReranker implements Reranker {

    @Override
    public List<ScoredMemory> rerank(String query, List<ScoredMemory> candidates, int topK) {
        return candidates.stream()
            .sorted((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()))
            .limit(topK)
            .collect(Collectors.toList());
    }
}
