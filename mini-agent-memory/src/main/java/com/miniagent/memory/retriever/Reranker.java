package com.miniagent.memory.retriever;

import com.miniagent.memory.model.ScoredMemory;

import java.util.List;

/**
 * 重排序器：对候选结果进行精排。
 */
public interface Reranker {

    /**
     * 对候选结果重排序，返回 top-K。
     */
    List<ScoredMemory> rerank(String query, List<ScoredMemory> candidates, int topK);
}
