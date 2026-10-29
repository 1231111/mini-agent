package com.miniagent.memory.service.persistence;

import com.miniagent.memory.service.web.ProjectAuthorizationRequest;
import com.miniagent.memory.service.web.ProjectAuthorizationResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 按版本 CAS 投影项目成员授权快照。 */
@Service
@Profile("memory-service")
public class ProjectAuthorizationSynchronizer {
    private final JdbcTemplate jdbc;

    public ProjectAuthorizationSynchronizer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ProjectAuthorizationResponse synchronize(ProjectAuthorizationRequest request) {
        List<Current> rows = jdbc.query(
                "SELECT owner_user_id, authorization_version, status FROM memory_project_authorizations "
                        + "WHERE tenant_id=? AND project_id=?",
                (rs, n) -> new Current(rs.getLong("owner_user_id"),
                        rs.getLong("authorization_version"), rs.getString("status")),
                request.tenantId(), request.projectId());
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO memory_project_authorizations "
                            + "(tenant_id,project_id,owner_user_id,authorization_version,status,updated_at) "
                            + "VALUES (?,?,?,?, 'ACTIVE', CURRENT_TIMESTAMP)",
                    request.tenantId(), request.projectId(), request.ownerUserId(), request.version());
            replaceMembers(request);
            return new ProjectAuthorizationResponse(ProjectAuthorizationResponse.Status.CREATED, request.version());
        }

        Current current = rows.getFirst();
        if (request.version() < current.version()) {
            return new ProjectAuthorizationResponse(ProjectAuthorizationResponse.Status.STALE, current.version());
        }
        if (request.version() == current.version()) {
            if (sameSnapshot(request, current)) {
                return new ProjectAuthorizationResponse(ProjectAuthorizationResponse.Status.UNCHANGED, current.version());
            }
            return new ProjectAuthorizationResponse(ProjectAuthorizationResponse.Status.CONFLICT, current.version());
        }
        int updated = jdbc.update("UPDATE memory_project_authorizations SET owner_user_id=?, "
                        + "authorization_version=?, status='ACTIVE', updated_at=CURRENT_TIMESTAMP "
                        + "WHERE tenant_id=? AND project_id=? AND authorization_version=?",
                request.ownerUserId(), request.version(), request.tenantId(), request.projectId(), current.version());
        if (updated != 1) {
            return new ProjectAuthorizationResponse(ProjectAuthorizationResponse.Status.CONFLICT, current.version());
        }
        replaceMembers(request);
        return new ProjectAuthorizationResponse(ProjectAuthorizationResponse.Status.UPDATED, request.version());
    }

    private boolean sameSnapshot(ProjectAuthorizationRequest request, Current current) {
        if (current.ownerUserId() != request.ownerUserId() || !"ACTIVE".equalsIgnoreCase(current.status())) {
            return false;
        }
        List<Long> actualMembers = jdbc.query(
                "SELECT user_id FROM memory_project_members WHERE tenant_id=? AND project_id=? ORDER BY user_id",
                (rs, rowNum) -> rs.getLong("user_id"), request.tenantId(), request.projectId());
        List<Long> requestedMembers = request.memberUserIds() == null ? List.of()
                : request.memberUserIds().stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        return actualMembers.equals(requestedMembers);
    }

    private void replaceMembers(ProjectAuthorizationRequest request) {
        jdbc.update("DELETE FROM memory_project_members WHERE tenant_id=? AND project_id=?",
                request.tenantId(), request.projectId());
        if (request.memberUserIds() == null) return;
        for (Long memberUserId : request.memberUserIds().stream().distinct().toList()) {
            jdbc.update("INSERT INTO memory_project_members "
                            + "(tenant_id,project_id,user_id,authorization_version) VALUES (?,?,?,?)",
                    request.tenantId(), request.projectId(), memberUserId, request.version());
        }
    }

    private record Current(long ownerUserId, long version, String status) {
    }
}
