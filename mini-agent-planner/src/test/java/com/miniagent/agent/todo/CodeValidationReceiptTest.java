package com.miniagent.agent.todo;

import com.miniagent.agent.planner.CodeValidationPolicy;
import com.miniagent.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CodeValidationReceiptTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void existingCodeVerificationDoesNotRequireANewDiffReview() {
        String verificationOnly = "验证当前已有 index.html 是否符合要求，不要修改或覆盖文件";
        String verifyAndRepair = "检查现有 index.html，有问题就修复";
        String implementation = "修复 index.html 的脚本错误并补充测试";

        assertThat(CodeValidationPolicy.isVerificationOnlyCodeGoal(verificationOnly)).isTrue();
        assertThat(CodeValidationPolicy.isCodeChangeGoal(verificationOnly)).isFalse();
        assertThat(CodeValidationPolicy.isVerificationOnlyCodeGoal(verifyAndRepair)).isFalse();
        assertThat(CodeValidationPolicy.isCodeChangeGoal(verifyAndRepair)).isTrue();
        assertThat(CodeValidationPolicy.isVerificationOnlyCodeGoal(implementation)).isFalse();
        assertThat(CodeValidationPolicy.isCodeChangeGoal(implementation)).isTrue();
    }

    @Test
    void verificationOfExistingCodeWithoutAnExplicitMutationStaysReadOnly() {
        assertThat(CodeValidationPolicy.isVerificationOnlyCodeGoal(
                "请审查现有 Java 源代码并报告测试结果")).isTrue();
        assertThat(CodeValidationPolicy.isVerificationOnlyCodeGoal(
                "Verify the existing index.html and report whether it passes the checks")).isTrue();
        assertThat(CodeValidationPolicy.isVerificationOnlyCodeGoal(
                "实现新的 index.html 页面")).isFalse();
    }

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

    @Test
    void legacyCodeValidationDoesNotResetPlannerManagedDagNodeStatus() {
        TaskTodoStore todos = new TaskTodoStore(temporaryDirectory.toString());
        String sessionId = "s_dag_code_status_is_planner_owned";
        Map<String, Object> dagNode = new LinkedHashMap<>();
        dagNode.put("id", 1);
        dagNode.put("content", "VLA 五步流程离线单页页面 vla-final.html");
        dagNode.put("status", "completed");
        dagNode.put("done_when", Map.of(
                "type", "file_exists",
                "path", "vla-final.html",
                "criteria", "页面包含五步和点击切换"));
        dagNode.put("evidence", "vla-final.html");
        todos.overwrite(sessionId, java.util.List.of(dagNode));

        assertThat(todos.isDagManaged(sessionId, 1)).isTrue();
        assertThat(todos.hasIncompleteCodeTask(sessionId)).isFalse();
        todos.recordCodeChange(sessionId, "vla-final.html");

        TaskTodoStore.TodoItem item = todos.get(sessionId).getFirst();
        assertThat(item.status()).isEqualTo(TaskTodoStore.Status.completed);
        assertThat(item.evidence()).isEqualTo("vla-final.html");
    }
}
