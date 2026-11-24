package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerCompilerContractTest {

    @Test
    void compilerPromptStatesTheValidatorHardConstraints() {
        String prompt = GoalCompiler.compilerSystem();

        assertThat(prompt)
                .contains("id、name 都必须非空", "capability 必须是上方词表里的已知能力",
                        "每个节点都必须有有效 doneWhen", "dependsOn 中的每个 id 必须真实存在",
                        "节点 inputs 只能引用 intent.inputs", "获取与持久化必须拆为不同节点",
                        "代码改动必须有独立 shell 测试节点", "逐条修复校验报告",
                        "字段职责不可混用", "intent.constraints 只放禁止项/实现限制",
                        "不要把 intent.constraints 整段复制到 successCriteria",
                        "节点 name 必须说清被交付/验收的对象")
                .doesNotContain("{CAPS}");
        for (String doneWhenType : List.of("note_required", "file_exists", "media_delivered",
                "llm_judge", "command_success", "validation_passed")) {
            assertThat(prompt).contains(doneWhenType);
        }
    }

    @Test
    void compilerPromptReflectsConfiguredGraphBudgets() {
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxGraphNodes(12);
        properties.setMaxGraphEdges(31);
        properties.setMaxGraphDepth(7);

        assertThat(GoalCompiler.graphBudgetPrompt(properties))
                .contains("最多 12 个节点", "31 条依赖边", "依赖深度 7");
    }

    @Test
    void replanBudgetIsConfigurableButHardCapped() {
        PlannerProperties properties = new PlannerProperties();

        assertThat(properties.getMaxReplanRetries()).isEqualTo(PlannerProperties.MAX_REPLAN_RETRIES_LIMIT);
        properties.setMaxReplanRetries(1);
        assertThat(properties.getMaxReplanRetries()).isEqualTo(1);
        properties.setMaxReplanRetries(-5);
        assertThat(properties.getMaxReplanRetries()).isZero();
        properties.setMaxReplanRetries(100);
        assertThat(properties.getMaxReplanRetries()).isEqualTo(PlannerProperties.MAX_REPLAN_RETRIES_LIMIT);
    }

    @Test
    void extraReplansContinueOnlyWhileAUsableGraphStrictlyReducesErrors() {
        PlanValidationReport elevenErrors = reportWithErrors(11);
        PlanValidationReport sixErrors = reportWithErrors(6);
        PlanValidationReport twoErrors = reportWithErrors(2);
        PlanValidationReport threeErrors = reportWithErrors(3);

        assertThat(PlanningLoop.hasStrictValidationProgress(elevenErrors, sixErrors, true)).isTrue();
        assertThat(PlanningLoop.hasStrictValidationProgress(sixErrors, twoErrors, true)).isTrue();
        assertThat(PlanningLoop.hasStrictValidationProgress(twoErrors, twoErrors, true)).isFalse();
        assertThat(PlanningLoop.hasStrictValidationProgress(twoErrors, threeErrors, true)).isFalse();
        assertThat(PlanningLoop.hasStrictValidationProgress(elevenErrors, sixErrors, false)).isFalse();
    }

    @Test
    void failureDiagnosticsRecordActualAttemptsRatherThanConfiguredBudget() {
        PlanValidationReport remaining = reportWithErrors(2);

        assertThat(PlanningLoop.validationFailureReason(3, 4, "initial", "final"))
                .contains("retries=3/4", "initial", "finalReplan");
        assertThat(PlanningLoop.recoveryValidationTrace(remaining, 3))
                .contains("\"correctionRetries\":3");
    }

    @Test
    void rejectedInitialGraphPersistsBoundedValidatorDiagnosticInTracePayload() {
        GoalCompiler.CompileResult rejected = new GoalCompiler.CompileResult(
                null, new TaskGraph(List.of()), true,
                GoalCompiler.CompilationSource.VALIDATION_ERROR,
                "PLAN_VALIDATION_FAILED: retries=2/4; initial=[CAPABILITY_MISMATCH]@n1; finalReplan=[INVALID_DONE_WHEN]@n1");

        String trace = PlanningLoop.invalidPlanTrace(rejected);

        assertThat(trace).contains("PLAN_VALIDATION_FAILED", "CAPABILITY_MISMATCH", "INVALID_DONE_WHEN",
                "retries=2/4");
        assertThat(trace).hasSizeLessThan(6_500);
        assertThat(PlanningLoop.invalidPlanTrace(null)).contains("missing compile result");
    }

    private static PlanValidationReport reportWithErrors(int count) {
        List<PlanValidationReport.ValidationError> errors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            errors.add(new PlanValidationReport.ValidationError(
                    PlanValidationReport.ValidationError.Type.GENERAL,
                    "n" + i, "validation error " + i, "fix error " + i));
        }
        return new PlanValidationReport(errors, List.of());
    }
}
