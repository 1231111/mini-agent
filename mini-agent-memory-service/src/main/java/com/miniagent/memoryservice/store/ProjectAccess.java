package com.miniagent.memoryservice.store;

import com.miniagent.memory.port.ProjectAuthorizationCommand;
import com.miniagent.memory.port.ProjectAuthorizationResult;
import com.miniagent.memory.port.ProjectAuthorizationResult.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 项目授权快照（V2 表）：App 按版本推送 owner + 成员，记忆服务据此判断 PROJECT 域可见性。
 * 版本只增不减；同版本内容不同视为冲突。
 */
@Repository
public class ProjectAccess {
    private static final String ACTIVE = "ACTIVE";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProjectAccess(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean canRead(String tenantId, String projectId, long userId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        Integer hits = jdbc.queryForObject("SELECT COUNT(*) FROM memory_project_authorizations a"
                        + " WHERE a.tenant_id = ? AND a.project_id = ? AND a.status = ?"
                        + " AND (a.owner_user_id = ? OR EXISTS (SELECT 1 FROM memory_project_members m"
                        + " WHERE m.tenant_id = a.tenant_id AND m.project_id = a.project_id"
                        + " AND m.user_id = ?))",
                Integer.class, tenantId, projectId, ACTIVE, userId, userId);
        return hits != null && hits > 0;
    }

    public List<String> projectsOf(String tenantId, long userId) {
        return jdbc.queryForList("SELECT a.project_id FROM memory_project_authorizations a"
                        + " WHERE a.tenant_id = ? AND a.status = ? AND (a.owner_user_id = ?"
                        + " OR EXISTS (SELECT 1 FROM memory_project_members m"
                        + " WHERE m.tenant_id = a.tenant_id AND m.project_id = a.project_id"
                        + " AND m.user_id = ?))",
                String.class, tenantId, ACTIVE, userId, userId);
    }

    @Transactional
    public ProjectAuthorizationResult synchronize(ProjectAuthorizationCommand command) {
        Instant now = clock.instant();
        List<long[]> rows = jdbc.query("SELECT owner_user_id, authorization_version"
                        + " FROM memory_project_authorizations WHERE tenant_id = ? AND project_id = ?"
                        + " FOR UPDATE",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)},
                command.tenantId(), command.projectId());
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO memory_project_authorizations (tenant_id, project_id,"
                            + " owner_user_id, authorization_version, status, updated_at)"
                            + " VALUES (?,?,?,?,?,?)", command.tenantId(), command.projectId(),
                    command.ownerUserId(), command.version(), ACTIVE, Db.ts(now));
            replaceMembers(command);
            return new ProjectAuthorizationResult(Status.CREATED, command.version());
        }
        long currentOwner = rows.getFirst()[0];
        long currentVersion = rows.getFirst()[1];
        if (command.version() < currentVersion) {
            return new ProjectAuthorizationResult(Status.STALE, currentVersion);
        }
        if (command.version() == currentVersion) {
            boolean same = currentOwner == command.ownerUserId()
                    && members(command).equals(command.memberUserIds());
            return new ProjectAuthorizationResult(same ? Status.UNCHANGED : Status.CONFLICT,
                    currentVersion);
        }
        jdbc.update("UPDATE memory_project_authorizations SET owner_user_id = ?,"
                        + " authorization_version = ?, status = ?, updated_at = ?"
                        + " WHERE tenant_id = ? AND project_id = ?", command.ownerUserId(),
                command.version(), ACTIVE, Db.ts(now), command.tenantId(), command.projectId());
        replaceMembers(command);
        return new ProjectAuthorizationResult(Status.UPDATED, command.version());
    }

    private List<Long> members(ProjectAuthorizationCommand command) {
        return jdbc.queryForList("SELECT user_id FROM memory_project_members"
                        + " WHERE tenant_id = ? AND project_id = ? ORDER BY user_id", Long.class,
                command.tenantId(), command.projectId());
    }

    private void replaceMembers(ProjectAuthorizationCommand command) {
        jdbc.update("DELETE FROM memory_project_members WHERE tenant_id = ? AND project_id = ?",
                command.tenantId(), command.projectId());
        for (Long member : command.memberUserIds()) {
            jdbc.update("INSERT INTO memory_project_members (tenant_id, project_id, user_id,"
                            + " authorization_version) VALUES (?,?,?,?)", command.tenantId(),
                    command.projectId(), member, command.version());
        }
    }
}
