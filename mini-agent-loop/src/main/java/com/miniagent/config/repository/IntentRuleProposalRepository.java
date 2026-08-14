package com.miniagent.config.repository;

import com.miniagent.config.entity.IntentRuleProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntentRuleProposalRepository extends JpaRepository<IntentRuleProposal, Long> {
    List<IntentRuleProposal> findByStatusOrderByIdDesc(String status);
    List<IntentRuleProposal> findTop50ByOrderByIdDesc();
}
