package com.miniagent.account.repository;

import com.miniagent.account.entity.MembershipPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MembershipPlanRepository extends JpaRepository<MembershipPlan, Long> {

    Optional<MembershipPlan> findByCode(String code);

    List<MembershipPlan> findByEnabledTrueOrderByPriceCentsAsc();
}
