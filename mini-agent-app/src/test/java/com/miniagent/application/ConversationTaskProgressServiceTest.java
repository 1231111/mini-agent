package com.miniagent.application;

import com.miniagent.config.entity.AgentSessionPlanner;
import com.miniagent.config.entity.AgentTraceStep;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.repository.AgentSessionPlannerRepository;
import com.miniagent.config.repository.AgentTraceStepRepository;
import com.miniagent.config.repository.ChatTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationTaskProgressServiceTest {
    private final AgentSessionPlannerRepository planners = mock(AgentSessionPlannerRepository.class);
    private final AgentTraceStepRepository traces = mock(AgentTraceStepRepository.class);
    private final ChatTaskRepository tasks = mock(ChatTaskRepository.class);
    private final ConversationTaskProgressService service = new ConversationTaskProgressService(planners, traces, tasks);
    private final LocalDateTime start = LocalDateTime.of(2026, 11, 12, 9, 0);

    @Test
    void persistedTurnSnapshotsSurviveReloadWithoutReadingTheCurrentGraph() {
        ChatTask first = task(1, 1), second = task(2, 2);
        first.setTaskProgressJson(new TaskProgressSnapshot("exec-1", 0, "SUCCESS", "SUCCESS",
                List.of(new TaskProgressSnapshot.Item(1, "生成页面", "completed", "", List.of()))).toJson());
        second.setTaskProgressJson(new TaskProgressSnapshot("exec-2", 1, "FAILURE", "TOOL_ERROR",
                List.of(new TaskProgressSnapshot.Item(1, "校验页面", "blocked", "命令失败", List.of()))).toJson());

        var restored = service.forMessages(7L, "session", List.of(first, second));

        assertEquals("生成页面", restored.get(1L).items().getFirst().content());
        assertEquals("completed", restored.get(1L).items().getFirst().status());
        assertEquals("FAILURE", restored.get(2L).status());
        assertEquals("blocked", restored.get(2L).items().getFirst().status());
        verifyNoInteractions(planners, traces, tasks);
    }

    @Test
    void legacyDagMatchesExecutionAndTurnIntervalEvenForIdenticalQuestions() {
        ChatTask first = task(1, 1), second = task(2, 3);
        when(planners.findBySessionIdOrSessionIdStartingWith("session", "session#"))
                .thenReturn(List.of(graph("session", "exec-1", "第一轮节点"), graph("session#1", "exec-2", "第二轮节点")));
        when(traces.findBySessionIdOrderByTurnIndexAscIdAsc("session"))
                .thenReturn(List.of(trace("exec-1", 0), trace("exec-2", 2)));
        when(tasks.findFirstByUserIdAndSessionIdAndDeletedFalseAndIdLessThanOrderByIdDesc(7L, "session", 1L))
                .thenReturn(Optional.empty());
        when(tasks.findFirstByUserIdAndSessionIdAndDeletedFalseAndIdLessThanOrderByIdDesc(7L, "session", 2L))
                .thenReturn(Optional.of(first));

        var restored = service.forMessages(7L, "session", List.of(first, second));

        assertEquals("第一轮节点", restored.get(1L).items().getFirst().content());
        assertEquals("第二轮节点", restored.get(2L).items().getFirst().content());
    }

    @Test
    void absentOrAmbiguousLegacyEvidenceDoesNotTurnToolLoopsIntoNodes() {
        ChatTask task = task(1, 5);
        when(planners.findBySessionIdOrSessionIdStartingWith("session", "session#"))
                .thenReturn(List.of(graph("session", "exec-1", "节点一"), graph("session#1", "exec-2", "节点二")));
        when(traces.findBySessionIdOrderByTurnIndexAscIdAsc("session"))
                .thenReturn(List.of(trace("exec-1", 0), trace("exec-2", 2)));
        assertTrue(service.forMessages(7L, "session", List.of(task)).isEmpty());
    }

    @Test
    void newerResumedGraphCannotOverwriteAnOlderReplysNodeStates() {
        ChatTask first = task(1, 1);
        var newer = graph("session", "exec-1", "后来完成的节点");
        newer.setUpdatedAt(start.plusMinutes(4));
        when(planners.findBySessionIdOrSessionIdStartingWith("session", "session#")).thenReturn(List.of(newer));
        when(traces.findBySessionIdOrderByTurnIndexAscIdAsc("session")).thenReturn(List.of(trace("exec-1", 0)));

        assertTrue(service.forMessages(7L, "session", List.of(first)).isEmpty());
    }

    private ChatTask task(long id, int minutes) {
        ChatTask task = new ChatTask(); task.setId(id); task.setQuestion("相同的问题");
        task.setCreatedAt(start.plusMinutes(minutes)); return task;
    }

    private AgentTraceStep trace(String execution, int minutes) {
        AgentTraceStep trace = new AgentTraceStep(); trace.setExecutionId(execution);
        trace.setUserQuestion("相同的问题"); trace.setCreatedAt(start.plusMinutes(minutes)); return trace;
    }

    private AgentSessionPlanner graph(String scope, String execution, String node) {
        AgentSessionPlanner row = new AgentSessionPlanner(); row.setSessionId(scope);
        row.setStateJson("""
                {"version":1,"sessionId":"%s","executionId":"%s",
                 "graph":{"nodes":[{"id":"n1","name":"%s","status":"SUCCESS"}]}}
                """.formatted(scope, execution, node));
        return row;
    }
}
