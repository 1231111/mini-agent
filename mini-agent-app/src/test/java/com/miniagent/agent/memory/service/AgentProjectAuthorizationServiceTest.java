package com.miniagent.agent.memory.service;

import com.miniagent.agent.memory.entity.AgentProjectEntity;
import com.miniagent.agent.memory.entity.AgentProjectMemberEntity;
import com.miniagent.agent.memory.repository.AgentProjectMemberRepository;
import com.miniagent.agent.memory.repository.AgentProjectRepository;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentProjectAuthorizationServiceTest {
    private final AgentProjectRepository projects = mock(AgentProjectRepository.class);
    private final AgentProjectMemberRepository members = mock(AgentProjectMemberRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final com.miniagent.agent.memory.manager.ProjectAuthorizationSyncService authorizationSync =
            mock(com.miniagent.agent.memory.manager.ProjectAuthorizationSyncService.class);
    private final AgentProjectAuthorizationService service =
            new AgentProjectAuthorizationService(projects, members, users, authorizationSync);

    @AfterEach
    void clearOwner() { OwnerContext.clearCurrentTenant(); OwnerContext.clearCurrentUser(); }

    @Test
    void addMemberRequiresActiveSameTenantUserAndProjectOwner() {
        AgentProjectEntity project = project();
        when(projects.findForUpdate("project-a", "21")).thenReturn(Optional.of(project));
        when(projects.findByProjectIdAndTenantId("project-a", "21")).thenReturn(Optional.of(project));
        User target = new User(); target.setId(10L); target.setTenantId(21L); target.setEnabled(true);
        when(users.findById(10L)).thenReturn(Optional.of(target));
        when(members.findByProjectIdAndUserId("project-a", 10L)).thenReturn(Optional.empty());
        when(members.findAllByProjectIdOrderByUserIdAsc("project-a")).thenReturn(java.util.List.of());
        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "21"))) {
            service.addMember("project-a", 10L);
        }
        verify(members).saveAndFlush(argThat(member -> member.getProjectId().equals("project-a")
                && member.getUserId().equals(10L)));
        verify(authorizationSync).enqueue(eq("21"), eq("project-a"), eq(9L), eq(2L), anyList());

        target.setTenantId(22L);
        assertThrows(AccessDeniedException.class, () -> {
            try (var ignored = OwnerContext.bind(new OwnerContext(9L, "21"))) {
                service.addMember("project-a", 10L);
            }
        });
    }

    @Test
    void rejectsMissingOrDisabledMember() {
        when(projects.findByProjectIdAndTenantId("project-a", "21")).thenReturn(Optional.of(project()));
        when(users.findById(10L)).thenReturn(Optional.empty());
        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "21"))) {
            assertThrows(AccessDeniedException.class, () -> service.addMember("project-a", 10L));
        }
        User disabled = new User(); disabled.setId(10L); disabled.setTenantId(21L); disabled.setEnabled(false);
        when(users.findById(10L)).thenReturn(Optional.of(disabled));
        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "21"))) {
            assertThrows(AccessDeniedException.class, () -> service.addMember("project-a", 10L));
        }
        verify(members, never()).saveAndFlush(any(AgentProjectMemberEntity.class));
    }

    private AgentProjectEntity project() {
        AgentProjectEntity entity = new AgentProjectEntity();
        entity.setProjectId("project-a"); entity.setTenantId("21");
        entity.setOwnerUserId(9L); entity.setProjectName("Project A");
        return entity;
    }
}
