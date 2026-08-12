package com.miniagent.agent.planner;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 进程内工具成败计数，供 Router 加权。无样本时视为中性。
 */
@Component
public class ToolSuccessStats {

    private static final double NEUTRAL_RATE = 0.5d;

    private final ConcurrentHashMap<String, Counters> byTool = new ConcurrentHashMap<>();

    public void record(String tool, boolean success) {
        if (tool == null || tool.isBlank()) return;
        Counters c = byTool.computeIfAbsent(tool.trim().toLowerCase(), k -> new Counters());
        if (success) c.ok.increment();
        else c.fail.increment();
    }

    /** 成功率；无样本返回 {@link #NEUTRAL_RATE}。 */
    public double rate(String tool) {
        if (tool == null || tool.isBlank()) return NEUTRAL_RATE;
        Counters c = byTool.get(tool.trim().toLowerCase());
        if (c == null) return NEUTRAL_RATE;
        long ok = c.ok.sum();
        long fail = c.fail.sum();
        long total = ok + fail;
        if (total <= 0) return NEUTRAL_RATE;
        return (double) ok / (double) total;
    }

    public long samples(String tool) {
        if (tool == null || tool.isBlank()) return 0L;
        Counters c = byTool.get(tool.trim().toLowerCase());
        if (c == null) return 0L;
        return c.ok.sum() + c.fail.sum();
    }

    private static final class Counters {
        final LongAdder ok = new LongAdder();
        final LongAdder fail = new LongAdder();
    }
}
