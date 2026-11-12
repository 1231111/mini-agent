package com.miniagent.agent.desktop;

import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the registered schema and JSON handler before any desktop service is invoked. */
class DesktopToolsTest {
    private ToolRegistry registry;
    private DesktopInteractionService desktop;

    @BeforeEach void register() {
        registry = new ToolRegistry();
        desktop = mock(DesktopInteractionService.class);
        new DesktopTools(registry, desktop).register();
    }

    @Test void rejectsNullOrMissingClickCoordinatesBeforeDesktopDispatch() {
        for (String coordinates : List.of(
                "\"x\":null,\"y\":20", "\"x\":10,\"y\":null",
                "\"x\":null,\"y\":null", "\"y\":20", "\"x\":10")) {
            var result = registry.executeResult("desktop_act",
                    "{\"observationId\":\"observed\",\"action\":\"click\"," + coordinates + "}");
            assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.errorCode(), result.legacyText());
        }
        verifyNoInteractions(desktop);
    }

    @Test void rejectsOutOfRangeCoordinatesInsteadOfTruncatingThemIntoTheWindow() {
        for (String coordinates : List.of(
                "\"x\":4294967297,\"y\":20", "\"x\":10,\"y\":4294967297",
                "\"x\":-4294967295,\"y\":20", "\"x\":10,\"y\":-4294967295")) {
            var result = registry.executeResult("desktop_act",
                    "{\"observationId\":\"observed\",\"action\":\"click\"," + coordinates + "}");
            assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.errorCode(), result.legacyText());
        }
        verifyNoInteractions(desktop);
    }

    @Test void rejectsOutOfRangeScrollAmountsBeforeDesktopDispatch() {
        for (String amount : List.of("4294967297", "-4294967295")) {
            var result = registry.executeResult("desktop_act",
                    "{\"observationId\":\"observed\",\"action\":\"scroll\",\"amount\":" + amount + "}");
            assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.errorCode(), result.legacyText());
        }
        verifyNoInteractions(desktop);
    }

    @Test void passesValidCoordinatesExactlyAsProvided() {
        when(desktop.act(anyString(), any())).thenReturn(ToolResult.success("{\"success\":true}"));
        var result = registry.executeResult("desktop_act",
                "{\"observationId\":\"observed\",\"action\":\"click\",\"x\":12,\"y\":34}");
        assertTrue(result.isSuccess(), result.legacyText());
        ArgumentCaptor<DesktopBackend.Action> action = ArgumentCaptor.forClass(DesktopBackend.Action.class);
        verify(desktop).act(eq("observed"), action.capture());
        assertEquals(12, action.getValue().x().intValue());
        assertEquals(34, action.getValue().y().intValue());
    }
}
