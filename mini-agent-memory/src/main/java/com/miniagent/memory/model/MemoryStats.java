package com.miniagent.memory.model;

import java.util.Map;

/**
 * 记忆统计信息。
 */
public class MemoryStats {
    private long totalMemories;
    private long activeMemories;
    private long archivedMemories;
    private long deletedMemories;
    private long totalEpisodes;
    private long totalFacts;
    private long totalProcedures;
    private long totalEvents;
    private long unprocessedEvents;
    private Map<MemoryType, Long> typeDistribution;

    // --- getters/setters ---

    public long getTotalMemories() { return totalMemories; }
    public void setTotalMemories(long totalMemories) { this.totalMemories = totalMemories; }

    public long getActiveMemories() { return activeMemories; }
    public void setActiveMemories(long activeMemories) { this.activeMemories = activeMemories; }

    public long getArchivedMemories() { return archivedMemories; }
    public void setArchivedMemories(long archivedMemories) { this.archivedMemories = archivedMemories; }

    public long getDeletedMemories() { return deletedMemories; }
    public void setDeletedMemories(long deletedMemories) { this.deletedMemories = deletedMemories; }

    public long getTotalEpisodes() { return totalEpisodes; }
    public void setTotalEpisodes(long totalEpisodes) { this.totalEpisodes = totalEpisodes; }

    public long getTotalFacts() { return totalFacts; }
    public void setTotalFacts(long totalFacts) { this.totalFacts = totalFacts; }

    public long getTotalProcedures() { return totalProcedures; }
    public void setTotalProcedures(long totalProcedures) { this.totalProcedures = totalProcedures; }

    public long getTotalEvents() { return totalEvents; }
    public void setTotalEvents(long totalEvents) { this.totalEvents = totalEvents; }

    public long getUnprocessedEvents() { return unprocessedEvents; }
    public void setUnprocessedEvents(long unprocessedEvents) { this.unprocessedEvents = unprocessedEvents; }

    public Map<MemoryType, Long> getTypeDistribution() { return typeDistribution; }
    public void setTypeDistribution(Map<MemoryType, Long> typeDistribution) { this.typeDistribution = typeDistribution; }
}
