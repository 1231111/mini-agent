package com.miniagent.agent.todo;

import com.miniagent.agent.planner.CodeValidationPolicy;
import com.miniagent.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CodeValidationReceiptTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void nodeBuiltInTestRunnerProvidesReceiptForStandaloneHtmlArtifact() {
        TaskTodoStore todos = new TaskTodoStore(temporaryDirectory.toString());
        String sessionId = "s_html_validation_receipt";
        todos.recordCodeChange(sessionId, "workspace/vla-ui.html");

        String customScript = "node test_vla_ui.js";
        assertThat(CodeValidationPolicy.isTestCommand(customScript)).isFalse();
        todos.recordTrustedCodeValidation(sessionId, customScript,
                ToolResult.success("exit_code=0\nALL CHECKS PASSED"));
        assertThat(todos.hasPendingCodeValidation(sessionId)).isTrue();

        String nodeTestRunner = "node --test test_vla_ui.test.js";
        assertThat(CodeValidationPolicy.isTestCommand(nodeTestRunner)).isTrue();
        assertThat(CodeValidationPolicy.isTestCommandForModules(nodeTestRunner, Set.of(), true)).isTrue();
        todos.recordTrustedCodeValidation(sessionId, nodeTestRunner,
                ToolResult.success("exit_code=0\n# tests 11\n# pass 11\n# fail 0"));

        assertThat(todos.hasPendingCodeValidation(sessionId)).isFalse();
        assertThat(todos.isCodeValidationSatisfied(sessionId)).isTrue();
    }
}
