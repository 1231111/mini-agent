package com.miniagent.memory;

import com.miniagent.memory.model.MemoryReadPolicy;

import java.util.List;
import java.util.Map;

/**
 * 记忆语义门面：上下文只经此检索，工具只经此写入。
 * 底层仍是 Blob + 结构化存储；本接口负责策略、去重、分类。
 */
public interface MemoryService {

    /**
     * 按策略组装本轮可注入的单一记忆块。policy 全关时返回空串。
     */
    String retrieveForPrompt(String sessionId, String query, MemoryReadPolicy policy);

    Map<String, Object> add(String target, String content);

    Map<String, Object> replace(String target, String oldText, String newContent);

    Map<String, Object> remove(String target, String oldText);

    List<String> read(String target);

    /**
     * 把遗留 USER blob 条目晋升为语义事实，成功后从 blob 删除。已存在则只清 blob。
     */
    void promoteUserBlob();
}
