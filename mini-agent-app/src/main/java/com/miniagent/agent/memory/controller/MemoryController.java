package com.miniagent.agent.memory.controller;

import com.miniagent.common.ApiResponse;
import com.miniagent.memory.MemoryManager;
import com.miniagent.memory.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 记忆系统 REST API。
 */
@RestController
@RequestMapping("/v1/memory")
public class MemoryController {

    @Autowired
    private MemoryManager memoryManager;

    /**
     * 记录 Agent 事件。
     */
    @PostMapping("/events")
    public ApiResponse<Void> recordEvent(@RequestBody AgentEvent event) {
        memoryManager.recordEvent(event);
        return ApiResponse.ok(null);
    }

    /**
     * 写入一条记忆。
     */
    @PostMapping("/memories")
    public ApiResponse<Map<String, Object>> writeMemory(@RequestBody MemoryEntry entry) {
        memoryManager.writeMemory(entry);
        return ApiResponse.ok(Map.of("id", entry.getId()));
    }

    /**
     * 检索记忆。
     */
    @PostMapping("/memories/search")
    public ApiResponse<List<ScoredMemory>> search(@RequestBody MemoryQuery query) {
        List<ScoredMemory> results = memoryManager.retrieve(query);
        return ApiResponse.ok(results);
    }

    /**
     * 构建 Agent 上下文（给 Planner 用）。
     */
    @PostMapping("/context")
    public ApiResponse<MemoryContext> buildContext(@RequestBody AgentContext ctx) {
        MemoryContext context = memoryManager.buildContext(ctx);
        return ApiResponse.ok(context);
    }

    /**
     * 获取单条记忆。
     */
    @GetMapping("/memories/{id}")
    public ApiResponse<Object> getMemory(@PathVariable Long id) {
        // 简化实现：通过 search 返回
        return ApiResponse.ok("使用 /memories/search 接口检索");
    }

    /**
     * 更新记忆。
     */
    @PatchMapping("/memories/{id}")
    public ApiResponse<Void> updateMemory(@PathVariable Long id, @RequestBody MemoryEntry update) {
        memoryManager.updateMemory(id, update);
        return ApiResponse.ok(null);
    }

    /**
     * 删除记忆（软删除）。
     */
    @DeleteMapping("/memories/{id}")
    public ApiResponse<Void> deleteMemory(@PathVariable Long id) {
        memoryManager.deleteMemory(id);
        return ApiResponse.ok(null);
    }

    /**
     * 写入语义事实（三元组）。
     */
    @PostMapping("/facts")
    public ApiResponse<Void> writeFact(@RequestBody SemanticFact fact) {
        memoryManager.writeFact(fact);
        return ApiResponse.ok(null);
    }

    /**
     * 查询语义事实。
     */
    @GetMapping("/facts")
    public ApiResponse<List<SemanticFact>> queryFacts(
            @RequestParam String tenantId,
            @RequestParam String scopeType,
            @RequestParam String scopeId,
            @RequestParam(required = false) String subject) {
        List<SemanticFact> facts = memoryManager.queryFacts(tenantId, scopeType, scopeId, subject);
        return ApiResponse.ok(facts);
    }

    /**
     * 写入 SOP。
     */
    @PostMapping("/procedures")
    public ApiResponse<Void> writeProcedure(@RequestBody Procedure procedure) {
        memoryManager.writeProcedure(procedure);
        return ApiResponse.ok(null);
    }

    /**
     * 查询 SOP。
     */
    @GetMapping("/procedures")
    public ApiResponse<List<Procedure>> queryProcedures(
            @RequestParam String tenantId,
            @RequestParam String scopeType,
            @RequestParam String scopeId,
            @RequestParam(required = false) String name) {
        List<Procedure> procs = memoryManager.queryProcedures(tenantId, scopeType, scopeId, name);
        return ApiResponse.ok(procs);
    }

    /**
     * 回忆历史经验。
     */
    @GetMapping("/episodes/recall")
    public ApiResponse<List<Episode>> recallEpisodes(
            @RequestParam String query,
            @RequestParam String tenantId,
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "5") int topK) {
        List<Episode> episodes = memoryManager.recallEpisodes(query, tenantId, projectId, topK);
        return ApiResponse.ok(episodes);
    }

    /**
     * 执行记忆巩固。
     */
    @PostMapping("/consolidate")
    public ApiResponse<Void> consolidate(@RequestParam String sessionId) {
        memoryManager.consolidate(sessionId);
        return ApiResponse.ok(null);
    }

    /**
     * 执行遗忘。
     */
    @PostMapping("/forget")
    public ApiResponse<Void> forget(@RequestParam String tenantId) {
        memoryManager.forget(tenantId);
        return ApiResponse.ok(null);
    }

    /**
     * 获取记忆统计。
     */
    @GetMapping("/stats")
    public ApiResponse<MemoryStats> getStats(
            @RequestParam String tenantId,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) String scopeId) {
        MemoryStats stats = memoryManager.getStats(tenantId, scopeType, scopeId);
        return ApiResponse.ok(stats);
    }
}
