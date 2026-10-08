package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 幂等键必须包含**绑定参数摘要**。
 *
 * <p>背景：{@code ToolPipeline} 命中动作日志里的 {@code SUCCEEDED} 时会直接返回
 * {@code {"success":true,"deduplicated":true}} 而不执行。此前幂等键只有
 * {@code planVersion + nodeId}，于是"上一步参数写错但被记为成功 → 用改正的参数重试"
 * 会命中同一条日志被静默跳过，节点还被标成成功 —— 表面全绿、实际没做。
 * 这些用例锁住"参数变则键变"，同时锁住"参数不变则键不变"（崩溃恢复的去重语义要保留）。</p>
 */
class GraphSchedulerIdempotencyKeyTest {

    private static ActionBinder.Bound bound(Map<String, Object> args) {
        return new ActionBinder.Bound("write_file", args);
    }

    private static StateSnapshot snap(long planVersion) {
        PlanRevision revision = new PlanRevision(planVersion, Math.max(0, planVersion - 1),
                System.currentTimeMillis(), "test");
        return new StateSnapshot(1L, "s-1", "exec-1", null, null,
                Map.of(), Map.of(), List.of(), 0, revision);
    }

    private static TaskNode node(String id) {
        return new TaskNode(id, "写报告", "write_file", List.of(), List.of(), List.of(),
                TaskNodeStatus.READY, 0, null, null, Map.of(), null, List.of(), null, 0, null, Map.of());
    }

    @Test
    void differentArgumentsProduceDifferentKeys() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("path", "report.md");
        first.put("content", "v1");
        Map<String, Object> corrected = new LinkedHashMap<>();
        corrected.put("path", "report.md");
        corrected.put("content", "v2-corrected");

        String k1 = GraphScheduler.idempotencyKey(snap(3), node("n7"), bound(first));
        String k2 = GraphScheduler.idempotencyKey(snap(3), node("n7"), bound(corrected));

        assertNotEquals(k1, k2,
                "改正后的参数必须换一个幂等键，否则重试会被当成重复动作静默跳过");
    }

    @Test
    void sameArgumentsProduceSameKeyRegardlessOfMapOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("path", "x.md");
        a.put("content", "hello");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("content", "hello");
        b.put("path", "x.md");

        assertEquals(GraphScheduler.idempotencyKey(snap(3), node("n7"), bound(a)),
                GraphScheduler.idempotencyKey(snap(3), node("n7"), bound(b)),
                "同一个参数的两种键序必须算出同一个键（否则恢复时的去重会失效）");
    }

    @Test
    void keyStillDependsOnPlanVersionAndNode() {
        Map<String, Object> args = Map.of("path", "x.md");
        String base = GraphScheduler.idempotencyKey(snap(3), node("n7"), bound(args));
        assertNotEquals(base, GraphScheduler.idempotencyKey(snap(4), node("n7"), bound(args)),
                "计划版本变化必须换键");
        assertNotEquals(base, GraphScheduler.idempotencyKey(snap(3), node("n8"), bound(args)),
                "节点变化必须换键");
    }

    @Test
    void emptyArgumentsAreStable() {
        String k1 = GraphScheduler.idempotencyKey(snap(1), node("n1"), bound(Map.of()));
        String k2 = GraphScheduler.idempotencyKey(snap(1), node("n1"), null);
        assertEquals(k1, k2, "空参数与无绑定必须得到同一个稳定的键");
    }
}
