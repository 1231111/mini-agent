package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.memory.model.MemoryEntry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 {@code AgentMemoryEntryEntity → MemoryEntry} 的字段映射。
 *
 * <p>这是「遗忘策略失效」这个 bug 的真正发生地：实体里有 {@code last_accessed_at} 列，
 * 但映射方法漏了它，模型上的 {@code lastAccessedAt} 永远是 0，
 * 于是 {@code RetentionForgettingPolicy.recencyDecay(0)} 恒返回「从未访问」的 0.5，
 * 时间维度彻底失效——被频繁召回的老记忆和新记忆拿一样的 recency 分。
 *
 * <p>{@link com.miniagent.agent.memory.lifecycle.RetentionForgettingPolicyTest} 证的是
 * 「公式对这两个字段敏感」，本类证的是「字段真的被搬过来了」，两者缺一不可。
 */
class DefaultMemoryManagerMappingTest {

    /** 映射方法不触碰任何注入字段，因此可以直接 new 而不启 Spring 容器。 */
    private final DefaultMemoryManager manager = new DefaultMemoryManager();

    private AgentMemoryEntryEntity entity(LocalDateTime lastAccessedAt, Integer accessCount) {
        AgentMemoryEntryEntity e = new AgentMemoryEntryEntity();
        e.setId(1L);
        e.setTenantId("tenant-1");
        e.setMemoryType(AgentMemoryEntryEntity.MemoryType.SEMANTIC);
        e.setScopeType(AgentMemoryEntryEntity.ScopeType.USER);
        e.setScopeId("user-1");
        e.setContent("用户偏好使用中文回答");
        e.setStatus(AgentMemoryEntryEntity.Status.ACTIVE);
        e.setLastAccessedAt(lastAccessedAt);
        e.setAccessCount(accessCount);
        return e;
    }

    private MemoryEntry map(AgentMemoryEntryEntity e) {
        return (MemoryEntry) ReflectionTestUtils.invokeMethod(manager, "toMemoryEntryModel", e);
    }

    @Test
    void 最近访问时间必须从实体搬到模型() {
        LocalDateTime twoHoursAgo = LocalDateTime.now().minusHours(2);
        MemoryEntry m = map(entity(twoHoursAgo, 7));

        assertTrue(m.getLastAccessedAt() > 0,
                "lastAccessedAt 为 0 会让 recencyDecay 恒走「从未访问」分支，时间维度失效");
        assertEquals(
                twoHoursAgo.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                m.getLastAccessedAt(),
                "时间戳必须与实体列一致，否则衰减曲线会整体偏移");
        assertEquals(7, m.getAccessCount(), "accessCount 必须一并搬过来");
    }

    @Test
    void 从未访问的记忆保持为0() {
        MemoryEntry m = map(entity(null, 0));

        assertEquals(0L, m.getLastAccessedAt(),
                "空列应映射为 0，由 recencyDecay 统一解释为「从未访问」");
    }
}
