package com.miniagent.account.repository;

import com.miniagent.account.entity.Tenant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findBySlug(String slug);

    /**
     * 写配额上限时用。订阅变更与"另一个订阅同时生效"会并发改同一行，
     * 不加锁的话后写的那次会覆盖先写的，而两次都返回成功 —— 用户看到的是
     * "我买了贵的套餐，额度却变成了便宜的那个"。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.id = :id")
    Optional<Tenant> findByIdForUpdate(@Param("id") Long id);
}
