package com.miniagent.agent.memory.controller;

import com.miniagent.agent.memory.entity.AgentProjectEntity;
import com.miniagent.agent.memory.service.AgentProjectAuthorizationService;
import com.miniagent.common.ApiResponse;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.JwtSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/projects")
public class ProjectController {
    @Autowired private AgentProjectAuthorizationService projects;
    @Autowired private com.miniagent.application.ConversationProjectService conversationProjects;

    public record CreateProjectRequest(String projectId, String name) {}
    public record AddMemberRequest(Long userId) {}
    public record BindProjectRequest(String projectId) {}

    @PostMapping
    public ApiResponse<AgentProjectEntity> create(@RequestBody CreateProjectRequest request,
                                                   HttpServletRequest servletRequest) {
        AuthenticatedUser user = requireUser(servletRequest);
        try (var ignored = com.miniagent.common.context.OwnerContext.bind(
                new com.miniagent.common.context.OwnerContext(user.userId(), user.tenantKey()))) {
            return ApiResponse.ok(projects.create(request.projectId(), request.name()));
        }
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    public ApiResponse<Void> removeMember(@PathVariable String projectId, @PathVariable Long userId,
                                          HttpServletRequest servletRequest) {
        AuthenticatedUser user = requireUser(servletRequest);
        try (var ignored = com.miniagent.common.context.OwnerContext.bind(
                new com.miniagent.common.context.OwnerContext(user.userId(), user.tenantKey()))) {
            projects.removeMember(projectId, userId);
            return ApiResponse.ok(null);
        }
    }

    @PostMapping("/{projectId}/members")
    public ApiResponse<Void> addMember(@PathVariable String projectId, @RequestBody AddMemberRequest request,
                                       HttpServletRequest servletRequest) {
        AuthenticatedUser user = requireUser(servletRequest);
        try (var ignored = com.miniagent.common.context.OwnerContext.bind(
                new com.miniagent.common.context.OwnerContext(user.userId(), user.tenantKey()))) {
            projects.addMember(projectId, request.userId());
            return ApiResponse.ok(null);
        }
    }

    @DeleteMapping("/sessions/{sessionId}/binding")
    public ApiResponse<Void> unbindSession(@PathVariable String sessionId,
                                           HttpServletRequest servletRequest) {
        AuthenticatedUser user = requireUser(servletRequest);
        try (var ignored = com.miniagent.common.context.OwnerContext.bind(
                new com.miniagent.common.context.OwnerContext(user.userId(), user.tenantKey()))) {
            conversationProjects.unbind(user.userId(), sessionId);
            return ApiResponse.ok(null);
        }
    }

    @PostMapping("/sessions/{sessionId}/binding")
    public ApiResponse<Void> bindSession(@PathVariable String sessionId, @RequestBody BindProjectRequest request,
                                         HttpServletRequest servletRequest) {
        AuthenticatedUser user = requireUser(servletRequest);
        try (var ignored = com.miniagent.common.context.OwnerContext.bind(
                new com.miniagent.common.context.OwnerContext(user.userId(), user.tenantKey()))) {
            conversationProjects.bind(user.userId(), sessionId, request.projectId());
            return ApiResponse.ok(null);
        }
    }

    private AuthenticatedUser requireUser(HttpServletRequest request) {
        Object principal = request == null ? null : request.getAttribute(JwtSessionService.ATTR_PRINCIPAL);
        if (principal instanceof AuthenticatedUser user) return user;
        throw new AccessDeniedException("Authentication required");
    }
}
