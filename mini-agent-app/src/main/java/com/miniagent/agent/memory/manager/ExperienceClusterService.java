package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentEpisodeEntity;
import com.miniagent.agent.memory.entity.ExperienceClusterEntity;
import com.miniagent.agent.memory.entity.ExperienceClusterMemberEntity;
import com.miniagent.agent.memory.repository.AgentEpisodeRepository;
import com.miniagent.agent.memory.repository.ExperienceClusterMemberRepository;
import com.miniagent.agent.memory.repository.ExperienceClusterRepository;
import com.miniagent.common.context.OwnerContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ExperienceClusterService {
    private final ExperienceClusterRepository clusters;
    private final ExperienceClusterMemberRepository members;
    private final AgentEpisodeRepository episodes;
    private final com.miniagent.agent.memory.service.AgentProjectAuthorizationService projectAuthorization;

    public ExperienceClusterService(ExperienceClusterRepository clusters,
                                    ExperienceClusterMemberRepository members,
                                    AgentEpisodeRepository episodes,
                                    com.miniagent.agent.memory.service.AgentProjectAuthorizationService projectAuthorization) {
        this.clusters = clusters; this.members = members; this.episodes = episodes;
        this.projectAuthorization = projectAuthorization;
    }

    @Transactional
    public ExperienceClusterEntity create(String key, String label, String scopeType, String scopeId) {
        OwnerContext owner = OwnerContext.capture(); requireOwner(owner);
        if (!List.of("USER", "PROJECT").contains(scopeType)) throw new IllegalArgumentException("unsupported scope");
        if ("USER".equals(scopeType) && !String.valueOf(owner.userId()).equals(scopeId)) throw new SecurityException("scope denied");
        if ("PROJECT".equals(scopeType)) {
            if (!scopeId.equals(OwnerContext.currentProjectId())) throw new SecurityException("trusted project context required");
            projectAuthorization.requireMember(scopeId, owner.userId(), owner.tenantId());
        }
        ExperienceClusterEntity c = new ExperienceClusterEntity();
        c.setTenantId(owner.tenantId()); c.setOwnerUserId(owner.userId()); c.setScopeType(scopeType);
        c.setScopeId(scopeId); c.setClusterKey(key); c.setLabel(label); c.setVersion(1);
        return clusters.saveAndFlush(c);
    }

    @Transactional
    public boolean addEpisode(Long clusterId, Long episodeId, int expectedVersion) {
        OwnerContext owner = OwnerContext.capture(); requireOwner(owner);
        ExperienceClusterEntity c = requireCluster(clusterId, owner);
        AgentEpisodeEntity episode = episodes.findByIdAndTenantIdAndUserId(
                episodeId, owner.tenantId(), String.valueOf(owner.userId())).orElseThrow();
        if ("PROJECT".equals(c.getScopeType()) && !c.getScopeId().equals(episode.getProjectId()))
            throw new SecurityException("episode scope mismatch");
        if ("PROJECT".equals(c.getScopeType()))
            projectAuthorization.requireMember(c.getScopeId(), owner.userId(), owner.tenantId());
        if ("USER".equals(c.getScopeType()) && episode.getProjectId() != null && !episode.getProjectId().isBlank())
            throw new SecurityException("project episode cannot join user cluster");
        if (!"ACTIVE".equals(c.getStatus())) return false;
        if ("PROJECT".equals(c.getScopeType())) {
            if (!c.getScopeId().equals(OwnerContext.currentProjectId()))
                throw new SecurityException("trusted project context required");
            projectAuthorization.requireMember(c.getScopeId(), owner.userId(), owner.tenantId());
        }
        if (!expectedVersionEquals(c, expectedVersion)) return false;
        if (clusters.updateIfVersion(clusterId, owner.tenantId(), owner.userId(), expectedVersion,
                expectedVersion + 1, c.getLabel()) != 1) return false;
        ExperienceClusterMemberEntity member = new ExperienceClusterMemberEntity();
        member.setTenantId(owner.tenantId()); member.setClusterId(clusterId); member.setEpisodeId(episodeId);
        try {
            members.saveAndFlush(member);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalStateException("episode already belongs to a cluster", duplicate);
        }
    }

    @Transactional
    public boolean rename(Long clusterId, String label, int expectedVersion) {
        OwnerContext owner = OwnerContext.capture(); requireOwner(owner);
        return clusters.updateIfVersion(clusterId, owner.tenantId(), owner.userId(), expectedVersion,
                expectedVersion + 1, label) == 1;
    }

    @Transactional
    public boolean merge(Long sourceId, Long targetId, int sourceVersion, int targetVersion) {
        if (sourceId.equals(targetId)) throw new IllegalArgumentException("source and target must differ");
        OwnerContext owner = OwnerContext.capture(); requireOwner(owner);
        ExperienceClusterEntity source = requireCluster(sourceId, owner);
        ExperienceClusterEntity target = requireCluster(targetId, owner);
        if (!source.getScopeType().equals(target.getScopeType()) || !source.getScopeId().equals(target.getScopeId()))
            throw new SecurityException("cluster scopes differ");
        if (!"ACTIVE".equals(source.getStatus()) || !"ACTIVE".equals(target.getStatus())) return false;
        if ("PROJECT".equals(source.getScopeType())) {
            if (!source.getScopeId().equals(OwnerContext.currentProjectId()))
                throw new SecurityException("trusted project context required");
            projectAuthorization.requireMember(source.getScopeId(), owner.userId(), owner.tenantId());
        }
        if (!expectedVersionEquals(source, sourceVersion) || !expectedVersionEquals(target, targetVersion)) return false;
        if (clusters.updateIfVersion(sourceId, owner.tenantId(), owner.userId(), sourceVersion,
                sourceVersion + 1, source.getLabel()) != 1) return false;
        if (clusters.updateIfVersion(targetId, owner.tenantId(), owner.userId(), targetVersion,
                targetVersion + 1, target.getLabel()) != 1)
            throw new IllegalStateException("cluster merge CAS conflict");
        List<ExperienceClusterMemberEntity> moving = members.findByTenantIdAndClusterId(owner.tenantId(), sourceId);
        List<Long> targetEpisodes = members.findByTenantIdAndClusterId(owner.tenantId(), targetId)
                .stream().map(ExperienceClusterMemberEntity::getEpisodeId).toList();
        moving.stream().filter(member -> !targetEpisodes.contains(member.getEpisodeId()))
                .forEach(member -> member.setClusterId(targetId));
        moving.stream().filter(member -> targetEpisodes.contains(member.getEpisodeId()))
                .forEach(members::delete);
        members.saveAllAndFlush(moving.stream()
                .filter(member -> !targetEpisodes.contains(member.getEpisodeId())).toList());
        if (clusters.transitionIfVersion(sourceId, owner.tenantId(), owner.userId(), sourceVersion + 1,
                sourceVersion + 2, "MERGED") != 1)
            throw new IllegalStateException("cluster merge transition conflict");
        return true;
    }

    @Transactional
    public boolean split(Long sourceId, Long targetId, List<Long> episodeIds,
                         int sourceVersion, int targetVersion) {
        if (sourceId.equals(targetId) || episodeIds == null || episodeIds.isEmpty())
            throw new IllegalArgumentException("distinct clusters and episodeIds required");
        OwnerContext owner = OwnerContext.capture(); requireOwner(owner);
        ExperienceClusterEntity source = requireCluster(sourceId, owner);
        ExperienceClusterEntity target = requireCluster(targetId, owner);
        if ("PROJECT".equals(source.getScopeType())) {
            if (!source.getScopeId().equals(OwnerContext.currentProjectId()))
                throw new SecurityException("trusted project context required");
            projectAuthorization.requireMember(source.getScopeId(), owner.userId(), owner.tenantId());
        }
        if (!"ACTIVE".equals(source.getStatus()) || !"ACTIVE".equals(target.getStatus())
                || !source.getScopeType().equals(target.getScopeType())
                || !source.getScopeId().equals(target.getScopeId()))
            throw new SecurityException("cluster scopes differ or cluster is inactive");
        if (!expectedVersionEquals(source, sourceVersion) || !expectedVersionEquals(target, targetVersion)) return false;
        List<ExperienceClusterMemberEntity> sourceMembers = members.findByTenantIdAndClusterId(owner.tenantId(), sourceId);
        List<Long> requested = episodeIds.stream().distinct().toList();
        if (requested.size() != episodeIds.size() || sourceMembers.stream()
                .filter(m -> requested.contains(m.getEpisodeId())).count() != requested.size())
            throw new IllegalArgumentException("all episodeIds must be unique members of source cluster");
        if (clusters.updateIfVersion(sourceId, owner.tenantId(), owner.userId(), sourceVersion,
                sourceVersion + 1, source.getLabel()) != 1) return false;
        if (clusters.updateIfVersion(targetId, owner.tenantId(), owner.userId(), targetVersion,
                targetVersion + 1, target.getLabel()) != 1)
            throw new IllegalStateException("cluster split CAS conflict");
        sourceMembers.stream().filter(m -> requested.contains(m.getEpisodeId()))
                .forEach(m -> m.setClusterId(targetId));
        members.saveAllAndFlush(sourceMembers);
        return true;
    }

    private ExperienceClusterEntity requireCluster(Long id, OwnerContext owner) {
        ExperienceClusterEntity c = clusters.findById(id).orElseThrow();
        if (!c.getTenantId().equals(owner.tenantId()) || !c.getOwnerUserId().equals(owner.userId()))
            throw new SecurityException("cluster inaccessible");
        return c;
    }
    private boolean expectedVersionEquals(ExperienceClusterEntity c, int expected) {
        return c.getVersion() != null && c.getVersion() == expected;
    }
    private void requireOwner(OwnerContext owner) {
        if (owner.userId() == null || owner.tenantId() == null || owner.tenantId().isBlank())
            throw new SecurityException("owner required");
    }
}
