package com.miniagent.memoryservice.eval;

import com.miniagent.memory.contract.MemoryApi.CaseKind;
import com.miniagent.memory.contract.MemoryApi.CaseVerdict;
import com.miniagent.memory.contract.MemoryApi.EvalCase;
import com.miniagent.memory.contract.MemoryApi.EvalJob;
import com.miniagent.memory.contract.MemoryApi.EvalMode;
import com.miniagent.memory.contract.MemoryApi.EvalResult;
import com.miniagent.memory.contract.MemoryApi.Verdict;
import com.miniagent.memoryservice.config.MemoryServiceProperties;
import com.miniagent.memoryservice.evolution.EvalCoordinator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 与升级作业同一个进程领取评估：逐案回放、判定、回交。
 * 只在 worker / all 角色运行。单个案例出错只记该案 ERROR。
 */
@Component
public class EvalRunner {
    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final int MAX_NOTE_CHARS = 300;

    private final EvalCoordinator coordinator;
    private final EvalModels models;
    private final PlanReplayer replayer;
    private final PlanJudge judge;
    private final EvalWorkerProperties props;
    private final MemoryServiceProperties service;
    private final MeterRegistry metrics;

    public EvalRunner(EvalCoordinator coordinator, EvalModels models, PlanReplayer replayer,
                      PlanJudge judge, EvalWorkerProperties props, MemoryServiceProperties service,
                      MeterRegistry metrics) {
        this.coordinator = coordinator;
        this.models = models;
        this.replayer = replayer;
        this.judge = judge;
        this.props = props;
        this.service = service;
        this.metrics = metrics;
    }

    @Scheduled(initialDelayString = "${eval-worker.initial-delay-ms:5000}",
            fixedDelayString = "${eval-worker.poll-ms:5000}")
    public void poll() {
        if (!service.runsWorker()) {
            return;
        }
        if (!models.ready()) {
            log.warn("未配置规划或裁判模型（eval-worker.planner.* / eval-worker.judge.*），跳过回放");
            return;
        }
        try {
            while (runOnce()) {
                log.debug("继续领取下一个评估作业");
            }
        } catch (RuntimeException e) {
            log.warn("评估作业失败: {}", e.getMessage());
        }
    }

    /** 领取并完成一个作业；没有可领的返回 false。 */
    public boolean runOnce() {
        EvalJob job = coordinator.lease(props.workerId()).orElse(null);
        if (job == null) {
            return false;
        }
        models.planner().drain();
        models.judge().drain();
        List<CaseVerdict> verdicts = new ArrayList<>();
        for (EvalCase c : job.cases()) {
            CaseVerdict verdict = evaluate(job, c);
            metrics.counter("eval_worker_cases_total", "mode", job.mode().name(),
                    "verdict", verdict.verdict().name()).increment();
            verdicts.add(verdict);
        }
        long tokens = models.planner().drain() + models.judge().drain();
        coordinator.complete(job.jobId(), new EvalResult(job.leaseToken(), verdicts, tokens,
                props.planner().model(), props.judgeVersion()));
        log.info("评估作业完成 job={} proposal={} mode={} cases={} tokens={}", job.jobId(),
                job.proposalId(), job.mode(), verdicts.size(), tokens);
        return true;
    }

    CaseVerdict evaluate(EvalJob job, EvalCase c) {
        try {
            PlanReplayer.Replay candidate = replayer.replay(models.planner(), job.itemName(),
                    job.candidate(), c.userMessage());
            if (job.mode() == EvalMode.REVALIDATE || c.kind() == CaseKind.REGRESSION) {
                if (!candidate.compiled()) {
                    return new CaseVerdict(c.caseId(), Verdict.LOSS, false, false,
                            note("计划编译失败 " + candidate.plan()));
                }
                return new CaseVerdict(c.caseId(), judge.single(models.judge(), c,
                        candidate.plan()), false, true, null);
            }
            PlanReplayer.Replay baseline = replayer.replay(models.planner(), job.itemName(),
                    job.baseline(), c.userMessage());
            if (!baseline.compiled() || !candidate.compiled()) {
                Verdict verdict = baseline.compiled() ? Verdict.LOSS
                        : candidate.compiled() ? Verdict.WIN : Verdict.ERROR;
                return new CaseVerdict(c.caseId(), verdict, baseline.compiled(),
                        candidate.compiled(), note("计划编译失败"));
            }
            return new CaseVerdict(c.caseId(), judge.pairwise(models.judge(), c, baseline.plan(),
                    candidate.plan()), true, true, null);
        } catch (RuntimeException e) {
            log.warn("评估案例失败 job={} case={}", job.jobId(), c.caseId(), e);
            return new CaseVerdict(c.caseId(), Verdict.ERROR, false, false,
                    note(String.valueOf(e.getMessage())));
        }
    }

    private static String note(String text) {
        return text.length() <= MAX_NOTE_CHARS ? text : text.substring(0, MAX_NOTE_CHARS);
    }
}
