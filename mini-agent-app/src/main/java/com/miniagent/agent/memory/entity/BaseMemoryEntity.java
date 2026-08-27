package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 记忆模块实体基类，提供 createdAt / updatedAt 自动管理。
 * 消除 AgentMemoryEntryEntity、AgentSemanticFactEntity、AgentProcedureEntity 等中的重复样板代码。
 */
@MappedSuperclass
public abstract class BaseMemoryEntity {

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
