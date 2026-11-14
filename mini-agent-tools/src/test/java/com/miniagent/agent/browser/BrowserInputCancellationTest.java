package com.miniagent.agent.browser;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.JSHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real service actor with controlled SDK waits, without touching a browser or desktop. */
class BrowserInputCancellationTest {
    @TempDir Path root;
    private final BrowserService service = new BrowserService();
    private final Page page = mock(Page.class);
    private final Locator input = mock(Locator.class);
    private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        ReflectionTestUtils.setField(service, "networkGuard", mock(NetworkGuard.class));
        ((Map<String, Page>) ReflectionTestUtils.getField(service, "pages")).put("chat", page);
        when(page.url()).thenReturn("https://fixture.test/");
        when(page.getByPlaceholder(anyString(), any(Page.GetByPlaceholderOptions.class))).thenReturn(input);
        when(input.count()).thenReturn(1);
    }

    @AfterEach void stop() { release.countDown(); service.shutdown(); }

    @ParameterizedTest @ValueSource(strings = {"type", "press", "scroll", "navigate", "evaluate"})
    void cancellationDuringPreparationCannotDispatchInputAfterTheCallerHasStopped(String action) throws Exception {
        when(page.isClosed()).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return false;
        });
        Supplier<String> operation = switch (action) {
            case "type" -> () -> service.type("chat", "Name", "Fixture");
            case "press" -> () -> service.press("chat", "Enter");
            case "scroll" -> () -> service.scroll("chat", "down");
            case "navigate" -> () -> service.navigate("chat", "https://fixture.test/next");
            case "evaluate" -> () -> service.evaluate("chat", "window.submitted=true");
            default -> throw new IllegalArgumentException(action);
        };
        cancelAndDrain(operation);
        verify(input, never()).fill(anyString(), any(Locator.FillOptions.class));
        verify(page, never()).keyboard();
        verify(page, never()).mouse();
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(page, never()).evaluate(anyString());
    }

    @ParameterizedTest @ValueSource(strings = {"select", "setChecked", "upload"})
    void cancellationDuringFormReadbackCannotDispatchALateFormMutation(String action) throws Exception {
        ElementHandle control = mock(ElementHandle.class);
        Locator body = mock(Locator.class), combobox = mock(Locator.class);
        when(page.locator("body")).thenReturn(body);
        when(body.ariaSnapshot()).thenReturn("- combobox \"Field\"");
        when(page.getByRole(eq(AriaRole.COMBOBOX), any(Page.GetByRoleOptions.class))).thenReturn(combobox);
        when(combobox.count()).thenReturn(1);
        when(combobox.elementHandle()).thenReturn(control);
        Map<String, Object> info = switch (action) {
            case "select" -> Map.of("tag", "select", "selectedValues", List.of(),
                    "options", List.of(Map.of("value", "shanghai", "label", "Shanghai")));
            case "setChecked" -> Map.of("tag", "input", "type", "checkbox", "checked", false);
            case "upload" -> Map.of("tag", "input", "type", "file");
            default -> throw new IllegalArgumentException(action);
        };
        when(control.evaluate(anyString())).thenAnswer(call -> {
            if ("el => el.isConnected".equals(call.getArgument(0))) return true;
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return info;
        });
        var match = Pattern.compile("\\[(b\\d+\\.\\d+)]").matcher(service.snapshot("chat", false));
        assertTrue(match.find());
        String ref = match.group(1);
        Path file = Files.writeString(root.resolve("fixture.txt"), "fixture");
        Supplier<String> operation = switch (action) {
            case "select" -> () -> service.select("chat", ref, List.of("shanghai"), "value");
            case "setChecked" -> () -> service.setChecked("chat", ref, true);
            case "upload" -> () -> service.upload("chat", ref, List.of(file));
            default -> throw new IllegalArgumentException(action);
        };
        cancelAndDrain(operation);
        verify(control, never()).selectOption(any(com.microsoft.playwright.options.SelectOption[].class),
                any(ElementHandle.SelectOptionOptions.class));
        verify(control, never()).setChecked(anyBoolean(), any(ElementHandle.SetCheckedOptions.class));
        verify(control, never()).setInputFiles(any(Path[].class), any(ElementHandle.SetInputFilesOptions.class));
    }

    @Test void cancellationAfterDropdownExpansionCannotClickTheOptionWhenItFinallyBecomesReady() throws Exception {
        ElementHandle control = mock(ElementHandle.class), option = mock(ElementHandle.class);
        Locator body = mock(Locator.class), combobox = mock(Locator.class);
        Frame frame = mock(Frame.class);
        JSHandle target = mock(JSHandle.class), ready = mock(JSHandle.class);
        when(page.locator("body")).thenReturn(body);
        when(body.ariaSnapshot()).thenReturn("- combobox \"City\"");
        when(page.getByRole(eq(AriaRole.COMBOBOX), any(Page.GetByRoleOptions.class))).thenReturn(combobox);
        when(combobox.count()).thenReturn(1);
        when(combobox.elementHandle()).thenReturn(control);
        when(control.evaluate("el => el.isConnected")).thenReturn(true);
        when(control.evaluate(BrowserFormSupport.READ_FIELD)).thenReturn(Map.of(
                "tag", "div", "role", "combobox", "customSelectSupported", true,
                "customPopupHidden", true, "selectedValues", List.of(),
                "options", List.of(Map.of("value", "shanghai", "label", "Shanghai"))));
        when(control.evaluateHandle(eq(BrowserFormSupport.CUSTOM_OPTION_HANDLE), any())).thenReturn(target);
        when(target.asElement()).thenReturn(option);
        when(control.ownerFrame()).thenReturn(frame);
        when(frame.waitForFunction(eq(BrowserFormSupport.CUSTOM_OPTION_READY), any(), any(Frame.WaitForFunctionOptions.class)))
                .thenAnswer(call -> {
                    entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return ready;
                });
        var match = Pattern.compile("\\[(b\\d+\\.\\d+)]").matcher(service.snapshot("chat", false));
        assertTrue(match.find());
        String ref = match.group(1);
        cancelAndDrain(() -> service.select("chat", ref, List.of("shanghai"), "value"));
        verify(control, times(1)).click(any(ElementHandle.ClickOptions.class));
        verify(option, never()).click(any(ElementHandle.ClickOptions.class));
    }

    private void cancelAndDrain(Supplier<String> operation) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> result.set(operation.get()));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the actor must be preparing an operation");
            caller.interrupt(); caller.join(2_000);
            assertFalse(caller.isAlive());
            assertEquals(ToolStatus.UNKNOWN, ToolResult.fromLegacy(result.get()).status(), result.get());
            assertTrue(service.hasPendingOperations("chat"), "a stopped caller cannot hide the still-running actor");
            release.countDown();
            assertEquals("drained", ReflectionTestUtils.invokeMethod(service, "onActor", (Callable<String>) () -> "drained"));
            assertFalse(service.hasPendingOperations("chat"));
        } finally {
            release.countDown(); caller.interrupt(); caller.join(5_000);
        }
    }
}
