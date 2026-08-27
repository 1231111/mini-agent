package com.miniagent.memory.model;

/**
 * 混合检索评分。
 * score = w1*semantic + w2*keyword + w3*importance + w4*recency + w5*confidence + w6*scopeRelevance
 */
public class HybridScore {
    private double semantic;
    private double keyword;
    private double importance;
    private double recency;
    private double confidence;
    private double scopeRelevance;
    private double finalScore;

    private static final double W_SEMANTIC = 0.35;
    private static final double W_KEYWORD = 0.20;
    private static final double W_IMPORTANCE = 0.15;
    private static final double W_RECENCY = 0.10;
    private static final double W_CONFIDENCE = 0.10;
    private static final double W_SCOPE = 0.10;

    public HybridScore() {}

    public HybridScore(double semantic, double keyword, double importance,
                       double recency, double confidence, double scopeRelevance) {
        this.semantic = semantic;
        this.keyword = keyword;
        this.importance = importance;
        this.recency = recency;
        this.confidence = confidence;
        this.scopeRelevance = scopeRelevance;
        this.finalScore = calculate();
    }

    private double calculate() {
        return W_SEMANTIC * semantic
             + W_KEYWORD * keyword
             + W_IMPORTANCE * importance
             + W_RECENCY * recency
             + W_CONFIDENCE * confidence
             + W_SCOPE * scopeRelevance;
    }

    public double getSemantic() { return semantic; }
    public void setSemantic(double semantic) { this.semantic = semantic; }

    public double getKeyword() { return keyword; }
    public void setKeyword(double keyword) { this.keyword = keyword; }

    public double getImportance() { return importance; }
    public void setImportance(double importance) { this.importance = importance; }

    public double getRecency() { return recency; }
    public void setRecency(double recency) { this.recency = recency; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public double getScopeRelevance() { return scopeRelevance; }
    public void setScopeRelevance(double scopeRelevance) { this.scopeRelevance = scopeRelevance; }

    public double getFinalScore() { return finalScore; }
    public void setFinalScore(double finalScore) { this.finalScore = finalScore; }
}
