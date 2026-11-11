package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinToolsCommandInjectionTest {

    @Test
    void windowsStderrRedirectToNulDoesNotLookLikeAnExternalFileRedirect() {
        assertFalse(containsCommandInjection("where py node findstr 2>nul & ver"));
        assertFalse(containsCommandInjection("where py node findstr 2> nul & ver"));
        assertFalse(containsCommandInjection("where py 2>\"nul\" & ver"));
    }

    @Test
    void redirectionToAnExternalFileRemainsBlocked() {
        assertTrue(containsCommandInjection("where py > C:\\temp\\output.txt"));
        assertTrue(containsCommandInjection("where py > nul.txt"));
        assertTrue(containsCommandInjection("where py >\"nul\".txt"));
        assertTrue(containsCommandInjection("where py 2>nul & ver >C:\\temp\\output.txt"));
    }

    private static boolean containsCommandInjection(String command) {
        Boolean blocked = ReflectionTestUtils.invokeMethod(
                new BuiltinTools(), "containsCommandInjection", command);
        return Boolean.TRUE.equals(blocked);
    }
}
