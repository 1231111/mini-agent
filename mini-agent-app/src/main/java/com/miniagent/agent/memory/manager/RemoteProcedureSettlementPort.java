package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.memory.port.ProcedureDefinitionCommand;
import com.miniagent.memory.port.ProcedureDefinitionPort;
import com.miniagent.memory.port.ProcedureDefinitionResult;
import com.miniagent.memory.port.ProcedureSettlementCommand;
import com.miniagent.memory.port.ProcedureSettlementPort;
import com.miniagent.memory.port.ProcedureSettlementResult;
import com.miniagent.memory.port.ProjectAuthorizationCommand;
import com.miniagent.memory.port.ProjectAuthorizationPort;
import com.miniagent.memory.port.ProjectAuthorizationResult;
import com.miniagent.memory.service.web.EvolutionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Objects;

/** 通过服务间密钥调用独立记忆服务的 Procedure 投影与结算 API。 */
public class RemoteProcedureSettlementPort implements ProcedureSettlementPort, ProcedureDefinitionPort,
        ProjectAuthorizationPort {
    private static final Logger log = LoggerFactory.getLogger(RemoteProcedureSettlementPort.class);
    private static final String INTERNAL_KEY_HEADER = "X-Memory-Internal-Key";

    private final ObjectMapper objectMapper;
    private final ProcedureSettlementModeProperties properties;
    private final RestClient client;

    public RemoteProcedureSettlementPort(ObjectMapper objectMapper,
                                         ProcedureSettlementModeProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        if (properties.baseUrl().isEmpty()) {
            throw new IllegalStateException("agent.memory.service.base-url is required in remote mode");
        }
        if (properties.internalKey().isEmpty()) {
            throw new IllegalStateException("agent.memory.service.internal-key is required in remote mode");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeoutMs());
        factory.setReadTimeout(properties.readTimeoutMs());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public ProcedureSettlementResult settle(ProcedureSettlementCommand command) {
        try {
            byte[] body = client.post()
                    .uri(properties.baseUrl() + "/internal/v1/memory/procedure-settlements")
                    .header(INTERNAL_KEY_HEADER, properties.internalKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RemoteProcedureSettlementRequest(command))
                    .retrieve()
                    .body(byte[].class);
            if (body == null || body.length == 0) {
                throw new IllegalStateException("memory service returned an empty response");
            }
            return objectMapper.readValue(body, RemoteSettlementResponse.class).toResult();
        } catch (Exception e) {
            log.warn("Remote memory settlement failed: procedure={} version={} status={}",
                    command.procedureId(), command.procedureVersion(), e.getClass().getSimpleName());
            throw new RemoteProcedureSettlementException("memory service settlement failed", e);
        }
    }

    @Override
    public ProcedureDefinitionResult synchronize(ProcedureDefinitionCommand command) {
        ProcedureDefinitionSyncRequest request = ProcedureDefinitionSyncRequest.from(command);
        try {
            byte[] body = client.put()
                    .uri(properties.baseUrl() + "/internal/v1/memory/procedures/" + command.procedureId())
                    .header(INTERNAL_KEY_HEADER, properties.internalKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(byte[].class);
            if (body == null || body.length == 0) {
                throw new IllegalStateException("memory service returned an empty response");
            }
            ProcedureDefinitionSyncResponse response =
                    objectMapper.readValue(body, ProcedureDefinitionSyncResponse.class);
            if (response.status() == null) {
                throw new IllegalStateException("memory service response status is missing");
            }
            return new ProcedureDefinitionResult(
                    ProcedureDefinitionResult.Status.valueOf(response.status().name()),
                    response.currentVersion());
        } catch (Exception e) {
            log.warn("Remote Procedure synchronization failed: procedure={} version={} status={}",
                    command.procedureId(), command.procedureVersion(), e.getClass().getSimpleName());
            throw new RemoteProcedureSettlementException("memory service Procedure synchronization failed", e);
        }
    }

    public java.util.List<EvolutionResponse> listCanaryCandidates(String tenantId, long userId) {
        try {
            EvolutionResponse[] responses = client.get()
                    .uri(properties.baseUrl() + "/internal/v1/memory/evolution/proposals?tenantId={tenantId}&userId={userId}",
                            tenantId, userId)
                    .header(INTERNAL_KEY_HEADER, properties.internalKey())
                    .retrieve()
                    .body(EvolutionResponse[].class);
            return responses == null ? java.util.List.of() : java.util.List.of(responses);
        } catch (Exception e) {
            log.warn("Remote Canary candidate listing failed: user={} status={}",
                    userId, e.getClass().getSimpleName());
            throw new RemoteProcedureSettlementException("memory service Canary candidate listing failed", e);
        }
    }

    public EvolutionResponse fetchCanaryCandidate(String tenantId, long userId, long procedureId,
                                                 long proposalId, String candidateHash) {
        try {
            return client.get()
                    .uri(properties.baseUrl() + "/internal/v1/memory/evolution/proposals/" + proposalId
                            + "/candidate?tenantId={tenantId}&userId={userId}&procedureId={procedureId}&candidateHash={candidateHash}",
                            tenantId, userId, procedureId, candidateHash)
                    .header(INTERNAL_KEY_HEADER, properties.internalKey())
                    .retrieve()
                    .body(EvolutionResponse.class);
        } catch (Exception e) {
            log.warn("Remote Canary candidate fetch failed: proposal={} procedure={} status={}",
                    proposalId, procedureId, e.getClass().getSimpleName());
            throw new RemoteProcedureSettlementException("memory service Canary candidate fetch failed", e);
        }
    }

    public void submitFailureEvidence(FailureEvidenceRequest request) {
        try {
            byte[] body = client.post()
                    .uri(properties.baseUrl() + "/internal/v1/memory/evolution/proposals/failure-evidence")
                    .header(INTERNAL_KEY_HEADER, properties.internalKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(byte[].class);
            if (body == null || body.length == 0) {
                throw new IllegalStateException("memory service returned an empty failure-evidence response");
            }
        } catch (Exception e) {
            log.warn("Remote failure evidence submission failed: procedure={} version={} status={}",
                    request.procedureId(), request.procedureVersion(), e.getClass().getSimpleName());
            throw new RemoteProcedureSettlementException("memory service failure evidence submission failed", e);
        }
    }

    public record FailureEvidenceRequest(String tenantId, Long userId, Long procedureId, Integer procedureVersion,
                                         String sessionId, Long taskId, String nodeId, String failureCode,
                                         String taskSummary, String resolution) { }

    @Override
    public ProjectAuthorizationResult synchronize(ProjectAuthorizationCommand command) {
        ProjectAuthorizationSyncRequest request = ProjectAuthorizationSyncRequest.from(command);
        try {
            byte[] body = client.put()
                    .uri(properties.baseUrl() + "/internal/v1/memory/projects/"
                            + command.projectId() + "/authorization")
                    .header(INTERNAL_KEY_HEADER, properties.internalKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(byte[].class);
            if (body == null || body.length == 0) {
                throw new IllegalStateException("memory service returned an empty authorization response");
            }
            ProjectAuthorizationSyncResponse response =
                    objectMapper.readValue(body, ProjectAuthorizationSyncResponse.class);
            if (response.status() == null) throw new IllegalStateException("authorization status is missing");
            return new ProjectAuthorizationResult(
                    ProjectAuthorizationResult.Status.valueOf(response.status().name()), response.currentVersion());
        } catch (Exception e) {
            log.warn("Remote project authorization synchronization failed: project={} version={} status={}",
                    command.projectId(), command.version(), e.getClass().getSimpleName());
            throw new RemoteProcedureSettlementException("memory service authorization synchronization failed", e);
        }
    }

    public record ProjectAuthorizationSyncRequest(String tenantId, String projectId, Long ownerUserId,
                                                  java.util.List<Long> memberUserIds, Long version) {
        static ProjectAuthorizationSyncRequest from(ProjectAuthorizationCommand command) {
            return new ProjectAuthorizationSyncRequest(command.tenantId(), command.projectId(),
                    command.ownerUserId(), command.memberUserIds(), command.version());
        }
    }

    public record ProjectAuthorizationSyncResponse(Status status, Long currentVersion) {
        public enum Status { CREATED, UPDATED, UNCHANGED, STALE, CONFLICT }
    }

    /** 与服务端 {@code ProcedureDefinitionRequest} 一一对应。 */
    public record ProcedureDefinitionSyncRequest(String tenantId, Long userId, Long procedureId,
                                                 Integer procedureVersion, String scopeType, String scopeId,
                                                 String name, String description,
                                                 String preconditionsJson, String stepsJson,
                                                 String successConditionsJson, Double importance, String status) {

        static ProcedureDefinitionSyncRequest from(ProcedureDefinitionCommand command) {
            return new ProcedureDefinitionSyncRequest(
                    command.principal().tenantId(),
                    command.principal().userId(),
                    command.procedureId(),
                    command.procedureVersion(),
                    command.scopeType(),
                    command.scopeId(),
                    command.name(),
                    command.description(),
                    command.preconditionsJson(),
                    command.stepsJson(),
                    command.successConditionsJson(),
                    command.importance(),
                    command.status());
        }
    }

    public record ProcedureDefinitionSyncResponse(Status status, Integer currentVersion) {
        public enum Status { CREATED, UPDATED, UNCHANGED, STALE, CONFLICT }
    }

    public record RemoteProcedureSettlementRequest(String tenantId, Long userId, String projectId,
                                                   String sessionId, long taskId, String nodeId,
                                                   String actionId, long procedureId, int procedureVersion,
                                                   ProcedureSettlementCommand.Outcome outcome,
                                                   Long evolutionProposalId, String evolutionCandidateHash,
                                                   String procedureVariant) {
        static RemoteProcedureSettlementRequest from(ProcedureSettlementCommand command) {
            return new RemoteProcedureSettlementRequest(command.principal().tenantId(),
                    command.principal().userId(), command.principal().projectId(), command.principal().sessionId(),
                    command.taskId(), command.nodeId(), command.actionId(), command.procedureId(),
                    command.procedureVersion(), command.outcome(), command.evolutionProposalId(),
                    command.evolutionCandidateHash(), command.procedureVariant());
        }

        RemoteProcedureSettlementRequest(ProcedureSettlementCommand command) {
            this(command.principal().tenantId(), command.principal().userId(), command.principal().projectId(),
                    command.principal().sessionId(), command.taskId(), command.nodeId(), command.actionId(),
                    command.procedureId(), command.procedureVersion(), command.outcome(),
                    command.evolutionProposalId(), command.evolutionCandidateHash(), command.procedureVariant());
        }
    }

    public record RemoteSettlementResponse(ProcedureSettlementResult.Status status) {
        ProcedureSettlementResult toResult() {
            if (status == null) {
                throw new IllegalStateException("memory service response status is missing");
            }
            return new ProcedureSettlementResult(status);
        }
    }

    public static class RemoteProcedureSettlementException extends RuntimeException {
        public RemoteProcedureSettlementException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
