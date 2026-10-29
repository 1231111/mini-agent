package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentEpisodeEntity;
import com.miniagent.agent.memory.entity.ExperienceClusterEntity;
import com.miniagent.agent.memory.entity.ExperienceClusterMemberEntity;
import com.miniagent.agent.memory.repository.AgentEpisodeRepository;
import com.miniagent.agent.memory.repository.ExperienceClusterMemberRepository;
import com.miniagent.agent.memory.repository.ExperienceClusterRepository;
import com.miniagent.agent.memory.service.AgentProjectAuthorizationService;
import com.miniagent.common.context.OwnerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:experience-clusters;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ExperienceClusterService.class)
class ExperienceClusterPersistenceTest {
    @SpyBean private ExperienceClusterRepository clusters;
    @Autowired private ExperienceClusterMemberRepository members;
    @Autowired private AgentEpisodeRepository episodes;
    @Autowired private ExperienceClusterService clusterService;
    @MockBean private AgentProjectAuthorizationService projectAuthorization;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearRows() {
        members.deleteAll();
        clusters.deleteAll();
        episodes.deleteAll();
    }

    @AfterEach
    void clearOwner() { OwnerContext.clearCurrentTenant(); OwnerContext.clearCurrentUser(); }

    @Test
    void mergeMovesAllSourceEpisodesAndMarksSourceMerged() {
        ExperienceClusterEntity source = saveCluster("source");
        ExperienceClusterEntity target = saveCluster("target");
        AgentEpisodeEntity first = saveEpisode("episode-first", 1L);
        AgentEpisodeEntity moving = saveEpisode("episode-moving", 2L);
        saveMember(source, first);
        saveMember(source, moving);

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "tenant-a"))) {
            assertTrue(clusterService.merge(source.getId(), target.getId(), 1, 1));
        }

        assertEquals("MERGED", clusters.findById(source.getId()).orElseThrow().getStatus());
        assertEquals(3, clusters.findById(source.getId()).orElseThrow().getVersion());
        List<ExperienceClusterMemberEntity> targetMembers = members
                .findByTenantIdAndClusterId("tenant-a", target.getId());
        assertEquals(2, targetMembers.size());
        assertEquals(2, members.findAll().size(), "tenant/episode unique identity leaves exactly one row per Episode");
        assertEquals(2, clusters.findById(target.getId()).orElseThrow().getVersion());
    }

    @Test
    void staleSourceVersionDoesNotChangeAnyClusterOrMember() {
        ExperienceClusterEntity source = saveCluster("source-stale");
        ExperienceClusterEntity target = saveCluster("target-stale");
        AgentEpisodeEntity episode = saveEpisode("episode-stale", 3L);
        saveMember(source, episode);

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "tenant-a"))) {
            assertFalse(clusterService.merge(source.getId(), target.getId(), 99, 1));
        }
        assertEquals(1, clusters.findById(source.getId()).orElseThrow().getVersion());
        assertEquals(1, clusters.findById(target.getId()).orElseThrow().getVersion());
        assertEquals(source.getId(), members.findAll().get(0).getClusterId());
    }

    @Test
    void mergeRollsBackSourceCasWhenTargetCasConflicts() {
        ExperienceClusterEntity source = saveCluster("source-conflict");
        ExperienceClusterEntity target = saveCluster("target-conflict");
        AgentEpisodeEntity episode = saveEpisode("episode-conflict", 4L);
        saveMember(source, episode);

        doReturn(0).when(clusters).updateIfVersion(
                eq(target.getId()), eq("tenant-a"), eq(9L), eq(1), eq(2), eq("target-conflict"));

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "tenant-a"))) {
            assertThrows(IllegalStateException.class,
                    () -> clusterService.merge(source.getId(), target.getId(), 1, 1));
        }

        assertEquals(1, clusters.findById(source.getId()).orElseThrow().getVersion());
        assertEquals(1, clusters.findById(target.getId()).orElseThrow().getVersion());
        assertEquals(source.getId(), members.findAll().get(0).getClusterId());
        verify(clusters).updateIfVersion(source.getId(), "tenant-a", 9L, 1, 2, "source-conflict");
    }

    @Test
    void splitMovesSelectedEpisodesAndRejectsNonMembersWithoutMutation() {
        ExperienceClusterEntity source = saveCluster("source-split");
        ExperienceClusterEntity target = saveCluster("target-split");
        AgentEpisodeEntity selected = saveEpisode("episode-selected", 5L);
        AgentEpisodeEntity retained = saveEpisode("episode-retained", 6L);
        AgentEpisodeEntity outsider = saveEpisode("episode-outsider", 7L);
        saveMember(source, selected);
        saveMember(source, retained);

        try (var ignored = OwnerContext.bind(new OwnerContext(9L, "tenant-a"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> clusterService.split(source.getId(), target.getId(), List.of(outsider.getId()), 1, 1));
            assertTrue(clusterService.split(source.getId(), target.getId(), List.of(selected.getId()), 1, 1));
        }

        assertEquals(2, clusters.findById(source.getId()).orElseThrow().getVersion());
        assertEquals(2, clusters.findById(target.getId()).orElseThrow().getVersion());
        assertEquals(target.getId(), members.findAll().stream()
                .filter(member -> member.getEpisodeId().equals(selected.getId())).findFirst().orElseThrow().getClusterId());
        assertEquals(source.getId(), members.findAll().stream()
                .filter(member -> member.getEpisodeId().equals(retained.getId())).findFirst().orElseThrow().getClusterId());
        assertEquals(2, members.findAll().size());
    }

    private ExperienceClusterEntity saveCluster(String key) {
        ExperienceClusterEntity cluster = new ExperienceClusterEntity();
        cluster.setTenantId("tenant-a"); cluster.setOwnerUserId(9L);
        cluster.setScopeType("USER"); cluster.setScopeId("9");
        cluster.setClusterKey(key); cluster.setLabel(key); cluster.setVersion(1); cluster.setStatus("ACTIVE");
        return new TransactionTemplate(transactionManager).execute(status -> clusters.saveAndFlush(cluster));
    }

    private AgentEpisodeEntity saveEpisode(String task, Long idSeed) {
        AgentEpisodeEntity episode = new AgentEpisodeEntity();
        episode.setTenantId("tenant-a"); episode.setUserId("9"); episode.setSessionId("session-" + idSeed);
        episode.setTaskId(task); episode.setTaskSummary(task); episode.setOutcome(AgentEpisodeEntity.Outcome.SUCCESS);
        episode.setActionsJson("[]");
        return new TransactionTemplate(transactionManager).execute(status -> episodes.saveAndFlush(episode));
    }

    private void saveMember(ExperienceClusterEntity cluster, AgentEpisodeEntity episode) {
        ExperienceClusterMemberEntity member = new ExperienceClusterMemberEntity();
        member.setTenantId("tenant-a"); member.setClusterId(cluster.getId()); member.setEpisodeId(episode.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> members.saveAndFlush(member));
    }
}
