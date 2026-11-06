package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.common.exception.SystemException;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.cloud.CloudAccountClient;
import com.miniagent.config.cloud.CloudUser;
import com.miniagent.memory.contract.MemoryApi;
import com.miniagent.memory.contract.MemoryApi.AssembleRequest;
import com.miniagent.memory.contract.MemoryApi.AssembleResponse;
import com.miniagent.memory.contract.MemoryApi.AssembledProcedure;
import com.miniagent.memory.contract.MemoryApi.ChangeView;
import com.miniagent.memory.contract.MemoryApi.FeedbackRequest;
import com.miniagent.memory.contract.MemoryApi.ItemImport;
import com.miniagent.memory.contract.MemoryApi.ItemPage;
import com.miniagent.memory.contract.MemoryApi.ItemView;
import com.miniagent.memory.contract.MemoryApi.ItemWrite;
import com.miniagent.memory.contract.MemoryApi.LockRequest;
import com.miniagent.memory.contract.MemoryApi.OutcomeBatch;
import com.miniagent.memory.contract.MemoryApi.OutcomeBatchResult;
import com.miniagent.memory.contract.MemoryApi.OutcomeEvent;
import com.miniagent.memory.contract.MemoryApi.ProposalView;
import com.miniagent.memory.contract.MemoryApi.RevertRequest;
import com.miniagent.memory.contract.MemoryApi.ReviewRequest;
import com.miniagent.memory.contract.MemoryServiceClient;
import com.miniagent.memory.contract.MemoryServiceClient.CallFailed;
import com.miniagent.memory.port.ProjectAuthorizationCommand;
import com.miniagent.memory.port.ProjectAuthorizationPort;
import com.miniagent.memory.port.ProjectAuthorizationResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class CloudMemoryClient implements ProjectAuthorizationPort {
    private static final Logger log = LoggerFactory.getLogger(CloudMemoryClient.class);
    static final String METRIC_FALLBACK = "memory_context_fallback_total";
    private static final String FALLBACK_KEY_PREFIX = "mem:sop:";
    private static final String NO_PROJECT = "-";

    public record Assembled(AssembleResponse response, boolean fallback) { }

    private final MemoryServiceClient reads;
    private final MemoryServiceClient writes;
    private final ProcedureSettlementModeProperties props;
    private final ObjectMapper json;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final MeterRegistry metrics;
    private final UserRepository users;
    private final CloudAccountClient accounts;

    public CloudMemoryClient(ProcedureSettlementModeProperties props, ObjectMapper json,
                             ObjectProvider<StringRedisTemplate> redis, MeterRegistry metrics,
                             UserRepository users, CloudAccountClient accounts) {
        if (props.baseUrl().isEmpty()) {
            throw new IllegalStateException("agent.memory.service.base-url is required in remote mode");
        }
        this.props = props;
        this.json = json;
        this.redis = redis;
        this.metrics = metrics;
        this.users = users;
        this.accounts = accounts;
        this.reads = new MemoryServiceClient(props.baseUrl(),
                Duration.ofMillis(props.contextTimeoutMs()), json);
        this.writes = new MemoryServiceClient(props.baseUrl(),
                Duration.ofMillis(props.readTimeoutMs()), json);
    }

    public Assembled assemble(String taskKey, String query) {
        String cloudTenant = null;
        try {
            String token = memoryToken();
            cloudTenant = cloudTenantId();
            AssembleResponse response = reads.call(HttpMethod.POST, MemoryApi.ASSEMBLE,
                    new AssembleRequest(taskKey, query), token, AssembleResponse.class);
            cacheActive(cloudTenant, response);
            return new Assembled(response, false);
        } catch (RuntimeException e) {
            metrics.counter(METRIC_FALLBACK).increment();
            log.warn("context:assemble 失败，改用降级快照: {}", e.getMessage());
            return new Assembled(new AssembleResponse(cloudTenant == null ? List.of() : cached(cloudTenant, query), false), true);
        }
    }

    public ItemView upsert(ItemWrite write) {
        return write(HttpMethod.POST, MemoryApi.ITEMS, write, ItemView.class);
    }

    public ItemView importItem(ItemImport request) {
        if (!request.tenantId().equals(cloudTenantId())) throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        String externalId = users.findExternalIdById(OwnerContext.currentUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED));
        long cloudUserId;
        try { cloudUserId = Long.parseLong(externalId); }
        catch (NumberFormatException e) { throw new BusinessException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE); }
        return write(HttpMethod.POST, MemoryApi.ITEMS_IMPORT,
                new ItemImport(request.tenantId(), cloudUserId, request.item()), ItemView.class);
    }

    public ItemPage list(String name, Long cursor, int limit) {
        String uri = MemoryApi.ITEMS + "?limit=" + limit
                + (name == null || name.isBlank() ? "" : "&name=" + name)
                + (cursor == null ? "" : "&cursor=" + cursor);
        return write(HttpMethod.GET, uri, null, ItemPage.class);
    }

    public ItemView get(long id) { return write(HttpMethod.GET, MemoryApi.ITEMS + "/" + id, null, ItemView.class); }
    public void delete(long id) { write(HttpMethod.DELETE, MemoryApi.ITEMS + "/" + id, null, Void.class); }
    public ItemView revert(long id, int revisionNo) {
        return write(HttpMethod.POST, MemoryApi.ITEMS + "/" + id + ":revert", new RevertRequest(revisionNo), ItemView.class);
    }
    public ItemView lock(long id, boolean locked) {
        return write(HttpMethod.POST, MemoryApi.ITEMS + "/" + id + ":lock", new LockRequest(locked), ItemView.class);
    }
    public ItemView restore(long id) {
        return write(HttpMethod.POST, MemoryApi.ITEMS + "/" + id + ":restore", null, ItemView.class);
    }
    public List<ChangeView> changes(Instant since) {
        return write(HttpMethod.GET, MemoryApi.CHANGES + "?since=" + since, null,
                json.getTypeFactory().constructCollectionType(List.class, ChangeView.class));
    }
    public List<ProposalView> proposals(String state, int limit) {
        String uri = MemoryApi.PROPOSALS + "?limit=" + limit
                + (state == null || state.isBlank() ? "" : "&state=" + state);
        return write(HttpMethod.GET, uri, null,
                json.getTypeFactory().constructCollectionType(List.class, ProposalView.class));
    }
    public ProposalView approve(long id, String note) {
        return write(HttpMethod.POST, MemoryApi.PROPOSALS + "/" + id + ":approve", new ReviewRequest(note), ProposalView.class);
    }
    public ProposalView reject(long id, String note) {
        return write(HttpMethod.POST, MemoryApi.PROPOSALS + "/" + id + ":reject", new ReviewRequest(note), ProposalView.class);
    }
    public int feedback(String taskKey, boolean positive) {
        Integer n = write(HttpMethod.POST, MemoryApi.FEEDBACK, new FeedbackRequest(taskKey, positive), Integer.class);
        return n == null ? 0 : n;
    }
    public OutcomeBatchResult submitOutcomes(String tenantId, List<OutcomeEvent> events) {
        var grouped = events.stream().collect(java.util.stream.Collectors.groupingBy(
                OutcomeEvent::userId, java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<com.miniagent.memory.contract.MemoryApi.OutcomeAck> acknowledgements = new java.util.ArrayList<>();
        for (var entry : grouped.entrySet()) {
            Long localUserId = entry.getKey();
            String externalId = users.findExternalIdById(localUserId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED));
            String localTenantId = users.findLocalTenantIdById(localUserId)
                    .map(String::valueOf)
                    .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED));
            if (!localTenantId.equals(tenantId) || entry.getValue().stream()
                    .anyMatch(event -> !tenantId.equals(event.tenantId()))) {
                throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
            }
            String cloudTenant = users.findCloudTenantIdById(localUserId)
                    .filter(value -> !value.isBlank())
                    .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED));
            long cloudUserId;
            try { cloudUserId = Long.parseLong(externalId); }
            catch (NumberFormatException e) { throw new BusinessException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE); }
            List<OutcomeEvent> cloudEvents = entry.getValue().stream().map(event -> new OutcomeEvent(
                    event.eventId(), cloudTenant, cloudUserId, event.projectId(), event.itemId(),
                    event.revisionNo(), event.proposalId(), event.arm(), event.trafficPercent(),
                    event.taskKey(), event.nodeKey(), event.result(), event.errorCode(),
                    event.failureReason(), event.userMessage(), event.nodeName(), event.envVersion(),
                    event.occurredAt())).toList();
            OutcomeBatchResult result = callWithRefresh(localUserId, HttpMethod.POST, MemoryApi.OUTCOMES,
                    new OutcomeBatch(cloudEvents), OutcomeBatchResult.class);
            if (result != null && result.results() != null) acknowledgements.addAll(result.results());
        }
        return new OutcomeBatchResult(acknowledgements);
    }
    @Override
    public ProjectAuthorizationResult synchronize(ProjectAuthorizationCommand command) {
        String externalOwnerId = users.findExternalIdById(command.ownerUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED));
        String localTenantId = users.findLocalTenantIdById(command.ownerUserId())
                .map(String::valueOf)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED));
        if (!command.tenantId().equals(localTenantId)) throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        String cloudTenant = users.findCloudTenantIdById(command.ownerUserId())
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED));
        long cloudOwnerId = parseCloudUserId(externalOwnerId);
        List<Long> cloudMembers = command.memberUserIds().stream()
                .map(localId -> users.findById(localId)
                        .filter(com.miniagent.config.entity.User::isEnabled)
                        .filter(member -> java.util.Objects.equals(member.getTenantId(),
                                Long.valueOf(localTenantId)))
                        .filter(member -> java.util.Objects.equals(member.getCloudTenantId(), cloudTenant))
                        .map(com.miniagent.config.entity.User::getExternalId)
                        .filter(value -> value != null && !value.isBlank())
                        .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_FORBIDDEN,
                                "项目成员必须是同一有效租户中的云端账号")))
                .map(CloudMemoryClient::parseCloudUserId)
                .toList();
        ProjectAuthorizationCommand cloudCommand = new ProjectAuthorizationCommand(
                cloudTenant, command.projectId(), cloudOwnerId, cloudMembers, command.version());
        try {
            return callWithRefresh(command.ownerUserId(), HttpMethod.PUT,
                    MemoryApi.PROJECTS + "/" + command.projectId() + "/authorization",
                    cloudCommand, ProjectAuthorizationResult.class);
        } catch (CallFailed e) {
            throw translate(e);
        }
    }

    private static long parseCloudUserId(String externalId) {
        try { return Long.parseLong(externalId); }
        catch (NumberFormatException e) { throw new BusinessException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE); }
    }

    public String envVersion() { return props.envVersion().isBlank() ? null : props.envVersion(); }

    private String memoryToken() {
        Long localUserId = OwnerContext.currentUserId();
        if (localUserId == null) throw new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED);
        return users.findMemoryAccessTokenById(localUserId)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED,
                        "当前本地账号没有有效的云端记忆凭证"));
    }

    private <T> T callWithRefresh(Long localUserId, HttpMethod method, String uri,
                                  Object body, Class<T> responseType) {
        String token = users.findMemoryAccessTokenById(localUserId)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED));
        try {
            return writes.call(method, uri, body, token, responseType);
        } catch (CallFailed failure) {
            if (!isAuthenticationFailure(failure)) throw translate(failure);
        }
        String refreshCredential = users.findMemoryRefreshCredentialById(localUserId)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED,
                        "云端记忆凭证已过期，且本地没有续期凭证"));
        CloudUser refreshed = accounts.refreshMemoryCredential(refreshCredential);
        int updated = users.updateMemoryCredentialsIfUnchanged(localUserId, refreshCredential,
                refreshed.memoryToken(), refreshed.memoryRefreshCredential());
        String accessToken = updated == 1 ? refreshed.memoryToken()
                : users.findMemoryAccessTokenById(localUserId)
                .filter(value -> !value.isBlank()).orElse(refreshed.memoryToken());
        try {
            return writes.call(method, uri, body, accessToken, responseType);
        } catch (CallFailed retryFailure) {
            throw translate(retryFailure);
        }
    }

    private static boolean isAuthenticationFailure(CallFailed failure) {
        return failure.httpStatus() == 401 || failure.httpStatus() == 403;
    }

    private String cloudTenantId() {
        Long localUserId = OwnerContext.currentUserId();
        if (localUserId == null) throw new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED);
        return users.findCloudTenantIdById(localUserId)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED,
                        "当前本地账号未绑定云端租户"));
    }

    private <T> T write(HttpMethod method, String uri, Object body, Class<T> type) {
        try {
            return writes.call(method, uri, body, memoryToken(), type);
        } catch (CallFailed e) { throw translate(e); }
    }
    private <T> T write(HttpMethod method, String uri, Object body,
                        com.fasterxml.jackson.databind.JavaType type) {
        try {
            return writes.call(method, uri, body, memoryToken(), type);
        } catch (CallFailed e) { throw translate(e); }
    }
    private static RuntimeException translate(CallFailed e) {
        ErrorCode code = ErrorCode.ofCode(e.code());
        return code != null ? new BusinessException(code, e.getMessage(), e)
                : new SystemException(ErrorCode.MEMORY_WRITE_FAILED, e.getMessage(), e);
    }
    private void cacheActive(String tenant, AssembleResponse response) {
        StringRedisTemplate store = redis.getIfAvailable();
        if (store == null || response == null || response.holdout() || response.procedures() == null) return;
        List<AssembledProcedure> plain = response.procedures().stream()
                .filter(p -> p != null && p.proposalId() == null).toList();
        try { store.opsForValue().set(fallbackKey(tenant), json.writeValueAsString(plain), Duration.ofHours(props.fallbackTtlHours())); }
        catch (RuntimeException | JsonProcessingException e) { log.warn("刷新 SOP 降级快照失败: {}", e.getMessage()); }
    }
    private List<AssembledProcedure> cached(String tenant, String query) {
        StringRedisTemplate store = redis.getIfAvailable();
        if (store == null) return List.of();
        try {
            String raw = store.opsForValue().get(fallbackKey(tenant));
            if (raw == null || raw.isBlank()) return List.of();
            List<AssembledProcedure> all = json.readValue(raw, new TypeReference<>() { });
            if (query == null || query.isBlank()) return all;
            String q = query.toLowerCase();
            return all.stream().filter(p -> p.name().toLowerCase().contains(q)
                    || p.content().description().toLowerCase().contains(q)).toList();
        } catch (RuntimeException | JsonProcessingException e) { log.warn("读取 SOP 降级快照失败: {}", e.getMessage()); return List.of(); }
    }
    private static String fallbackKey(String tenant) { return FALLBACK_KEY_PREFIX + tenant + ":" + NO_PROJECT; }
}
