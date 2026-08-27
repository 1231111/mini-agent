package com.miniagent.config.repository;

import com.miniagent.config.entity.IntentRuleFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntentRuleFeedbackRepository extends JpaRepository<IntentRuleFeedback, Long> {
    List<IntentRuleFeedback> findByStatusOrderByIdDesc(String status);
    List<IntentRuleFeedback> findTop50ByOrderByIdDesc();
}
