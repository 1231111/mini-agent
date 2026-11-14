package com.miniagent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The actual standalone main renders untrusted artifacts, not a model's claimed verification. */
@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")
class ArtifactUiCollectorIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;
    private record Capture(int exit, JsonNode result, String stdout) {}

    @Test void standaloneCollectorReadsActualControlsAndCreatesViewportPngEvidence() throws Exception {
        Path root = directory("good");
        Files.createDirectories(root.resolve("assets"));
        Files.writeString(root.resolve("assets/style.css"), "body{margin:0;font:16px sans-serif}main{box-sizing:border-box;width:100%;padding:12px}input,select{max-width:100%}");
        Files.writeString(root.resolve("assets/ui.js"), "document.querySelector('#save').onclick=()=>setTimeout(()=>document.querySelector('#receipt').textContent=document.querySelector('#name').value+'|'+document.querySelector('#city').value+'|'+document.querySelector('#agree').checked,75);");
        Files.writeString(root.resolve("index.html"), """
                <!doctype html><html><meta charset="utf-8"><link rel="stylesheet" href="assets/style.css">
                <main><h1>独立验收页面</h1><input id="name"><select id="city"><option value="a">A</option><option value="b">B</option></select>
                <input id="agree" type="checkbox"><button id="save">保存</button><output id="receipt"></output></main>
                <script src="assets/ui.js"></script></html>
                """);
        List<Map<String, Object>> actions = List.of(
                action("name", "fill", "#name", "真实回读", "#name", "value"),
                action("city", "select", "#city", "b", "#city", "value"),
                action("agree", "check", "#agree", true, "#agree", "checked"),
                action("save", "click", "#save", null, "#receipt", "textContent"));
        Capture capture = run(request(root, "index.html", actions));
        assertEquals(0, capture.exit, capture.stdout);
        JsonNode result = capture.result;
        assertTrue(result.path("loaded").asBoolean());
        assertEquals(sha(root.resolve("index.html")), result.path("artifactSha256").asText());
        assertEquals(0, result.path("consoleErrors").size(), result.toString());
        assertEquals(0, result.path("failedRequests").size(), result.toString());
        assertEquals(List.of("真实回读", "b", "true", "真实回读|b|true"), strings(result.path("actions"), "observedState"));
        assertFalse(capture.stdout.contains("DO_NOT_TRUST_EXPECTED"));
        assertEquals(2, result.path("viewports").size());
        for (JsonNode viewport : result.path("viewports")) {
            assertTrue(viewport.path("scrollWidth").asInt() <= viewport.path("width").asInt());
            assertTrue(viewport.path("elements").path("#save").path("visible").asBoolean());
            Path screenshot = root.resolve(viewport.path("screenshot").path("path").asText());
            assertEquals(sha(screenshot), viewport.path("screenshot").path("sha256").asText());
            var image = ImageIO.read(screenshot.toFile());
            assertNotNull(image);
            assertEquals(viewport.path("width").asInt(), image.getWidth());
            assertEquals(viewport.path("height").asInt(), image.getHeight());
        }
    }

    @Test void brokenLayoutAndMissingControlsAreObservedInsteadOfBeingMarkedPassed() throws Exception {
        Path root = directory("broken");
        Files.writeString(root.resolve("index.html"), """
                <!doctype html><html><body style="margin:0"><div style="width:2200px">overflow</div>
                <script>console.error('PRIVATE_CONSOLE_PAYLOAD');setTimeout(()=>{throw Error('PRIVATE_ERROR_PAYLOAD')},1)</script></body></html>
                """);
        Capture capture = run(request(root, "index.html", List.of()));
        assertEquals(0, capture.exit, capture.stdout);
        assertTrue(capture.result.path("viewports").get(1).path("scrollWidth").asInt() > 390);
        assertFalse(capture.result.path("viewports").get(0).path("elements").path("#save").path("visible").asBoolean());
        assertTrue(strings(capture.result.path("consoleErrors"), null).contains("CONSOLE_ERROR"));
        assertTrue(strings(capture.result.path("consoleErrors"), null).contains("PAGE_SCRIPT_ERROR"));
        assertFalse(capture.stdout.contains("PRIVATE_"));
    }

    @Test void externalHttpWebsocketAndPopupRequestsNeverReachTheFixtureServer() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { hits.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            Path root = directory("network");
            String remote = "127.0.0.1:" + server.getAddress().getPort();
            Files.writeString(root.resolve("index.html"), "<!doctype html><button id='save'>save</button><script>"
                    + "fetch('http://" + remote + "/private?token=PRIVATE_NETWORK_TOKEN').catch(()=>{});"
                    + "new WebSocket('ws://" + remote + "/socket?token=PRIVATE_WEBSOCKET_TOKEN');"
                    + "window.open('http://" + remote + "/popup');</script>");
            Capture capture = run(request(root, "index.html", List.of()));
            assertEquals(0, capture.exit, capture.stdout);
            List<String> failures = strings(capture.result.path("failedRequests"), null);
            assertTrue(failures.contains("EXTERNAL_REQUEST_BLOCKED"), failures.toString());
            assertTrue(failures.contains("WEBSOCKET_BLOCKED"), failures.toString());
            assertTrue(failures.contains("POPUP_BLOCKED"), failures.toString());
            assertEquals(0, hits.get());
            assertFalse(capture.stdout.contains("PRIVATE_"));
            assertFalse(capture.stdout.contains(remote));
        } finally { server.stop(0); }
    }

    @Test void traversalAndHashMismatchFailWithFixedNonSensitiveErrors() throws Exception {
        Path root = directory("invalid");
        Files.writeString(root.resolve("index.html"), "<button id='save'>save</button>");
        JsonNode mismatch = request(root, "index.html", List.of());
        ((com.fasterxml.jackson.databind.node.ObjectNode) mismatch.path("artifact")).put("sha256", "0".repeat(64));
        Capture badHash = run(mismatch);
        assertEquals(2, badHash.exit);
        assertEquals("UI_ARTIFACT_HASH_MISMATCH", badHash.result.path("error").asText());
        Files.writeString(temporary.resolve("PRIVATE_OUTSIDE.html"), "PRIVATE_FILE_CONTENT");
        JsonNode traversal = request(root, "index.html", List.of());
        ((com.fasterxml.jackson.databind.node.ObjectNode) traversal.path("artifact")).put("path", "../PRIVATE_OUTSIDE.html");
        Capture outside = run(traversal);
        assertEquals(2, outside.exit);
        assertEquals("UI_RESOURCE_PATH_REJECTED", outside.result.path("error").asText());
        assertFalse(outside.stdout.contains("PRIVATE_"));
        assertFalse(outside.stdout.contains(temporary.toString()));
    }

    @Test void symlinkOrJunctionResourcesCannotEscapeTheCaseRoot() throws Exception {
        Path root = directory("links"), outside = directory("outside-assets");
        Files.writeString(outside.resolve("private.css"), "/*PRIVATE_LINK_PAYLOAD*/ body{background:red}");
        Path link = root.resolve("linked");
        createDirectoryLink(link, outside);
        try {
            Files.writeString(root.resolve("index.html"), "<link rel='stylesheet' href='linked/private.css'><button id='save'>save</button>");
            Capture capture = run(request(root, "index.html", List.of()));
            assertEquals(0, capture.exit, capture.stdout);
            assertTrue(strings(capture.result.path("failedRequests"), null).contains("LOCAL_RESOURCE_REJECTED"));
            assertFalse(capture.stdout.contains("PRIVATE_LINK_PAYLOAD"));
        } finally { Files.deleteIfExists(link); }
    }

    @Test void duplicateViewportsOversizedActionsAndNonBooleanCheckAreRejectedBeforeBrowserLaunch() throws Exception {
        Path root = directory("limits");
        Files.writeString(root.resolve("index.html"), "<input id='save' type='checkbox'>");
        JsonNode duplicate = request(root, "index.html", List.of());
        ((com.fasterxml.jackson.databind.node.ObjectNode) duplicate.path("check")).set("viewports", JSON.valueToTree(
                List.of(Map.of("width", 390, "height", 844), Map.of("width", 390, "height", 844))));
        assertEquals("INVALID_COLLECTOR_VIEWPORTS", run(duplicate).result.path("error").asText());
        JsonNode badCheck = request(root, "index.html", List.of(action("bad", "check", "#save", "true", "#save", "checked")));
        assertEquals("INVALID_COLLECTOR_ACTIONS", run(badCheck).result.path("error").asText());
        JsonNode manyActions = request(root, "index.html", Collections.nCopies(17, action("fill", "fill", "#save", "x", "#save", "value")));
        assertEquals("INVALID_COLLECTOR_ACTIONS", run(manyActions).result.path("error").asText());
    }

    private Path directory(String name) throws Exception { return Files.createDirectory(temporary.resolve(name)).toRealPath(); }
    private static Map<String, Object> action(String id, String kind, String selector, Object value, String observed, String property) {
        Map<String, Object> action = new LinkedHashMap<>(Map.of("id", id, "action", kind, "selector", selector,
                "observe", Map.of("selector", observed, "property", property), "expectedState", "DO_NOT_TRUST_EXPECTED"));
        if (value != null) action.put("value", value);
        return action;
    }
    private static JsonNode request(Path root, String artifact, List<Map<String, Object>> actions) throws Exception {
        return JSON.valueToTree(Map.of("artifactDir", root.toString(), "artifact", Map.of("path", artifact, "sha256", sha(root.resolve(artifact))),
                "check", Map.of("viewports", List.of(Map.of("width", 1366, "height", 900), Map.of("width", 390, "height", 844)),
                        "requiredSelectors", List.of("#save"), "requiredActions", actions)));
    }
    private Capture run(JsonNode request) throws Exception {
        Path input = temporary.resolve("request-" + UUID.randomUUID() + ".json"), stderr = temporary.resolve("stderr-" + UUID.randomUUID() + ".log");
        JSON.writeValue(input.toFile(), request);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(repository.resolve("mini-agent-desktop"))) repository = repository.getParent();
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Path argumentFile = temporary.resolve("java-" + UUID.randomUUID() + ".args");
        Files.writeString(argumentFile, "-Xmx256m\n-cp\n" + javaArgument(classpath) + "\n"
                + ArtifactUiCollector.class.getName() + "\n" + javaArgument(input.toString()), StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder(java, "@" + argumentFile)
                .directory(repository.toFile()).redirectError(stderr.toFile());
        Map<String, String> inherited = new HashMap<>(builder.environment());
        builder.environment().clear();
        for (String name : List.of("PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "HOME", "USERPROFILE", "LOCALAPPDATA", "APPDATA"))
            if (inherited.containsKey(name)) builder.environment().put(name, inherited.get(name));
        Path browsers = repository.resolve("mini-agent-desktop/browsers");
        if (Files.isDirectory(browsers)) builder.environment().put("PLAYWRIGHT_BROWSERS_PATH", browsers.toString());
        builder.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS), "Collector exceeded fixture deadline");
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            JsonNode result = JSON.readTree(stdout);
            assertNotNull(result, "Collector must emit one JSON result");
            return new Capture(process.exitValue(), result, stdout);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
    private static List<String> strings(JsonNode array, String field) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : array) result.add(field == null ? item.asText() : item.path(field).asText());
        return result;
    }
    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static String javaArgument(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) { Files.createSymbolicLink(link, target); return; }
        String literalLink = link.toString().replace("'", "''"), literalTarget = target.toString().replace("'", "''");
        String script = "$ErrorActionPreference='Stop'; New-Item -ItemType Junction -Path '" + literalLink + "' -Target '" + literalTarget + "' | Out-Null";
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE))).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
