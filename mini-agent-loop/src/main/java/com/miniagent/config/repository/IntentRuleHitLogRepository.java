package com.miniagent.config.repository;

import com.miniagent.config.entity.IntentRuleHitLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntentRuleHitLogRepository extends JpaRepository<IntentRuleHitLog, Long> {
    List<IntentRuleHitLog> findByExecutionIdOrderByIdAsc(String executionId);
    List<IntentRuleHitLog> findTop50ByOrderByIdDesc();
}
