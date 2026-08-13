package com.miniagent.agent.memory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 向量存储的 MySQL fallback 表。
 * 正常走 Milvus 检索，此表用于索引重建和 Milvus 不可用时的降级。
 */
@Entity
@Table(name = "agent_memory_embeddings")
public class AgentMemoryEmbeddingEntity {

    @Id
    @Column(name = "memory_id", nullable = false)
    private Long memoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_type", nullable = false, length = 32)
    private EmbeddingMemoryType memoryType;

    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Lob
    @Column(name = "vector_json", nullable = false, columnDefinition = "LONGTEXT")
    private String vectorJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public enum EmbeddingMemoryType {
        ENTRY, EPISODE
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // --- getters/setters ---

    public Long getMemoryId() { return memoryId; }
    public void setMemoryId(Long memoryId) { this.memoryId = memoryId; }

    public EmbeddingMemoryType getMemoryType() { return memoryType; }
    public void setMemoryType(EmbeddingMemoryType memoryType) { this.memoryType = memoryType; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public String getVectorJson() { return vectorJson; }
    public void setVectorJson(String vectorJson) { this.vectorJson = vectorJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
