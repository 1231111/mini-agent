package com.miniagent.web;

import com.miniagent.application.AgentChatApplicationService;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.security.SessionAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Human controls address an existing execution; their body cannot introduce a new goal. */
@RestController
public class TaskContinuationController {
    public record Confirm(@NotBlank String executionId, @NotNull @Min(0) Long version) { }
    private final AgentChatApplicationService chats;
    private final SessionAuthorizationService authorization;

    public TaskContinuationController(AgentChatApplicationService chats, SessionAuthorizationService authorization) {
        this.chats = chats; this.authorization = authorization;
    }

    @PostMapping(value = "/api/conversations/{sessionId}/todos/{id}/continue", consumes = "application/json")
    public SseEmitter confirm(@PathVariable String sessionId, @PathVariable int id,
                              @Valid @RequestBody Confirm body, HttpServletRequest request) {
        Object value = request.getAttribute(JwtSessionService.ATTR_PRINCIPAL);
        if (!(value instanceof AuthenticatedUser user))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录。");
        authorization.requireOwner(user.userId(), sessionId);
        return chats.confirmAndContinue(user.userId(), sessionId, id, body.executionId(), body.version());
    }
}
