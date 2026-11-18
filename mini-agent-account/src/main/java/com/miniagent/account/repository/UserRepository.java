package com.miniagent.account.repository;

import com.miniagent.account.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

/**
 * 账号仓库。
 *
 * <p>字段比云端 agent 侧的同名接口少得多 —— 那边还有 {@code findByExternalIdForUpdate}、
 * {@code countByRole} 等一系列给影子用户和权限统计用的查询，那些在本服务里没有意义。
 * 少即是好：接口上没有的方法，就不会有人误用。
 */
public interface UserRepository extends JpaRepository<User, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    Optional<User> findByUsername(String username);

    Optional<User> findByMemoryRefreshTokenHash(String memoryRefreshTokenHash);

    boolean existsByUsername(String username);
}
