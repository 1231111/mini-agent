package com.miniagent.config.repository;

import com.miniagent.config.entity.AgentTokenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentTokenUsageRepository extends JpaRepository<AgentTokenUsage, String> {
    @Modifying
    @Query(value = "INSERT INTO agent_token_usage "
            + "(session_id,input_tokens,output_tokens,tool_calls,llm_calls,version,created_at,updated_at) "
            + "VALUES (:sessionId,:input,:output,:tools,:llms,0,NOW(6),NOW(6)) "
            + "ON DUPLICATE KEY UPDATE input_tokens=input_tokens+:input, "
            + "output_tokens=output_tokens+:output, tool_calls=tool_calls+:tools, "
            + "llm_calls=llm_calls+:llms, version=version+1, updated_at=NOW(6)", nativeQuery = true)
    void increment(@Param("sessionId") String sessionId,
                   @Param("input") long input,
                   @Param("output") long output,
                   @Param("tools") int tools,
                   @Param("llms") int llms);

    @Query("select u from AgentTokenUsage u where u.sessionId in "
            + "(select c.id from ChatConversation c where c.userId = :userId)")
    List<AgentTokenUsage> findAllForUser(@Param("userId") Long userId);
}
