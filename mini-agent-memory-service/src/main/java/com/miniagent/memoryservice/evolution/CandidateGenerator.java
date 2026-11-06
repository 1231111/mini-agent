package com.miniagent.memoryservice.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.llm.DedicatedChatModel;
import com.miniagent.common.llm.TokenCount;
import com.miniagent.memory.contract.ProcedureContent;
import com.miniagent.memoryservice.config.EvolutionProperties;
import com.miniagent.memoryservice.store.AuditStore;
import com.miniagent.memoryservice.store.Db;
import com.miniagent.memoryservice.store.ItemStore;
import com.miniagent.memoryservice.store.ItemStore.Item;
import com.miniagent.memoryservice.store.ItemStore.Origin;
import com.miniagent.memoryservice.store.ItemStore.Revision;
import com.miniagent.memoryservice.store.JobQueue;
import com.miniagent.memoryservice.store.ProposalState;
import com.miniagent.memoryservice.store.ProposalStore;
import com.miniagent.memoryservice.store.ProposalStore.Proposal;
import com.miniagent.memoryservice.store.ProposalStore.Trigger;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 候选生成（设计文档 §7.2）：基线 + 触发证据 + 成功案例 → LLM 输出修订后的完整内容与证据引用 →
 * 静态闸门 → 物化为未激活的 EVOLUTION 修订并排评估作业。LLM 调用在事务外。
 * 与文档的差异：模型输出完整修订内容而不是补丁，改动幅度由闸门按步骤 LCS 计算。
 */
@Service
public class CandidateGenerator {
    private static final Logger log = LoggerFactory.getLogger(CandidateGenerator.class);
    private static final String CHALLENGER_RULE =
            "1. 可以重写、合并或重排步骤，但每处修改都要能由证据解释。";
    private static final String CHAMPION_RULE =
            "1. 只做修正这些失败所必需的最小修改；与失败无关的步骤保持原文，不要改写措辞。";
    private static final String SYSTEM_TEMPLATE = """
            你是 SOP（标准作业流程）修订器。输入是一条 SOP 的当前版本、它近期失败的任务证据和做成功的任务。
            %s
            2. 修改后仍要能完成「成功案例」里的任务。
            3. 不得加入 URL、凭据、越权或绕过安全检查的指令。
            4. evidenceRefs 只能填输入里给出的证据 id，列出这次修改依据的全部证据。
            只输出一个 JSON 对象，不要任何其他文字：
            {"description":"...","preconditions":["..."],"steps":["..."],\
            "successConditions":["..."],"rationale":"改了什么、为什么（一两句）",\
            "evidenceRefs":["T:e1"]}
            """;
    private static final int MAX_RATIONALE = 2000;

    record Generated(String description, List<String> preconditions, List<String> steps,
                     List<String> successConditions, String rationale,
                     List<String> evidenceRefs) {
        ProcedureContent content() {
            return new ProcedureContent(description, preconditions, steps, successConditions);
        }
    }

    private final ItemStore items;
    private final ProposalStore proposals;
    private final JobQueue jobs;
    private final EvidenceCases evidence;
    private final StaticGate gate;
    private final EvolutionPolicy policy;
    private final AuditStore audit;
    private final EvolutionProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    private final ChatModel llm;

    public CandidateGenerator(ItemStore items, ProposalStore proposals, JobQueue jobs,
                              EvidenceCases evidence, StaticGate gate, EvolutionPolicy policy,
                              AuditStore audit, EvolutionProperties props, ObjectMapper json,
                              Clock clock, MeterRegistry metrics,
                              PlatformTransactionManager transactions,
                              ObjectProvider<ChatModel> chatModelOverride) {
        this.items = items;
        this.proposals = proposals;
        this.jobs = jobs;
        this.evidence = evidence;
        this.gate = gate;
        this.policy = policy;
        this.audit = audit;
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(transactions);
        EvolutionProperties.Llm llm = props.getLlm();
        this.llm = chatModelOverride.getIfAvailable(() -> DedicatedChatModel.openAiOrNull(
                llm.getModel(), llm.getBaseUrl(), llm.getApiKey(), llm.getTimeoutSeconds()));
    }

    /** 返回下次运行时间（冻结 / 预算用完时推迟），null 表示作业结束。 */
    public Instant generate(JobQueue.Job job) {
        Proposal p = proposals.find(job.proposalId()).orElse(null);
        if (p == null || (p.state() != ProposalState.DETECTED
                && p.state() != ProposalState.GENERATING)) {
            return null;
        }
        Instant now = clock.instant();
        if (!policy.of(p.tenantId()).evolves()) {
            return now.plus(Duration.ofMinutes(props.getExperiment().getCheckIntervalMinutes()));
        }
        if (!policy.budgetAvailable(p.tenantId())) {
            return policy.today().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        if (p.state() == ProposalState.DETECTED && !proposals.move(p.id(), ProposalState.DETECTED,
                ProposalState.GENERATING, null, now)) {
            return null;
        }
        Item item = items.find(p.itemId()).orElse(null);
        if (item == null || !Objects.equals(item.activeRevisionId(), p.baseRevisionId())) {
            finish(p, ProposalState.SUPERSEDED, "生成前基线已变化", now);
            return null;
        }
        if (llm == null) {
            finish(p, ProposalState.FAILED, "未配置进化模型（memory.evolution.llm.*）", now);
            return null;
        }
        Revision base = items.revision(p.baseRevisionId()).orElseThrow();
        List<EvidenceCases.Case> triggers = evidence.triggers(p,
                props.getGate().getPromptFailureCases());
        if (triggers.isEmpty()) {
            finish(p, ProposalState.REJECTED_STATIC, "没有带详情的失败证据可供修订", now);
            return null;
        }
        List<EvidenceCases.Case> successes = evidence.successes(base.id(),
                props.getGate().getPromptSuccessCases());
        List<EvidenceCases.Case> fixedBefore = p.trigger() == Trigger.RECURRENCE
                ? evidence.regressions(item.id(), p.fingerprint(),
                props.getGate().getPromptFailureCases()).stream()
                .filter(c -> p.fingerprint().equals(c.fingerprint())).toList()
                : List.of();
        String system = SYSTEM_TEMPLATE.formatted(
                props.getMeta().getGeneratorChampion().equals(p.generatorVersion())
                        ? CHAMPION_RULE : CHALLENGER_RULE);
        String user = prompt(item, base, triggers, successes, fixedBefore);
        ChatResponse response = llm.chat(ChatRequest.builder()
                .messages(SystemMessage.from(system), UserMessage.from(user)).build());
        String text = response.aiMessage() == null ? null : response.aiMessage().text();
        long tokens = TokenCount.of(response, system.length() + user.length());
        policy.charge(p.tenantId(), EvolutionPolicy.PURPOSE_GENERATE, tokens);
        proposals.addTokens(p.id(), tokens);
        Generated generated = parse(text);
        if (generated == null) {
            finish(p, ProposalState.REJECTED_STATIC, "生成结果不是合法 JSON", clock.instant());
            return null;
        }
        Map<String, EvidenceCases.Case> provided = triggers.stream()
                .collect(Collectors.toMap(EvidenceCases.Case::id, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        StaticGate.Result verdict = gate.check(item.scopeType(), base.content(),
                generated.content(), generated.evidenceRefs(),
                new StaticGate.Evidence(provided, p.trigger() != Trigger.REVALIDATION),
                proposals.rejectedHashes(item.id()));
        if (!verdict.passed()) {
            metrics.counter("evo_static_gate_rejected_total").increment();
            finish(p, ProposalState.REJECTED_STATIC, "静态闸门：" + verdict.reason(),
                    clock.instant());
            return null;
        }
        materialize(p, generated);
        return null;
    }

    private void materialize(Proposal p, Generated generated) {
        tx.executeWithoutResult(status -> {
            Instant now = clock.instant();
            Item locked = items.lock(p.itemId()).orElse(null);
            if (locked == null || !Objects.equals(locked.activeRevisionId(), p.baseRevisionId())) {
                finish(p, ProposalState.SUPERSEDED, "生成期间基线已变化", now);
                return;
            }
            Revision candidate = items.insertRevision(locked.id(), p.baseRevisionId(),
                    Origin.EVOLUTION, generated.content(), p.id(),
                    "evolution:" + p.generatorVersion(), now);
            if (proposals.candidateReady(p.id(), candidate.id(), candidate.contentHash(),
                    Db.clip(generated.rationale(), MAX_RATIONALE), now)) {
                jobs.enqueue(JobQueue.Type.EVALUATE, "EVAL:" + p.id(), p.tenantId(), p.itemId(),
                        p.id(), null, p.trigger().priority(), now, now);
                audit.audit(p.tenantId(), "generator", "CANDIDATE_GENERATED", "PROPOSAL", p.id(),
                        Map.of("revisionNo", candidate.revisionNo()), now);
                log.info("候选已生成 proposal={} item={} r{}", p.id(), p.itemId(),
                        candidate.revisionNo());
            }
        });
    }

    private String prompt(Item item, Revision base, List<EvidenceCases.Case> triggers,
                          List<EvidenceCases.Case> successes,
                          List<EvidenceCases.Case> fixedBefore) {
        StringBuilder sb = new StringBuilder();
        sb.append("SOP 名称：").append(item.name()).append("\n当前版本：\n")
                .append(write(base.content())).append("\n\n失败证据：\n");
        for (EvidenceCases.Case c : triggers) {
            sb.append("[").append(c.id()).append("] 用户目标：").append(c.userMessage());
            if (c.nodeName() != null) {
                sb.append("；失败节点：").append(c.nodeName());
            }
            if (c.failureReason() != null) {
                sb.append("；失败原因：").append(c.failureReason());
            }
            sb.append('\n');
        }
        if (!fixedBefore.isEmpty()) {
            sb.append("\n这类失败以前修过一次，以下是当时修好的案例（修订不能让它们再失败）：\n");
            fixedBefore.forEach(c -> sb.append("- ").append(c.userMessage()).append('\n'));
        }
        sb.append("\n成功案例：\n");
        if (successes.isEmpty()) {
            sb.append("（无）\n");
        }
        successes.forEach(c -> sb.append("- 用户目标：").append(c.userMessage()).append('\n'));
        return sb.toString();
    }

    private Generated parse(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return json.readValue(text.substring(start, end + 1), Generated.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void finish(Proposal p, ProposalState to, String reason, Instant now) {
        ProposalState from = proposals.find(p.id()).map(Proposal::state).orElse(p.state());
        if (proposals.finish(p.id(), from, to, reason, null, now)) {
            jobs.cancelForProposal(p.id(), now);
            audit.audit(p.tenantId(), "generator", "PROPOSAL_" + to.name(), "PROPOSAL", p.id(),
                    Map.of("reason", reason), now);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
