package com.miniagent.agent.planner;

import com.miniagent.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolSuccessStatsTest {

    @Test
    void rateNeutralWithoutSamplesThenTracksWins() {
        ToolSuccessStats stats = new ToolSuccessStats();
        assertEquals(0.5d, stats.rate("write_file"), 1e-9);
        stats.record("write_file", true);
        stats.record("write_file", true);
        stats.record("write_file", false);
        assertEquals(2.0 / 3.0, stats.rate("write_file"), 1e-9);
        assertEquals(3L, stats.samples("write_file"));
    }

    @Test
    void routerPrefersHigherSuccessRateAmongPeers() {
        ToolSuccessStats stats = new ToolSuccessStats();
        stats.record("read_file", true);
        stats.record("read_file", true);
        stats.record("write_file", false);
        stats.record("write_file", false);
        ToolCapabilityIndex index = new ToolCapabilityIndex(new ToolRegistry()) {
            @Override
            public List<String> toolsFor(String capability) {
                return List.of("write_file", "read_file");
            }
        };
        ToolRouter router = new ToolRouter(index, stats);
        TaskNode node = new TaskNode("n1", "读资料", "file_write", List.of(),
                TaskNodeStatus.READY, 1, "note_required", "", "", 0);
        assertEquals("read_file", router.pickTool(node));
    }
}
