package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Legacy configuration stays bindable without creating an unsafe acceptance mode. */
@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings("deprecation")
class PlannerPropertiesCompatibilityTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void legacyStrictEvalBindsButCannotDisableAcceptance(boolean configured, CapturedOutput output) {
        PlannerProperties properties = bindStrictEval(configured);

        assertTrue(properties.isStrictEval());
        if (!configured) {
            assertTrue(output.getAll().contains("agent.planner.strict-eval 已弃用"));
            assertTrue(output.getAll().contains("false 不再生效"));
        }
    }

    @Test
    void disabledLegacyFlagCannotBypassMissingCommandOrJudgeEvidence() {
        PlannerProperties properties = bindStrictEval(false);
        StepEvaluator evaluator = new StepEvaluator(properties, new PlannerMetrics(), null);
        TaskNode command = node(DoneWhen.command());
        TaskNode judged = node(DoneWhen.judge("报告必须覆盖全部来源"));

        assertFalse(evaluator.evaluate(command, "命令完成了", "命令完成了").ok());
        assertFalse(evaluator.evaluate(judged, "报告完成了", "报告完成了").ok());
        assertTrue(evaluator.evaluate(command, "exit_code=0\nverified", "exit_code=0\nverified").ok());
    }

    private static PlannerProperties bindStrictEval(boolean configured) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(
                Map.of("agent.planner.strict-eval", Boolean.toString(configured)));
        return new Binder(source).bind("agent.planner", Bindable.of(PlannerProperties.class))
                .orElseThrow(() -> new IllegalStateException("planner configuration was not bound"));
    }

    private static TaskNode node(DoneWhen doneWhen) {
        return new TaskNode("verify", "核验结果", "shell", List.of(), List.of(), List.of("已核验结果"),
                TaskNodeStatus.READY, 50, doneWhen, "", "", 0, "");
    }
}
