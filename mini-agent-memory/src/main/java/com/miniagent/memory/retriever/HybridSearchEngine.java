package com.miniagent.memory.retriever;

import com.miniagent.memory.model.MemoryQuery;
import com.miniagent.memory.model.ScoredMemory;

import java.util.List;

/**
 * 混合检索引擎：向量语义检索 + 关键词检索 + 元数据过滤 → 加权融合。
 */
public interface HybridSearchEngine {

    /**
     * 混合检索。返回按最终得分降序排列的结果。
     */
    List<ScoredMemory> search(MemoryQuery query);
}
