package com.miniagent.config.repository;

import com.miniagent.config.entity.IntentRuleSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IntentRuleSetRepository extends JpaRepository<IntentRuleSet, Long> {
    Optional<IntentRuleSet> findFirstByStatusOrderByVersionDesc(String status);
    List<IntentRuleSet> findByStatusOrderByVersionDesc(String status);
    Optional<IntentRuleSet> findFirstByOrderByVersionDesc();
}
