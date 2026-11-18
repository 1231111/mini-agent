package com.miniagent.agent.memory.controller;

import com.miniagent.agent.memory.manager.ProcedureRevisionService;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.service.SystemAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.NoSuchElementException;

/** Owner-only rollback of a user procedure to a captured content revision. */
@RestController
@RequestMapping("/v1/memory/procedures")
public class ProcedureRevisionController {
    private final ProcedureRevisionService revisions;
    private final SystemAdminService audit;

    public ProcedureRevisionController(ProcedureRevisionService revisions, SystemAdminService audit) {
        this.revisions = revisions;
        this.audit = audit;
    }

    public record RollbackReceipt(boolean applied, int fromVersion, int toVersion,
                                  int restoredRevision) { }

    @PostMapping("/{procedureId}/revisions/{targetVersion}:rollback")
    @Transactional
    public ApiResponse<RollbackReceipt> rollback(@PathVariable Long procedureId,
                                                  @PathVariable Integer targetVersion,
                                                  HttpServletRequest request) {
        if (procedureId == null || procedureId <= 0 || targetVersion == null || targetVersion <= 0) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID,
                    "procedureId and targetVersion must be positive");
        }
        AuthenticatedUser user = requireUser(request);
        try (OwnerContext.Scope ignored = OwnerContext.bind(
                new OwnerContext(user.userId(), user.tenantKey()))) {
            ProcedureRevisionService.RollbackResult result;
            try {
                result = revisions.rollbackWithReceipt(procedureId, targetVersion);
            } catch (SecurityException denied) {
                throw new AccessDeniedException("Procedure inaccessible", denied);
            } catch (NoSuchElementException missing) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Procedure or revision not found", missing);
            }
            if (!result.applied()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Procedure changed while rollback was being applied; refresh and retry");
            }

            // The controller transaction joins the revision update and this append-only
            // audit write, so a successful rollback cannot commit without its receipt.
            audit.recordAudit(user.userId(), "PROCEDURE_REVISION_ROLLBACK", "AGENT_PROCEDURE",
                    procedureId, user.tenantId(), Map.of(
                            "fromVersion", result.fromVersion(),
                            "toVersion", result.toVersion(),
                            "restoredRevision", result.restoredRevision(),
                            "contentHash", result.restoredContentHash()));
            return ApiResponse.ok(new RollbackReceipt(true, result.fromVersion(), result.toVersion(),
                    result.restoredRevision()));
        }
    }

    private static AuthenticatedUser requireUser(HttpServletRequest request) {
        Object principal = request == null ? null
                : request.getAttribute(JwtSessionService.ATTR_PRINCIPAL);
        if (principal instanceof AuthenticatedUser user && user.userId() != null && user.tenantId() != null) {
            return user;
        }
        throw new AccessDeniedException("Authentication required");
    }
}
