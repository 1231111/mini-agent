package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentSemanticFactEntity;
import com.miniagent.agent.memory.repository.AgentSemanticFactRepository;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.MemoryManager;
import com.miniagent.memory.contract.MemoryApi.UserFactMutation;
import com.miniagent.memory.contract.MemoryApi.UserFactSync;
import com.miniagent.memory.contract.MemoryApi.UserFactSyncResult;
import com.miniagent.memory.contract.MemoryApi.UserFactView;
import com.miniagent.memory.model.SemanticFact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserMemoryCloudSyncTest {
    private static final String TENANT = "local-tenant";
    private static final Long USER_ID = 17L;
    private static final ZoneId DB_ZONE = ZoneId.systemDefault();

    private final AgentSemanticFactRepository facts = mock(AgentSemanticFactRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CloudMemoryClient cloud = mock(CloudMemoryClient.class);
    private final MemoryManager memory = mock(MemoryManager.class);
    private final List<AgentSemanticFactEntity> stored = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    private UserMemoryCloudSync sync;

    @BeforeEach
    void setUp() {
        when(facts.findByTenantIdAndScopeTypeAndScopeId(any(), any(), any()))
                .thenAnswer(invocation -> {
                    String tenant = invocation.getArgument(0);
                    AgentMemoryEntryEntity.ScopeType scope = invocation.getArgument(1);
                    String scopeId = invocation.getArgument(2);
                    return stored.stream()
                            .filter(row -> tenant.equals(row.getTenantId()))
                            .filter(row -> scope == row.getScopeType())
                            .filter(row -> scopeId.equals(row.getScopeId()))
                            .toList();
                });
        when(facts.save(any(AgentSemanticFactEntity.class))).thenAnswer(invocation -> {
            AgentSemanticFactEntity row = invocation.getArgument(0);
            if (row.getId() == null) row.setId(ids.incrementAndGet());
            if (row.getCreatedAt() == null) row.setCreatedAt(LocalDateTime.now());
            if (row.getUpdatedAt() == null) row.setUpdatedAt(row.getCreatedAt());
            if (stored.stream().noneMatch(existing -> existing.getId().equals(row.getId()))) {
                stored.add(row);
            }
            return row;
        });
        doAnswer(invocation -> {
            SemanticFact imported = invocation.getArgument(0);
            AgentSemanticFactEntity row = new AgentSemanticFactEntity();
            row.setTenantId(imported.getTenantId());
            row.setScopeType(AgentMemoryEntryEntity.ScopeType.USER);
            row.setScopeId(imported.getScope().scopeId());
            row.setSubject(imported.getSubject());
            row.setPredicate(imported.getPredicate());
            row.setObjectValue(imported.getObjectValue());
            row.setSource(imported.getSource());
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(row.getCreatedAt());
            facts.save(row);
            return null;
        }).when(memory).writeFact(any(SemanticFact.class));

        sync = new UserMemoryCloudSync(facts, users, cloud, memory);
    }

    @Test
    void pushesEveryUserFactTypeAndPreservesScopeIsolation() {
        stored.add(fact(17L, "assistant style", "preference", "concise", at("2026-11-15T09:00:00Z")));
        stored.add(fact(18L, "work context", "learned", "owns MiniAgent", at("2026-11-15T10:00:00Z")));
        stored.get(1).setScopeId("18");
        when(cloud.userFacts()).thenAnswer(invocation -> {
            assertEquals(USER_ID, OwnerContext.currentUserId());
            return List.of();
        });
        when(cloud.syncUserFacts(any(UserFactSync.class))).thenReturn(new UserFactSyncResult(List.of()));

        sync.synchronizeUser(USER_ID, TENANT);

        var request = org.mockito.ArgumentCaptor.forClass(UserFactSync.class);
        verify(cloud).syncUserFacts(request.capture());
        assertEquals(List.of("preference"), request.getValue().facts().stream()
                .map(UserFactMutation::predicate).toList());
        assertEquals("assistant style", request.getValue().facts().getFirst().subject());
    }

    @Test
    void replacementExportsSuccessorAsActiveInsteadOfSupersededRowAsDelete() {
        Instant successorAt = at("2026-11-15T10:00:00Z");
        AgentSemanticFactEntity old = fact(1L, "profile", "preference", "old value",
                successorAt.minusMillis(5));
        old.setSupersededBy(2L);
        old.setValidTo(LocalDateTime.ofInstant(successorAt.plusMillis(5), DB_ZONE));
        stored.add(old);
        stored.add(fact(2L, "profile", "preference", "new value", successorAt));
        when(cloud.userFacts()).thenReturn(List.of());

        sync.synchronizeUser(USER_ID, TENANT);

        var request = org.mockito.ArgumentCaptor.forClass(UserFactSync.class);
        verify(cloud).syncUserFacts(request.capture());
        assertEquals(1, request.getValue().facts().size());
        assertEquals("new value", request.getValue().facts().getFirst().objectValue());
        assertFalse(request.getValue().facts().getFirst().deleted());
    }

    @Test
    void deletedCurrentFactIsSyncedAsTombstone() {
        AgentSemanticFactEntity deleted = fact(1L, "assistant style", "preference", "concise",
                at("2026-11-15T09:00:00Z"));
        deleted.setValidTo(LocalDateTime.ofInstant(at("2026-11-15T11:00:00Z"), DB_ZONE));
        stored.add(deleted);
        when(cloud.userFacts()).thenReturn(List.of());

        sync.synchronizeUser(USER_ID, TENANT);

        var request = org.mockito.ArgumentCaptor.forClass(UserFactSync.class);
        verify(cloud).syncUserFacts(request.capture());
        assertTrue(request.getValue().facts().getFirst().deleted());
    }

    @Test
    void pullsPreferencesAndLearnedFactsWithoutChangingTheirSourceTimestampOrEchoingThem() {
        Instant remoteAt = at("2026-11-15T12:34:56Z");
        List<UserFactView> remote = List.of(
                view("assistant style", "preference", "concise", remoteAt),
                view("work context", "learned", "owns MiniAgent", remoteAt.plusSeconds(1)));
        when(cloud.userFacts()).thenReturn(remote);

        sync.synchronizeUser(USER_ID, TENANT);

        assertEquals(2, stored.size());
        assertTrue(stored.stream().allMatch(row -> "CLOUD_SYNC".equals(row.getSource())));
        assertEquals(remoteAt, stored.stream().filter(row -> row.getSubject().equals("assistant style"))
                .findFirst().orElseThrow().getValidFrom().atZone(DB_ZONE).toInstant());
        verify(cloud, never()).syncUserFacts(any(UserFactSync.class));

        sync.synchronizeUser(USER_ID, TENANT);
        verify(cloud, never()).syncUserFacts(any(UserFactSync.class));
    }

    @Test
    void newerCloudVersionWinsAndOlderCloudVersionDoesNotOverwriteLocalFact() {
        Instant localAt = at("2026-11-15T12:00:00Z");
        stored.add(fact(1L, "assistant style", "preference", "local newer", localAt));
        when(cloud.userFacts()).thenReturn(List.of(view("assistant style", "preference",
                "cloud older", localAt.minusSeconds(60))));

        sync.synchronizeUser(USER_ID, TENANT);

        verify(memory, never()).writeFact(any(SemanticFact.class));

        when(cloud.userFacts()).thenReturn(List.of(view("assistant style", "preference",
                "cloud newer", localAt.plusSeconds(60))));
        sync.synchronizeUser(USER_ID, TENANT);

        verify(memory).writeFact(any(SemanticFact.class));
        assertTrue(stored.stream().anyMatch(row -> "cloud newer".equals(row.getObjectValue())
                && "CLOUD_SYNC".equals(row.getSource())));
    }

    @Test
    void failedCloudReadKeepsLocalFactAndNextPassRetriesIt() {
        stored.add(fact(1L, "assistant style", "preference", "concise",
                at("2026-11-15T09:00:00Z")));
        when(cloud.userFacts()).thenThrow(new IllegalStateException("offline"))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> sync.synchronizeUser(USER_ID, TENANT));
        assertEquals("concise", stored.getFirst().getObjectValue());
        sync.synchronizeUser(USER_ID, TENANT);

        verify(cloud).syncUserFacts(any(UserFactSync.class));
    }

    private static AgentSemanticFactEntity fact(long id, String subject, String predicate,
                                                 String objectValue, Instant updatedAt) {
        AgentSemanticFactEntity row = new AgentSemanticFactEntity();
        row.setId(id);
        row.setTenantId(TENANT);
        row.setScopeType(AgentMemoryEntryEntity.ScopeType.USER);
        row.setScopeId(String.valueOf(USER_ID));
        row.setSubject(subject);
        row.setPredicate(predicate);
        row.setObjectValue(objectValue);
        row.setSource("USER");
        row.setCreatedAt(LocalDateTime.ofInstant(updatedAt, DB_ZONE));
        row.setUpdatedAt(LocalDateTime.ofInstant(updatedAt, DB_ZONE));
        return row;
    }

    private static UserFactView view(String subject, String predicate, String value, Instant sourceAt) {
        return new UserFactView(subject, predicate, value, false, 1, sourceAt, sourceAt);
    }

    private static Instant at(String value) {
        return Instant.parse(value);
    }
}
