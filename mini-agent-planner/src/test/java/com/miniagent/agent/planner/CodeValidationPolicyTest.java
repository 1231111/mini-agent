package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CodeValidationPolicyTest {

    @Test
    void recognizesEnglishAndChineseFileModificationGoals() {
        for (String goal : Set.of(
                "update Parser.java",
                "change Parser.java",
                "modify Parser.java",
                "edit src/main/java/Parser.java",
                "更新 Parser.java",
                "修改 src/main/java/Parser.java")) {
            assertTrue(CodeValidationPolicy.isCodeChangeGoal(goal), goal);
        }
        assertFalse(CodeValidationPolicy.isCodeChangeGoal("Tell me what Parser.java does"));
        assertFalse(CodeValidationPolicy.isCodeChangeGoal("fixture lookup"),
                "The word fixture must not be treated as a request to fix code.");
    }

    @Test
    void rejectsTestRunnerModesThatCanSucceedWithoutExecutingTests() {
        for (String command : Set.of(
                "pytest --help",
                "pytest --collect-only",
                "pytest --version",
                "pytest -V",
                "phpunit --help",
                "phpunit --version",
                "python -m unittest -h",
                "python -m unittest --help",
                "cargo test --no-run",
                "go test -run '^$'",
                "npm test --if-present",
                "mvn -Dmaven.test.failure.ignore=true test",
                "mvn -Dmaven.test.failure.ignore test",
                "mvn -DfailIfNoTests=false test",
                "mvn -Dsurefire.failIfNoSpecifiedTests=false test")) {
            assertFalse(CodeValidationPolicy.isTestCommand(command), command);
        }
    }

    @Test
    void acceptsActualRunnerInvocationsWithNormalOptions() {
        for (String command : Set.of(
                "pytest -v",
                "python -m pytest tests",
                "python -m unittest",
                "phpunit",
                "cargo test",
                "cargo test --workspace",
                "go test ./...",
                "npm test",
                "npm test --workspaces",
                "mvn test")) {
            assertTrue(CodeValidationPolicy.isTestCommand(command), command);
        }
    }

    @Test
    void recognizesScopedProjectSelectionAcrossSupportedRunners() {
        Set<String> changed = Set.of("mini-agent-planner");
        assertFalse(CodeValidationPolicy.isTestCommandForModules(
                "cargo test -p unrelated-crate", changed));
        assertFalse(CodeValidationPolicy.isTestCommandForModules(
                "cargo test", changed));
        assertTrue(CodeValidationPolicy.isTestCommandForModules(
                "cargo test -p mini-agent-planner", changed));
        assertFalse(CodeValidationPolicy.isTestCommandForModules(
                "npm test --workspace unrelated-package", changed));
        assertTrue(CodeValidationPolicy.isTestCommandForModules(
                "npm test --workspace mini-agent-planner", changed));
        assertFalse(CodeValidationPolicy.isTestCommandForModules(
                "pnpm --filter unrelated-package test", changed));
        assertFalse(CodeValidationPolicy.isTestCommandForModules(
                "npm test", changed));
        assertFalse(CodeValidationPolicy.isTestCommandForModules(
                "go test", changed));
        assertTrue(CodeValidationPolicy.isTestCommandForModules(
                "go test ./...", changed));
        assertTrue(CodeValidationPolicy.isTestCommandForModules(
                "npm test --workspaces", changed));
    }

    @Test
    void unknownAffectedModuleRequiresUnscopedWholeProjectTest() {
        assertFalse(CodeValidationPolicy.isTestCommandForModules(
                "mvn -pl mini-agent-planner test", Set.of("mini-agent-planner"), true));
        assertFalse(CodeValidationPolicy.isTestCommandForModules(
                "cargo test -p mini-agent-planner", Set.of("mini-agent-planner"), true));
        assertFalse(CodeValidationPolicy.isTestCommandForModules(
                "cargo test", Set.of("mini-agent-planner"), true));
        assertFalse(CodeValidationPolicy.isTestCommandForModules(
                "go test", Set.of("mini-agent-planner"), true));
        assertFalse(CodeValidationPolicy.isTestCommandForModules(
                "npm test --workspace mini-agent-planner", Set.of("mini-agent-planner"), true));
        assertTrue(CodeValidationPolicy.isTestCommandForModules(
                "mvn test", Set.of("mini-agent-planner"), true));
        assertTrue(CodeValidationPolicy.isTestCommandForModules(
                "cargo test --workspace", Set.of("mini-agent-planner"), true));
        assertTrue(CodeValidationPolicy.isTestCommandForModules(
                "go test ./...", Set.of("mini-agent-planner"), true));
        assertTrue(CodeValidationPolicy.isTestCommandForModules(
                "npm test --workspaces", Set.of("mini-agent-planner"), true));
    }

    @Test
    void scopedTestMustCoverEveryKnownChangedModule() {
        Set<String> changed = Set.of("mini-agent-planner", "mini-agent-tools");
        assertFalse(CodeValidationPolicy.isTestCommandForModules(
                "mvn -pl mini-agent-planner -am test", changed));
        assertTrue(CodeValidationPolicy.isTestCommandForModules(
                "mvn -pl mini-agent-planner,mini-agent-tools test", changed));
        assertFalse(CodeValidationPolicy.isTestCommandForModules(
                "cargo test -p mini-agent-planner", Set.of("mini-agent-planner", "mini-agent-tools")));
        assertTrue(CodeValidationPolicy.isTestCommandForModules(
                "cargo test -p mini-agent-planner -p mini-agent-tools",
                Set.of("mini-agent-planner", "mini-agent-tools")));
    }
}
