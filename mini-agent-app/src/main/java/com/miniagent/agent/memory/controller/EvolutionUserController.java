package com.miniagent.agent.memory.controller;

import com.miniagent.agent.memory.config.ConditionalOnCloudMemory;
import com.miniagent.agent.memory.manager.CloudMemoryClient;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.memory.contract.MemoryApi.ChangeView;
import com.miniagent.memory.contract.MemoryApi.FeedbackRequest;
import com.miniagent.memory.contract.MemoryApi.ItemView;
import com.miniagent.memory.contract.MemoryApi.ProposalView;
import com.miniagent.memory.contract.MemoryApi.ReviewRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** 云端升级的审批、回退、锁定、变更列表、任务反馈。没配云端地址时不注册。 */
@RestController
@RequestMapping("/v1/memory/evolution")
@ConditionalOnCloudMemory
public class EvolutionUserController {
    private static final int DEFAULT_LIMIT = 50;

    private final CloudMemoryClient cloud;

    public EvolutionUserController(CloudMemoryClient cloud) {
        this.cloud = cloud;
    }

    @GetMapping("/proposals")
    public ApiResponse<List<ProposalView>> proposals(
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        return withUser(request, () -> cloud.proposals(state, clamp(limit)));
    }

    @PostMapping("/proposals/{id}:approve")
    public ApiResponse<ProposalView> approve(@PathVariable long id,
                                             @RequestBody(required = false) ReviewRequest body,
                                             HttpServletRequest request) {
        return withUser(request, () -> cloud.approve(id, body == null ? null : body.note()));
    }

    @PostMapping("/proposals/{id}:reject")
    public ApiResponse<ProposalView> reject(@PathVariable long id,
                                            @RequestBody(required = false) ReviewRequest body,
                                            HttpServletRequest request) {
        return withUser(request, () -> cloud.reject(id, body == null ? null : body.note()));
    }

    @GetMapping("/changes")
    public ApiResponse<List<ChangeView>> changes(@RequestParam Instant since,
                                                 HttpServletRequest request) {
        return withUser(request, () -> cloud.changes(since));
    }

    @PostMapping("/items/{id}:revert")
    public ApiResponse<ItemView> revert(@PathVariable long id, @RequestParam int revisionNo,
                                        HttpServletRequest request) {
        return withUser(request, () -> cloud.revert(id, revisionNo));
    }

    @PostMapping("/items/{id}:lock")
    public ApiResponse<ItemView> lock(@PathVariable long id, @RequestParam boolean locked,
                                      HttpServletRequest request) {
        return withUser(request, () -> cloud.lock(id, locked));
    }

    @PostMapping("/feedback")
    public ApiResponse<Integer> feedback(@RequestBody FeedbackRequest body,
                                         HttpServletRequest request) {
        if (body == null || body.taskKey() == null || body.taskKey().isBlank()) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "taskKey 不能为空");
        }
        return withUser(request, () -> cloud.feedback(body.taskKey(), body.positive()));
    }

    private <T> ApiResponse<T> withUser(HttpServletRequest request, SupplierThrowing<T> action) {
        AuthenticatedUser user = requireUser(request);
        try (OwnerContext.Scope ignored = OwnerContext.bind(
                new OwnerContext(user.userId(), user.tenantKey()))) {
            return ApiResponse.ok(action.get());
        }
    }

    private static AuthenticatedUser requireUser(HttpServletRequest request) {
        Object principal = request == null ? null
                : request.getAttribute(JwtSessionService.ATTR_PRINCIPAL);
        if (principal instanceof AuthenticatedUser user) {
            return user;
        }
        throw new AccessDeniedException("Authentication required");
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(DEFAULT_LIMIT, limit));
    }

    @FunctionalInterface
    private interface SupplierThrowing<T> {
        T get();
    }
}
