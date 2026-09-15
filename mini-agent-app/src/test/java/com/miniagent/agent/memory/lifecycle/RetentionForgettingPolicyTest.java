package com.miniagent.agent.memory.lifecycle;

import com.miniagent.memory.model.MemoryEntry;
import com.miniagent.memory.model.MemoryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定「访问计数与最近访问时间真的参与遗忘评分」。
 *
 * <p>背景：store 侧曾经从不写回 {@code accessCount} / {@code lastAccessedAt}
 * （{@code touchAccessBatch} 无调用方、实体映射漏字段），导致
 * {@code accessFreq} 恒为 0、{@code recencyDecay} 恒走「从未访问」分支返回 0.5。
 * 于是遗忘退化为「只看 importance 和 confidence」，被频繁召回的记忆照旧被判死。
 *
 * <p>这些用例断言的是修复后的不变量：<b>同一条记忆，仅仅因为被召回，
 * 就必须能从更差的保留档位回到更好的档位</b>。若有人再删掉写回链路，此处会红。
 */
class RetentionForgettingPolicyTest {

    private RetentionForgettingPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new RetentionForgettingPolicy();
        // 字段注入在裸单测里不会发生，手动给上与 application.yml 一致的阈值
        ReflectionTestUtils.setField(policy, "archiveThreshold", 0.4);
        ReflectionTestUtils.setField(policy, "deleteThreshold", 0.2);
    }

    private MemoryEntry entry(double importance, double confidence, int accessCount, long lastAccessedAt) {
        MemoryEntry m = new MemoryEntry();
        m.setImportance(importance);
        m.setConfidence(confidence);
        m.setAccessCount(accessCount);
        m.setLastAccessedAt(lastAccessedAt);
        m.setStatus(MemoryStatus.ACTIVE);
        return m;
    }

    @Test
    void 召回后的记忆能从归档档位回到活跃档位() {
        // 同样的重要度/置信度，唯一变量是「有没有被召回过」
        MemoryEntry recalled = entry(0.3, 0.3, 5, System.currentTimeMillis());
        MemoryEntry neverRecalled = entry(0.3, 0.3, 0, 0L);

        assertEquals(MemoryStatus.ACTIVE, policy.evaluate(recalled),
                "刚被召回 5 次的记忆应保持 ACTIVE");
        assertEquals(MemoryStatus.ARCHIVED, policy.evaluate(neverRecalled),
                "从未被召回的记忆应被归档");
    }

    @Test
    void 访问计数越高保留分数越高() {
        long now = System.currentTimeMillis();
        double cold = scoreOf(entry(0.3, 0.3, 0, now));
        double warm = scoreOf(entry(0.3, 0.3, 5, now));
        double hot = scoreOf(entry(0.3, 0.3, 50, now));

        assertTrue(cold < warm, "accessCount 0 → 5 必须带来分数上升");
        assertTrue(warm < hot, "accessCount 5 → 50 必须带来分数上升");
    }

    @Test
    void 最近访问时间越久远保留分数越低() {
        long now = System.currentTimeMillis();
        long day = 24L * 60 * 60 * 1000;
        double fresh = scoreOf(entry(0.3, 0.3, 1, now));
        double week = scoreOf(entry(0.3, 0.3, 1, now - 7 * day));
        double ancient = scoreOf(entry(0.3, 0.3, 1, now - 40 * day));

        assertTrue(fresh > week, "新鲜访问应高于一周前");
        assertTrue(week > ancient, "一周前应高于四十天前");
    }

    @Test
    void 超过三十天未访问落入衰减下限() {
        long ancient = System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000;
        // 0.1(recency) * 0.2 = 0.02，低于 delete 阈值 → DELETED
        assertEquals(MemoryStatus.DELETED, policy.evaluate(entry(0.0, 0.0, 0, ancient)),
                "importance/confidence 为 0 且已过衰减下限的记忆应被删除");
    }

    @Test
    void 已删除状态是终态() {
        MemoryEntry m = entry(1.0, 1.0, 100, System.currentTimeMillis());
        m.setStatus(MemoryStatus.DELETED);
        assertEquals(MemoryStatus.DELETED, policy.evaluate(m),
                "已 DELETED 的记忆不应因为分数高而复活");
    }

    /** 反射取私有 retentionScore，避免把断言耦合在档位边界上。 */
    private double scoreOf(MemoryEntry m) {
        return (double) ReflectionTestUtils.invokeMethod(policy, "retentionScore", m);
    }
}
