package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WritePathTest {

    @Test
    void basenameLandsOnWorkspaceRoot() {
        Path root = Path.of("C:/tmp/miniagent-ws").toAbsolutePath().normalize();
        Path out = BuiltinTools.resolveWritePath(root, "", "qa_s03_hello.txt", false);
        assertEquals(root.resolve("qa_s03_hello.txt").normalize(), out);
    }

    @Test
    void subAgentOverrideKeepsSubdir() {
        Path root = Path.of("C:/tmp/miniagent-ws").toAbsolutePath().normalize();
        Path out = BuiltinTools.resolveWritePath(root, "sub_1", "app.py", false);
        assertEquals(root.resolve("sub_1").resolve("app.py").normalize(), out);
    }
}
