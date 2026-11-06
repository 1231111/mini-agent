package com.miniagent.config.repository;

import com.miniagent.config.entity.User;
import com.miniagent.config.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    @Query("select u.memoryAccessToken from User u where u.id = :id")
    Optional<String> findMemoryAccessTokenById(@org.springframework.data.repository.query.Param("id") Long id);

    @Query("select u.memoryRefreshCredential from User u where u.id = :id")
    Optional<String> findMemoryRefreshCredentialById(
            @org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("update User u set u.memoryAccessToken = :accessToken, "
            + "u.memoryRefreshCredential = :refreshCredential where u.id = :id and "
            + "u.memoryRefreshCredential = :expectedCredential")
    int updateMemoryCredentialsIfUnchanged(
            @org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("expectedCredential") String expectedCredential,
            @org.springframework.data.repository.query.Param("accessToken") String accessToken,
            @org.springframework.data.repository.query.Param("refreshCredential") String refreshCredential);

    @Query("select u.cloudTenantId from User u where u.id = :id")
    Optional<String> findCloudTenantIdById(@org.springframework.data.repository.query.Param("id") Long id);

    @Query("select u.externalId from User u where u.id = :id")
    Optional<String> findExternalIdById(@org.springframework.data.repository.query.Param("id") Long id);

    @Query("select u.tenantId from User u where u.id = :id")
    Optional<Long> findLocalTenantIdById(@org.springframework.data.repository.query.Param("id") Long id);

    @Query("select u.memoryAccessToken from User u where u.externalId = :externalId")
    Optional<String> findMemoryAccessTokenByExternalId(
            @org.springframework.data.repository.query.Param("externalId") String externalId);

    @Query("select u.cloudTenantId from User u where u.externalId = :externalId")
    Optional<String> findCloudTenantIdByExternalId(
            @org.springframework.data.repository.query.Param("externalId") String externalId);

    boolean existsByUsername(String username);
    long countByRole(UserRole role);

    /**
     * 按云端身份 id 定位影子用户。云端账号登录/注册成功后用它把「云端是谁」
     * 映射到「本地哪一行」。
     *
     * <p>锁的边界要说清楚，别把它当成了并发保证：影子用户走的是 find-or-create，
     * 而 {@code FOR UPDATE} 只能锁住<b>已经存在</b>的行。两个并发请求都要新建同一账号时，
     * 双方都查不到行、谁也没被锁住，照样会双双插入。真正的保证是
     * {@code ux_users_external_id} 这个唯一索引 —— 它会让后来者插入失败，
     * 由上层捕获 {@code DataIntegrityViolationException} 后重新按 externalId 读一次。
     * 这里的锁只负责「行已存在时」的并发更新串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.externalId = :externalId")
    Optional<User> findByExternalIdForUpdate(@org.springframework.data.repository.query.Param("externalId") String externalId);
    @Query("select count(u) from User u, Tenant t where u.tenantId = t.id "
            + "and u.role = :role and u.enabled = true and t.enabled = true")
    long countEffectiveByRole(UserRole role);
    long countByTenantId(Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role")
    List<User> findByRoleForUpdate(UserRole role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(Long id);
}
