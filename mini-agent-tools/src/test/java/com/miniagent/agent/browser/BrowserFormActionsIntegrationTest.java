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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real Chromium proves selected/checked/file state, stale rejection and non-replayed side effects. */
@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class BrowserFormActionsIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;
    private BrowserService browser;
    private HttpServer fixture;

    @BeforeEach void start() throws Exception {
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            byte[] html = """
                    <!doctype html><html lang="zh"><meta charset="utf-8"><title>Form fixture</title>
                    <label>部门<select id="department" name="department"><option value="">请选择</option>
                      <option value="engineering">研发部</option><option value="finance">财务部</option>
                      <option value="blocked" disabled>禁用项</option></select></label>
                    <label>技能<select id="skills" name="skills" multiple><option value="java">Java</option>
                      <option value="python">Python</option><option value="sql">SQL</option></select></label>
                    <label>通知<input type="checkbox" id="notify" name="notify" onchange="window.changes++"></label>
                    <label>线上<input type="radio" name="mode" id="online" value="online"></label>
                    <label>线下<input type="radio" name="mode" id="offline" value="offline"></label>
                    <label>附件<input type="file" id="attachment" name="attachment" multiple></label>
                    <label>隐藏附件<input type="file" id="hiddenAttachment" name="hiddenAttachment" style="display:none"></label>
                    <label>拒绝附件<input type="file" id="rejectAttachment" name="rejectAttachment" onchange="this.value=''"></label>
                    <script>window.changes=0;</script></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        fixture.start();
        browser = new BrowserService();
        ReflectionTestUtils.setField(browser, "networkGuard", mock(NetworkGuard.class));
        ReflectionTestUtils.setField(browser, "headless", true);
        assertTrue(browser.navigate("form-chat", "http://127.0.0.1:" + fixture.getAddress().getPort() + "/").contains("Form fixture"));
    }

    @AfterEach void stop() {
        if (browser != null) browser.shutdown();
        if (fixture != null) fixture.stop(0);
    }

    @Test void selectsByLabelAndValueIncludingMultipleAndConsumesObservation() throws Exception {
        String oldRef = ref("department");
        JsonNode single = success(browser.select("form-chat", oldRef, List.of("研发部"), "label"));
        assertEquals("engineering", single.path("selectedValues").get(0).asText());
        assertTrue(browser.evaluate("form-chat", "document.querySelector('#department').value").contains("engineering"));
        assertEquals(ToolErrorCode.CONFLICT, ToolResult.fromLegacy(browser.select("form-chat", oldRef,
                List.of("finance"), "value")).errorCode());

        JsonNode multiple = success(browser.select("form-chat", ref("skills"), List.of("sql", "java"), "value"));
        assertEquals(List.of("java", "sql"), JSON.convertValue(multiple.path("selectedValues"), List.class));
        assertTrue(success(browser.select("form-chat", ref("skills"), List.of(), "value")).path("selectedValues").isEmpty());
    }

    @Test void rejectsUnavailableOrAmbiguousSelectionsBeforeDispatch() throws Exception {
        assertFailure(browser.select("form-chat", ref("department"), List.of("blocked"), "value"), ToolErrorCode.CONFLICT);
        assertFailure(browser.select("form-chat", ref("department"), List.of("missing"), "label"), ToolErrorCode.CONFLICT);
        assertFailure(browser.select("form-chat", ref("department"), List.of("finance", "engineering"), "value"), ToolErrorCode.INVALID_ARGUMENT);
        assertTrue(browser.evaluate("form-chat", "document.querySelector('#department').value === ''").contains("true"));
        browser.evaluate("form-chat", "document.querySelector('#skills').insertAdjacentHTML('beforeend','<option value=java>Duplicate Java</option>');true");
        assertFailure(browser.select("form-chat", ref("skills"), List.of("java"), "value"), ToolErrorCode.CONFLICT);
    }

    @Test void checkedIsAnExplicitIdempotentStateAndRadioMustSwitchToAnotherOption() throws Exception {
        assertTrue(success(browser.setChecked("form-chat", ref("notify"), true)).path("checked").asBoolean());
        assertTrue(success(browser.setChecked("form-chat", ref("notify"), true)).path("checked").asBoolean());
        assertTrue(browser.evaluate("form-chat", "window.changes === 1").contains("true"), "setting true twice must not toggle or fire two changes");
        assertFalse(success(browser.setChecked("form-chat", ref("notify"), false)).path("checked").asBoolean());
        assertTrue(success(browser.setChecked("form-chat", ref("online"), true)).path("checked").asBoolean());
        assertFailure(browser.setChecked("form-chat", ref("online"), false), ToolErrorCode.INVALID_ARGUMENT);
        success(browser.setChecked("form-chat", ref("offline"), true));
        assertTrue(browser.evaluate("form-chat", "document.querySelector('#offline').checked && !document.querySelector('#online').checked").contains("true"));
    }

    @Test void uploadsActualFilesIncludingHiddenInputsAndReadsNamesSizesAndContents() throws Exception {
        Path first = Files.writeString(workspace.resolve("简历.txt"), "MiniAgent 文件上传验证", StandardCharsets.UTF_8);
        Path second = Files.writeString(workspace.resolve("data.xml"), "<record>42</record>");
        JsonNode result = success(browser.upload("form-chat", ref("attachment"), List.of(first.toAbsolutePath(), second.toAbsolutePath())));
        assertEquals(2, result.path("files").size());
        assertEquals("简历.txt", result.path("files").get(0).path("name").asText());
        assertEquals(Files.size(first), result.path("files").get(0).path("size").asLong());
        assertTrue(browser.evaluate("form-chat", "async () => await document.querySelector('#attachment').files[0].text()").contains("MiniAgent 文件上传验证"));
        assertEquals(1, success(browser.upload("form-chat", ref("hiddenAttachment"), List.of(first.toAbsolutePath()))).path("files").size());
    }

    @Test void refusesDirectoriesAndSingleInputMultipleFilesWithoutChangingActualFiles() throws Exception {
        assertFailure(browser.upload("form-chat", ref("attachment"), List.of(workspace.toAbsolutePath())), ToolErrorCode.INVALID_ARGUMENT);
        Path first = Files.writeString(workspace.resolve("one.txt"), "one").toAbsolutePath();
        Path second = Files.writeString(workspace.resolve("two.txt"), "two").toAbsolutePath();
        assertFailure(browser.upload("form-chat", ref("hiddenAttachment"), List.of(first, second)), ToolErrorCode.INVALID_ARGUMENT);
        assertTrue(browser.evaluate("form-chat", "document.querySelector('#hiddenAttachment').files.length === 0").contains("true"));
    }

    @Test void doesNotReportSuccessWhenPageRejectsAnAlreadyDispatchedFile() throws Exception {
        Path first = Files.writeString(workspace.resolve("one.txt"), "one").toAbsolutePath();
        ToolResult rejected = ToolResult.fromLegacy(browser.upload("form-chat", ref("rejectAttachment"), List.of(first)));
        assertEquals(ToolStatus.UNKNOWN, rejected.status());
        assertFalse(rejected.retriable());
        assertTrue(browser.evaluate("form-chat", "document.querySelector('#rejectAttachment').files.length === 0").contains("true"));
    }

    @Test void neverActsOnAnIdenticallyReplacedNodeFromAnOldObservation() throws Exception {
        String stale = ref("notify");
        browser.evaluate("form-chat", "document.querySelector('#notify').replaceWith(document.querySelector('#notify').cloneNode(true));true");
        assertFailure(browser.setChecked("form-chat", stale, true), ToolErrorCode.CONFLICT);
        assertTrue(browser.evaluate("form-chat", "!document.querySelector('#notify').checked").contains("true"));
    }

    private String ref(String id) throws Exception {
        JsonNode inspected = success(browser.formInspect("form-chat"));
        for (JsonNode field : inspected.path("fields")) if (id.equals(field.path("id").asText())) return field.path("ref").asText();
        fail("No field ref for " + id + ": " + inspected);
        return null;
    }

    private JsonNode success(String raw) throws Exception {
        JsonNode result = JSON.readTree(raw);
        assertTrue(result.path("success").asBoolean(), raw);
        return result;
    }

    private void assertFailure(String raw, ToolErrorCode code) {
        ToolResult result = ToolResult.fromLegacy(raw);
        assertFalse(result.isSuccess(), raw);
        assertEquals(code, result.errorCode(), raw);
    }
}
