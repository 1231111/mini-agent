package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.agent.memory.repository.AgentSemanticFactRepository;
import com.miniagent.memory.model.AgentContext;
import com.miniagent.memory.model.MemoryContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 锁定「系统上下文里的可用方法」与「规划器看到的 Procedure 候选」是同一个作用域。
 *
 * <p>这两条路径曾经分裂：{@code ProcedurePromptPort.findForCurrentOwner} 按 USER scope 取，
 * 而 {@code buildContext} 只查 PROJECT。对话执行没有 projectId，于是同一份 SOP
 * 在规划器里可见、在系统提示里不可见 —— 排查「为什么模型不用这条 SOP」时会被引到错方向。</p>
 */
class ProcedureContextInjectionTest {

    private final AgentProcedureRepository procedures = mock(AgentProcedureRepository.class);
    private final AgentSemanticFactRepository facts = mock(AgentSemanticFactRepository.class);
    private final DefaultMemoryManager manager = new DefaultMemoryManager();

    ProcedureContextInjectionTest() {
        ReflectionTestUtils.setField(manager, "procedureRepository", procedures);
        ReflectionTestUtils.setField(manager, "factRepository", facts);
        when(facts.findActiveFacts(anyString(), any(), anyString())).thenReturn(List.of());
    }

    @Test
    void userScopedProcedureIsInjectedWhenNoProjectIsBound() {
        when(procedures.findByTenantIdAndScopeTypeAndScopeIdAndStatus(
                anyString(), any(), anyString(), any()))
                .thenReturn(List.of(procedure("发布检查清单", "先跑测试再打 tag")));

        MemoryContext context = manager.buildContext(context(null, "9"));

        assertEquals(1, context.getSkills().size());
        assertTrue(context.getSkills().get(0).contains("发布检查清单"),
                "USER scope 的 SOP 必须出现在可用方法里，否则规划和提示看到的是两份不同的记忆");
        verify(procedures).findByTenantIdAndScopeTypeAndScopeIdAndStatus(
                "tenant-a", AgentMemoryEntryEntity.ScopeType.USER, "9",
                AgentMemoryEntryEntity.Status.ACTIVE);
    }

    @Test
    void projectContextTakesPrecedenceOverUserScope() {
        when(procedures.findByTenantIdAndScopeTypeAndScopeIdAndStatus(
                anyString(), any(), anyString(), any()))
                .thenReturn(List.of());

        manager.buildContext(context("project-7", "9"));

        verify(procedures).findByTenantIdAndScopeTypeAndScopeIdAndStatus(
                "tenant-a", AgentMemoryEntryEntity.ScopeType.PROJECT, "project-7",
                AgentMemoryEntryEntity.Status.ACTIVE);
    }

    private AgentContext context(String projectId, String userId) {
        AgentContext ctx = new AgentContext();
        ctx.setTenantId("tenant-a");
        ctx.setUserId(userId);
        ctx.setProjectId(projectId);
        // 空目标：跳过检索分支，本用例只关心 SOP 作用域。
        ctx.setGoal("");
        return ctx;
    }

    private AgentProcedureEntity procedure(String name, String description) {
        AgentProcedureEntity entity = new AgentProcedureEntity();
        entity.setId(1L);
        entity.setTenantId("tenant-a");
        entity.setScopeType(AgentMemoryEntryEntity.ScopeType.USER);
        entity.setScopeId("9");
        entity.setName(name);
        entity.setDescription(description);
        entity.setStepsJson("[]");
        return entity;
    }
}
