package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** DB 与向量索引之间的可靠异步投递记录。 */
@Entity
@Table(name = "agent_memory_index_outbox", indexes = {
        @Index(name = "idx_amio_pending", columnList = "status,next_attempt_at"),
        @Index(name = "idx_amio_memory", columnList = "memory_id")
})
public class AgentMemoryIndexOutboxEntity extends BaseMemoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "memory_id", nullable = false)
    private Long memoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 16)
    private Operation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt = LocalDateTime.now();

    @Column(name = "last_error", length = 1000)
    private String lastError;

    public enum Operation { UPSERT, DELETE }
    public enum Status { PENDING, PROCESSING, DONE, FAILED }

    public Long getId() { return id; }
    public Long getMemoryId() { return memoryId; }
    public void setMemoryId(Long memoryId) { this.memoryId = memoryId; }
    public Operation getOperation() { return operation; }
    public void setOperation(Operation operation) { this.operation = operation; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
