package com.miniagent.agent.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionControlBudgetTest {

    @AfterEach
    void clearRunContext() {
        TaskExecutionContext.clear();
    }

    @Test
    void runBudgetAllowsExactlyConfiguredCallsAndOnlyResetsForANewRunToken() {
        ExecutionControl control = new ExecutionControl(60_000, 120, 1_000_000);
        control.start("s_execution_budget", null, "run-1");

        try (TaskExecutionContext.Scope ignored = TaskExecutionContext.bindRun("run-1")) {
            for (int call = 0; call < 120; call++) {
                assertThat(control.beforeTool("s_execution_budget"))
                        .as("tool call %s within run budget", call + 1)
                        .isEqualTo(ExecutionControl.StopReason.NONE);
            }
            assertThat(control.beforeTool("s_execution_budget"))
                    .isEqualTo(ExecutionControl.StopReason.TOOL_BUDGET_EXCEEDED);

            // Re-admitting the same run token is idempotent and cannot reset its counter.
            control.start("s_execution_budget", null, "run-1");
            assertThat(control.beforeTool("s_execution_budget"))
                    .isEqualTo(ExecutionControl.StopReason.TOOL_BUDGET_EXCEEDED);
        } finally {
            control.finish("s_execution_budget", "run-1");
        }

        control.start("s_execution_budget", null, "run-2");
        try (TaskExecutionContext.Scope ignored = TaskExecutionContext.bindRun("run-2")) {
            assertThat(control.beforeTool("s_execution_budget"))
                    .isEqualTo(ExecutionControl.StopReason.NONE);
        } finally {
            control.finish("s_execution_budget", "run-2");
        }
    }

    @Test
    void zeroRunToolBudgetMeansNoCountBasedTermination() {
        ExecutionControl control = new ExecutionControl(60_000, 0, 1_000_000);
        control.start("s_execution_unlimited", null, "run-unlimited");
        try (TaskExecutionContext.Scope ignored = TaskExecutionContext.bindRun("run-unlimited")) {
            for (int call = 0; call < 500; call++) {
                assertThat(control.beforeTool("s_execution_unlimited"))
                        .as("unlimited tool call %s", call + 1)
                        .isEqualTo(ExecutionControl.StopReason.NONE);
            }
        } finally {
            control.finish("s_execution_unlimited", "run-unlimited");
        }
    }
}
