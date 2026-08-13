package com.miniagent.memory;

import com.miniagent.memory.model.*;

import java.util.List;

/**
 * 记忆管理器门面接口。对上层 Planner / Executor / Agent Runtime 提供统一 API。
 */
public interface MemoryManager {

    // === 写入 ===

    /** 记录一个 Agent 事件，由内部管线决定是否转化为长期记忆 */
    void recordEvent(AgentEvent event);

    /** 直接写入一条记忆 */
    void writeMemory(MemoryEntry entry);

    /** 更新一条记忆 */
    void updateMemory(Long id, MemoryEntry update);

    /** 删除一条记忆（软删除） */
    void deleteMemory(Long id);

    // === 检索 ===

    /** 语义+关键词混合检索 */
    List<ScoredMemory> retrieve(MemoryQuery query);

    /** 为 Planner 构建结构化上下文 */
    MemoryContext buildContext(AgentContext ctx);

    // === 工作记忆 ===

    /** 获取当前 session 的工作记忆 */
    WorkingMemory getWorkingMemory(String sessionId);

    /** 更新工作记忆 */
    void updateWorkingMemory(String sessionId, WorkingMemory update);

    // === 语义事实 ===

    /** 写入一个三元组事实 */
    void writeFact(SemanticFact fact);

    /** 查询事实 */
    List<SemanticFact> queryFacts(String tenantId, String scopeType, String scopeId, String subject);

    // === 程序性记忆 ===

    /** 写入一个 SOP */
    void writeProcedure(Procedure procedure);

    /** 按名称查询 SOP */
    List<Procedure> queryProcedures(String tenantId, String scopeType, String scopeId, String name);

    // === 情景记忆 ===

    /** 查询历史经验 */
    List<Episode> recallEpisodes(String query, String tenantId, String projectId, int topK);

    // === 生命周期 ===

    /** 记忆巩固：session 结束时从事件流提炼 Episode */
    void consolidate(String sessionId);

    /** 遗忘：按策略归档/删除低价值记忆 */
    void forget(String tenantId);

    // === 统计 ===

    /** 获取记忆统计 */
    MemoryStats getStats(String tenantId, String scopeType, String scopeId);
}
