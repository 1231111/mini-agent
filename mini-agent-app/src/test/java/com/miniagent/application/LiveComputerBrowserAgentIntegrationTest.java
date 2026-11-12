package com.miniagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.browser.BrowserService;
import com.miniagent.agent.browser.BrowserSessionIdentity;
import com.miniagent.agent.core.*;
import com.miniagent.agent.desktop.*;
import com.miniagent.agent.execution.*;
import com.miniagent.agent.hook.*;
import com.miniagent.agent.permission.*;
import com.miniagent.agent.planner.*;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.agent.surface.ToolSurface;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.todo.TaskTodoContext;
import com.miniagent.agent.todo.TodoTool;
import com.miniagent.agent.todo.LlmJudgeTodoValidator;
import com.miniagent.agent.tool.*;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.common.io.AgentDataPaths;
import com.miniagent.common.model.EffectiveModelContext;
import com.miniagent.config.model.*;
import com.miniagent.config.repository.UserModelConfigRepository;
import com.miniagent.config.service.UserModelConfigService;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.swing.*;
import java.awt.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in paid/live-model acceptance test. Runs the production loop, execution pipeline,
 * tool schemas, native desktop backend and Chromium. The model selects every action;
 * this test supplies no element refs or click coordinates. It only confines execution
 * to its own temporary page/window and independently checks resulting UI state.
 *
 * No application database is opened. The only repository substitute returns no user
 * override, so the real UserModelConfigService/ModelClientFactory resolve YAML presets.
 */
@EnabledIfSystemProperty(named = "miniagent.live.control", matches = "true")
class LiveComputerBrowserAgentIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long USER = 999_991L;
    private static final String TENANT = "live-control-fixture";
    private static final String BROWSER_TEXT = "MiniAgent浏览器中文验证";
    private static final String DESKTOP_TEXT = "MiniAgent桌面中文验证";

    @AfterEach void clearContexts() {
        AgentLoop.clearCurrentSession(); AgentLoop.clearCurrentModels();
        LoopTurnContext.clear(); ExecutionTurnContext.clear();
        PermissionContext.clear(); PermissionContext.bindStore(null);
        TaskTodoContext.clear();
        WorkspaceContext.clearAll(); OwnerContext.clearCurrentUser(); OwnerContext.clearCurrentTenant();
        Thread.interrupted();
    }

    @Test @Timeout(600)
    void liveModelAutomaticallyPlansAndDeliversABrowserDashboard() throws Exception {
        Path evidence = evidence("auto-dag");
        ToolRegistry registry = new ToolRegistry();
        BrowserService browser = new BrowserService();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String route = exchange.getRequestURI().getPath();
            Path file = evidence.resolve(route.substring(1)).normalize();
            if (route.equals("/")) {
                try (var paths = Files.walk(evidence, 2)) {
                    file = paths.filter(path -> path.toString().endsWith(".html")).findFirst().orElse(file);
                }
            }
            if (!file.startsWith(evidence) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1); exchange.close(); return;
            }
            byte[] bytes = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", file.toString().endsWith(".html")
                    ? "text/html; charset=utf-8" : "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        NetworkGuard fixtureGuard = new NetworkGuard() {
            @Override public String validateUrl(String candidate) {
                try {
                    URI uri = URI.create(candidate);
                    return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                            && uri.getPort() == server.getAddress().getPort() ? null : "Only dashboard fixture is allowed";
                } catch (RuntimeException invalid) { return "Invalid fixture URL"; }
            }
        };
        ReflectionTestUtils.setField(browser, "networkGuard", fixtureGuard);
        ReflectionTestUtils.setField(browser, "headless", true);
        Fixture fixture = null;
        try {
            BuiltinTools builtins = new BuiltinTools();
            ReflectionTestUtils.setField(builtins, "registry", registry);
            ReflectionTestUtils.setField(builtins, "browserService", browser);
            ReflectionTestUtils.setField(builtins, "networkGuard", fixtureGuard);
            ReflectionTestUtils.invokeMethod(builtins, "registerFileTools");
            ReflectionTestUtils.invokeMethod(builtins, "registerBrowserTools");
            registry.unregister("read_package");
            fixture = fixture("auto-dag", evidence, registry, 540_000, 100);
            TaskTodoStore todos = (TaskTodoStore) ReflectionTestUtils.getField(fixture.loop(), "taskTodoStore");
            ToolPipeline pipeline = (ToolPipeline) ReflectionTestUtils.getField(fixture.loop(), "toolPipeline");
            TodoTool todoTool = new TodoTool();
            ReflectionTestUtils.setField(todoTool, "toolRegistry", registry);
            ReflectionTestUtils.setField(todoTool, "todoStore", todos);
            todoTool.register();
            TaskTodoContext.set(fixture.session());
            PlannerProperties properties = new PlannerProperties();
            properties.setMaxReplanRetries(1);
            properties.setCompilerRetry(0);
            properties.setMaxOuterRounds(16);
            PlannerStateStore store = new PlannerStateStore();
            CapabilityRegistry capabilities = new CapabilityRegistry(registry);
            PlannerMetrics metrics = new PlannerMetrics();
            LlmJudgeTodoValidator judge = new LlmJudgeTodoValidator();
            ReflectionTestUtils.setField(judge, "chatModel", fixture.model().chat());
            List<TaskGraph> graphHistory = new CopyOnWriteArrayList<>();
            TodoStateProjector projector = new TodoStateProjector(todos) {
                @Override public void project(String session, TaskGraph graph) {
                    graphHistory.add(graph);
                    writeQuietly(evidence.resolve("graph-history.json"), graphHistory);
                    super.project(session, graph);
                }
            };
            List<GoalCompiler.CompileResult> compilations = new CopyOnWriteArrayList<>();
            GoalCompiler compiler = new GoalCompiler(properties) {
                @Override public CompileResult compile(dev.langchain4j.model.chat.ChatModel chat, String message, TaskPlan plan) {
                    CompileResult result = super.compile(chat, message, plan);
                    compilations.add(result);
                    writeQuietly(evidence.resolve("compilations.json"), compilations);
                    writeQuietly(evidence.resolve("validation.json"), new PlanValidator().validate(result.graph(), plan, result.goal(), result.intent()));
                    writeQuietly(evidence.resolve("intent-validation.json"), new IntentGraphValidator().validate(result.intent(), result.graph()));
                    return result;
                }
                @Override public CompileResult compileWithCorrection(dev.langchain4j.model.chat.ChatModel chat, String message,
                                                                      TaskPlan plan, String correction) {
                    writeQuietly(evidence.resolve("correction.json"), correction);
                    CompileResult result = super.compileWithCorrection(chat, message, plan, correction);
                    compilations.add(result);
                    writeQuietly(evidence.resolve("compilations.json"), compilations);
                    writeQuietly(evidence.resolve("validation-corrected.json"), new PlanValidator().validate(result.graph(), plan, result.goal(), result.intent()));
                    writeQuietly(evidence.resolve("intent-validation-corrected.json"), new IntentGraphValidator().validate(result.intent(), result.graph()));
                    return result;
                }
            };
            PlanningLoop planner = new PlanningLoop(properties, compiler, new PlanValidator(), store,
                    new GraphScheduler(properties), new ToolRouter(capabilities), new StepEvaluator(properties, metrics, judge),
                    new RecoveryEngine(store, capabilities, properties), projector,
                    new NodeExecutor(fixture.loop(), pipeline), metrics,
                    (sid, fence) -> sid.equals(AgentLoop.getCurrentSession()), new ToolSuccessStats());
            List<Map<String, Object>> matrix = new ArrayList<>();
            String[][] cases = {
                    {"你好", "direct"},
                    {"帮我查询今日AI新闻", "direct"},
                    {"比较今日AI新闻的多个原始来源，核对冲突信息，形成带来源的技术趋势对比分析。", "dag"},
                    {"根据这个技术需求文档给我设计一个大屏工作台，大屏用来展示虚拟仿真其他要可以放在pc浏览器中", "dag"}
            };
            PermissionContext.force(fixture.session(), PermissionMode.DEFAULT, ConfirmPolicy.DANGEROUS, true);
            for (String[] item : cases) {
                PlanningRoute decision = planner.selectRoute(fixture.model().chat(), TaskPlan.of(item[0]),
                        fixture.session(), item[0], null, List.of());
                matrix.add(Map.of("task", item[0], "expected", item[1], "decision", decision));
                writeQuietly(evidence.resolve("route-matrix.json"), matrix);
                assertEquals(item[1].equals("dag"), decision.useDag(), decision.toString());
                assertEquals("model", decision.source(), "Classification must use the live model");
            }
            String task = cases[3][0] + "。请实际实现可运行的单页，包含虚拟仿真、AI算法、闭环控制、"
                    + "场景管理、测评和硬件监控六个模块，场景切换按钮要能工作；适合PC浏览器使用。"
                    + "完成后用浏览器实际检查页面与交互，交付可打开的页面链接，不生成说明报告。\n"
                    + "本次功能验收用简洁的六模块卡片页面即可，每个模块显示一个状态字段；数据在切换场景时变化即可。\n"
                    + "验收环境已将当前工作区通过 " + url + " 提供静态服务，直接使用此地址加文件名访问，无需另启服务器。\n"
                    + "===== 附件#1：技术要求.docx（正文）=====\n"
                    + "虚拟仿真展示设备运行，AI算法卡展示推理状态，闭环控制展示目标值与当前值，"
                    + "场景管理支持生产/测试场景切换，测评展示准确率与延迟，硬件监控显示CPU与内存。"
                    + "演示数据需标明示例，不依赖外网资源。";
            PermissionContext.force(fixture.session(), PermissionMode.ACCEPT_EDITS, ConfirmPolicy.AUTO, true);
            var decision = planner.selectRoute(fixture.model().chat(), TaskPlan.of(task), fixture.session(), task, null, List.of());
            writeQuietly(evidence.resolve("execution-route.json"), decision);
            assertTrue(decision.useDag());
            assertEquals(Boolean.TRUE, decision.artifactDeliveryRequested());
            long started = System.nanoTime();
            var outcome = planner.runWithOutcome(fixture.model().chat(),
                    "实际实现并验证用户要求的页面，文件保存在当前工作区。不要生成说明报告或只回答设计方案。"
                            + "当前工作区由临时HTTP服务器提供，根地址为 " + url + "；文件写入后可用此地址加文件名打开。"
                            + "这是已批准的本机验收地址，仅允许该地址。页面需用内联CSS/JS，无外网依赖。"
                            + "按本轮DAG节点执行，节点内部操作与验证不另建重复todo。"
                            + "浏览器工具使用最新ref，操作后重新观察，不凭空宣称通过。",
                    task, null, List.of(), TaskPlan.of(task), fixture.session(), "live-fence", "live-execution", null, decision);
            StateSnapshot snapshot = store.get(fixture.session()).orElseThrow();
            writeQuietly(evidence.resolve("final-state.json"), snapshot);
            writeQuietly(evidence.resolve("result.json"), Map.of("outcome", outcome, "route", decision,
                    "elapsedSeconds", (System.nanoTime() - started) / 1_000_000_000d,
                    "metrics", metrics.snapshot(), "todos", todos.get(fixture.session())));
            assertTrue(outcome.goalSatisfied(), outcome.reason() + ": " + outcome.answer());
            assertEquals(GoalCompiler.CompilationSource.MODEL, snapshot.compilationSource());
            assertTrue(snapshot.graph().nodes().size() >= 2, "Complex implementation must produce multiple DAG nodes");
            assertTrue(snapshot.graph().nodes().stream().anyMatch(node -> !node.dependsOn().isEmpty()), "Must have actual dependencies");
            assertTrue(snapshot.graph().allTerminalSuccess());
            assertTrue(snapshot.graph().nodes().stream().anyMatch(node -> node.capability().equals("browser")));
            assertFalse(outcome.answer().isBlank());
            var receipts = JSON.readTree(evidence.resolve("tool-results.json").toFile());
            boolean verifiedClick = false;
            for (var receipt : receipts) {
                if ("browser_click".equals(receipt.path("tool").asText())
                        && "SUCCESS".equals(receipt.path("status").asText())) verifiedClick = true;
            }
            assertTrue(verifiedClick, "Browser verification must actually click a control successfully");
            try (var files = Files.walk(evidence, 3)) {
                List<Path> html = files.filter(path -> path.toString().endsWith(".html")).toList();
                assertFalse(html.isEmpty(), "The real write_file tool must create an HTML page");
            }
            String actual = browser.evaluate(BrowserSessionIdentity.resolve(null), "document.body.innerText");
            writeQuietly(evidence.resolve("actual-browser-text.json"), actual);
            for (String module : List.of("仿真", "算法", "闭环", "场景", "测评", "硬件")) assertTrue(actual.contains(module), module);
        } finally {
            if (fixture != null) fixture.close();
            browser.shutdown(); server.stop(0);
        }
    }

    @Test @Timeout(240)
    void liveModelOperatesChromiumAndReadsTheActualVerificationReceipt() throws Exception {
        Path evidence = evidence("browser");
        String nonce = "B-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicInteger saves = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/saved")) {
                saves.incrementAndGet();
                byte[] response = nonce.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (var out = exchange.getResponseBody()) { out.write(response); }
                return;
            }
            String html = """
                    <!doctype html><html lang="zh-CN"><meta charset="utf-8">
                    <title>MiniAgent 浏览器真实控制验证</title>
                    <style>body{font:22px sans-serif;margin:70px;background:#eef3fa;color:#18243d}
                    main{max-width:780px;padding:38px;background:white;border-radius:20px}
                    input,button{font:22px sans-serif;padding:15px;margin:16px} #receipt{padding:20px;background:#eaf7ef}</style>
                    <main><h1>MiniAgent 浏览器操作验收</h1>
                    <label>姓名<input id="name" aria-label="姓名" autocomplete="off"></label>
                    <button id="save" onclick="save()">保存并验证</button>
                    <div id="receipt" role="status">等待填写，尚未保存</div></main>
                    <script>async function save(){const code=await(await fetch('/saved')).text();
                    document.getElementById('receipt').textContent='保存成功 | 姓名：'+document.getElementById('name').value+' | 验收码：'+code;}</script>
                    </html>
                    """;
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        BrowserService browser = new BrowserService();
        // A test-only exception for this exact ephemeral fixture; production SSRF policy is unchanged.
        NetworkGuard fixtureGuard = new NetworkGuard() {
            @Override public String validateUrl(String candidate) {
                try {
                    URI uri = URI.create(candidate);
                    return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                            && uri.getPort() == server.getAddress().getPort() ? null : "Only this test fixture is allowed";
                } catch (RuntimeException invalid) { return "Invalid fixture URL"; }
            }
        };
        ReflectionTestUtils.setField(browser, "networkGuard", fixtureGuard);
        ReflectionTestUtils.setField(browser, "headless", true);
        Fixture fixture = null;
        try {
            ToolRegistry registry = new ToolRegistry();
            BuiltinTools builtins = new BuiltinTools();
            ReflectionTestUtils.setField(builtins, "registry", registry);
            ReflectionTestUtils.setField(builtins, "browserService", browser);
            ReflectionTestUtils.setField(builtins, "networkGuard", fixtureGuard);
            ReflectionTestUtils.invokeMethod(builtins, "registerBrowserTools");
            // The real schemas/handlers remain in place; source extraction and close are
            // outside this acceptance task and must not replace UI interaction.
            registry.unregister("browser_extract_text"); registry.unregister("browser_close");
            fixture = fixture("browser", evidence, registry);
            String task = "请打开测试网页 " + url + "，在姓名框填写“" + BROWSER_TEXT
                    + "”，点击“保存并验证”。操作后截一张图，核实实际页面中的保存回执，并在最终回答准确告诉我验收码。"
                    + "这是本机临时验收页面，本次仅允许此URL。不要执行JavaScript、Shell或生成文件。";
            AgentLoop.LoopOutcome outcome = fixture.run(task,
                    "使用浏览器工具实际完成网页操作。按最新无障碍快照的完整ref定位，每次交互后使用新ref；"
                            + "工具返回STALE_OBSERVATION时重新snapshot。结果未知时不能直接重试提交。"
                            + "此测试的localhost地址已经单独批准，仅允许该fixture。必须观察实际回执，不能编造成功。"
                            + "保存之后使用browser_screenshot，并读出图中/页面中的验收码。");
            String session = BrowserSessionIdentity.resolve(null);
            String actual = browser.evaluate(session, "document.getElementById('receipt').textContent");
            String entered = browser.evaluate(session, "document.getElementById('name').value");
            saveResult(evidence, fixture, outcome, Map.of("receipt", actual, "entered", entered,
                    "saveRequests", saves.get(), "expectedCode", nonce));
            assertTrue(actual.contains(BROWSER_TEXT) && actual.contains(nonce), "Actual browser receipt: " + actual);
            assertEquals(1, saves.get(), "a successful UI submission must not be replayed");
            assertTrue(outcome.toolsInvoked().containsAll(List.of("browser_navigate", "browser_type", "browser_click", "browser_screenshot")),
                    outcome.toolsInvoked().toString());
            assertTrue(outcome.successful(), outcome.endReason() + ": " + outcome.text());
            assertTrue(outcome.text().contains(nonce), "The model must read the random UI receipt, not just claim success: " + outcome.text());
        } finally {
            if (fixture != null) fixture.close(); browser.shutdown(); server.stop(0);
        }
    }

    @Test @Timeout(420)
    void liveModelFillsAndVerifiesAComplexFormUploadsChineseFileAndSubmitsExactlyOnce() throws Exception {
        Path evidence = evidence("form");
        String nonce = "F-" + UUID.randomUUID().toString().substring(0, 8);
        String name = "MiniAgent复杂表单中文验证";
        String attachmentText = "MiniAgent 真实模型附件验收：" + UUID.randomUUID();
        Path attachment = Files.writeString(evidence.resolve("表单验收附件.txt"), attachmentText, StandardCharsets.UTF_8);
        AtomicInteger submissions = new AtomicInteger();
        AtomicReference<Map<String, Object>> submitted = new AtomicReference<>(Map.of());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/submit", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1); exchange.close(); return;
            }
            submissions.incrementAndGet();
            @SuppressWarnings("unchecked") Map<String, Object> payload = JSON.readValue(exchange.getRequestBody(), Map.class);
            submitted.set(payload);
            byte[] response = nonce.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.createContext("/", exchange -> {
            byte[] html = """
                    <!doctype html><html lang="zh-CN"><meta charset="utf-8">
                    <title>MiniAgent 复杂表单真实模型验收</title>
                    <style>body{font:18px sans-serif;margin:28px;background:#eef3fa;color:#18243d}
                    main{max-width:980px;padding:24px;background:white;border-radius:18px}
                    form{display:grid;grid-template-columns:1fr 1fr;gap:18px}label,fieldset{display:block}
                    input,select,button{font:18px sans-serif;padding:8px}fieldset{border:1px solid #dbe2ee}
                    #receipt{padding:16px;margin-top:16px;background:#eaf7ef}button{background:#dbeafa}</style>
                    <main><h1>MiniAgent 复杂表单验收</h1>
                    <form id="registration">
                    <label>姓名 <input id="name" name="name" aria-label="姓名" autocomplete="off" required></label>
                    <label>部门 <select id="department" name="department" aria-label="部门" required>
                      <option value="">请选择部门</option><option value="engineering">研发部</option>
                      <option value="finance">财务部</option></select></label>
                    <label>通知 <input id="notify" name="notify" type="checkbox" aria-label="接收通知"></label>
                    <fieldset><legend>参加方式</legend>
                      <label><input id="online" name="mode" type="radio" value="online" aria-label="线上参加" required>线上参加</label>
                      <label><input id="offline" name="mode" type="radio" value="offline" aria-label="线下参加">线下参加</label>
                    </fieldset>
                    <label>参加日期 <input id="date" name="date" type="date" aria-label="参加日期" required></label>
                    <label>附件 <input id="attachment" name="attachment" type="file" aria-label="验收附件" accept=".txt" required></label>
                    <button type="submit" id="submit">提交并验证</button></form>
                    <div id="receipt" role="status">尚未提交</div></main>
                    <script>document.getElementById('registration').addEventListener('submit',async event=>{
                      event.preventDefault();const file=document.getElementById('attachment').files[0];
                      const payload={name:document.getElementById('name').value,department:document.getElementById('department').value,
                        notify:document.getElementById('notify').checked,mode:document.querySelector('input[name=mode]:checked')?.value||'',
                        date:document.getElementById('date').value,fileName:file?.name||'',fileSize:file?.size||0,
                        fileContent:file?await file.text():''};
                      const response=await fetch('/submit',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
                      if(response.ok){const code=await response.text();document.getElementById('receipt').textContent=
                        '提交成功 | 姓名：'+payload.name+' | 部门：'+payload.department+' | 日期：'+payload.date+
                        ' | 附件：'+payload.fileName+' | 验收码：'+code;}
                    });</script></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        BrowserService browser = new BrowserService();
        NetworkGuard fixtureGuard = new NetworkGuard() {
            @Override public String validateUrl(String candidate) {
                try {
                    URI uri = URI.create(candidate);
                    return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                            && uri.getPort() == server.getAddress().getPort() ? null : "Only this complex-form fixture is allowed";
                } catch (RuntimeException invalid) { return "Invalid fixture URL"; }
            }
        };
        ReflectionTestUtils.setField(browser, "networkGuard", fixtureGuard);
        ReflectionTestUtils.setField(browser, "headless", true);
        Fixture fixture = null;
        try {
            ToolRegistry registry = new ToolRegistry();
            BuiltinTools builtins = new BuiltinTools();
            ReflectionTestUtils.setField(builtins, "registry", registry);
            ReflectionTestUtils.setField(builtins, "browserService", browser);
            ReflectionTestUtils.setField(builtins, "networkGuard", fixtureGuard);
            ReflectionTestUtils.invokeMethod(builtins, "registerBrowserTools");
            registry.unregister("browser_extract_text"); registry.unregister("browser_close");
            fixture = fixture("form", evidence, registry, 360_000, 80);
            String task = "请打开临时测试表单 " + url + "，先识别实际表单字段，再填写：姓名“" + name
                    + "”，部门选择“研发部”，勾选“接收通知”，参加方式选择“线上参加”，参加日期填写2026-11-13。"
                    + "附件使用当前会话工作区文件“" + attachment.getFileName() + "”。"
                    + "我明确授权将这个验收附件上传到该临时测试页面，并在核对后点击“提交并验证”，只提交一次。"
                    + "请使用browser_form_verify回读核验每项实际值，包括附件文件名；提交成功后截图并准确告诉我页面显示的随机验收码。"
                    + "仅允许此URL，不使用JavaScript、Shell、桌面工具，不生成文件，不访问其他网站。";
            AgentLoop.LoopOutcome outcome = fixture.run(task,
                    "你正在执行真实浏览器复杂表单验收。使用browser_form_inspect识别字段、最新ref、类型和约束；"
                            + "使用browser_type输入姓名和ISO格式日期，browser_select选择部门，browser_set_checked设置复选及单选，"
                            + "browser_upload选择用户已明确授权的当前会话附件，browser_form_verify回读核验每项字段。"
                            + "每次改变表单后重新browser_form_inspect取得新ref；不要在一次工具批次里对同一观察发多个修改动作。"
                            + "核验工具是只读工具，同一新观察的多个字段可以逐项核验。必须按用户数据操作，不能凭已有知识编造回执。"
                            + "用户已经明确授权此fixture的上传和提交，执行过程中不得重复提交；结果未知先观察实际回执。"
                            + "在收到实际提交成功与验收码后使用browser_screenshot保存证据，最终回答真实验收码。", 35);
            String session = BrowserSessionIdentity.resolve(null);
            String receipt = browser.evaluate(session, "document.getElementById('receipt').textContent");
            String actualState = browser.evaluate(session, """
                    JSON.stringify({name:document.querySelector('#name').value,department:document.querySelector('#department').value,
                    notify:document.querySelector('#notify').checked,mode:document.querySelector('input[name=mode]:checked')?.value,
                    date:document.querySelector('#date').value,fileName:document.querySelector('#attachment').files[0]?.name})
                    """);
            Map<String, Object> actualSubmitted = submitted.get();
            saveResult(evidence, fixture, outcome, Map.of("receipt", receipt, "actualFormState", actualState,
                    "submissions", submissions.get(), "submitted", actualSubmitted, "expectedCode", nonce,
                    "expectedAttachmentSize", Files.size(attachment)));
            assertEquals(1, submissions.get(), "The actual POST must happen exactly once");
            assertEquals(name, actualSubmitted.get("name"));
            assertEquals("engineering", actualSubmitted.get("department"));
            assertEquals(Boolean.TRUE, actualSubmitted.get("notify"));
            assertEquals("online", actualSubmitted.get("mode"));
            assertEquals("2026-11-13", actualSubmitted.get("date"));
            assertEquals(attachment.getFileName().toString(), actualSubmitted.get("fileName"));
            assertEquals(Files.size(attachment), ((Number) actualSubmitted.get("fileSize")).longValue());
            assertEquals(attachmentText, actualSubmitted.get("fileContent"), "Uploaded bytes must reach the actual fixture server");
            assertTrue(receipt.contains(nonce), receipt);
            assertTrue(outcome.toolsInvoked().containsAll(List.of("browser_form_inspect", "browser_select",
                    "browser_set_checked", "browser_upload", "browser_form_verify", "browser_screenshot")), outcome.toolsInvoked().toString());
            assertTrue(outcome.successful(), outcome.endReason() + ": " + outcome.text());
            assertTrue(outcome.text().contains(nonce), "Model must read the real random receipt: " + outcome.text());
        } finally {
            if (fixture != null) fixture.close(); browser.shutdown(); server.stop(0);
        }
    }

    @Test @Timeout(240) @EnabledOnOs(OS.WINDOWS)
    void liveVisionModelSelectsDesktopCoordinatesTypesChineseAndVerifiesTheScreen() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "Native acceptance test needs -Djava.awt.headless=false");
        Path evidence = evidence("desktop");
        String nonce = "D-" + UUID.randomUUID().toString().substring(0, 8);
        JFrame frame = new JFrame("MiniAgent live desktop acceptance " + ProcessHandle.current().pid());
        JTextField field = new JTextField(25); JButton save = new JButton("保存并验证");
        JLabel receipt = new JLabel("等待填写，尚未保存"); AtomicInteger saves = new AtomicInteger();
        save.addActionListener(event -> {
            saves.incrementAndGet();
            receipt.setText("保存成功 | 内容：" + field.getText() + " | 验收码：" + nonce);
        });
        WindowsDesktopBackend nativeBackend = new WindowsDesktopBackend();
        DesktopInteractionService desktop = null; Fixture fixture = null;
        try {
            SwingUtilities.invokeAndWait(() -> {
                JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
                panel.setBorder(BorderFactory.createEmptyBorder(28, 30, 28, 30));
                JLabel title = new JLabel("MiniAgent 电脑操作验收"); title.setFont(new Font("Microsoft YaHei", Font.BOLD, 24));
                JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 22));
                JLabel label = new JLabel("填写内容："); label.setFont(new Font("Microsoft YaHei", Font.PLAIN, 20));
                field.setFont(new Font("Microsoft YaHei", Font.PLAIN, 20)); save.setFont(new Font("Microsoft YaHei", Font.PLAIN, 20));
                field.setMaximumSize(new Dimension(480, 50)); inputRow.add(label); inputRow.add(field);
                JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 10)); buttons.add(save);
                receipt.setFont(new Font("Microsoft YaHei", Font.PLAIN, 18));
                panel.add(title); panel.add(inputRow); panel.add(buttons); panel.add(receipt);
                frame.add(panel); frame.setBounds(120, 100, 850, 380); frame.setAlwaysOnTop(true);
                frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); frame.setVisible(true);
                field.requestFocusInWindow();
            });
            DesktopBackend.Window target = nativeBackend.windows().stream().filter(window ->
                    window.processId() == ProcessHandle.current().pid() && window.title().equals(frame.getTitle()))
                    .findFirst().orElseThrow();
            nativeBackend.activate(target);
            DesktopBackend guarded = new FixtureDesktopBackend(nativeBackend, target.id());
            var constructor = DesktopInteractionService.class.getDeclaredConstructor(DesktopBackend.class, Clock.class, boolean.class);
            constructor.setAccessible(true); desktop = constructor.newInstance(guarded, Clock.systemUTC(), true);
            ToolRegistry registry = new ToolRegistry(); new DesktopTools(registry, desktop).register();
            fixture = fixture("desktop", evidence, registry);
            String task = "请操作当前标题为“" + frame.getTitle() + "”的验收窗口，在“填写内容”输入框输入“"
                    + DESKTOP_TEXT + "”，点击“保存并验证”，最后根据真实截图准确告诉我显示的验收码。"
                    + "仅允许操作这个测试窗口；不要打开其他应用、执行命令或生成文件。";
            AgentLoop.LoopOutcome outcome = fixture.run(task,
                    "这是受控的Windows桌面验收。必须先desktop_observe看真实截图，再自行定位控件。"
                            + "坐标为截图左上角起的物理像素，请结合imageWidth/imageHeight。每个动作使用最新observationId。"
                            + "一次只做一个动作；每次desktop_act返回新截图，应仔细核对，再做下一步。"
                            + "输入接收不代表业务成功；从最终画面读出随机验收码后才能回答成功。不要重复点击保存。"
                            + "仅允许工具提供的当前测试窗口，不允许切换其他应用或发系统快捷键。");
            String[] observed = new String[2];
            SwingUtilities.invokeAndWait(() -> { observed[0] = field.getText(); observed[1] = receipt.getText(); });
            saveResult(evidence, fixture, outcome, Map.of("entered", observed[0], "receipt", observed[1],
                    "saveRequests", saves.get(), "expectedCode", nonce, "fixturePid", target.processId(), "fixtureWindow", target.id()));
            assertEquals(DESKTOP_TEXT, observed[0], "Native input must reach the actual field");
            assertTrue(observed[1].contains(nonce), "The actual button action must update the fixture UI");
            assertEquals(1, saves.get(), "a successful desktop submission must not be replayed");
            assertTrue(outcome.toolsInvoked().containsAll(List.of("desktop_observe", "desktop_act")), outcome.toolsInvoked().toString());
            assertTrue(outcome.successful(), outcome.endReason() + ": " + outcome.text());
            assertTrue(outcome.text().contains(nonce), "The live vision model must read the random screen code: " + outcome.text());
        } finally {
            if (desktop != null) desktop.close(); if (fixture != null) fixture.close();
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    private static Fixture fixture(String kind, Path evidence, ToolRegistry registry) throws Exception {
        return fixture(kind, evidence, registry, 180_000, 35);
    }

    private static Fixture fixture(String kind, Path evidence, ToolRegistry registry, long budgetMillis, int maxToolCalls) throws Exception {
        StandardEnvironment environment = loadEnvironment();
        AgentModelsProperties properties = Binder.get(environment).bind("agent.models", Bindable.of(AgentModelsProperties.class))
                .orElseGet(AgentModelsProperties::new);
        UserModelConfigService configuration = new UserModelConfigService();
        UserModelConfigRepository repository = mock(UserModelConfigRepository.class);
        when(repository.findByUserId(anyLong())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(configuration, "repository", repository);
        ReflectionTestUtils.setField(configuration, "modelsProperties", properties);
        ReflectionTestUtils.setField(configuration, "globalApiKey", environment.getRequiredProperty("langchain4j.open-ai.chat-model.api-key"));
        ReflectionTestUtils.setField(configuration, "globalBaseUrl", environment.getRequiredProperty("langchain4j.open-ai.chat-model.base-url"));
        ReflectionTestUtils.setField(configuration, "globalModelName", environment.getRequiredProperty("langchain4j.open-ai.chat-model.model-name"));
        ReflectionTestUtils.setField(configuration, "customBaseUrlEnabled", false);
        ModelClientFactory models = new ModelClientFactory();
        ReflectionTestUtils.setField(models, "userModelConfigService", configuration);
        ReflectionTestUtils.setField(models, "timeout", Duration.ofSeconds(45));
        ReflectionTestUtils.setField(models, "visionPreset", environment.getProperty("agent.multimodal.vision-preset", "default"));
        var resolved = models.resolve(USER);
        assertTrue(resolved.settings().apiKey() != null && !resolved.settings().apiKey().isBlank(), "Live test requires configured model credentials");
        String session = "live-control-" + kind + "-" + UUID.randomUUID();
        OwnerContext.setCurrentUser(USER); OwnerContext.setCurrentTenant(TENANT);
        AgentLoop.setCurrentSession(session); WorkspaceContext.setSessionOverride(session); WorkspaceContext.setRootOverride(evidence);
        EffectiveModelContext.set(resolved.chat(), null, resolved.settings().contextWindowTokens());
        PermissionContext.force(session, PermissionMode.ACCEPT_EDITS, ConfirmPolicy.AUTO, true);
        SessionPermissionStore permissions = new SessionPermissionStore();
        ExecutionControl control = new ExecutionControl(budgetMillis, maxToolCalls, 100_000);
        control.start(session);
        ToolExecutionGuards guards = new ToolExecutionGuards(registry, new AgentToolsProperties(1));
        FileActionJournal journal = new FileActionJournal(new AgentDataPaths(evidence.toString()));
        ReflectionTestUtils.invokeMethod(journal, "initialize");
        List<Map<String, Object>> calls = new CopyOnWriteArrayList<>();
        ToolHook observer = new ToolHook() {
            @Override public String name() { return "live-acceptance-evidence"; }
            @Override public ToolPreDecision before(ToolHookContext context) {
                calls.add(Map.of("turn", context.turn(), "tool", context.toolName(), "arguments", context.argumentsJson()));
                writeQuietly(evidence.resolve("tool-calls.json"), calls);
                return ToolPreDecision.proceed(context.argumentsJson());
            }
        };
        ToolPipeline pipeline = new ToolPipeline(control, new ToolHookChain(List.of(observer)), permissions, journal,
                guards, registry, new ExecPolicyService("allow", "", permissions));
        ToolSurface surface = new ToolSurface(); ReflectionTestUtils.setField(surface, "toolRegistry", registry);
        ContextCompressor compressor = new ContextCompressor();
        ReflectionTestUtils.setField(compressor, "chatModel", resolved.chat());
        ReflectionTestUtils.setField(compressor, "tokenEstimator", new TokenEstimator());
        ReflectionTestUtils.setField(compressor, "preMessageThreshold", 0.5d);
        ReflectionTestUtils.setField(compressor, "emergencyThreshold", 0.9d);
        ReflectionTestUtils.setField(compressor, "engineeringSufficiencyRatio", 0.15d);
        ReflectionTestUtils.setField(compressor, "protectedTailRatio", 0.1d);
        ReflectionTestUtils.setField(compressor, "llmSummaryEnabled", true);
        AgentLoop loop = new AgentLoop();
        ReflectionTestUtils.setField(loop, "toolRegistry", registry); ReflectionTestUtils.setField(loop, "toolSurface", surface);
        ReflectionTestUtils.setField(loop, "toolExecutionGuards", guards); ReflectionTestUtils.setField(loop, "toolPipeline", pipeline);
        ReflectionTestUtils.setField(loop, "executionControl", control);
        ReflectionTestUtils.setField(loop, "executionProperties", new ExecutionProperties(budgetMillis, maxToolCalls, 100_000));
        ReflectionTestUtils.setField(loop, "contextCompressor", compressor);
        ReflectionTestUtils.setField(loop, "taskTodoStore", new TaskTodoStore(evidence.resolve("todos").toString()));
        ReflectionTestUtils.setField(loop, "stopHookChain", new StopHookChain(new ArrayList<>()));
        ReflectionTestUtils.setField(loop, "toolVisionModelResolver", new ConfiguredToolVisionModelResolver(models));
        ReflectionTestUtils.setField(loop, "maxContextTokens", 32000);
        List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        loop.setToolExecutionSink((sid, turn, name, status, result) -> {
            events.add(Map.of("turn", turn, "tool", name, "status", status.name(), "result", result));
            writeQuietly(evidence.resolve("tool-results.json"), events);
        });
        writeQuietly(evidence.resolve("model-metadata.json"), Map.of("preset", resolved.settings().presetId(),
                "model", resolved.settings().modelName(), "host", URI.create(resolved.settings().baseUrl()).getHost(),
                "visionPreset", environment.getProperty("agent.multimodal.vision-preset", "default"),
                "startedAt", Instant.now().toString(), "session", session));
        return new Fixture(loop, resolved, control, session, evidence);
    }

    private static StandardEnvironment loadEnvironment() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        String home = environment.getProperty("MINI_AGENT_HOME", Path.of(System.getProperty("user.home"), ".miniagent").toString());
        Path external = Path.of(System.getProperty("miniagent.live.config", Path.of(home, "config.yml").toString()));
        assertTrue(Files.isRegularFile(external), "Live model configuration file is missing");
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<Resource> resources = List.of(new FileSystemResource(external),
                new ClassPathResource("application-desktop.yml"), new ClassPathResource("application.yml"));
        int i = 0;
        for (Resource resource : resources) {
            for (var source : loader.load("live-config-" + i++, resource)) environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    private static Path evidence(String kind) throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (project.getFileName().toString().equals("mini-agent-app")) project = project.getParent();
        Path base = Path.of(System.getProperty("miniagent.live.evidence", project.resolve(".verify/live-control-2026-11-12").toString()));
        Path directory = base.resolve(kind + "-" + System.currentTimeMillis()).toAbsolutePath();
        Files.createDirectories(directory); return directory;
    }

    private static void saveResult(Path evidence, Fixture fixture, AgentLoop.LoopOutcome outcome, Map<String, Object> oracle) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completedAt", Instant.now().toString()); result.put("endReason", outcome.endReason());
        result.put("successful", outcome.successful()); result.put("answer", outcome.text());
        result.put("toolsInvoked", outcome.toolsInvoked()); result.put("lastErrorCode", outcome.lastErrorCode().name());
        result.put("oracle", oracle);
        JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("result.json").toFile(), result);
        List<Map<String, Object>> history = new ArrayList<>();
        for (ChatMessage message : outcome.messages()) {
            if (message instanceof UserMessage user) {
                List<String> texts = user.contents().stream().filter(TextContent.class::isInstance).map(TextContent.class::cast)
                        .map(TextContent::text).toList();
                history.add(Map.of("role", "user", "text", String.join("\n", texts)));
            } else if (message instanceof ToolExecutionResultMessage tool) {
                history.add(Map.of("role", "tool", "name", tool.toolName(), "text", tool.text()));
            } else if (message instanceof AiMessage ai) {
                List<Map<String, String>> calls = ai.hasToolExecutionRequests() ? ai.toolExecutionRequests().stream()
                        .map(call -> Map.of("id", Optional.ofNullable(call.id()).orElse(""),
                                "name", Optional.ofNullable(call.name()).orElse(""),
                                "arguments", Optional.ofNullable(call.arguments()).orElse(""))).toList() : List.of();
                history.add(Map.of("role", "assistant", "text", ai.text() == null ? "" : ai.text(),
                        "calls", calls));
            }
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("history-without-images.json").toFile(), history);
    }

    private static void writeQuietly(Path destination, Object value) {
        try { JSON.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot write live acceptance evidence", failure); }
    }

    private record Fixture(AgentLoop loop, ModelClientFactory.ResolvedModels model, ExecutionControl control,
                           String session, Path evidence) implements AutoCloseable {
        AgentLoop.LoopOutcome run(String task, String system) {
            return run(task, system, 15);
        }
        AgentLoop.LoopOutcome run(String task, String system, int iterations) {
            return loop.runOutcome(model.chat(), system, task, List.of(), iterations, TaskPlan.of(task), null);
        }
        @Override public void close() { control.cancel(session); control.finish(session); }
    }

    /** Guard changes only test scope, never supplies coordinates or substitutes OS input. */
    private record FixtureDesktopBackend(WindowsDesktopBackend delegate, String fixtureWindow) implements DesktopBackend {
        private void requireOwn(Window window) {
            if (!window.id().equals(fixtureWindow) || window.processId() != ProcessHandle.current().pid())
                throw new IllegalStateException("Live acceptance may only operate its own fixture window");
        }
        @Override public boolean available() { return delegate.available(); }
        @Override public String unavailableReason() { return delegate.unavailableReason(); }
        @Override public List<Window> windows() { return delegate.windows().stream().filter(window ->
                window.id().equals(fixtureWindow) && window.processId() == ProcessHandle.current().pid()).toList(); }
        @Override public Window foreground() { Window window = delegate.foreground(); requireOwn(window); return window; }
        @Override public Capture capture(Window window) { requireOwn(window); return delegate.capture(window); }
        @Override public void activate(Window window) { requireOwn(window); delegate.activate(window); }
        @Override public void execute(Window window, Action action) throws InterruptedException {
            requireOwn(window);
            if ("key".equals(action.action()) && !Set.of("TAB", "ENTER", "SHIFT+TAB", "CTRL+A", "BACKSPACE",
                    "DELETE", "LEFT", "RIGHT", "HOME", "END").contains(action.key().toUpperCase()))
                throw new IllegalArgumentException("Only local editing keys are allowed in this fixture acceptance scope");
            delegate.execute(window, action);
        }
        @Override public void releaseInputs() { delegate.releaseInputs(); }
    }
}
