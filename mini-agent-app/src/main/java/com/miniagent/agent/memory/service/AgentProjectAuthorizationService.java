package com.miniagent.agent.memory.service;

import com.miniagent.agent.memory.entity.AgentProjectEntity;
import com.miniagent.agent.memory.repository.AgentProjectMemberRepository;
import com.miniagent.agent.memory.repository.AgentProjectRepository;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentProjectAuthorizationService {
    private final AgentProjectRepository projects;
    private final AgentProjectMemberRepository members;
    private final UserRepository users;

    public AgentProjectAuthorizationService(AgentProjectRepository projects,
                                            AgentProjectMemberRepository members,
                                            UserRepository users) {
        this.projects = projects;
        this.members = members;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public AgentProjectEntity requireMember(String projectId, Long userId, String tenantId) {
        if (projectId == null || projectId.isBlank() || userId == null || tenantId == null || tenantId.isBlank()) {
            throw new AccessDeniedException("Trusted project and owner are required");
        }
        AgentProjectEntity project = projects.findByProjectIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> new AccessDeniedException("Project is not accessible"));
        if (!project.getOwnerUserId().equals(userId)
                && members.findByProjectIdAndUserId(projectId, userId).isEmpty()) {
            throw new AccessDeniedException("Project is not accessible");
        }
        return project;
    }

    @Transactional
    public AgentProjectEntity create(String projectId, String name) {
        OwnerContext owner = OwnerContext.capture();
        if (owner.userId() == null || owner.tenantId() == null || projectId == null || projectId.isBlank()
                || name == null || name.isBlank()) throw new IllegalArgumentException("project fields required");
        AgentProjectEntity entity = new AgentProjectEntity();
        entity.setProjectId(projectId.trim()); entity.setTenantId(owner.tenantId());
        entity.setOwnerUserId(owner.userId()); entity.setProjectName(name.trim());
        return projects.save(entity);
    }

    @Transactional
    public void removeMember(String projectId, Long userId) {
        OwnerContext owner = OwnerContext.capture();
        AgentProjectEntity project = projects.findByProjectIdAndTenantId(projectId, owner.tenantId())
                .orElseThrow(() -> new AccessDeniedException("Project is not accessible"));
        if (!project.getOwnerUserId().equals(owner.userId()))
            throw new AccessDeniedException("Only project owner can manage membership");
        if (project.getOwnerUserId().equals(userId))
            throw new IllegalArgumentException("Project owner cannot be removed");
        members.deleteByProjectIdAndUserId(projectId, userId);
    }

    @Transactional
    public void addMember(String projectId, Long userId) {
        OwnerContext owner = OwnerContext.capture();
        if (userId == null) throw new IllegalArgumentException("member userId required");
        AgentProjectEntity project = projects.findByProjectIdAndTenantId(projectId, owner.tenantId())
                .orElseThrow(() -> new AccessDeniedException("Project is not accessible"));
        if (!project.getOwnerUserId().equals(owner.userId())) {
            throw new AccessDeniedException("Only project owner can manage membership");
        }
        if (project.getOwnerUserId().equals(userId)) return;
        User target = users.findById(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new AccessDeniedException("Member user is not active"));
        if (!Long.valueOf(owner.tenantId()).equals(target.getTenantId()))
            throw new AccessDeniedException("Member belongs to another tenant");
        if (members.findByProjectIdAndUserId(projectId, userId).isPresent()) return;
        var member = new com.miniagent.agent.memory.entity.AgentProjectMemberEntity();
        member.setProjectId(projectId); member.setUserId(userId); member.setRole("MEMBER");
        members.saveAndFlush(member);
    }
}
