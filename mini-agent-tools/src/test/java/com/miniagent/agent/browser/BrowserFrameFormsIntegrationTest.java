package com.miniagent.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolStatus;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real Chromium: frame identity, native actions, explicit ARIA semantics and bounded discovery. */
@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class BrowserFrameFormsIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "frame-forms";
    @TempDir Path workspace;
    private BrowserService browser;
    private NetworkGuard guard;
    private HttpServer server;
    private HttpServer crossOrigin;

    @BeforeEach void start() throws Exception {
        crossOrigin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        crossOrigin.createContext("/", exchange -> respond(exchange, form("cross")));
        crossOrigin.start();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/graphic.svg".equals(path)) {
                respond(exchange, "<svg xmlns='http://www.w3.org/2000/svg' width='50' height='30'><rect width='20' height='20'/></svg>", "image/svg+xml");
                return;
            }
            if ("/data.xml".equals(path)) {
                respond(exchange, "<?xml version='1.0'?><records><item>Frame XML</item></records>", "application/xml");
                return;
            }
            String html = switch (path) {
                case "/child" -> form("child") + "<iframe id=nested src=/nested name=nested></iframe>";
                case "/nested" -> form("nested");
                case "/large" -> "<!doctype html><title>Large</title>" + inputs(110)
                        + "<iframe src=/large-child></iframe><iframe src=/large-third></iframe>";
                case "/large-child" -> inputs(110);
                case "/large-third" -> inputs(20);
                case "/custom" -> customControls();
                case "/nonhtml" -> "<!doctype html><title>Non HTML frames</title><label>姓名<input id=name></label>"
                        + "<iframe src=/graphic.svg name=graphic></iframe><iframe src=/data.xml name=xml></iframe>";
                case "/shadow" -> """
                        <!doctype html><meta charset=utf-8><title>Shadow labels</title>
                        <span id=caption>外部错误标签</span><span id=hint>外部错误说明</span><div id=host></div>
                        <div aria-hidden=true><input id=ariaHidden></div><div inert><input id=inertField></div>
                        <script>document.getElementById('host').attachShadow({mode:'open'}).innerHTML=
                          '<span id=caption>影子姓名</span><span id=hint>影子字段说明</span>'+
                          '<input id=shadowName aria-labelledby=caption aria-describedby=hint>';</script>
                        """;
                case "/inline" -> "<!doctype html><title>Inline</title>"
                        + "<iframe srcdoc=\"<label>内联<input id=inline></label>\"></iframe>"
                        + "<iframe src=\"data:text/html,%3Clabel%3EData%3Cinput%20id=data%3E%3C/label%3E\"></iframe>";
                default -> "<!doctype html><meta charset=utf-8><title>Frame forms</title>" + form("main")
                        + "<iframe id=same src=/child name=same></iframe><iframe id=cross name=cross src='http://127.0.0.1:"
                        + crossOrigin.getAddress().getPort() + "/cross'></iframe>";
            };
            respond(exchange, html);
        });
        server.start();
        browser = new BrowserService();
        guard = mock(NetworkGuard.class);
        ReflectionTestUtils.setField(browser, "networkGuard", guard);
        ReflectionTestUtils.setField(browser, "headless", true);
        open("/");
    }

    @AfterEach void stop() {
        if (browser != null) browser.shutdown();
        if (server != null) server.stop(0);
        if (crossOrigin != null) crossOrigin.stop(0);
    }

    @Test void isolatesDuplicateLabelsAcrossSameOriginCrossOriginAndNestedFrames() throws Exception {
        JsonNode data = inspect();
        assertEquals(4, data.path("frames").size());
        String top = field(data, "main", "name").path("ref").asText();
        String child = field(data, "main/0", "name").path("ref").asText();
        String nested = field(data, "main/0/0", "name").path("ref").asText();
        String cross = field(data, "main/1", "name").path("ref").asText();
        assertEquals(4, java.util.Set.of(top, child, nested, cross).size());
        assertTrue(browser.type(SESSION, cross, "跨源姓名").contains("输入成功"));
        assertTrue(success(browser.formVerify(SESSION, ref("main/1", "name"), "跨源姓名", null, null, null))
                .path("matched").asBoolean());
        assertEquals("", field(inspect(), "main", "name").path("value").asText());
        assertTrue(browser.type(SESSION, ref("main/0/0", "name"), "嵌套姓名").contains("输入成功"));
        assertEquals("嵌套姓名", field(inspect(), "main/0/0", "name").path("value").asText());
        assertEquals("", field(inspect(), "main/0", "name").path("value").asText());
    }

    @Test void performsNativeSelectionCheckingUploadVerificationAndSubmitInAChildFrame() throws Exception {
        assertEquals("rd", success(browser.select(SESSION, ref("main/0", "department"), List.of("研发"), "label"))
                .path("selectedValues").get(0).asText());
        assertTrue(success(browser.setChecked(SESSION, ref("main/0", "notify"), true)).path("checked").asBoolean());
        Path file = Files.writeString(workspace.resolve("frame.xml"), "<verified>yes</verified>").toAbsolutePath();
        success(browser.upload(SESSION, ref("main/0", "file"), List.of(file)));
        success(browser.formVerify(SESSION, ref("main/0", "file"), null, null, null, List.of("frame.xml")));
        JsonNode data = inspect();
        String snapshot = data.path("snapshot").asText();
        int start = snapshot.indexOf("frame=main/0 url=");
        assertTrue(start >= 0, snapshot);
        int end = snapshot.indexOf("\nframe=", start + 1);
        String block = snapshot.substring(start, end < 0 ? snapshot.length() : end);
        var button = Pattern.compile("\\[(b\\d+\\.\\d+)] - button \\\"提交\\\"").matcher(block);
        assertTrue(button.find(), block);
        assertTrue(browser.click(SESSION, button.group(1)).contains("点击成功"));
        assertTrue(browser.evaluate(SESSION, "document.querySelector('#same').contentWindow.submits === 1 && window.submits === 0").contains("true"));
    }

    @Test void redactsPasswordsInEveryFrameIncludingEchoedAriaText() throws Exception {
        String raw = browser.formInspect(SESSION);
        for (String name : List.of("main", "child", "nested", "cross")) assertFalse(raw.contains("secret-" + name), raw);
        JsonNode result = success(raw);
        assertTrue(field(result, "main/1", "password").path("valueRedacted").asBoolean());
        assertTrue(field(result, "main/1", "password").path("hasValue").asBoolean());
        assertFailure(browser.formVerify(SESSION, field(result, "main/1", "password").path("ref").asText(),
                "guess", null, null, null), ToolErrorCode.PERMISSION_DENIED);
    }

    @Test void rejectsOldRefsAfterSameUrlFrameReloadBeforeDispatch() throws Exception {
        browser.evaluate(SESSION, "setTimeout(()=>document.querySelector('#same').contentWindow.location.reload(),1000); true");
        String stale = ref("main/0", "notify");
        Thread.sleep(1200);
        assertFailure(browser.setChecked(SESSION, stale, true), ToolErrorCode.CONFLICT);
        assertFalse(field(inspect(), "main/0", "notify").path("checked").asBoolean());
    }

    @Test void rejectsOldRefsAfterIdenticalFrameReplacementOrNestedNavigation() throws Exception {
        browser.evaluate(SESSION, "setTimeout(()=>document.querySelector('#same').replaceWith(document.querySelector('#same').cloneNode(true)),1000); true");
        String stale = ref("main/0", "name");
        Thread.sleep(1200);
        assertFailure(browser.type(SESSION, stale, "不得填入"), ToolErrorCode.CONFLICT);
        browser.evaluate(SESSION, "setTimeout(()=>document.querySelector('#same').contentDocument.querySelector('#nested').contentWindow.location.reload(),1000); true");
        JsonNode afterReplacement = inspect();
        stale = field(afterReplacement, framePathByName(afterReplacement, "nested"), "notify").path("ref").asText();
        Thread.sleep(1200);
        assertFailure(browser.setChecked(SESSION, stale, true), ToolErrorCode.CONFLICT);
        JsonNode afterNavigation = inspect();
        assertFalse(field(afterNavigation, framePathByName(afterNavigation, "nested"), "notify").path("checked").asBoolean());
    }

    @Test void sharesTheCandidateBudgetAcrossFramesAndReportsTruncation() throws Exception {
        open("/large");
        JsonNode data = inspect();
        assertEquals(240, data.path("candidateCount").asInt());
        assertEquals(200, data.path("fields").size());
        assertTrue(data.path("truncated").asBoolean());
        long top = java.util.stream.StreamSupport.stream(data.path("fields").spliterator(), false)
                .filter(field -> "main".equals(field.path("framePath").asText())).count();
        assertEquals(110, top);
    }

    @Test void setsAriaCheckStatesIdempotentlyAndRejectsUncertainOrDisabledStates() throws Exception {
        open("/custom");
        assertTrue(success(browser.setChecked(SESSION, ref("main", "toggle"), true)).path("checked").asBoolean());
        assertTrue(success(browser.setChecked(SESSION, ref("main", "toggle"), true)).path("checked").asBoolean());
        assertTrue(browser.evaluate(SESSION, "window.toggleClicks === 1").contains("true"));
        assertFailure(browser.setChecked(SESSION, ref("main", "mixed"), true), ToolErrorCode.INVALID_ARGUMENT);
        assertFailure(browser.setChecked(SESSION, ref("main", "disabled"), true), ToolErrorCode.CONFLICT);
        ToolResult rejected = ToolResult.fromLegacy(browser.setChecked(SESSION, ref("main", "reject"), true));
        assertEquals(ToolStatus.UNKNOWN, rejected.status());
        assertFalse(rejected.retriable());
        assertTrue(browser.evaluate(SESSION, "window.rejectClicks === 1").contains("true"));
    }

    @Test void selectsOnlyUniquelyOwnedAriaOptionsAndReadsTheInputValue() throws Exception {
        open("/custom");
        JsonNode before = inspect();
        assertTrue(field(before, "main", "city").path("customSelectSupported").asBoolean());
        JsonNode selected = success(browser.select(SESSION, field(before, "main", "city").path("ref").asText(), List.of("上海"), "label"));
        assertEquals("sh", selected.path("selectedValues").get(0).asText());
        JsonNode data = inspect();
        assertEquals("上海", field(data, "main", "city").path("value").asText());
        success(browser.formVerify(SESSION, field(data, "main", "city").path("ref").asText(), "上海", null, null, null));
        assertTrue(browser.evaluate(SESSION, "window.cityClicks === 1 && window.unrelatedClicks === 0").contains("true"));
        assertFailure(browser.select(SESSION, ref("main", "city"), List.of("disabled"), "value"), ToolErrorCode.CONFLICT);
        assertFailure(browser.select(SESSION, ref("main", "ambiguous"), List.of("上海"), "label"), ToolErrorCode.INVALID_ARGUMENT);
        assertFailure(browser.select(SESSION, ref("main", "duplicate"), List.of("重复"), "label"), ToolErrorCode.CONFLICT);
    }

    @Test void acceptsInlineFramesOnlyWithAnAllowedParentChainAndRefusesBlockedFrameReads() throws Exception {
        open("/inline");
        JsonNode data = inspect();
        assertNotNull(field(data, "main/0", "inline"));
        assertNotNull(field(data, "main/1", "data"));
        when(guard.validateUrl(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).contains("/inline") ? "blocked parent" : null);
        assertFailure(browser.formInspect(SESSION), ToolErrorCode.PERMISSION_DENIED);
        reset(guard);
        open("/");
        when(guard.validateUrl(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).contains("/cross") ? "blocked frame" : null);
        assertFailure(browser.formInspect(SESSION), ToolErrorCode.PERMISSION_DENIED);
    }

    @Test void nonHtmlFramesDoNotBlockTheParentFormOrBypassUrlPolicy() throws Exception {
        open("/nonhtml");
        JsonNode data = inspect();
        assertEquals(3, data.path("frames").size());
        assertEquals(1, data.path("fields").size());
        assertEquals("姓名", field(data, "main", "name").path("label").asText());
        assertTrue(browser.type(SESSION, field(data, "main", "name").path("ref").asText(), "张三").contains("输入成功"));
        when(guard.validateUrl(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).endsWith(".xml") ? "blocked XML" : null);
        assertFailure(browser.formInspect(SESSION), ToolErrorCode.PERMISSION_DENIED);
    }

    @Test void resolvesShadowLabelsInTheirOwnRootAndSkipsAriaHiddenOrInertFields() throws Exception {
        open("/shadow");
        JsonNode data = inspect();
        assertEquals(1, data.path("fields").size());
        JsonNode field = field(data, "main", "shadowName");
        assertEquals("影子姓名", field.path("label").asText());
        assertEquals("影子字段说明", field.path("describedBy").asText());
        assertTrue(browser.type(SESSION, field.path("ref").asText(), "王五").contains("输入成功"));
        success(browser.formVerify(SESSION, ref("main", "shadowName"), "王五", null, null, null));
    }

    @Test void expandsAriaHiddenPopupsButNeverClicksHiddenOptionsOrStillHiddenPopups() throws Exception {
        open("/custom");
        for (String value : List.of("ariaHidden", "domHidden", "ancestorHidden"))
            assertFailure(browser.select(SESSION, ref("main", "city"), List.of(value), "value"), ToolErrorCode.CONFLICT);
        assertTrue(browser.evaluate(SESSION, "window.cityClicks === 0").contains("true"));
        assertEquals("sh", success(browser.select(SESSION, ref("main", "ariaCity"), List.of("sh"), "value"))
                .path("selectedValues").get(0).asText());
        assertTrue(browser.evaluate(SESSION, "window.ariaOpens === 1 && window.ariaClicks === 1").contains("true"));
        ToolResult closed = ToolResult.fromLegacy(browser.select(SESSION, ref("main", "closedCity"), List.of("sh"), "value"));
        assertEquals(ToolStatus.UNKNOWN, closed.status());
        assertFalse(closed.retriable());
        assertTrue(browser.evaluate(SESSION, "window.closedOpens === 1 && window.closedOptionClicks === 0").contains("true"));
    }

    private void open(String path) {
        String result = browser.navigate(SESSION, "http://127.0.0.1:" + server.getAddress().getPort() + path);
        assertTrue(ToolResult.fromLegacy(result).isSuccess(), result);
    }

    private String ref(String frame, String id) throws Exception { return field(inspect(), frame, id).path("ref").asText(); }
    private JsonNode inspect() throws Exception { return success(browser.formInspect(SESSION)); }
    private JsonNode success(String raw) throws Exception {
        JsonNode result = JSON.readTree(raw);
        assertTrue(result.path("success").asBoolean(), raw);
        return result;
    }
    private JsonNode field(JsonNode result, String frame, String id) {
        for (JsonNode field : result.path("fields"))
            if (id.equals(field.path("id").asText()) && frame.equals(field.path("framePath").asText())) return field;
        throw new AssertionError("Missing field " + frame + "/" + id + ": " + result);
    }
    private String framePathByName(JsonNode result, String name) {
        for (JsonNode frame : result.path("frames")) if (name.equals(frame.path("name").asText())) return frame.path("path").asText();
        throw new AssertionError("Missing frame " + name + ": " + result);
    }
    private void assertFailure(String raw, ToolErrorCode code) {
        ToolResult result = ToolResult.fromLegacy(raw);
        assertFalse(result.isSuccess(), raw);
        assertEquals(code, result.errorCode(), raw);
    }
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String html) throws java.io.IOException {
        respond(exchange, html, "text/html; charset=utf-8");
    }
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String html, String contentType) throws java.io.IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }
    private static String inputs(int count) {
        StringBuilder fields = new StringBuilder("<!doctype html><title>Fields</title>");
        for (int i = 0; i < count; i++) fields.append("<input aria-label='Field ").append(i).append("' id='f").append(i).append("'>");
        return fields.toString();
    }
    private static String form(String name) {
        return """
                <meta charset=utf-8><label>姓名<input id=name></label>
                <label>部门<select id=department><option value=''>请选择</option><option value=rd>研发</option></select></label>
                <label>通知<input id=notify type=checkbox></label><label>附件<input id=file type=file></label>
                <label>密码<input id=password type=password value='secret-%s'></label><p>secret-%s</p>
                <button onclick='window.submits++'>提交</button><script>window.submits=0;</script>
                """.formatted(name, name);
    }
    private static String customControls() {
        return """
                <!doctype html><meta charset=utf-8><title>Custom forms</title>
                <div id=toggle role=switch tabindex=0 aria-label=通知 aria-checked=false
                  onclick="window.toggleClicks++;this.setAttribute('aria-checked',this.getAttribute('aria-checked')==='false')">通知</div>
                <div id=mixed role=checkbox tabindex=0 aria-checked=mixed>混合</div>
                <div aria-disabled=true><div id=disabled role=checkbox tabindex=0 aria-checked=false>禁用</div></div>
                <div id=reject role=checkbox tabindex=0 aria-checked=false onclick='window.rejectClicks++'>拒绝状态</div>
                <input id=city role=combobox aria-label=城市 aria-controls=cities aria-expanded=false readonly value=北京
                  onclick="document.getElementById('cities').hidden=false;this.setAttribute('aria-expanded','true')">
                <div id=cities role=listbox hidden>
                  <div role=option data-value=bj aria-selected=true onclick="choose(this,'北京')">北京</div>
                  <div role=option data-value=sh aria-selected=false onclick="choose(this,'上海')">上海</div>
                  <div role=option data-value=ariaHidden aria-hidden=true aria-selected=false>ARIA隐藏</div>
                  <div role=option data-value=domHidden hidden aria-selected=false>DOM隐藏</div>
                  <div hidden><div role=option data-value=ancestorHidden aria-selected=false>内部祖先隐藏</div></div>
                  <div role=option data-value=disabled aria-disabled=true aria-selected=false>禁用城市</div></div>
                <div id=unrelated role=listbox><div role=option aria-selected=false onclick='window.unrelatedClicks++'>上海</div></div>
                <div id=ambiguous role=combobox aria-controls='cities unrelated'>无确定关联</div>
                <div id=duplicate role=combobox aria-controls=duplicates>重复</div>
                <div id=duplicates role=listbox><div role=option data-value=x aria-selected=false>重复</div>
                  <div role=option data-value=y aria-selected=false>重复</div></div>
                <input id=ariaCity role=combobox aria-controls=ariaCities readonly value=北京
                  onclick="window.ariaOpens++;document.getElementById('ariaCities').setAttribute('aria-hidden','false')">
                <div id=ariaCities role=listbox aria-hidden=true><div role=option data-value=bj aria-selected=true>北京</div>
                  <div role=option data-value=sh aria-selected=false onclick="window.ariaClicks++;
                    this.previousElementSibling.setAttribute('aria-selected','false');this.setAttribute('aria-selected','true');
                    document.getElementById('ariaCity').value='上海';this.parentElement.setAttribute('aria-hidden','true')">上海</div></div>
                <input id=closedCity role=combobox aria-controls=closedCities readonly value=北京 onclick='window.closedOpens++'>
                <div id=closedCities role=listbox aria-hidden=true><div role=option data-value=sh aria-selected=false
                  onclick="window.closedOptionClicks++;this.setAttribute('aria-selected','true')">上海</div></div>
                <script>window.toggleClicks=0;window.rejectClicks=0;window.cityClicks=0;window.unrelatedClicks=0;
                  window.ariaOpens=0;window.ariaClicks=0;window.closedOpens=0;window.closedOptionClicks=0;
                  function choose(option,label){window.cityClicks++;
                    document.querySelectorAll('#cities [role=option]').forEach(o=>o.setAttribute('aria-selected',o===option));
                    document.getElementById('city').value=label;document.getElementById('cities').hidden=true;
                    document.getElementById('city').setAttribute('aria-expanded','false');}</script>
                """;
    }
}
