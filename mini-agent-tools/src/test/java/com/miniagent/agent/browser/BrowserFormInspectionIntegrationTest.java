package com.miniagent.agent.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.security.NetworkGuard;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class BrowserFormInspectionIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private BrowserService browser;
    private HttpServer server;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = """
                    <!doctype html><meta charset="utf-8"><title>表单识别</title>
                    <form id="signup"><label>姓名<input id="name" name="fullName" required minlength="2" maxlength="60"></label>
                    <label>邮箱<input id="email" type="email" required></label>
                    <label>入职日期<input id="date" type="date" min="2026-01-01"></label>
                    <label>部门<select id="dept"><option value="rd">研发部</option><option value="hr" disabled>人事部</option></select></label>
                    <label>同意<input id="agree" type="checkbox" required></label>
                    <label>密码<input id="password" type="password" value="not-for-model"></label>
                    <label>简历<input id="file" type="file" accept=".pdf" style="display:none"></label>
                    <input id="csrf" type="hidden" value="csrf-secret"><textarea id="note" aria-label="备注">初始备注</textarea>
                    <button>提交</button></form><div role="combobox" aria-label="自定义城市" aria-expanded="false">北京</div>
                    <div id="custom" role="checkbox" aria-label="自定义勾选" aria-checked="true">自定义勾选</div>
                    <div id="empty" role="textbox" aria-label="自定义必填" aria-required="true" style="width:100px;height:30px"></div>
                    <div id="invalid" role="textbox" aria-label="自定义无效" aria-invalid="grammar">error</div>
                    <script>window.invalidEvents=0;document.addEventListener('invalid',()=>invalidEvents++,true);</script>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html;charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start(); browser = new BrowserService();
        ReflectionTestUtils.setField(browser, "networkGuard", mock(NetworkGuard.class));
        ReflectionTestUtils.setField(browser, "headless", true);
        browser.navigate("chat", "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stop() { if (browser != null) browser.shutdown(); if (server != null) server.stop(0); }

    @Test void discoversConstraintsOptionsHiddenUploadAndRedactsPassword() throws Exception {
        String raw = browser.formInspect("chat"); JsonNode result = json.readTree(raw);
        assertTrue(result.path("success").asBoolean(), raw);
        JsonNode name = field(result, "name");
        assertEquals("姓名", name.path("label").asText());
        assertTrue(name.path("required").asBoolean());
        assertEquals("60", name.path("constraints").path("maxlength").asText());
        assertEquals("signup", name.path("formId").asText());
        assertTrue(name.path("validity").path("valueMissing").asBoolean());
        assertTrue(field(result,"dept").path("options").get(1).path("disabled").asBoolean());
        assertEquals("file", field(result,"file").path("type").asText());
        assertFalse(field(result,"file").path("visible").asBoolean());
        assertFalse(raw.contains("not-for-model"),raw); assertFalse(raw.contains("csrf-secret"),raw);
        assertTrue(field(result,"password").path("valueRedacted").asBoolean());
        assertTrue(field(result,"password").path("hasValue").asBoolean());
        assertTrue(browser.evaluate("chat","window.invalidEvents").endsWith("0"));
    }

    @Test void readsActualValueRejectsMismatchAndDoesNotDispatchValidationEvents() throws Exception {
        JsonNode first = json.readTree(browser.formInspect("chat"));
        JsonNode missing = json.readTree(browser.formVerify("chat", field(first,"name").path("ref").asText(), null,null,null,null));
        assertFalse(missing.path("success").asBoolean()); assertFalse(missing.path("valid").asBoolean());
        browser.type("chat", field(first,"name").path("ref").asText(), "张三");
        String ref = field(json.readTree(browser.formInspect("chat")), "name").path("ref").asText();
        assertTrue(json.readTree(browser.formVerify("chat", ref, "张三",null,null,null)).path("success").asBoolean());
        JsonNode mismatch = json.readTree(browser.formVerify("chat", ref,"李四",null,null,null));
        assertFalse(mismatch.path("success").asBoolean()); assertFalse(mismatch.path("matched").asBoolean());
        assertEquals("张三", mismatch.path("field").path("value").asText());
        assertTrue(browser.evaluate("chat","window.invalidEvents").endsWith("0"));
    }

    @Test void silentValueOrConstraintChangesInvalidateRefsEvenWhenAriaTextStaysTheSame() throws Exception {
        JsonNode first = json.readTree(browser.formInspect("chat"));
        String ref = field(first,"name").path("ref").asText();
        // Mutation through an independent timer, not browser.evaluate (which itself invalidates refs).
        browser.evaluate("chat","setTimeout(()=>document.getElementById('name').minLength=30,200); true");
        first = json.readTree(browser.formInspect("chat")); ref = field(first,"name").path("ref").asText();
        Thread.sleep(300);
        assertTrue(browser.formVerify("chat",ref,null,null,null,null).contains("STALE_OBSERVATION"));
    }

    @Test void checksSelectCheckboxAndFilesAndRejectsPasswordComparisons() throws Exception {
        JsonNode data = json.readTree(browser.formInspect("chat"));
        assertTrue(json.readTree(browser.formVerify("chat",field(data,"dept").path("ref").asText(),null,null,java.util.List.of("rd"),null)).path("success").asBoolean());
        JsonNode unchecked = json.readTree(browser.formVerify("chat",field(data,"agree").path("ref").asText(),null,false,null,null));
        assertTrue(unchecked.path("matched").asBoolean()); assertFalse(unchecked.path("valid").asBoolean());
        assertTrue(json.readTree(browser.formVerify("chat",field(data,"file").path("ref").asText(),null,null,null,java.util.List.of())).path("success").asBoolean());
        assertTrue(browser.formVerify("chat",field(data,"password").path("ref").asText(),"guess",null,null,null).contains("PERMISSION_DENIED"));
        assertTrue(browser.formVerify("chat",field(data,"name").path("ref").asText(),"A",true,null,null).contains("INVALID_ARGUMENT"));
    }

    @Test void rejectsNonFieldRefsAndChecksCustomAriaStatesWithoutClaimingFullNativeValidation() throws Exception {
        JsonNode data = json.readTree(browser.formInspect("chat"));
        String snapshot = data.path("snapshot").asText();
        var button = java.util.regex.Pattern.compile("\\[(b\\d+\\.\\d+)] - button").matcher(snapshot);
        assertTrue(button.find(),snapshot);
        assertTrue(browser.formVerify("chat",button.group(1),null,null,null,null).contains("INVALID_ARGUMENT"));
        assertTrue(json.readTree(browser.formVerify("chat",field(data,"custom").path("ref").asText(),null,true,null,null)).path("success").asBoolean());
        assertFalse(json.readTree(browser.formVerify("chat",field(data,"empty").path("ref").asText(),null,null,null,null)).path("success").asBoolean());
        assertFalse(json.readTree(browser.formVerify("chat",field(data,"invalid").path("ref").asText(),null,null,null,null)).path("success").asBoolean());
    }

    @Test void hidingAShortPasswordDoesNotCorruptObservationIdentifiers() throws Exception {
        browser.evaluate("chat","document.getElementById('password').value='1'; true");
        JsonNode inspected = json.readTree(browser.formInspect("chat"));
        assertTrue(inspected.path("observationId").asText().matches("b[0-9]+"));
        String ref = field(inspected,"name").path("ref").asText();
        assertTrue(ref.matches("b[0-9]+\\.[0-9]+"));
        assertTrue(inspected.path("snapshot").asText().contains("["+ref.substring(0,ref.indexOf('.'))+"."));
        assertTrue(browser.type("chat",ref,"张三").contains("输入成功"));
    }

    private JsonNode field(JsonNode result, String id) {
        for (JsonNode field : result.path("fields")) if (id.equals(field.path("id").asText())) return field;
        throw new AssertionError("Missing " + id + ": " + result);
    }
}
