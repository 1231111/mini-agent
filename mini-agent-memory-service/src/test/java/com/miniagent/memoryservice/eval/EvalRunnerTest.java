package com.miniagent.memoryservice.eval;

import com.miniagent.memory.contract.MemoryApi.CaseKind;
import com.miniagent.memory.contract.MemoryApi.CaseVerdict;
import com.miniagent.memory.contract.MemoryApi.EvalCase;
import com.miniagent.memory.contract.MemoryApi.EvalJob;
import com.miniagent.memory.contract.MemoryApi.EvalMode;
import com.miniagent.memory.contract.MemoryApi.Verdict;
import com.miniagent.memory.contract.ProcedureContent;
import com.miniagent.memoryservice.config.MemoryServiceProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 GoalCompiler 回放、脚本化的规划 / 裁判模型，验证判定口径。 */
class EvalRunnerTest {
    private static final String FIX = "检查页脚";
    private static final String BROKEN = "BROKEN";
    private static final ProcedureContent BASELINE = sop(List.of("打开模板", "导出 PDF"));
    private static final ProcedureContent CANDIDATE = sop(List.of("打开模板", FIX, "导出 PDF"));
    private static final EvalCase TRIGGER =
            new EvalCase("T:e1", CaseKind.TRIGGER, "导出本周周报", "导出时缺少页脚");
    private static final EvalCase SUCCESS =
            new EvalCase("S:e2", CaseKind.SUCCESS, "导出本周周报", null);
    private static final String PLAN_A = "计划 A：";
    private static final String PLAN_B = "计划 B：";

    /** 规划模型：SOP 里有修复步骤就把它编进节点名；看到 BROKEN 就答非所问（编译失败）。 */
    private static final UnaryOperator<String> PLANNER = user -> user.contains(BROKEN)
            ? "抱歉，无法规划"
            : "{\"objective\":\"导出周报\",\"nodes\":[{\"id\":\"n1\",\"name\":\""
            + (user.contains(FIX) ? FIX + "后导出 PDF" : "直接导出 PDF")
            + "\",\"capability\":\"deliver\",\"dependsOn\":[],\"inputs\":[],\"outputs\":[],"
            + "\"doneWhen\":{\"type\":\"note_required\"}}]}";

    /** 公正的裁判：含修复步骤的计划更好，与位置无关。 */
    private static final UnaryOperator<String> FAIR_JUDGE = user -> {
        if (!user.contains(PLAN_A)) {
            return "{\"pass\":" + user.contains(FIX) + ",\"reason\":\"-\"}";
        }
        String a = user.substring(user.indexOf(PLAN_A), user.indexOf(PLAN_B));
        String b = user.substring(user.indexOf(PLAN_B));
        String winner = a.contains(FIX) == b.contains(FIX) ? "TIE" : a.contains(FIX) ? "A" : "B";
        return "{\"winner\":\"" + winner + "\",\"reason\":\"-\"}";
    };

    @Test
    void pairwiseReplaysBothSopsAndCreditsTheCandidateOnlyWhenItIsBetter() {
        EvalRunner runner = runner(FAIR_JUDGE);

        CaseVerdict better = runner.evaluate(job(EvalMode.PAIRWISE, BASELINE, CANDIDATE), TRIGGER);
        assertEquals(Verdict.WIN, better.verdict());
        assertTrue(better.baselineCompiled() && better.candidateCompiled());
        assertEquals(Verdict.LOSS,
                runner.evaluate(job(EvalMode.PAIRWISE, CANDIDATE, BASELINE), TRIGGER).verdict());
    }

    @Test
    void positionBiasedJudgeCancelsOutToTie() {
        EvalRunner runner = runner(user -> "{\"winner\":\"A\",\"reason\":\"-\"}");
        assertEquals(Verdict.TIE,
                runner.evaluate(job(EvalMode.PAIRWISE, BASELINE, CANDIDATE), TRIGGER).verdict());
    }

    @Test
    void revalidationJudgesAloneAndUncompilableCandidateLoses() {
        EvalRunner runner = runner(FAIR_JUDGE);
        assertEquals(Verdict.WIN,
                runner.evaluate(job(EvalMode.REVALIDATE, null, CANDIDATE), SUCCESS).verdict());
        assertEquals(Verdict.LOSS,
                runner.evaluate(job(EvalMode.REVALIDATE, null, BASELINE), SUCCESS).verdict());

        CaseVerdict broken = runner.evaluate(
                job(EvalMode.PAIRWISE, BASELINE, sop(List.of(BROKEN))), TRIGGER);
        assertEquals(Verdict.LOSS, broken.verdict());
        assertTrue(broken.baselineCompiled());
        assertFalse(broken.candidateCompiled());
    }

    @Test
    void unparsableJudgeAnswerIsErrorAndTokensAreMetered() {
        EvalModels models = models(user -> "我觉得都行");
        EvalRunner runner = runner(models);
        assertEquals(Verdict.ERROR,
                runner.evaluate(job(EvalMode.PAIRWISE, BASELINE, CANDIDATE), TRIGGER).verdict());
        assertTrue(models.planner().drain() > 0);
        assertTrue(models.judge().drain() > 0);
        assertEquals(0, models.judge().drain(), "drain 后清零，按作业回交");
    }

    private static EvalRunner runner(UnaryOperator<String> judge) {
        return runner(models(judge));
    }

    private static EvalRunner runner(EvalModels models) {
        EvalWorkerProperties props = new EvalWorkerProperties("test", null, null, null);
        return new EvalRunner(null, models, new PlanReplayer(), new PlanJudge(), props,
                new MemoryServiceProperties("api", Map.of(), "", "", "", 1500, 2500),
                new SimpleMeterRegistry());
    }

    private static EvalModels models(UnaryOperator<String> judge) {
        return new EvalModels(new MeteredChatModel(new Scripted(PLANNER)),
                new MeteredChatModel(new Scripted(judge)));
    }

    private static EvalJob job(EvalMode mode, ProcedureContent baseline,
                               ProcedureContent candidate) {
        return new EvalJob(1L, "lease", 1L, mode, "周报导出", baseline, candidate,
                List.of(TRIGGER, SUCCESS));
    }

    private static ProcedureContent sop(List<String> steps) {
        return new ProcedureContent("每周导出周报", List.of(), steps, List.of());
    }

    /** 按最后一条用户消息脚本化回答的模型。 */
    private record Scripted(UnaryOperator<String> answer) implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            UserMessage last = (UserMessage) request.messages().get(request.messages().size() - 1);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(answer.apply(last.singleText()))).build();
        }
    }
}
