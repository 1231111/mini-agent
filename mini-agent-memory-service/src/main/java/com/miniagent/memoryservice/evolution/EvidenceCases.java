package com.miniagent.memoryservice.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.memory.contract.MemoryApi.CaseKind;
import com.miniagent.memory.contract.MemoryApi.EvalCase;
import com.miniagent.memoryservice.config.EvolutionProperties;
import com.miniagent.memoryservice.store.AuditStore;
import com.miniagent.memoryservice.store.OutcomeStore;
import com.miniagent.memoryservice.store.OutcomeStore.CaseRow;
import com.miniagent.memoryservice.store.ProposalStore.Proposal;
import com.miniagent.memoryservice.store.ProposalStore.Trigger;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 证据与评估案例的统一来源。案例 id = 类别字母 + ":" + 来源 id（e=结果事件，r=回归案例），
 * 生成器引用、闸门校验、评估回写都用同一个 id。
 */
@Component
public class EvidenceCases {
    private static final String TRIGGER_PREFIX = "T:";
    private static final String SUCCESS_PREFIX = "S:";
    private static final String REGRESSION_PREFIX = "R:";
    private static final String EVENT_SOURCE = "e";
    private static final String REGRESSION_SOURCE = "r";
    private static final char TASK_SEPARATOR = ':';

    /** session / userId 只用于防投毒校验，不下发给评估 worker。 */
    public record Case(String id, CaseKind kind, String session, Long userId, String userMessage,
                       String nodeName, String failureReason, String fingerprint) {
        public EvalCase toEval() {
            return new EvalCase(id, kind, userMessage, failureReason);
        }
    }

    private final OutcomeStore outcomes;
    private final AuditStore audit;
    private final EvolutionProperties props;
    private final ObjectMapper json;
    private final Clock clock;

    public EvidenceCases(OutcomeStore outcomes, AuditStore audit, EvolutionProperties props,
                         ObjectMapper json, Clock clock) {
        this.outcomes = outcomes;
        this.audit = audit;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    /** 触发案例：失败簇 / 复发取同指纹，健康与优化取不限指纹的近期失败，重验取开提案时存下的失败案例。 */
    public List<Case> triggers(Proposal p, int limit) {
        if (p.trigger() == Trigger.REVALIDATION) {
            return storedTriggers(p.evidenceJson(), limit);
        }
        Instant now = clock.instant();
        List<CaseRow> rows = switch (p.trigger()) {
            case FAILURE -> outcomes.failureCases(p.baseRevisionId(), p.fingerprint(),
                    days(now, props.getEvidence().getWindowDays()), limit);
            case RECURRENCE -> outcomes.failureCases(p.baseRevisionId(), p.fingerprint(),
                    days(now, props.getRecurrence().getWindowDays()), limit);
            case HEALTH -> outcomes.anyFailureCases(p.baseRevisionId(),
                    days(now, props.getHealth().getRecentDays()), limit);
            case OPTIMIZATION -> outcomes.anyFailureCases(p.baseRevisionId(),
                    days(now, props.getExperiment().getTrafficWindowDays()), limit);
            case REVALIDATION, LIFECYCLE -> List.of();
        };
        return fromRows(rows, CaseKind.TRIGGER, TRIGGER_PREFIX);
    }

    public List<Case> successes(long revisionId, int limit) {
        Instant since = days(clock.instant(), props.getExperiment().getTrafficWindowDays());
        return fromRows(outcomes.successCases(revisionId, since, limit), CaseKind.SUCCESS,
                SUCCESS_PREFIX);
    }

    /** 回归集；复发触发时把该指纹的案例排在前面（文档：每个已修复指纹至少一条由上限内的最新案例保证）。 */
    public List<Case> regressions(long itemId, String preferFingerprint, int limit) {
        List<Case> preferred = new ArrayList<>();
        List<Case> others = new ArrayList<>();
        for (AuditStore.RegressionCase r : audit.regressionCases(itemId,
                props.getRegression().getMaxCasesPerItem())) {
            Case c = new Case(REGRESSION_PREFIX + REGRESSION_SOURCE + r.id(), CaseKind.REGRESSION,
                    null, null, r.userMessage(), null, r.failureReason(), r.fingerprint());
            (r.fingerprint() != null && r.fingerprint().equals(preferFingerprint) ? preferred : others)
                    .add(c);
        }
        preferred.addAll(others);
        return preferred.subList(0, Math.min(limit, preferred.size()));
    }

    /** 重验掉分的案例改记为触发案例（id 换成 T: 前缀），存进提案证据，生成与评估都从这里取。 */
    public static List<EvalCase> asTriggers(List<EvalCase> failed) {
        return failed.stream().map(c -> new EvalCase(TRIGGER_PREFIX + sourceId(c.caseId()),
                CaseKind.TRIGGER, c.userMessage(), c.failureReason())).toList();
    }

    /**
     * 同一会话里的同一用户目标只保留第一条：一个会话反复重试不该灌水评估集、也不该冒充多份证据；
     * 不同会话里的相同目标是独立证据，保留（防投毒按会话计数，按目标去重会把它们误合并）。
     */
    public static List<Case> distinctPerSession(List<Case> cases) {
        Set<String> seen = new HashSet<>();
        return cases.stream().filter(c -> seen.add(c.session() + "\n" + c.userMessage())).toList();
    }

    private List<Case> storedTriggers(String evidenceJson, int limit) {
        List<Case> cases = new ArrayList<>();
        try {
            JsonNode stored = evidenceJson == null ? null : json.readTree(evidenceJson).get("cases");
            if (stored != null) {
                for (JsonNode c : stored) {
                    if (cases.size() >= limit) {
                        break;
                    }
                    cases.add(new Case(c.path("caseId").asText(), CaseKind.TRIGGER, null, null,
                            c.path("userMessage").asText(), null,
                            c.path("failureReason").asText(null), null));
                }
            }
        } catch (JsonProcessingException corrupt) {
            return List.of();
        }
        return cases;
    }

    private List<Case> fromRows(List<CaseRow> rows, CaseKind kind, String prefix) {
        List<Case> cases = new ArrayList<>();
        for (CaseRow row : rows) {
            JsonNode detail = parse(row.detailJson());
            String message = detail == null ? null : detail.path("userMessage").asText(null);
            if (message == null || message.isBlank()) {
                continue;
            }
            cases.add(new Case(prefix + EVENT_SOURCE + row.eventId(), kind, session(row.taskKey()),
                    row.userId(), message, detail.path("nodeName").asText(null),
                    detail.path("failureReason").asText(null), row.fingerprint()));
        }
        return distinctPerSession(cases);
    }

    private JsonNode parse(String detailJson) {
        if (detailJson == null) {
            return null;
        }
        try {
            return json.readTree(detailJson);
        } catch (JsonProcessingException corrupt) {
            return null;
        }
    }

    /** taskKey = 会话 id + ":" + 任务 id（App 侧拼接），防投毒按会话去重。 */
    static String session(String taskKey) {
        int cut = taskKey.lastIndexOf(TASK_SEPARATOR);
        return cut > 0 ? taskKey.substring(0, cut) : taskKey;
    }

    private static String sourceId(String caseId) {
        int cut = caseId.indexOf(TASK_SEPARATOR);
        return cut >= 0 ? caseId.substring(cut + 1) : caseId;
    }

    private static Instant days(Instant now, int days) {
        return now.minus(Duration.ofDays(days));
    }
}
