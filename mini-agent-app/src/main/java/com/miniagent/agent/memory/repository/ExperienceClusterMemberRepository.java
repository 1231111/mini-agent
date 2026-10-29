package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.ExperienceClusterMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ExperienceClusterMemberRepository extends JpaRepository<ExperienceClusterMemberEntity, Long> {
    Optional<ExperienceClusterMemberEntity> findByTenantIdAndEpisodeId(String tenantId, Long episodeId);
    List<ExperienceClusterMemberEntity> findByTenantIdAndClusterId(String tenantId, Long clusterId);
    long deleteByTenantIdAndClusterIdAndEpisodeIdIn(String tenantId, Long clusterId, List<Long> episodeIds);
}
