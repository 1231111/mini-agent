package com.miniagent.memory.model;

import java.util.*;

/**
 * 记忆检索请求。
 */
public class MemoryQuery {
    private String query;
    private MemoryScope scope;
    private int topK;
    private List<MemoryType> typeFilter;
    private Double minImportance;
    private Long timeFrom;
    private Long timeTo;
    private double minScore;

    public MemoryQuery() {
        this.topK = 10;
        this.minScore = 0.3;
        this.typeFilter = new ArrayList<>();
    }

    public static MemoryQuery of(String query, MemoryScope scope) {
        MemoryQuery q = new MemoryQuery();
        q.query = query;
        q.scope = scope;
        return q;
    }

    // --- getters/setters ---

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public MemoryScope getScope() { return scope; }
    public void setScope(MemoryScope scope) { this.scope = scope; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }

    public List<MemoryType> getTypeFilter() { return typeFilter; }
    public void setTypeFilter(List<MemoryType> typeFilter) { this.typeFilter = typeFilter; }

    public Double getMinImportance() { return minImportance; }
    public void setMinImportance(Double minImportance) { this.minImportance = minImportance; }

    public Long getTimeFrom() { return timeFrom; }
    public void setTimeFrom(Long timeFrom) { this.timeFrom = timeFrom; }

    public Long getTimeTo() { return timeTo; }
    public void setTimeTo(Long timeTo) { this.timeTo = timeTo; }

    public double getMinScore() { return minScore; }
    public void setMinScore(double minScore) { this.minScore = minScore; }
}
