package com.miniagent.config.repository;

import com.miniagent.config.entity.UserModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserModelConfigRepository extends JpaRepository<UserModelConfig, Long> {
    Optional<UserModelConfig> findByUserId(Long userId);
}
