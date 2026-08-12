package com.miniagent.agent.planner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProposalGateTest {

    @AfterEach
    void clear() {
        PlanningContext.clear();
    }

    @Test
    void deniesOutsideToolsAndWrongTodo() {
        PlanningContext.set(new PlanningContext.Holder(
                "s", 1L, "p1", List.of("write_file", "todo"), "n1", "写文件",
                Set.of(2), true, true, new AtomicBoolean(false), new AtomicInteger(0)));
        assertNotNull(ProposalGate.denyTool("web_search"));
        assertNull(ProposalGate.denyTool("write_file"));
        assertNotNull(ProposalGate.denyTodo("set", 0));
        assertNotNull(ProposalGate.denyTodo("clear", 0));
        assertNull(ProposalGate.denyTodo("list", 0));
        assertNotNull(ProposalGate.denyTodo("update", 1));
        assertNull(ProposalGate.denyTodo("update", 2));
        assertNotNull(ProposalGate.denyTodoArgsJson("{\"action\":\"set\",\"items\":[]}"));
        assertNull(ProposalGate.denyTodoArgsJson(
                "{\"action\":\"update\",\"id\":2,\"status\":\"completed\"}"));
    }

    @Test
    void inactivePasses() {
        assertNull(ProposalGate.denyTool("anything"));
        assertNull(ProposalGate.denyTodo("set", 1));
    }
}
