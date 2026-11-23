package com.miniagent.agent.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SelfCheckScriptDiagnosticTest {

    @TempDir
    Path workspace;

    @AfterEach
    void clearWorkspace() {
        WorkspaceContext.clearAll();
    }

    @Test
    void identifiesReferenceErrorInTheInvokedWorkspaceTestScriptWithoutPassingTheCommand() throws Exception {
        Path script = Files.writeString(workspace.resolve("test_ui.js"), "console.log(CASES);");
        WorkspaceContext.setRootOverride(workspace);
        String output = "ReferenceError: CASES is not defined\r\n"
                + "    at Object.<anonymous> (" + script + ":63:1)\r\n";

        var diagnostic = SelfCheckScriptDiagnostic.classify("node test_ui.js", 1, output, workspace);

        assertThat(diagnostic).isPresent();
        assertThat(diagnostic.get().asToolNote())
                .contains("[TEST_HARNESS_ERROR]", "test_ui.js", "ReferenceError", "命令仍然失败")
                .contains("不要仅凭此错误修改被测页面");
    }

    @Test
    void doesNotClassifyProductRuntimeFramesAsHarnessBugs() throws Exception {
        Path harness = Files.writeString(workspace.resolve("test_ui.js"), "renderPage();");
        Path product = Files.writeString(workspace.resolve("app.js"), "renderPage();");
        WorkspaceContext.setRootOverride(workspace);
        String productFailure = "ReferenceError: missingNode is not defined\n"
                + "    at renderPage (" + product + ":12:4)\n"
                + "    at Object.<anonymous> (" + harness + ":63:1)\n";

        assertThat(SelfCheckScriptDiagnostic.classify("node test_ui.js", 1, productFailure, workspace)).isEmpty();
        assertThat(SelfCheckScriptDiagnostic.classify("node test_ui.js", 0,
                "ReferenceError: CASES is not defined\n    at Object.<anonymous> (" + harness + ":1:1)",
                workspace)).isEmpty();
    }

    @Test
    void classifiesFailAssertionAsBlockingButDoesNotAttributeItToTheHarnessOrProduct() throws Exception {
        Files.writeString(workspace.resolve("test_ui.js"), "assertElementExists('p');");
        WorkspaceContext.setRootOverride(workspace);
        String output = "[FAIL] JS 引用缺失元素: ['p']\n";

        var diagnostic = SelfCheckScriptDiagnostic.classify("node test_ui.js", 1, output, workspace);

        assertThat(diagnostic).isPresent();
        assertThat(diagnostic.get().category()).isEqualTo(
                SelfCheckScriptDiagnostic.Category.TEST_ASSERTION_FAILURE);
        assertThat(diagnostic.get().asToolNote())
                .contains("[TEST_ASSERTION_FAILURE]", "阻断性失败", "实际 DOM", "原验收标准", "独立证据验收")
                .doesNotContain("[TEST_HARNESS_ERROR]");
    }

    @Test
    void assertionMarkerRequiresAnExplicitWorkspaceTestScriptAndAnExactFailLine() throws Exception {
        Files.writeString(workspace.resolve("test_ui.js"), "assert(true);");
        WorkspaceContext.setRootOverride(workspace);

        assertThat(SelfCheckScriptDiagnostic.classify("node test_ui.js", 1,
                "FAILURE: expected #p\n", workspace)).isEmpty();
        assertThat(SelfCheckScriptDiagnostic.classify("npm test", 1,
                "[FAIL] expected #p\n", workspace)).isEmpty();
        assertThat(SelfCheckScriptDiagnostic.classify("node -e \"console.log('[FAIL]')\"", 1,
                "[FAIL] expected #p\n", workspace)).isEmpty();
        assertThat(SelfCheckScriptDiagnostic.classify("node test_ui.js", 0,
                "[FAIL] expected #p\n", workspace)).isEmpty();
    }

    @Test
    void onlyClassifiesTheSameTestScriptNamedByTheCommandAndInsideTheActiveWorkspace() throws Exception {
        Path harness = Files.writeString(workspace.resolve("test_ui.js"), "run();");
        WorkspaceContext.setRootOverride(workspace);
        String output = "SyntaxError: Unexpected token\n    at Object.<anonymous> (" + harness + ":4:2)\n";

        assertThat(SelfCheckScriptDiagnostic.classify("node test_ui.js", 1, output, workspace)).isPresent();
        assertThat(SelfCheckScriptDiagnostic.classify("node app.js", 1, output, workspace)).isEmpty();

        Path outside = workspace.getParent().resolve("test_external.js");
        Files.writeString(outside, "broken();");
        String externalOutput = "SyntaxError: Unexpected token\n"
                + "    at Object.<anonymous> (" + outside + ":4:2)\n";
        assertThat(SelfCheckScriptDiagnostic.classify("node test_external.js", 1,
                externalOutput, workspace)).isEmpty();
        Files.deleteIfExists(outside);
    }
}
