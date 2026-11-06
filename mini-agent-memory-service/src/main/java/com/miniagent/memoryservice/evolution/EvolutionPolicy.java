package com.miniagent.memoryservice.evolution;

import com.miniagent.memoryservice.config.EvolutionProperties;
import com.miniagent.memoryservice.store.PolicyStore;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** 租户生效策略（模式、冻结、预算、留存组）与 token 计费。 */
@Service
public class EvolutionPolicy {
    public enum Mode { OFF, SHADOW, ASSISTED, AUTO }

    public record Effective(Mode mode, boolean frozen, long dailyTokenBudget, int holdoutBps) {
        /** 能否产生新提案、推进实验与晋升。 */
        public boolean evolves() {
            return !frozen && mode != Mode.OFF;
        }
    }

    private static final int BPS_PER_PERCENT = 100;

    private final PolicyStore policies;
    private final EvolutionProperties props;
    private final Clock clock;
    private final MeterRegistry metrics;

    public EvolutionPolicy(PolicyStore policies, EvolutionProperties props, Clock clock,
                           MeterRegistry metrics) {
        this.policies = policies;
        this.props = props;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** App 按整数百分比回传流量，配置的万分比必须能整除，否则分臂复算会对不上。 */
    @PostConstruct
    void validateTraffic() {
        List<Integer> bps = new ArrayList<>(props.getExperiment().getProjectRampBps());
        bps.add(props.getExperiment().getUserScopeBps());
        bps.add(props.getMonitor().getControlBps());
        for (Integer value : bps) {
            if (value == null || value <= 0 || value >= Stats.BUCKETS
                    || value % BPS_PER_PERCENT != 0) {
                throw new IllegalStateException("traffic bps must be a multiple of 100 in (0,10000): "
                        + value);
            }
        }
        Mode.valueOf(props.getPolicy().getDefaultMode());
    }

    public Effective of(String tenantId) {
        PolicyStore.Policy global = policies.find(PolicyStore.GLOBAL).orElse(null);
        PolicyStore.Policy tenant = policies.find(tenantId).orElse(null);
        String mode = tenant != null && tenant.mode() != null ? tenant.mode()
                : props.getPolicy().getDefaultMode();
        boolean frozen = (global != null && global.frozen()) || (tenant != null && tenant.frozen());
        long budget = tenant != null && tenant.dailyTokenBudget() != null
                ? tenant.dailyTokenBudget() : props.getBudget().getDailyTokensPerTenant();
        int holdout = tenant != null && tenant.holdoutBps() != null ? tenant.holdoutBps()
                : props.getHoldout().getBps();
        return new Effective(Mode.valueOf(mode), frozen, budget, holdout);
    }

    /** 熔断 / 离线-在线一致率过低时把 AUTO 租户降为 ASSISTED；非 AUTO 不动。 */
    public boolean downgradeToAssisted(String tenantId, String actor) {
        if (of(tenantId).mode() != Mode.AUTO) {
            return false;
        }
        policies.upsert(tenantId, Mode.ASSISTED.name(), null, null, actor, clock.instant());
        metrics.counter("evo_tenant_downgrades_total", "actor", actor).increment();
        return true;
    }

    public boolean budgetAvailable(String tenantId) {
        return policies.usedTokens(tenantId, today()) < of(tenantId).dailyTokenBudget();
    }

    public static final String PURPOSE_GENERATE = "generate";
    public static final String PURPOSE_EVAL = "eval";

    public void charge(String tenantId, String purpose, long tokens) {
        policies.addTokens(tenantId, today(), tokens);
        metrics.counter("evo_tokens_total", "tenant", tenantId, "purpose", purpose)
                .increment(tokens);
    }

    /**
     * 留存组按任务分桶（文档 §7.8），周期号进盐，每个周期换一批任务。
     * 留存任务看到「本周期开始起不做进化激活」的修订，且不进任何实验。
     */
    public boolean inHoldout(String tenantId, String taskKey, int holdoutBps) {
        if (holdoutBps <= 0) {
            return false;
        }
        return Stats.bucket("holdout:" + tenantId + ":" + period(), taskKey) < holdoutBps;
    }

    /** 当前留存周期号：UTC 纪元日 / holdout.period-days。 */
    public int period() {
        return (int) (today().toEpochDay() / Math.max(1, props.getHoldout().getPeriodDays()));
    }

    public Instant periodStart() {
        long days = (long) period() * Math.max(1, props.getHoldout().getPeriodDays());
        return LocalDate.ofEpochDay(days).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public static int percent(int bps) {
        return bps / BPS_PER_PERCENT;
    }

    public static int bps(int percent) {
        return percent * BPS_PER_PERCENT;
    }
}
