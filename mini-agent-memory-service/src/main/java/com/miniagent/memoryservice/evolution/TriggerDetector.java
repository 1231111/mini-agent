package com.miniagent.memoryservice.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.memoryservice.config.EvolutionProperties;
import com.miniagent.memoryservice.store.AuditStore;
import com.miniagent.memoryservice.store.ItemStore;
import com.miniagent.memoryservice.store.ItemStore.Item;
import com.miniagent.memoryservice.store.JobQueue;
import com.miniagent.memoryservice.store.OutcomeStore;
import com.miniagent.memoryservice.store.ProposalStore;
import com.miniagent.memoryservice.store.ProposalStore.Trigger;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内环触发（设计文档 §5.2 / §5.5）。证据到达后：先查已修复指纹的复发，再查失败簇。
 * 所有触发源（含中环的健康、优化、归档与外环的重验）都经 {@link #open} 落同一套共同前置条件。
 */
@Service
public class TriggerDetector {
    private static final Logger log = LoggerFactory.getLogger(TriggerDetector.class);

    private final ItemStore items;
    private final OutcomeStore outcomes;
    private final ProposalStore proposals;
    private final JobQueue jobs;
    private final EvolutionPolicy policy;
    private final ExperimentService experiments;
    private final AuditStore audit;
    private final EvolutionProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry metrics;

    public TriggerDetector(ItemStore items, OutcomeStore outcomes, ProposalStore proposals,
                           JobQueue jobs, EvolutionPolicy policy, ExperimentService experiments,
                           AuditStore audit, EvolutionProperties props, ObjectMapper json,
                           Clock clock, MeterRegistry metrics) {
        this.items = items;
        this.outcomes = outcomes;
        this.proposals = proposals;
        this.jobs = jobs;
        this.policy = policy;
        this.experiments = experiments;
        this.audit = audit;
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public void detect(long itemId) {
        Item item = items.find(itemId).orElse(null);
        if (item == null || item.activeRevisionId() == null) {
            return;
        }
        Instant now = clock.instant();
        long revisionId = item.activeRevisionId();
        Instant recurrenceSince = now.minus(Duration.ofDays(props.getRecurrence().getWindowDays()));
        for (String fixed : proposals.fixedFingerprints(itemId)) {
            long count = outcomes.recurrences(revisionId, fixed, recurrenceSince);
            if (count >= props.getRecurrence().getMinCount()) {
                metrics.counter("evo_recurrence_total").increment();
                open(item, Trigger.RECURRENCE, fixed, Map.of("recurrences", count));
                return;
            }
        }
        OutcomeStore.Counts recent = outcomes.recentTasks(revisionId,
                props.getTrigger().getRecentApplications());
        double failureRate = recent.tasks() == 0 ? 0 : (double) recent.failures() / recent.tasks();
        Instant since = now.minus(Duration.ofDays(props.getEvidence().getWindowDays()));
        long negatives = outcomes.negatives(revisionId, since);
        if (failureRate < props.getTrigger().getMinFailureRate()
                && negatives < props.getTrigger().getMinUserNegatives()) {
            return;
        }
        for (OutcomeStore.Cluster cluster : outcomes.clusters(revisionId, since)) {
            if (cluster.tasks() < props.getEvidence().getMinClusterSize()) {
                return;
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("clusterTasks", cluster.tasks());
            evidence.put("recentTasks", recent.tasks());
            evidence.put("failureRate", failureRate);
            evidence.put("userNegatives", negatives);
            if (open(item, Trigger.FAILURE, cluster.fingerprint(), evidence).isPresent()) {
                return;
            }
        }
    }

    /**
     * 共同前置条件：租户可进化（非 OFF、未冻结）、条目 ACTIVE 且未锁定、没有未终结提案、
     * 租户未终结提案数未达上限、预算有余、不在冷却期。冷却只约束改内容的提案：复发可提前解禁，
     * 归档提案恰恰发生在修复被拒之后；失败簇按指纹冷却，新指纹不受旧指纹的冷却影响。
     */
    @Transactional
    public Optional<Long> open(Item item, Trigger trigger, String fingerprint,
                               Map<String, ?> evidence) {
        Instant now = clock.instant();
        Instant cooldownSince = now.minus(Duration.ofDays(props.getProposal().getCooldownDays()));
        boolean cooling = trigger != Trigger.RECURRENCE && trigger != Trigger.LIFECYCLE
                && proposals.inCooldown(item.id(), fingerprint, cooldownSince);
        if (!policy.of(item.tenantId()).evolves() || item.evolutionLocked()
                || !ItemStore.STATUS_ACTIVE.equals(item.status())
                || item.activeRevisionId() == null || cooling
                || proposals.openForItem(item.id()).isPresent()
                || proposals.countOpen(item.tenantId()) >= props.getProposal().getMaxOpenPerTenant()
                || !policy.budgetAvailable(item.tenantId())) {
            return Optional.empty();
        }
        String generator = generatorFor(item.id(), now);
        Optional<Long> created = proposals.create(item.tenantId(), item.id(), trigger, fingerprint,
                item.activeRevisionId(), generator, write(evidence), now);
        created.ifPresent(id -> {
            metrics.counter("evo_proposals_total", "trigger", trigger.name()).increment();
            audit.audit(item.tenantId(), "detector", "PROPOSAL_DETECTED", "PROPOSAL", id,
                    Map.of("itemId", item.id(), "trigger", trigger.name()), now);
            if (trigger == Trigger.LIFECYCLE) {
                experiments.decideLifecycle(id);
            } else {
                jobs.enqueue(JobQueue.Type.GENERATE, "GEN:" + id, item.tenantId(), item.id(), id,
                        null, trigger.priority(), now, now);
            }
            log.info("提案已创建 proposal={} item={} trigger={} generator={}", id, item.id(),
                    trigger, generator);
        });
        return created;
    }

    /** 冠军-挑战者：配置了挑战者时按条目 + 时间分桶，挑战者拿 challenger-share 的提案。 */
    private String generatorFor(long itemId, Instant now) {
        EvolutionProperties.Meta meta = props.getMeta();
        boolean challenger = !meta.getGeneratorChallenger().isBlank()
                && Stats.bucket("generator", itemId + ":" + now.toEpochMilli())
                < meta.getChallengerShare() * Stats.BUCKETS;
        return challenger ? meta.getGeneratorChallenger() : meta.getGeneratorChampion();
    }

    private String write(Map<String, ?> evidence) {
        try {
            return json.writeValueAsString(evidence);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
