package com.miniagent.memory.retriever;

import com.miniagent.memory.model.ScoredMemory;

import java.util.List;

/**
 * 上下文压缩器：在 token 预算内截断和压缩记忆内容。
 */
public interface ContextCompressor {

    /**
     * 将记忆列表压缩为适合注入 prompt 的文本。
     *
     * @param memories   记忆列表（已按重要度排序）
     * @param maxTokens  token 预算
     * @return 压缩后的文本
     */
    String compress(List<ScoredMemory> memories, int maxTokens);
}
