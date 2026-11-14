package com.miniagent.web;

import com.microsoft.playwright.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Real Chromium renders the production JS/CSS; the review API is a local intercepted fixture. */
@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class ClientOutcomeReviewIntegrationTest {
    private static String resource(String name) throws Exception {
        try (var input = ClientOutcomeReviewIntegrationTest.class.getResourceAsStream("/static/" + name)) {
            if (input == null) throw new IllegalStateException("Missing client resource: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private Playwright runtime() {
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(repository.resolve("mini-agent-desktop"))) repository = repository.getParent();
        Path bundled = repository.resolve("mini-agent-desktop/browsers");
        var env = new java.util.HashMap<>(System.getenv()); env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        if (Files.isDirectory(bundled)) env.put("PLAYWRIGHT_BROWSERS_PATH", bundled.toString());
        return Playwright.create(new Playwright.CreateOptions().setEnv(env));
    }
    private static String response(boolean resolved) {
        String response = """
                {"success":true,"data":{"executionId":"fixture-execution","version":4,"planVersion":2,
                "nodes":[{"id":"verify","name":"核验候选航班可订性"}],
                "progress":{"status":"INTERRUPTED","reason":"LEGACY_DAG","items":[
                {"id":1,"content":"检索今日飞郑州航班","status":"completed"},
                {"id":2,"content":"核验候选航班可订性","status":"pending"}]}}}
                """;
        return resolved ? response.replace("\"nodes\":[{\"id\":\"verify\",\"name\":\"核验候选航班可订性\"}]", "\"nodes\":[]") : response;
    }
    private Page prepare(Browser browser, int width, AtomicInteger posts, AtomicReference<String> requestBody) throws Exception {
        Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(width, 900));
        page.route("http://miniagent-fixture.test/**", route -> {
            if (route.request().url().endsWith("/outcome-review")) {
                if (route.request().method().equals("POST")) {
                    posts.incrementAndGet(); requestBody.set(route.request().postData());
                }
                route.fulfill(new Route.FulfillOptions().setContentType("application/json")
                        .setBody(response(route.request().method().equals("POST"))));
            } else route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody(
                    "<!doctype html><html data-theme='light'><meta charset='utf-8'><body style='margin:0;display:block'>"
                    + "<main style='width:100%;max-width:840px;margin:0 auto;padding:20px;box-sizing:border-box'>"
                    + "<div class='bot-row'><div class='bot-body' id='reply'></div></div></main></body></html>"));
        });
        page.navigate("http://miniagent-fixture.test/");
        for (String css : new String[]{"chat.css", "shell.css", "ai-workspace.css", "chat-artifacts.css"})
            page.addStyleTag(new Page.AddStyleTagOptions().setContent(resource("css/" + css)));
        page.addScriptTag(new Page.AddScriptTagOptions().setContent("var currentId='fixture-chat';" + resource("js/chat/10-stream.js")));
        page.evaluate("""
                () => { const body = document.getElementById('reply'); body.dataset.executionId = 'fixture-execution';
                renderTaskProgress(body, [{id:1, content:'检索今日飞郑州航班', status:'completed'},
                    {id:2, content:'核验候选航班可订性', status:'blocked'}],
                    {status:'FAILURE',reason:'OUTCOME_UNKNOWN',live:false}); }
                """);
        return page;
    }

    @ParameterizedTest @ValueSource(ints = {1366, 390})
    void reviewIsInlineResponsiveAndRequiresExplicitEvidenceBeforeSubmission(int width) throws Exception {
        try (Playwright playwright = runtime(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            AtomicInteger posts = new AtomicInteger(); AtomicReference<String> payload = new AtomicReference<>();
            Page page = prepare(browser, width, posts, payload);
            page.locator(".outcome-review-open").click();
            page.locator(".outcome-review textarea").waitFor();
            assertEquals(0, posts.get());
            assertEquals(1, page.locator(".outcome-review").count());
            assertTrue(page.locator(".todo-progress-title").innerText().contains("待核验"));
            assertEquals("待核验", page.locator(".todo-item.blocked .todo-node-state").innerText());
            page.locator(".outcome-review button[type=submit]").click();
            assertEquals(0, posts.get());
            page.locator(".outcome-review select").nth(1).selectOption("CONFIRMED_NOT_APPLIED");
            page.locator(".outcome-review textarea").fill("已核对实际页面的订单列表，无新增记录且原操作已经退出。");
            page.locator(".outcome-review input[type=checkbox]").check();
            assertEquals(true, page.evaluate("() => document.documentElement.scrollWidth <= innerWidth"));
            String evidenceDir = System.getProperty("miniagent.ui.evidence");
            if (evidenceDir != null) {
                Path output = Path.of(evidenceDir); Files.createDirectories(output);
                page.screenshot(new Page.ScreenshotOptions().setPath(output.resolve("outcome-review-" + width + ".png")).setFullPage(true));
            }
            page.locator(".outcome-review button[type=submit]").click();
            page.waitForFunction("() => document.querySelector('.outcome-review').textContent.includes('核验已记录')");
            assertEquals(1, posts.get());
            var data = new ObjectMapper().readTree(payload.get());
            assertEquals("fixture-execution", data.path("executionId").asText());
            assertEquals("verify", data.path("nodeId").asText());
            assertTrue(data.path("confirmed").asBoolean());
            assertEquals("CONFIRMED_NOT_APPLIED", data.path("decision").asText());
            assertEquals(0, page.locator(".outcome-review form").count());
            assertTrue(page.locator(".outcome-review").innerText().contains("原任务"));
            page.close();
        }
    }

    @Test void historicCardCannotReviewDifferentCurrentExecution() throws Exception {
        try (Playwright playwright = runtime(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            AtomicInteger posts = new AtomicInteger();
            Page page = prepare(browser, 900, posts, new AtomicReference<>());
            page.evaluate("document.getElementById('reply').dataset.executionId='historic-task'");
            page.locator(".outcome-review-open").click();
            page.waitForFunction("() => document.querySelector('.outcome-review').textContent.includes('历史任务')");
            assertEquals(0, page.locator(".outcome-review form").count());
            assertEquals(0, posts.get());
            page.close();
        }
    }

    @Test void staleReviewCanReloadWithoutAutomaticallyResubmittingAnAction() throws Exception {
        try (Playwright playwright = runtime(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            AtomicInteger posts = new AtomicInteger(); AtomicInteger reads = new AtomicInteger();
            Page page = prepare(browser, 900, new AtomicInteger(), new AtomicReference<>());
            page.route("**/outcome-review", route -> {
                if (route.request().method().equals("POST")) {
                    posts.incrementAndGet();
                    route.fulfill(new Route.FulfillOptions().setContentType("application/json")
                            .setBody("{\"success\":false,\"code\":\"STATE_CONFLICT\",\"message\":\"任务状态已变化，请刷新后重新核验。\"}"));
                } else {
                    reads.incrementAndGet();
                    route.fulfill(new Route.FulfillOptions().setContentType("application/json").setBody(response(false)));
                }
            });
            page.locator(".outcome-review-open").click();
            page.locator(".outcome-review select").nth(1).selectOption("CONFIRMED_NOT_APPLIED");
            page.locator(".outcome-review textarea").fill("已检查回执及页面，确认操作未生效。");
            page.locator(".outcome-review input[type=checkbox]").check();
            page.locator(".outcome-review button[type=submit]").click();
            page.locator(".outcome-review-reload").waitFor();
            assertEquals(1, posts.get());
            page.locator(".outcome-review-reload").click();
            page.locator(".outcome-review textarea").waitFor();
            assertEquals(2, reads.get());
            assertEquals(1, posts.get());
            assertEquals("", page.locator(".outcome-review select").nth(1).inputValue());
            assertFalse(page.locator(".outcome-review input[type=checkbox]").isChecked());
            page.close();
        }
    }
}
