package com.miniagent.agent.memory.writer;

import com.miniagent.memory.lifecycle.ConflictResolver;
import com.miniagent.memory.model.MemoryEntry;
import com.miniagent.memory.model.MemoryStatus;
import com.miniagent.memory.model.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基于优先级链的冲突解决器。
 *
 * 优先级：USER_STATED > SYSTEM_CONFIG > TOOL_OBSERVED > AGENT_INFERRED > LLM_EXTRACTED
 *
 * 策略：
 * - 新记忆来源优先级更高 → 旧记忆 ARCHIVED，新记忆取代
 * - 新记忆来源优先级更低 → 丢弃新记忆
 * - 优先级相同 → 比较 confidence，高的胜出
 */
@Component
public class PriorityConflictResolver implements ConflictResolver {

    private static final Logger log = LoggerFactory.getLogger(PriorityConflictResolver.class);

    @Override
    public MemoryEntry resolve(MemoryEntry existing, MemoryEntry incoming) {
        double existPriority = getPriority(existing.getSourceType());
        double incomingPriority = getPriority(incoming.getSourceType());

        if (incomingPriority > existPriority) {
            // 新记忆优先级更高：归档旧记忆，保留新记忆
            log.debug("冲突解决: 新记忆优先级更高 ({} > {})，归档旧记忆 id={}",
                incoming.getSourceType(), existing.getSourceType(), existing.getId());
            incoming.setParentId(existing.getId());
            incoming.setVersionNum(existing.getVersionNum() + 1);
            return incoming;
        }

        if (incomingPriority < existPriority) {
            // 旧记忆优先级更高：丢弃新记忆
            log.debug("冲突解决: 旧记忆优先级更高 ({} > {})，丢弃新记忆",
                existing.getSourceType(), incoming.getSourceType());
            return null;
        }

        // 优先级相同：比较 confidence
        if (incoming.getConfidence() > existing.getConfidence()) {
            log.debug("冲突解决: 同优先级，新记忆 confidence 更高 ({} > {})",
                incoming.getConfidence(), existing.getConfidence());
            incoming.setParentId(existing.getId());
            incoming.setVersionNum(existing.getVersionNum() + 1);
            return incoming;
        }

        // 旧记忆 confidence 更高或相同：丢弃新记忆
        log.debug("冲突解决: 同优先级，旧记忆 confidence 更高或相同，丢弃新记忆");
        return null;
    }

    private double getPriority(SourceType sourceType) {
        if (sourceType == null) return SourceType.LLM_EXTRACTED.priority();
        return sourceType.priority();
    }
}
