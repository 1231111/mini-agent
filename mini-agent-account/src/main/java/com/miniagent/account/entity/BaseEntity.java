package com.miniagent.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.LocalDateTime;

/**
 * JPA 实体基类，提供 createdAt / updatedAt 自动管理。
 *
 * <p>与云端 agent 里同名类保持一致（包括 {@code columnDefinition} —— 共享库场景下，
 * 两个应用对同一列的 DDL 理解不同会让 {@code ddl-auto: validate} 各自看到不同的东西）。
 */
@MappedSuperclass
public abstract class BaseEntity {

    /** 带 DEFAULT，旧表 ADD COLUMN NOT NULL 才不会被填 0000-00-00。 */
    private static final String TS_COL =
            "datetime(6) not null default current_timestamp(6)";

    @Column(name = "created_at", nullable = false, columnDefinition = TS_COL)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = TS_COL)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
