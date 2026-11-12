package com.miniagent.agent.desktop;

import com.sun.jna.platform.win32.WinUser;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies input cleanup with a fake native sender; never sends real OS input. */
class WindowsDesktopInputTest {
    @Test void partialUnicodePairIsRecoveredBySendingOnlyTheMissingKeyUp() {
        List<WinUser.INPUT[]> submissions = new ArrayList<>();
        var backend = new WindowsDesktopBackend(inputs -> {
            submissions.add(inputs);
            return submissions.size() == 1 ? 1 : inputs.length;
        });
        char character = '\u4e2d';
        assertThrows(IllegalStateException.class, () -> backend.sendUnicode(character));
        assertEquals(1, submissions.size());
        assertEquals(2, submissions.get(0).length);
        assertUnicode(submissions.get(0)[0], character, false);
        assertUnicode(submissions.get(0)[1], character, true);

        backend.releaseInputs();
        assertEquals(2, submissions.size());
        assertEquals(1, submissions.get(1).length, "cleanup must not inject a second key down");
        assertUnicode(submissions.get(1)[0], character, true);
        backend.releaseInputs();
        assertEquals(2, submissions.size(), "acknowledged cleanup must clear the pending Unicode key");
    }

    @Test void acknowledgedUnicodePairDoesNotGenerateAnyExtraCleanupInput() {
        List<WinUser.INPUT[]> submissions = new ArrayList<>();
        var backend = new WindowsDesktopBackend(inputs -> {
            submissions.add(inputs);
            return inputs.length;
        });
        backend.sendUnicode('\u6587');
        backend.releaseInputs();
        assertEquals(1, submissions.size());
        assertEquals(2, submissions.get(0).length);
        assertUnicode(submissions.get(0)[0], '\u6587', false);
        assertUnicode(submissions.get(0)[1], '\u6587', true);
    }

    private static void assertUnicode(WinUser.INPUT input, char character, boolean up) {
        assertEquals(WinUser.INPUT.INPUT_KEYBOARD, input.type.intValue());
        assertEquals(0, input.input.ki.wVk.intValue());
        assertEquals(character, input.input.ki.wScan.intValue());
        assertEquals(0x0004 | (up ? 0x0002 : 0), input.input.ki.dwFlags.intValue());
    }
}
