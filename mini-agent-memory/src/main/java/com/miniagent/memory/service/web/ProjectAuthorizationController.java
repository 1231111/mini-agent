package com.miniagent.memory.service.web;

import com.miniagent.memory.service.persistence.ProjectAuthorizationSynchronizer;
import com.miniagent.memory.service.security.MemoryServiceSecurityProperties;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@Profile("memory-service")
@RequestMapping("/internal/v1/memory/projects")
public class ProjectAuthorizationController {
    private static final String INTERNAL_KEY_HEADER = "X-Memory-Internal-Key";

    private final ProjectAuthorizationSynchronizer synchronizer;
    private final MemoryServiceSecurityProperties security;

    public ProjectAuthorizationController(ProjectAuthorizationSynchronizer synchronizer,
                                          MemoryServiceSecurityProperties security) {
        this.synchronizer = synchronizer;
        this.security = security;
    }

    @PutMapping("/{projectId}/authorization")
    public ProjectAuthorizationResponse synchronize(
            @RequestHeader(value = INTERNAL_KEY_HEADER, required = false) String key,
            @PathVariable String projectId,
            @Valid @RequestBody ProjectAuthorizationRequest request) {
        requireKey(key);
        if (!projectId.equals(request.projectId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "project id mismatch");
        }
        return synchronizer.synchronize(request);
    }

    private void requireKey(String supplied) {
        if (!security.configured() || supplied == null
                || !MessageDigest.isEqual(security.internalKey().getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "memory service authentication required");
        }
    }
}
