package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentProjectMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AgentProjectMemberRepository extends JpaRepository<AgentProjectMemberEntity, AgentProjectMemberEntity.Key> {
    Optional<AgentProjectMemberEntity> findByProjectIdAndUserId(String projectId, Long userId);
    List<AgentProjectMemberEntity> findAllByProjectIdOrderByUserIdAsc(String projectId);
    void deleteByProjectIdAndUserId(String projectId, Long userId);
}
