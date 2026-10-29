package com.miniagent.agent.memory.controller;

import com.miniagent.agent.memory.entity.ExperienceClusterEntity;
import com.miniagent.agent.memory.manager.ExperienceClusterService;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.JwtSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/memory/experience-clusters")
public class ExperienceClusterController {
    private final ExperienceClusterService clusters;

    public ExperienceClusterController(ExperienceClusterService clusters) { this.clusters = clusters; }

    public record CreateRequest(String key, String label, String scopeType, String scopeId) {}
    public record AddEpisodeRequest(Long episodeId, int expectedVersion) {}
    public record RenameRequest(String label, int expectedVersion) {}
    public record MergeRequest(Long targetId, int sourceVersion, int targetVersion) {}
    public record SplitRequest(Long targetId, List<Long> episodeIds, int sourceVersion, int targetVersion) {}

    @PostMapping
    public ApiResponse<ExperienceClusterEntity> create(@RequestBody CreateRequest body,
                                                        HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        try (var ignored = OwnerContext.bind(new OwnerContext(user.userId(), user.tenantKey()))) {
            return ApiResponse.ok(clusters.create(body.key(), body.label(), body.scopeType(), body.scopeId()));
        }
    }

    @PostMapping("/{id}/episodes")
    public ApiResponse<Boolean> addEpisode(@PathVariable Long id, @RequestBody AddEpisodeRequest body,
                                           HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        try (var ignored = OwnerContext.bind(new OwnerContext(user.userId(), user.tenantKey()))) {
            return ApiResponse.ok(clusters.addEpisode(id, body.episodeId(), body.expectedVersion()));
        }
    }

    @PatchMapping("/{id}")
    public ApiResponse<Boolean> rename(@PathVariable Long id, @RequestBody RenameRequest body,
                                       HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        try (var ignored = OwnerContext.bind(new OwnerContext(user.userId(), user.tenantKey()))) {
            return ApiResponse.ok(clusters.rename(id, body.label(), body.expectedVersion()));
        }
    }

    @PostMapping("/{id}/merge")
    public ApiResponse<Boolean> merge(@PathVariable Long id, @RequestBody MergeRequest body,
                                      HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        try (var ignored = OwnerContext.bind(new OwnerContext(user.userId(), user.tenantKey()))) {
            return ApiResponse.ok(clusters.merge(id, body.targetId(), body.sourceVersion(), body.targetVersion()));
        }
    }

    @PostMapping("/{id}/split")
    public ApiResponse<Boolean> split(@PathVariable Long id, @RequestBody SplitRequest body,
                                      HttpServletRequest request) {
        AuthenticatedUser user = requireUser(request);
        try (var ignored = OwnerContext.bind(new OwnerContext(user.userId(), user.tenantKey()))) {
            return ApiResponse.ok(clusters.split(id, body.targetId(), body.episodeIds(),
                    body.sourceVersion(), body.targetVersion()));
        }
    }

    private AuthenticatedUser requireUser(HttpServletRequest request) {
        Object principal = request == null ? null : request.getAttribute(JwtSessionService.ATTR_PRINCIPAL);
        if (principal instanceof AuthenticatedUser user) return user;
        throw new AccessDeniedException("Authentication required");
    }
}
