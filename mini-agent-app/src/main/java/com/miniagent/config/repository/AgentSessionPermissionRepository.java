package com.miniagent.config.repository;

import com.miniagent.config.entity.AgentSessionPermission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentSessionPermissionRepository
        extends JpaRepository<AgentSessionPermission, String> {
}
