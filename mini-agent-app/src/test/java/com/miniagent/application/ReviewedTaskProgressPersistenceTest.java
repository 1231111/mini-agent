package com.miniagent.application;

import com.miniagent.agent.planner.DoneWhen;
import com.miniagent.agent.planner.Goal;
import com.miniagent.agent.planner.PlannerStateJson;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.agent.planner.TaskGraph;
import com.miniagent.agent.planner.TaskNode;
import com.miniagent.agent.planner.TaskNodeStatus;
import com.miniagent.config.entity.AgentSessionPlanner;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.repository.AgentSessionPlannerRepository;
import com.miniagent.config.repository.AgentTraceStepRepository;
import com.miniagent.config.repository.ChatTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual LONGTEXT JPQL compare-and-set and durable planner read-repair. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:reviewed-progress;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewedTaskProgressPersistenceTest {
    @Autowired ChatTaskRepository tasks;
    @Autowired AgentSessionPlannerRepository planners;
    @Autowired AgentTraceStepRepository traces;

    @Test void committedReviewIsStoredOnlyOnLatestExactReplyAndSurvivesReconstruction() {
        String session = "persist-review";
        ChatTask older = card(session, 7L, "exec");
        ChatTask latest = card(session, 7L, "exec");
        ChatTask otherExecution = card(session, 7L, "other-exec");
        ChatTask otherOwner = card(session, 8L, "exec");
        String original = latest.getTaskProgressJson();

        var update = service().persistReviewedOutcome(7L, session, graph(session));

        assertNotNull(update);
        assertEquals(latest.getId(), update.messageId());
        ChatTask reloaded = tasks.findById(latest.getId()).orElseThrow();
        assertEquals("original answer", reloaded.getAnswer());
        assertEquals("uploaded.png,brief.pdf", reloaded.getImages());
        assertEquals(original, tasks.findById(older.getId()).orElseThrow().getTaskProgressJson());
        assertEquals(original, tasks.findById(otherOwner.getId()).orElseThrow().getTaskProgressJson());
        assertEquals(otherExecution.getTaskProgressJson(), tasks.findById(otherExecution.getId()).orElseThrow().getTaskProgressJson());
        var restored = service().forMessages(7L, session, List.of(reloaded)).get(latest.getId());
        assertEquals("completed", restored.items().getFirst().status());
        assertEquals("pending", restored.items().get(1).status());
        assertEquals("OUTCOME_REVIEWED", restored.reason());
        assertEquals(1, restored.outcomeReviewReceipts().size());
        assertNull(service().persistReviewedOutcome(7L, session, graph(session)));
    }

    @Test void plannerCommitBeforeProjectionWriteIsRepairedAfterServiceReconstruction() {
        String session = "read-repair";
        ChatTask reply = card(session, 7L, "exec");
        // Represents a process stopping after planner commit, before the reply write.
        AgentSessionPlanner planner = new AgentSessionPlanner();
        planner.setSessionId(session);
        planner.setPlannerVersion(3);
        planner.setStateJson(PlannerStateJson.snapshotToJson(graph(session)));
        planners.saveAndFlush(planner);

        ChatTask fromDatabase = tasks.findById(reply.getId()).orElseThrow();
        assertEquals("OUTCOME_UNKNOWN", TaskProgressSnapshot.fromJson(fromDatabase.getTaskProgressJson()).reason());
        var repaired = service().forMessages(7L, session, List.of(fromDatabase)).get(reply.getId());
        assertEquals("OUTCOME_REVIEWED", repaired.reason());
        assertEquals("completed", repaired.items().getFirst().status());
        assertEquals("pending", repaired.items().get(1).status());
        var savedAgain = tasks.findById(reply.getId()).orElseThrow();
        assertEquals(repaired, TaskProgressSnapshot.fromJson(savedAgain.getTaskProgressJson()));
        assertEquals("original answer", savedAgain.getAnswer());
        assertEquals("uploaded.png,brief.pdf", savedAgain.getImages());
    }

    @Test void repositoryCasRejectsStaleJsonWrongScopeAndDeletedRows() {
        ChatTask reply = card("cas-review", 7L, "exec");
        String original = reply.getTaskProgressJson();
        assertEquals(0, tasks.compareAndSetTaskProgress(reply.getId(), 8L, "cas-review", original, "wrong owner"));
        assertEquals(0, tasks.compareAndSetTaskProgress(reply.getId(), 7L, "different-session", original, "wrong session"));
        assertEquals(0, tasks.compareAndSetTaskProgress(reply.getId(), 7L, "cas-review", "stale JSON", "wrong version"));
        assertEquals(1, tasks.compareAndSetTaskProgress(reply.getId(), 7L, "cas-review", original, "next JSON"));
        assertEquals(0, tasks.compareAndSetTaskProgress(reply.getId(), 7L, "cas-review", original, "replayed JSON"));
        assertEquals(1, tasks.softDeleteByUserIdAndSessionId(7L, "cas-review"));
        assertEquals(0, tasks.compareAndSetTaskProgress(reply.getId(), 7L, "cas-review", "next JSON", "deleted write"));
        assertEquals("next JSON", tasks.findById(reply.getId()).orElseThrow().getTaskProgressJson());
    }

    private ConversationTaskProgressService service() {
        return new ConversationTaskProgressService(planners, traces, tasks);
    }

    private ChatTask card(String session, Long userId, String execution) {
        ChatTask reply = new ChatTask();
        reply.setUserId(userId);
        reply.setSessionId(session);
        reply.setQuestion("提交测试表单");
        reply.setAnswer("original answer");
        reply.setImages("uploaded.png,brief.pdf");
        reply.setTaskProgressJson(new TaskProgressSnapshot(execution, 0, "FAILURE", "OUTCOME_UNKNOWN", List.of(
                new TaskProgressSnapshot.Item(1, "提交表单", "blocked", "结果待核验", List.of()),
                new TaskProgressSnapshot.Item(2, "返回回执", "pending", "", List.of(1)))).toJson());
        return tasks.saveAndFlush(reply);
    }

    private StateSnapshot graph(String session) {
        TaskNode submitted = new TaskNode("n1", "提交表单", "general", List.of(), List.of(), List.of(),
                TaskNodeStatus.SUCCESS, 1, DoneWhen.note(), "", "", 0, "");
        TaskNode successor = new TaskNode("n2", "返回回执", "general", List.of("n1"), List.of(), List.of(),
                TaskNodeStatus.SUCCESS, 1, DoneWhen.note(), "", "", 0, "");
        return new StateSnapshot(3, session, "exec", new Goal("g", "提交", "", Map.of(), List.of(), List.of()),
                new TaskGraph(List.of(submitted, successor)), Map.of("outcomeReconciliations", List.of(Map.of(
                    "reviewerId", 7L, "nodeId", "n1", "previousVersion", 1, "previousPlanVersion", 1,
                    "decision", "CONFIRMED_APPLIED", "at", Instant.now().plusSeconds(1).toString()))), Map.of(), List.of(), 0);
    }
}
