package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.AgentEpisodeEntity;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureApplicationEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.entity.ProcedureRewriteProposalEntity;
import com.miniagent.agent.memory.repository.AgentProcedureApplicationRepository;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.agent.memory.repository.ProcedureRewriteProposalRepository;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.SecurityScanner;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@Service
public class ProcedureRewriteProposalService {
    private final AgentProcedureApplicationRepository applications;
    private final AgentProcedureRepository procedures;
    private final ProcedureRewriteProposalRepository proposals;
    private final ProcedureGovernanceProperties governanceProperties;
    private final ProcedureRewriteProposalWriteService proposalWrite;
    private final ProcedureRevisionService revisionService;
    private final ObjectMapper mapper;

    public ProcedureRewriteProposalService(AgentProcedureApplicationRepository applications,
                                           AgentProcedureRepository procedures,
                                           ProcedureRewriteProposalRepository proposals,
                                           ProcedureGovernanceProperties governanceProperties,
                                           ProcedureRewriteProposalWriteService proposalWrite,
                                           ProcedureRevisionService revisionService,
                                           ObjectMapper mapper) {
        this.applications = applications;
        this.procedures = procedures;
        this.proposals = proposals;
        this.governanceProperties = governanceProperties;
        this.proposalWrite = proposalWrite;
        this.revisionService = revisionService;
        this.mapper = mapper;
    }

    /** Only a unique failure application within the episode's task can produce a proposal. */
    public int proposeFromFailureEpisode(AgentEpisodeEntity episode) {
        if (episode == null || episode.getOutcome() != AgentEpisodeEntity.Outcome.FAILURE
                || episode.getId() == null || episode.getSessionId() == null
                || episode.getTaskId() == null || episode.getUserId() == null) {
            return 0;
        }
        Long userId;
        try {
            userId = Long.valueOf(episode.getUserId());
        } catch (NumberFormatException ignored) {
            return 0;
        }
        if (episode.getResolution() == null || episode.getResolution().isBlank()
                || SecurityScanner.scan(episode.getResolution()) != null) {
            return 0;
        }
        final long taskId;
        try {
            taskId = Long.parseLong(episode.getTaskId());
        } catch (NumberFormatException ignored) {
            return 0;
        }
        List<AgentProcedureApplicationEntity> failed = applications
                .findByTenantIdAndSessionIdAndTaskIdAndUserIdAndResult(episode.getTenantId(),
                        episode.getSessionId(), taskId, userId,
                        AgentProcedureApplicationEntity.Result.FAILURE);
        if (failed.size() != 1) {
            return 0;
        }
        AgentProcedureApplicationEntity application = failed.get(0);
        if (!episode.getTenantId().equals(application.getTenantId())
                || !userId.equals(application.getUserId())) {
            return 0;
        }
        AgentProcedureEntity measured = procedures.findById(application.getProcedureId()).orElse(null);
        if (!governanceProperties.isEnabled() || measured == null
                || measured.getAttemptCount() < governanceProperties.getMinAttempts()
                || measured.getSuccessRate() == null
                || measured.getSuccessRate() > governanceProperties.getArchiveMaxSuccessRate()) {
            return 0;
        }
        AgentProcedureEntity procedure = measured;
        if (!episode.getTenantId().equals(procedure.getTenantId())
                || !episode.getTenantId().equals(application.getTenantId())
                || !userId.equals(application.getUserId())
                || procedure.getScopeType() != AgentMemoryEntryEntity.ScopeType.USER
                || !String.valueOf(userId).equals(procedure.getScopeId())
                || !application.getProcedureVersion().equals(procedure.getProcedureVersion())) {
            return 0;
        }
        String content = episode.getResolution().trim();
        if (SecurityScanner.REDACTED.equals(content)) {
            return 0;
        }
        if (content.length() > 4000) {
            return 0;
        }
        if (!isSafeProcedure(procedure) || SecurityScanner.scan(content) != null) {
            return 0;
        }
        try {
            ProcedureRewriteProposalEntity proposal = new ProcedureRewriteProposalEntity();
            proposal.setTenantId(episode.getTenantId());
            proposal.setUserId(userId);
            proposal.setProcedureId(procedure.getId());
            proposal.setBaseVersion(procedure.getProcedureVersion());
            proposal.setEpisodeId(episode.getId());
            proposal.setProcedureName(procedure.getName());
            proposal.setBaseContentJson(mapper.writeValueAsString(snapshot(procedure)));
            proposal.setProposedContentJson(mapper.writeValueAsString(candidate(procedure, content)));
            proposal.setEvidenceJson(mapper.writeValueAsString(Map.of(
                    "episodeId", episode.getId(), "sessionId", episode.getSessionId(),
                    "taskId", episode.getTaskId(), "outcome", episode.getOutcome().name(),
                    "resolution", content, "applicationId", application.getId(),
                    "nodeId", application.getNodeId(), "actionId", application.getActionId())));
            return proposalWrite.insertIfAbsent(proposal) ? 1 : 0;
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to persist Procedure rewrite proposal", failure);
        }
    }

    @Transactional(readOnly = true)
    public List<ProcedureRewriteProposalEntity> listMine() {
        OwnerContext owner = OwnerContext.capture();
        requireOwner(owner);
        return proposals.findByTenantIdAndUserIdOrderByCreatedAtDesc(owner.tenantId(), owner.userId());
    }

    @Transactional
    public ProcedureRewriteProposalEntity review(Long id, boolean approve) {
        OwnerContext owner = OwnerContext.capture();
        requireOwner(owner);
        ProcedureRewriteProposalEntity proposal = proposals
                .findForReview(id, owner.tenantId(), owner.userId())
                .orElseThrow(() -> new AccessDeniedException("Proposal is not accessible"));
        if (proposal.getStatus() != ProcedureRewriteProposalEntity.Status.PENDING) {
            throw new IllegalStateException("Only pending proposals can be reviewed");
        }
        if (!approve) {
            proposal.setStatus(ProcedureRewriteProposalEntity.Status.REJECTED);
            proposal.setReviewedBy(String.valueOf(owner.userId()));
            proposal.setReviewedAt(LocalDateTime.now());
            return proposals.save(proposal);
        }

        AgentProcedureEntity current = procedures.findById(proposal.getProcedureId()).orElse(null);
        if (current == null || !owner.tenantId().equals(current.getTenantId())
                || current.getScopeType() != AgentMemoryEntryEntity.ScopeType.USER
                || !String.valueOf(owner.userId()).equals(current.getScopeId())
                || !proposal.getBaseVersion().equals(current.getProcedureVersion())
                || current.getStatus() != AgentMemoryEntryEntity.Status.ACTIVE) {
            proposal.setStatus(ProcedureRewriteProposalEntity.Status.CONFLICT);
            proposal.setReviewedBy(String.valueOf(owner.userId()));
            proposal.setReviewedAt(LocalDateTime.now());
            return proposals.save(proposal);
        }
        try {
            Map<String, Object> candidate = mapper.readValue(proposal.getProposedContentJson(),
                    new TypeReference<>() {});
            if (!isSafeCandidate(candidate)) {
                throw new AccessDeniedException("Proposal content failed the security scan");
            }
            int expectedVersion = proposal.getBaseVersion();
            if (SecurityScanner.scan(proposal.getEvidenceJson()) != null
                    || SecurityScanner.scan(proposal.getProposedContentJson()) != null) {
                throw new AccessDeniedException("Proposal evidence failed the security scan");
            }
            revisionService.capture(current, "PROPOSAL_APPROVAL", String.valueOf(proposal.getId()), owner.userId());
            int changed = procedures.upgradeContent(current.getId(), owner.tenantId(),
                    expectedVersion, expectedVersion + 1, proposal.getProcedureName(),
                    (String) candidate.get("description"), writeList(candidate.get("preconditions")),
                    writeList(candidate.get("steps")), writeList(candidate.get("successConditions")),
                    current.getImportance() == null ? 0.5 : current.getImportance(),
                    AgentMemoryEntryEntity.Status.ACTIVE);
            if (changed != 1) {
                proposal.setStatus(ProcedureRewriteProposalEntity.Status.CONFLICT);
                proposal.setReviewedBy(String.valueOf(owner.userId()));
                proposal.setReviewedAt(LocalDateTime.now());
                return proposals.save(proposal);
            }
            proposal.setStatus(ProcedureRewriteProposalEntity.Status.APPROVED);
            proposal.setReviewedBy(String.valueOf(owner.userId()));
            proposal.setReviewedAt(LocalDateTime.now());
            return proposals.save(proposal);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to apply approved Procedure proposal", failure);
        }
    }

    private Map<String, Object> snapshot(AgentProcedureEntity procedure) {
        return Map.of("name", procedure.getName(), "description",
                procedure.getDescription() == null ? "" : procedure.getDescription(),
                "preconditions", readList(procedure.getPreconditionsJson()),
                "steps", readList(procedure.getStepsJson()),
                "successConditions", readList(procedure.getSuccessConditionsJson()));
    }

    private Map<String, Object> candidate(AgentProcedureEntity procedure, String resolution) {
        List<String> steps = readList(procedure.getStepsJson());
        if (steps.stream().noneMatch(step -> step.equals(resolution))) {
            steps = new ArrayList<>(steps);
            steps.add(resolution);
        }
        return Map.of("description", procedure.getDescription() == null ? "" : procedure.getDescription(),
                "preconditions", readList(procedure.getPreconditionsJson()), "steps", steps,
                "successConditions", readList(procedure.getSuccessConditionsJson()));
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private boolean isSafeProcedure(AgentProcedureEntity procedure) {
        return safe(procedure.getName()) && safe(procedure.getDescription())
                && readList(procedure.getPreconditionsJson()).stream().allMatch(this::safe)
                && readList(procedure.getStepsJson()).stream().allMatch(this::safe)
                && readList(procedure.getSuccessConditionsJson()).stream().allMatch(this::safe);
    }

    private boolean isSafeCandidate(Map<String, Object> candidate) {
        Object description = candidate.get("description");
        return (!(description instanceof String text) || safe(text))
                && strings(candidate.get("preconditions")).stream().allMatch(this::safe)
                && strings(candidate.get("steps")).stream().allMatch(this::safe)
                && strings(candidate.get("successConditions")).stream().allMatch(this::safe);
    }

    private boolean safe(String value) {
        return value == null || SecurityScanner.scan(value) == null;
    }

    private String writeList(Object value) throws Exception {
        return mapper.writeValueAsString(strings(value));
    }

    private List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private void requireOwner(OwnerContext owner) {
        if (owner.userId() == null || owner.tenantId() == null || owner.tenantId().isBlank()) {
            throw new AccessDeniedException("Authenticated owner required");
        }
    }
}
