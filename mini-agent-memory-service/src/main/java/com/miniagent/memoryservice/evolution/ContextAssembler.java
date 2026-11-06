package com.miniagent.memoryservice.evolution;

import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.memory.contract.MemoryApi.Arm;
import com.miniagent.memory.contract.MemoryApi.AssembleRequest;
import com.miniagent.memory.contract.MemoryApi.AssembleResponse;
import com.miniagent.memory.contract.MemoryApi.AssembledProcedure;
import com.miniagent.memory.contract.ServiceToken.Principal;
import com.miniagent.memoryservice.config.EvolutionProperties;
import com.miniagent.memoryservice.store.ItemStore;
import com.miniagent.memoryservice.store.ItemStore.Item;
import com.miniagent.memoryservice.store.ItemStore.Revision;
import com.miniagent.memoryservice.store.ProjectAccess;
import com.miniagent.memoryservice.store.ProposalState;
import com.miniagent.memoryservice.store.ProposalStore;
import com.miniagent.memoryservice.store.ProposalStore.Proposal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 读路径：按任务组装可用 SOP。先做留存分桶，再做实验分桶（设计文档 §6.2）。
 * 实验中的条目按 {@code bucket(salt, taskKey) < traffic_bps} 分臂，同一任务重试永远落同一臂；
 * 留存任务拿「本周期开始起不做进化激活」的修订，不进任何实验；冻结时只出对照臂。
 */
@Service
public class ContextAssembler {
    private final ItemStore items;
    private final ProposalStore proposals;
    private final ProjectAccess projects;
    private final EvolutionPolicy policy;
    private final EvolutionProperties props;
    private final MeterRegistry metrics;

    public ContextAssembler(ItemStore items, ProposalStore proposals, ProjectAccess projects,
                            EvolutionPolicy policy, EvolutionProperties props,
                            MeterRegistry metrics) {
        this.items = items;
        this.proposals = proposals;
        this.projects = projects;
        this.policy = policy;
        this.props = props;
        this.metrics = metrics;
    }

    public AssembleResponse assemble(Principal principal, AssembleRequest request) {
        Timer.Sample sample = Timer.start(metrics);
        ItemService.requireUser(principal);
        if (request == null || request.taskKey() == null || request.taskKey().isBlank()) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "taskKey 不能为空");
        }
        String tenantId = principal.tenantId();
        EvolutionPolicy.Effective effective = policy.of(tenantId);
        String projectId = projects.canRead(tenantId, principal.projectId(), principal.userId())
                ? principal.projectId() : null;
        boolean holdout = policy.inHoldout(tenantId, request.taskKey(), effective.holdoutBps());
        List<Item> candidates = items.forContext(tenantId, principal.userId(), projectId,
                props.getContext().getMaxItems());
        Map<Long, Proposal> experiments = holdout ? Map.of() : proposals.forItems(
                        candidates.stream().map(Item::id).toList(),
                        Set.of(ProposalState.EXPERIMENTING, ProposalState.MONITORING),
                        candidates.size())
                .stream().collect(Collectors.toMap(Proposal::itemId, Function.identity(),
                        (a, b) -> a));
        int period = policy.period();
        List<AssembledProcedure> result = new ArrayList<>();
        for (Item item : candidates) {
            AssembledProcedure assembled = holdout ? holdoutRevision(item, period)
                    : withArm(item, experiments.get(item.id()), request.taskKey(),
                    effective.frozen());
            if (assembled != null && matches(assembled, request.query())) {
                result.add(assembled);
            }
        }
        sample.stop(metrics.timer("memory_context_assemble_seconds",
                "holdout", String.valueOf(holdout)));
        return new AssembleResponse(result, holdout);
    }

    private AssembledProcedure holdoutRevision(Item item, int period) {
        return items.revision(item.holdoutRevision(period)).map(r -> plain(item, r)).orElse(null);
    }

    private AssembledProcedure withArm(Item item, Proposal experiment, String taskKey,
                                       boolean frozen) {
        Revision active = items.revision(item.activeRevisionId()).orElse(null);
        if (active == null) {
            return null;
        }
        if (experiment == null || experiment.candidateRevisionId() == null
                || experiment.experimentSalt() == null) {
            return plain(item, active);
        }
        // 实验期激活的是基线；监控期激活的是新修订。两者以外说明状态已漂移，按普通条目返回。
        long expectedActive = experiment.state() == ProposalState.EXPERIMENTING
                ? experiment.baseRevisionId() : experiment.candidateRevisionId();
        if (active.id() != expectedActive || frozen) {
            return plain(item, active);
        }
        boolean candidate = Stats.bucket(experiment.experimentSalt(), taskKey)
                < experiment.trafficBps();
        Revision served = items.revision(candidate ? experiment.candidateRevisionId()
                : experiment.baseRevisionId()).orElse(active);
        return new AssembledProcedure(item.id(), served.revisionNo(), item.scopeType(),
                item.name(), served.content(), experiment.id(), experiment.candidateHash(),
                candidate ? Arm.CANDIDATE : Arm.BASELINE,
                EvolutionPolicy.percent(experiment.trafficBps()));
    }

    private static AssembledProcedure plain(Item item, Revision revision) {
        return new AssembledProcedure(item.id(), revision.revisionNo(), item.scopeType(),
                item.name(), revision.content(), null, "", Arm.BASELINE, 0);
    }

    private static boolean matches(AssembledProcedure p, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.toLowerCase(Locale.ROOT);
        return p.name().toLowerCase(Locale.ROOT).contains(q)
                || p.content().description().toLowerCase(Locale.ROOT).contains(q);
    }
}
