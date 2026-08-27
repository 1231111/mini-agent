package com.miniagent.config.repository;

import com.miniagent.config.entity.IntentToolProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntentToolProfileRepository extends JpaRepository<IntentToolProfile, Long> {
    List<IntentToolProfile> findByRuleSetId(Long ruleSetId);
}
