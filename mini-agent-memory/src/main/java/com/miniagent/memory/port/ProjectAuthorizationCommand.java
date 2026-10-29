package com.miniagent.memory.port;

import java.util.List;
import java.util.Objects;

/** 独立记忆服务使用的项目授权快照。成员集合来自执行侧已认证的授权表。 */
public record ProjectAuthorizationCommand(
        String tenantId,
        String projectId,
        long ownerUserId,
        List<Long> memberUserIds,
        long version
) {
    public ProjectAuthorizationCommand {
        tenantId = requireText(tenantId, "tenantId");
        projectId = requireText(projectId, "projectId");
        if (ownerUserId <= 0) throw new IllegalArgumentException("ownerUserId must be positive");
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        memberUserIds = memberUserIds == null ? List.of() : memberUserIds.stream()
                .filter(Objects::nonNull).filter(id -> id > 0).distinct().sorted().toList();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
