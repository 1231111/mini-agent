package com.miniagent.agent.memory.config;

import com.miniagent.agent.memory.lifecycle.*;
import com.miniagent.agent.memory.manager.DefaultMemoryManager;
import com.miniagent.agent.memory.retriever.*;
import com.miniagent.agent.memory.writer.*;
import com.miniagent.memory.MemoryManager;
import com.miniagent.memory.lifecycle.*;
import com.miniagent.memory.retriever.ContextCompressor;
import com.miniagent.memory.retriever.HybridSearchEngine;
import com.miniagent.memory.retriever.Reranker;
import com.miniagent.memory.writer.Deduplicator;
import com.miniagent.memory.writer.EventProcessor;
import com.miniagent.memory.writer.ImportanceEvaluator;
import com.miniagent.memory.writer.MemoryClassifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 记忆系统装配配置。
 * 各组件使用 @ConditionalOnProperty 控制实现选择。
 * 这里提供 @Primary 和 @ConditionalOnMissingBean 兜底。
 */
@Configuration
public class MemorySystemConfig {

    // ImportanceEvaluator: 如果有 LLM evaluator 就用它，否则用规则
    @Bean
    @Primary
    @ConditionalOnMissingBean(ImportanceEvaluator.class)
    public ImportanceEvaluator importanceEvaluator(RuleBasedImportanceEvaluator ruleBased) {
        return ruleBased;
    }

    // MemoryClassifier: 规则优先
    @Bean
    @Primary
    @ConditionalOnMissingBean(MemoryClassifier.class)
    public MemoryClassifier memoryClassifier(RuleBasedMemoryClassifier ruleBased) {
        return ruleBased;
    }

    // Deduplicator
    @Bean
    @Primary
    @ConditionalOnMissingBean(Deduplicator.class)
    public Deduplicator deduplicator(EmbeddingDeduplicator embeddingDedup) {
        return embeddingDedup;
    }

    // ConflictResolver
    @Bean
    @Primary
    @ConditionalOnMissingBean(ConflictResolver.class)
    public ConflictResolver conflictResolver(PriorityConflictResolver priorityResolver) {
        return priorityResolver;
    }

    // Reranker: 规则评分 rerank
    @Bean
    @Primary
    @ConditionalOnMissingBean(Reranker.class)
    public Reranker reranker(ScoreBasedReranker scoreBased) {
        return scoreBased;
    }
}
