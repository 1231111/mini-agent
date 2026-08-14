package com.miniagent.config.repository;

import com.miniagent.config.entity.IntentRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntentRuleRepository extends JpaRepository<IntentRule, Long> {
    List<IntentRule> findByRuleSetIdAndEnabledTrueOrderByPriorityAscIdAsc(Long ruleSetId);
    List<IntentRule> findByRuleSetIdOrderByPriorityAscIdAsc(Long ruleSetId);
}
