package com.miniagent.agent.memory.controller;

import com.miniagent.common.ApiResponse;
import com.miniagent.web.dto.resp.MemoryIdDTO;
import com.miniagent.web.dto.resp.ProcedureWriteResultDTO;
import com.miniagent.web.dto.resp.ProcedureProposalReviewRequest;
import com.miniagent.agent.memory.entity.ProcedureRewriteProposalEntity;
import com.miniagent.agent.memory.manager.ProcedureRewriteProposalService;
import com.miniagent.agent.memory.manager.ProcedureCandidateValidationService;
import com.miniagent.web.dto.resp.ProcedureCandidateValidationRequest;
import com.miniagent.config.entity.UserRole;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.SessionAuthorizationService;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.memory.MemoryManager;
import com.miniagent.memory.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 记忆系统 REST API。
 */
@RestController
@RequestMapping("/v1/memory")
public class MemoryController {

    @Autowired
    private MemoryManager memoryManager;

    @Autowired
    private SessionAuthorizationService sessionAuthorization;

    @Autowired
    private ProcedureRewriteProposalService rewriteProposalService;
    @Autowired
    private ProcedureCandidateValidationService candidateValidationService;
    @Autowired
    private com.miniagent.agent.memory.service.AgentProjectAuthorizationService projectAuthorization;

    /**
     * 记录 Agent 事件。
     */
    @PostMapping("/events")
    public ApiResponse<Void> recordEvent(@RequestBody AgentEvent event,
                                         HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        bindEventToPrincipal(event, user);
        memoryManager.recordEvent(event);
        return ApiResponse.ok(null);
    }

    /**
     * 写入一条记忆。
     */
    @PostMapping("/memories")
    public ApiResponse<MemoryIdDTO> writeMemory(@RequestBody MemoryEntry entry,
                                                HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        bindMemoryToPrincipal(entry, user);
        memoryManager.writeMemory(entry);
        return ApiResponse.ok(new MemoryIdDTO(entry.getId()));
    }

    /**
     * 检索记忆。
     */
    @PostMapping("/memories/search")
    public ApiResponse<List<ScoredMemory>> search(@RequestBody MemoryQuery query,
                                                  HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        bindQueryToPrincipal(query, user);
        List<ScoredMemory> results = memoryManager.retrieve(query);
        return ApiResponse.ok(results);
    }

    /**
     * 构建 Agent 上下文（给 Planner 用）。
     */
    @PostMapping("/context")
    public ApiResponse<MemoryContext> buildContext(@RequestBody AgentContext ctx,
                                                   HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        bindContextToPrincipal(ctx, user);
        MemoryContext context = memoryManager.buildContext(ctx);
        return ApiResponse.ok(context);
    }

    /**
     * 获取单条记忆。
     */
    @GetMapping("/memories/{id}")
    public ApiResponse<Object> getMemory(@PathVariable Long id,
                                         HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        MemoryEntry memory = memoryManager.getMemory(user.tenantKey(), id)
                .orElseThrow(() -> new AccessDeniedException("Memory is not accessible"));
        requireMemoryAccess(user, memory);
        return ApiResponse.ok(memory);
    }

    /**
     * 更新记忆。
     */
    @PatchMapping("/memories/{id}")
    public ApiResponse<Void> updateMemory(@PathVariable Long id,
                                          @RequestBody MemoryEntry update,
                                          HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        MemoryEntry stored = memoryManager.getMemory(user.tenantKey(), id)
                .orElseThrow(() -> new AccessDeniedException("Memory is not accessible"));
        requireMemoryAccess(user, stored);
        // Scope is immutable from the API caller's perspective. Never accept a forged
        // tenant/user/project scope from the patch body.
        update.setTenantId(stored.getTenantId());
        update.setScope(stored.getScope());
        memoryManager.updateMemory(user.tenantKey(), id, update);
        return ApiResponse.ok(null);
    }

    /**
     * 删除记忆（软删除）。
     */
    @DeleteMapping("/memories/{id}")
    public ApiResponse<Void> deleteMemory(@PathVariable Long id, HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        MemoryEntry stored = memoryManager.getMemory(user.tenantKey(), id)
                .orElseThrow(() -> new AccessDeniedException("Memory is not accessible"));
        requireMemoryAccess(user, stored);
        memoryManager.deleteMemory(user.tenantKey(), id);
        return ApiResponse.ok(null);
    }

    /**
     * 写入语义事实（三元组）。
     */
    @PostMapping("/facts")
    public ApiResponse<Void> writeFact(@RequestBody SemanticFact fact,
                                       HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        bindScopedRecord(fact, user);
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
            @RequestParam(required = false) String subject,
            HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        MemoryScope scope = requestedScope(user, tenantId, scopeType, scopeId);
        List<SemanticFact> facts = memoryManager.queryFacts(
                scope.tenantId(), scope.scopeType().name(), scope.scopeId(), subject);
        return ApiResponse.ok(facts);
    }

    /**
     * 写入 SOP。同名同归属域是 upsert：内容没变幂等返回，内容变了原地升版。
     */
    @PostMapping("/procedures")
    public ApiResponse<ProcedureWriteResultDTO> writeProcedure(@RequestBody Procedure procedure,
                                                               HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        bindScopedRecord(procedure, user);
        Procedure written = memoryManager.writeProcedure(procedure);
        return ApiResponse.ok(new ProcedureWriteResultDTO(written.getId(), written.getVersion(),
                written.getAttemptCount(), written.getSuccessCount(), written.getSuccessRate()));
    }

    @GetMapping("/procedure-proposals")
    public ApiResponse<List<ProcedureRewriteProposalEntity>> listProcedureProposals(HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        try (var ignored = com.miniagent.common.context.OwnerContext.bind(
                new com.miniagent.common.context.OwnerContext(user.userId(), user.tenantKey()))) {
            return ApiResponse.ok(rewriteProposalService.listMine());
        }
    }

    @PostMapping("/procedure-proposals/{id}/validation")
    public ApiResponse<com.miniagent.agent.memory.entity.ProcedureCandidateValidationEntity> validateProcedureProposal(
            @PathVariable Long id, @RequestBody ProcedureCandidateValidationRequest request,
            HttpServletRequest servletRequest) {
        AuthenticatedUser user = requireUser(servletRequest);
        if (request == null) throw new IllegalArgumentException("validation request required");
        try (var ignored = com.miniagent.common.context.OwnerContext.bind(
                new com.miniagent.common.context.OwnerContext(user.userId(), user.tenantKey()))) {
            return ApiResponse.ok(candidateValidationService.record(id, user.userId(),
                    request.passed(), request.evidenceJson()));
        }
    }

    @PostMapping("/procedure-proposals/{id}/review")
    public ApiResponse<ProcedureRewriteProposalEntity> reviewProcedureProposal(
            @PathVariable Long id, @RequestBody ProcedureProposalReviewRequest request,
            HttpServletRequest servletRequest) {
        AuthenticatedUser user = requireUser(servletRequest);
        if (request == null) {
            throw new IllegalArgumentException("review request required");
        }
        try (var ignored = com.miniagent.common.context.OwnerContext.bind(
                new com.miniagent.common.context.OwnerContext(user.userId(), user.tenantKey()))) {
            return ApiResponse.ok(rewriteProposalService.review(id, request.approved()));
        }
    }

    /**
     * 查询 SOP。
     */
    @GetMapping("/procedures")
    public ApiResponse<List<Procedure>> queryProcedures(
            @RequestParam String tenantId,
            @RequestParam String scopeType,
            @RequestParam String scopeId,
            @RequestParam(required = false) String name,
            HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        MemoryScope scope = requestedScope(user, tenantId, scopeType, scopeId);
        List<Procedure> procs = memoryManager.queryProcedures(
                scope.tenantId(), scope.scopeType().name(), scope.scopeId(), name);
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
            @RequestParam(defaultValue = "5") int topK,
            HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        requireTenant(user, tenantId);
        if (projectId != null && user.role() == UserRole.USER) {
            throw new AccessDeniedException("Project-scoped memory requires elevated role");
        }
        if (projectId != null && !projectId.isBlank()) {
            projectAuthorization.requireMember(projectId, user.userId(), user.tenantKey());
        }
        int safeTopK = Math.max(1, Math.min(topK, 100));
        List<Episode> episodes = projectId == null || projectId.isBlank()
                ? memoryManager.recallEpisodes(query, user.tenantKey(), String.valueOf(user.userId()), null, safeTopK)
                : memoryManager.recallEpisodes(query, user.tenantKey(), projectId, safeTopK);
        return ApiResponse.ok(episodes);
    }

    /**
     * 执行记忆巩固。
     */
    @PostMapping("/consolidate")
    public ApiResponse<Void> consolidate(@RequestParam String sessionId,
                                         HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        sessionAuthorization.requireOwner(user.userId(), sessionId);
        memoryManager.consolidate(user.tenantKey(), sessionId);
        return ApiResponse.ok(null);
    }

    /**
     * 执行遗忘。
     */
    @PostMapping("/forget")
    public ApiResponse<Void> forget(@RequestParam String tenantId,
                                    HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        requireTenant(user, tenantId);
        if (user.role() == UserRole.USER) {
            throw new AccessDeniedException("Tenant-wide forgetting requires elevated role");
        }
        memoryManager.forget(user.tenantKey());
        return ApiResponse.ok(null);
    }

    /**
     * 获取记忆统计。
     */
    @GetMapping("/stats")
    public ApiResponse<MemoryStats> getStats(
            @RequestParam String tenantId,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) String scopeId,
            HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        MemoryScope scope = requestedScope(user, tenantId,
                scopeType == null ? "TENANT" : scopeType,
                scopeId == null ? tenantId : scopeId);
        MemoryStats stats = memoryManager.getStats(
                scope.tenantId(), scope.scopeType().name(), scope.scopeId());
        return ApiResponse.ok(stats);
    }

    private void bindScopedRecord(SemanticFact fact, AuthenticatedUser user) {
        if (fact == null) {
            throw new IllegalArgumentException("fact required");
        }
        fact.setTenantId(user.tenantKey());
        if (fact.getScope() == null) {
            fact.setScope(MemoryScope.ofUser(user.tenantKey(), String.valueOf(user.userId())));
        }
        requireRequestedScope(user, fact.getScope());
    }

    private void bindScopedRecord(Procedure procedure, AuthenticatedUser user) {
        if (procedure == null) {
            throw new IllegalArgumentException("procedure required");
        }
        procedure.setTenantId(user.tenantKey());
        if (procedure.getScope() == null) {
            procedure.setScope(MemoryScope.ofUser(user.tenantKey(), String.valueOf(user.userId())));
        }
        requireRequestedScope(user, procedure.getScope());
        if (procedure.getScope().scopeType() == MemoryScope.ScopeType.PROJECT) {
            projectAuthorization.requireMember(procedure.getScope().scopeId(), user.userId(), user.tenantKey());
        }
    }

    private MemoryScope requestedScope(AuthenticatedUser user, String tenantId,
                                       String scopeType, String scopeId) {
        requireTenant(user, tenantId);
        final MemoryScope.ScopeType type;
        try {
            type = MemoryScope.ScopeType.valueOf(scopeType.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid memory scope type");
        }
        MemoryScope scope = new MemoryScope(user.tenantKey(), type, scopeId);
        requireRequestedScope(user, scope);
        if (type == MemoryScope.ScopeType.PROJECT) {
            projectAuthorization.requireMember(scopeId, user.userId(), user.tenantKey());
        }
        return scope;
    }

    private void requireTenant(AuthenticatedUser user, String tenantId) {
        if (!Objects.equals(user.tenantKey(), tenantId)) {
            throw new AccessDeniedException("Tenant is not accessible");
        }
    }

    private AuthenticatedUser requireUser(HttpServletRequest request) {
        Object principal = request == null ? null
                : request.getAttribute(JwtSessionService.ATTR_PRINCIPAL);
        if (principal instanceof AuthenticatedUser user) {
            return user;
        }
        throw new AccessDeniedException("Authentication required");
    }

    private void bindEventToPrincipal(AgentEvent event, AuthenticatedUser user) {
        if (event == null || event.getSessionId() == null || event.getSessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId required");
        }
        sessionAuthorization.requireOwner(user.userId(), event.getSessionId());
        event.setTenantId(user.tenantKey());
        event.setActor("user");
        Map<String, Object> safe = new LinkedHashMap<>();
        if (event.getPayload() != null) {
            event.getPayload().forEach((key, value) -> {
                if (!Objects.equals(key, "scopeType")
                        && !Objects.equals(key, "scopeId")
                        && !Objects.equals(key, "projectId")
                        && !Objects.equals(key, "tenantId")
                        && !Objects.equals(key, "userId")) {
                    safe.put(key, value);
                }
            });
        }
        safe.put("userId", String.valueOf(user.userId()));
        event.setPayload(safe);
    }

    private void bindMemoryToPrincipal(MemoryEntry entry, AuthenticatedUser user) {
        if (entry == null) {
            throw new IllegalArgumentException("memory required");
        }
        entry.setTenantId(user.tenantKey());
        if (entry.getScope() == null) {
            entry.setScope(MemoryScope.ofUser(user.tenantKey(), String.valueOf(user.userId())));
        }
        requireRequestedScope(user, entry.getScope());
    }

    private void bindQueryToPrincipal(MemoryQuery query, AuthenticatedUser user) {
        if (query == null || query.getScope() == null) {
            throw new IllegalArgumentException("scope required");
        }
        MemoryScope requested = query.getScope();
        if (!Objects.equals(user.tenantKey(), requested.tenantId())) {
            throw new AccessDeniedException("Tenant is not accessible");
        }
        requireRequestedScope(user, requested);
    }

    private void bindContextToPrincipal(AgentContext ctx, AuthenticatedUser user) {
        if (ctx == null) {
            throw new IllegalArgumentException("context required");
        }
        ctx.setTenantId(user.tenantKey());
        ctx.setUserId(String.valueOf(user.userId()));
        if (ctx.getSessionId() != null && !ctx.getSessionId().isBlank()) {
            sessionAuthorization.requireOwner(user.userId(), ctx.getSessionId());
        }
        if (ctx.getProjectId() != null) {
            projectAuthorization.requireMember(ctx.getProjectId(), user.userId(), user.tenantKey());
        }
    }

    private void requireMemoryAccess(AuthenticatedUser user, MemoryEntry memory) {
        if (memory == null || !Objects.equals(user.tenantKey(), memory.getTenantId())) {
            throw new AccessDeniedException("Memory is not accessible");
        }
        requireRequestedScope(user, memory.getScope());
    }

    private void requireRequestedScope(AuthenticatedUser user, MemoryScope scope) {
        if (scope == null || !Objects.equals(user.tenantKey(), scope.tenantId())) {
            throw new AccessDeniedException("Memory scope is not accessible");
        }
        if (scope.scopeType() == MemoryScope.ScopeType.PROJECT) {
            projectAuthorization.requireMember(scope.scopeId(), user.userId(), user.tenantKey());
            return;
        }
        if (user.role() != UserRole.USER) {
            return;
        }
        if (scope.scopeType() == MemoryScope.ScopeType.USER
                && Objects.equals(scope.scopeId(), String.valueOf(user.userId()))) {
            return;
        }
        if (scope.scopeType() == MemoryScope.ScopeType.SESSION) {
            sessionAuthorization.requireOwner(user.userId(), scope.scopeId());
            return;
        }
        throw new AccessDeniedException("Memory scope is not accessible");
    }
}
