package com.miniagent.memoryservice.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.memory.SecurityScanner;
import com.miniagent.memory.contract.MemoryApi.AckStatus;
import com.miniagent.memory.contract.MemoryApi.Arm;
import com.miniagent.memory.contract.MemoryApi.FeedbackRequest;
import com.miniagent.memory.contract.MemoryApi.OutcomeAck;
import com.miniagent.memory.contract.MemoryApi.OutcomeBatch;
import com.miniagent.memory.contract.MemoryApi.OutcomeBatchResult;
import com.miniagent.memory.contract.MemoryApi.OutcomeEvent;
import com.miniagent.memory.contract.MemoryApi.OutcomeResult;
import com.miniagent.memory.contract.ServiceToken.Principal;
import com.miniagent.memoryservice.config.EvolutionProperties;
import com.miniagent.memoryservice.store.Db;
import com.miniagent.memoryservice.store.ItemStore;
import com.miniagent.memoryservice.store.ItemStore.Item;
import com.miniagent.memoryservice.store.ItemStore.Revision;
import com.miniagent.memoryservice.store.JobQueue;
import com.miniagent.memoryservice.store.OutcomeStore;
import com.miniagent.memoryservice.store.ProposalStore;
import com.miniagent.memoryservice.store.ProposalStore.Proposal;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结果事件写入：逐条校验、幂等落库，失败或用户否定时排一个去抖的 DETECT 作业。
 * 分臂由服务端用 {@code bucket(salt, taskKey)} 复算，与 App 回传不一致的事件照常存档，
 * 但 {@code arm_valid=false}，不进实验统计。
 */
@Service
public class OutcomeIngestor {
    private static final int MAX_BATCH = 500;
    private static final int MAX_KEY = 255;
    private static final int MAX_EVENT_ID = 128;
    private static final int MAX_MESSAGE = 1000;
    private static final int MAX_REASON = 500;
    private static final int MAX_NODE = 200;
    private static final int MAX_CODE = 64;

    private final ItemStore items;
    private final OutcomeStore outcomes;
    private final ProposalStore proposals;
    private final JobQueue jobs;
    private final ItemService itemService;
    private final EvolutionPolicy policy;
    private final EvolutionProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;

    public OutcomeIngestor(ItemStore items, OutcomeStore outcomes, ProposalStore proposals,
                           JobQueue jobs, ItemService itemService, EvolutionPolicy policy,
                           EvolutionProperties props, ObjectMapper json, Clock clock,
                           MeterRegistry metrics, PlatformTransactionManager transactions) {
        this.items = items;
        this.outcomes = outcomes;
        this.proposals = proposals;
        this.jobs = jobs;
        this.itemService = itemService;
        this.policy = policy;
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(transactions);
    }

    public OutcomeBatchResult ingest(OutcomeBatch batch) {
        if (batch == null || batch.events() == null || batch.events().size() > MAX_BATCH) {
            throw new BusinessException(ErrorCode.MEMORY_OUTCOME_INVALID, "批次为空或超过 500 条");
        }
        List<OutcomeAck> acks = new ArrayList<>();
        for (OutcomeEvent event : batch.events()) {
            OutcomeAck ack;
            try {
                ack = tx.execute(status -> ingestOne(event));
            } catch (BusinessException e) {
                ack = new OutcomeAck(event == null ? null : event.eventId(), AckStatus.REJECTED,
                        e.getErrorCode().getCode());
            }
            metrics.counter("memory_outcomes_total", "status", ack.status().name()).increment();
            acks.add(ack);
        }
        return new OutcomeBatchResult(acks);
    }

    /** 用户对整个任务的评价，复制到该任务用过的每个（条目, 修订, 臂）上；同一任务只认第一次。 */
    @Transactional
    public int feedback(Principal principal, FeedbackRequest request) {
        ItemService.requireUser(principal);
        if (request == null || request.taskKey() == null || request.taskKey().isBlank()) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "taskKey 不能为空");
        }
        List<OutcomeStore.Target> targets = outcomes.taskTargets(principal.tenantId(),
                principal.userId(), request.taskKey());
        if (targets.isEmpty()) {
            throw new BusinessException(ErrorCode.MEMORY_NOT_FOUND, "该任务没有使用任何 SOP");
        }
        Instant now = clock.instant();
        String result = request.positive() ? OutcomeResult.FEEDBACK_UP.name()
                : OutcomeResult.FEEDBACK_DOWN.name();
        boolean holdout = policy.inHoldout(principal.tenantId(), request.taskKey(),
                policy.of(principal.tenantId()).holdoutBps());
        int accepted = 0;
        for (OutcomeStore.Target target : targets) {
            String eventId = Db.clip("fb:" + request.taskKey() + ":" + target.itemId() + ":"
                    + target.revisionId(), MAX_EVENT_ID);
            boolean inserted = outcomes.insert(new OutcomeStore.Event(eventId,
                    principal.tenantId(), principal.userId(), target.projectId(), target.itemId(),
                    target.revisionId(), target.proposalId(), target.arm(), target.armValid(),
                    holdout, request.taskKey(), "feedback", result, true, null, null, null,
                    null, now, now));
            if (inserted) {
                accepted++;
                if (!request.positive()) {
                    scheduleDetect(principal.tenantId(), target.itemId(), now);
                }
            }
        }
        return accepted;
    }

    private OutcomeAck ingestOne(OutcomeEvent e) {
        validate(e);
        Item item = items.find(e.itemId()).orElseThrow(
                () -> new BusinessException(ErrorCode.MEMORY_NOT_FOUND));
        if (!itemService.visibleTo(item, e.tenantId(), e.userId())) {
            throw new BusinessException(ErrorCode.MEMORY_NOT_FOUND);
        }
        Revision revision = items.revisionByNo(item.id(), e.revisionNo()).orElseThrow(
                () -> new BusinessException(ErrorCode.MEMORY_REVISION_NOT_FOUND));
        Instant now = clock.instant();
        boolean armValid = e.proposalId() == null || armValid(e, item, revision);
        boolean failure = e.result() == OutcomeResult.FAILURE;
        boolean counted = !failure || !excluded(e.errorCode());
        boolean holdout = policy.inHoldout(e.tenantId(), e.taskKey(),
                policy.of(e.tenantId()).holdoutBps());
        boolean inserted = outcomes.insert(new OutcomeStore.Event(e.eventId(), e.tenantId(),
                e.userId(), blankToNull(e.projectId()), item.id(), revision.id(), e.proposalId(),
                e.proposalId() == null ? null : e.arm().name(), armValid, holdout,
                Db.clip(e.taskKey(), MAX_KEY), Db.clip(e.nodeKey(), MAX_KEY), e.result().name(),
                counted, Db.clip(e.errorCode(), MAX_CODE),
                failure ? Stats.fingerprint(e.errorCode(), e.failureReason()) : null,
                detail(e), Db.clip(e.envVersion(), MAX_CODE),
                e.occurredAt() == null ? now : e.occurredAt(), now));
        if (!inserted) {
            return new OutcomeAck(e.eventId(), AckStatus.DUPLICATE, null);
        }
        items.touch(Set.of(item.id()), now);
        if ((failure && counted) || e.result() == OutcomeResult.FEEDBACK_DOWN) {
            scheduleDetect(item.tenantId(), item.id(), now);
        }
        return new OutcomeAck(e.eventId(), AckStatus.ACCEPTED,
                armValid ? null : ErrorCode.MEMORY_OUTCOME_ARM_MISMATCH.getCode());
    }

    private void scheduleDetect(String tenantId, long itemId, Instant now) {
        jobs.enqueue(JobQueue.Type.DETECT, "DETECT:" + itemId, tenantId, itemId, null, null,
                now.plusSeconds(props.getTrigger().getDebounceSeconds()), now);
    }

    /** 复算分臂：桶号 < 回传流量 ⇔ CANDIDATE，且所给修订必须是该臂对应的修订。 */
    private boolean armValid(OutcomeEvent e, Item item, Revision revision) {
        Proposal proposal = proposals.find(e.proposalId()).orElse(null);
        if (proposal == null || proposal.itemId() != item.id() || e.arm() == null
                || proposal.experimentSalt() == null || proposal.candidateRevisionId() == null) {
            return false;
        }
        int sentBps = EvolutionPolicy.bps(e.trafficPercent());
        if (sentBps <= 0 || sentBps > proposal.maxTrafficBps()) {
            return false;
        }
        Arm expected = Stats.bucket(proposal.experimentSalt(), e.taskKey()) < sentBps
                ? Arm.CANDIDATE : Arm.BASELINE;
        long expectedRevision = expected == Arm.CANDIDATE ? proposal.candidateRevisionId()
                : proposal.baseRevisionId();
        return expected == e.arm() && revision.id() == expectedRevision;
    }

    private boolean excluded(String errorCode) {
        return errorCode != null && props.getSignal().getExcludedErrorPrefixes().stream()
                .anyMatch(errorCode::startsWith);
    }

    /** 回传详情只保留回放需要的四个字段，逐个过注入/凭据扫描并截断。 */
    private String detail(OutcomeEvent e) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("userMessage", safe(e.userMessage(), MAX_MESSAGE));
        detail.put("nodeName", safe(e.nodeName(), MAX_NODE));
        detail.put("failureReason", safe(e.failureReason(), MAX_REASON));
        detail.put("errorCode", Db.clip(e.errorCode(), MAX_CODE));
        if (detail.get("userMessage") == null) {
            return null;
        }
        try {
            return json.writeValueAsString(detail);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String safe(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Db.clip(SecurityScanner.redactIfUnsafe(value), max);
    }

    private static void validate(OutcomeEvent e) {
        boolean ok = e != null && notBlank(e.eventId()) && e.eventId().length() <= MAX_EVENT_ID
                && notBlank(e.tenantId()) && e.userId() > 0 && e.itemId() > 0
                && e.revisionNo() > 0 && notBlank(e.taskKey()) && notBlank(e.nodeKey())
                && e.result() != null && (e.proposalId() == null || e.arm() != null);
        if (!ok) {
            throw new BusinessException(ErrorCode.MEMORY_OUTCOME_INVALID);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return notBlank(value) ? value : null;
    }
}
