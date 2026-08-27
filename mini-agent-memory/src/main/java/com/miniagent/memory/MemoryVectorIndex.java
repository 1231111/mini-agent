package com.miniagent.memory;

import java.util.List;

/**
 * 长期记忆向量索引端口。实现可留在 app（依赖 LangChain embedding），核心 MemoryStore 不依赖具体实现。
 */
public interface MemoryVectorIndex {

    boolean isEnabled();

    boolean hasIndex(Long userId);

    void reindex(Long userId, List<String> entries);

    List<String> recall(Long userId, String query);
}
