package com.miniagent.memory.model;

/**
 * 带评分的记忆检索结果。
 */
public class ScoredMemory {
    private MemoryEntry memory;
    private HybridScore score;

    public ScoredMemory() {}

    public ScoredMemory(MemoryEntry memory, HybridScore score) {
        this.memory = memory;
        this.score = score;
    }

    public MemoryEntry getMemory() { return memory; }
    public void setMemory(MemoryEntry memory) { this.memory = memory; }

    public HybridScore getScore() { return score; }
    public void setScore(HybridScore score) { this.score = score; }

    public double getFinalScore() { return score != null ? score.getFinalScore() : 0; }
}
