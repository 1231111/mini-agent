package com.miniagent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Independent UI-artifact observer. This main does not start Spring or call a model. */
public final class ArtifactUiCollector {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGIN = "http://miniagent-artifact.invalid";
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final long MAX_SERVED_BYTES = 32L * 1024 * 1024;
    private static final int MAX_REQUESTS = 128;
    private static final Set<String> ACTIONS = Set.of("click", "fill", "select", "check");
    private static final Set<String> PROPERTIES = Set.of("textContent", "value", "checked", "visible");
    private static final Set<String> DRIVER_ENV = Set.of("PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "TEMP", "TMP",
            "HOME", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "LD_LIBRARY_PATH", "DYLD_LIBRARY_PATH",
            "PLAYWRIGHT_BROWSERS_PATH", "PLAYWRIGHT_NODEJS_PATH");

    private ArtifactUiCollector() {}

    public static void main(String[] args) {
        try {
            require(args.length == 1, "INVALID_COLLECTOR_REQUEST");
            Path input = Path.of(args[0]);
            require(input.isAbsolute() && Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(input), "INVALID_COLLECTOR_REQUEST");
            JsonNode request = JSON.readTree(readBounded(input, MAX_REQUEST_BYTES));
            // PrintStream's Windows console encoding may not represent Chinese.
            // The machine protocol is always UTF-8 bytes, including redirected stdout.
            System.out.write(JSON.writeValueAsBytes(collect(request)));
            System.out.write('\n');
        } catch (CollectorFailure failure) {
            System.out.println("{\"error\":\"" + failure.code + "\"}");
            System.exit(2);
        } catch (Exception | LinkageError failure) {
            // Provider URLs, artifact contents, host paths and raw SDK messages must not escape.
            System.out.println("{\"error\":\"UI_COLLECTOR_FAILED\"}");
            System.exit(2);
        }
    }

    public static Map<String, Object> collect(JsonNode request) throws Exception {
        Contract contract = parse(request);
        byte[] initial = readResource(contract.root, contract.entry);
        require(sha256(initial).equals(contract.sha256), "UI_ARTIFACT_HASH_MISMATCH");
        LinkedHashSet<String> consoleErrors = new LinkedHashSet<>(), failedRequests = new LinkedHashSet<>();
        List<Map<String, Object>> viewportEvidence = new ArrayList<>(), actionEvidence = new ArrayList<>();
        Map<String, String> environment = driverEnvironment();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(environment));
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)
                     .setTimeout(20000).setEnv(environment)
                     .setArgs(List.of("--disable-quic", "--force-webrtc-ip-handling-policy=disable_non_proxied_udp")));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(false)
                     .setServiceWorkers(ServiceWorkerPolicy.BLOCK))) {
            long[] served = {0, 0};
            context.route("**/*", route -> serve(route, contract, served, failedRequests));
            context.routeWebSocket("**/*", route -> {
                failedRequests.add("WEBSOCKET_BLOCKED");
                route.close(new WebSocketRoute.CloseOptions().setCode(1008).setReason("Collector network policy"));
            });
            Page page = context.newPage();
            page.setDefaultTimeout(2500);
            page.setDefaultNavigationTimeout(5000);
            context.onPage(other -> {
                if (other != page) {
                    failedRequests.add("POPUP_BLOCKED");
                    other.close();
                }
            });
            page.onDownload(download -> { failedRequests.add("DOWNLOAD_BLOCKED"); download.cancel(); });
            page.onConsoleMessage(message -> { if ("error".equals(message.type())) consoleErrors.add("CONSOLE_ERROR"); });
            page.onPageError(ignored -> consoleErrors.add("PAGE_SCRIPT_ERROR"));
            page.onRequestFailed(ignored -> failedRequests.add("RESOURCE_REQUEST_FAILED"));
            int index = 0;
            for (Viewport viewport : contract.viewports) {
                page.setViewportSize(viewport.width, viewport.height);
                if (index++ == 0) {
                    String url = ORIGIN + "/" + encodePath(contract.entry);
                    Response response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
                    require(response != null && response.status() == 200, "UI_ARTIFACT_NOT_LOADED");
                    for (Action action : contract.actions) actionEvidence.add(execute(page, action));
                }
                // Give normal microtasks, resize handlers and blocked-request events an observation turn.
                page.waitForTimeout(150);
                Map<String, Object> elements = new LinkedHashMap<>();
                for (String selector : contract.selectors) {
                    Locator locator = page.locator(selector);
                    int count = locator.count();
                    boolean visible = false;
                    for (int position = 0; position < Math.min(count, 64); position++) visible |= locator.nth(position).isVisible();
                    elements.put(selector, Map.of("count", count, "visible", visible));
                }
                Number scrollWidth = (Number) page.evaluate("() => document.documentElement.scrollWidth");
                byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(false)
                        .setAnimations(ScreenshotAnimations.DISABLED).setTimeout(5000));
                require(screenshot.length <= MAX_FILE_BYTES, "UI_SCREENSHOT_TOO_LARGE");
                String name = "collector-" + viewport.width + "-" + viewport.height + ".png";
                Path target = contract.root.resolve(name);
                Files.write(target, screenshot, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                viewportEvidence.add(Map.of("width", viewport.width, "height", viewport.height,
                        "scrollWidth", scrollWidth.intValue(), "elements", elements,
                        "screenshot", Map.of("path", name, "sha256", sha256(screenshot))));
            }
            require(sha256(readResource(contract.root, contract.entry)).equals(contract.sha256), "UI_ARTIFACT_CHANGED");
        }
        return Map.of("artifactSha256", contract.sha256, "loaded", true, "consoleErrors", List.copyOf(consoleErrors),
                "failedRequests", List.copyOf(failedRequests), "viewports", viewportEvidence, "actions", actionEvidence);
    }

    private static Map<String, Object> execute(Page page, Action action) {
        Locator target = page.locator(action.selector);
        require(target.count() == 1, "UI_ACTION_TARGET_NOT_UNIQUE");
        switch (action.kind) {
            case "click" -> target.click();
            case "fill" -> target.fill(action.value.asText());
            case "select" -> target.selectOption(action.value.asText());
            case "check" -> target.setChecked(action.value.asBoolean());
            default -> throw new CollectorFailure("INVALID_COLLECTOR_ACTION");
        }
        // Observe after a bounded event-loop settling window, not in the input
        // handler's stack. Never wait for or copy the caller's expectedState.
        page.waitForTimeout(250);
        Locator observed = page.locator(action.observeSelector);
        int count = observed.count();
        require(count == 1 || (count == 0 && action.property.equals("visible")), "UI_OBSERVATION_NOT_UNIQUE");
        String actual = switch (action.property) {
            case "textContent" -> Objects.toString(observed.textContent(), "");
            case "value" -> observed.inputValue();
            case "checked" -> Boolean.toString(observed.isChecked());
            case "visible" -> Boolean.toString(count == 1 && observed.isVisible());
            default -> throw new CollectorFailure("INVALID_COLLECTOR_OBSERVATION");
        };
        require(actual.length() <= 4096, "UI_OBSERVATION_TOO_LARGE");
        return Map.of("id", action.id, "observedState", actual);
    }

    private static void serve(Route route, Contract contract, long[] served, Set<String> failures) {
        try {
            URI uri = URI.create(route.request().url());
            if (!"http".equals(uri.getScheme()) || !"miniagent-artifact.invalid".equals(uri.getHost())
                    || uri.getPort() != -1 || uri.getUserInfo() != null) {
                failures.add("EXTERNAL_REQUEST_BLOCKED"); route.abort(); return;
            }
            if (!Set.of("GET", "HEAD").contains(route.request().method())) {
                failures.add("RESOURCE_METHOD_BLOCKED"); route.abort(); return;
            }
            require(++served[0] <= MAX_REQUESTS, "UI_RESOURCE_LIMIT");
            String relative = uri.getPath().substring(1);
            byte[] bytes = readResource(contract.root, relative);
            require((served[1] += bytes.length) <= MAX_SERVED_BYTES, "UI_RESOURCE_LIMIT");
            if (relative.equals(contract.entry)) require(sha256(bytes).equals(contract.sha256), "UI_ARTIFACT_CHANGED");
            route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType(contentType(relative))
                    .setHeaders(Map.of("X-Content-Type-Options", "nosniff", "Cache-Control", "no-store"))
                    .setBodyBytes(route.request().method().equals("HEAD") ? new byte[0] : bytes));
        } catch (Exception failure) {
            failures.add("LOCAL_RESOURCE_REJECTED");
            try { route.abort(); } catch (Exception ignored) { }
        }
    }

    private static Contract parse(JsonNode request) throws IOException {
        require(request != null && request.isObject(), "INVALID_COLLECTOR_REQUEST");
        Path root = Path.of(text(request, "artifactDir", 8192));
        require(root.isAbsolute() && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(root), "INVALID_COLLECTOR_ROOT");
        root = root.normalize();
        require(root.equals(root.toRealPath()), "INVALID_COLLECTOR_ROOT");
        JsonNode artifact = request.path("artifact"), check = request.path("check");
        String entry = text(artifact, "path", 2048), hash = text(artifact, "sha256", 64);
        require(hash.matches("[a-f0-9]{64}") && entry.toLowerCase(Locale.ROOT).matches(".*\\.html?"), "INVALID_COLLECTOR_ARTIFACT");
        require(check.path("viewports").isArray() && check.path("viewports").size() >= 2
                && check.path("viewports").size() <= 4, "INVALID_COLLECTOR_VIEWPORTS");
        List<Viewport> viewports = new ArrayList<>(); Set<String> sizes = new HashSet<>();
        for (JsonNode viewport : check.path("viewports")) {
            int width = viewport.path("width").asInt(-1), height = viewport.path("height").asInt(-1);
            require(viewport.path("width").isIntegralNumber() && viewport.path("height").isIntegralNumber()
                    && width >= 240 && width <= 2560 && height >= 240 && height <= 2160
                    && (long) width * height <= 4_000_000 && sizes.add(width + "x" + height), "INVALID_COLLECTOR_VIEWPORTS");
            viewports.add(new Viewport(width, height));
        }
        require(check.path("requiredSelectors").isArray() && check.path("requiredSelectors").size() > 0
                && check.path("requiredSelectors").size() <= 32, "INVALID_COLLECTOR_SELECTORS");
        List<String> selectors = new ArrayList<>();
        for (JsonNode selector : check.path("requiredSelectors")) selectors.add(selector(selector));
        JsonNode rawActions = check.path("requiredActions");
        require(rawActions.isMissingNode() || (rawActions.isArray() && rawActions.size() <= 16), "INVALID_COLLECTOR_ACTIONS");
        List<Action> actions = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (JsonNode action : rawActions) {
            String id = text(action, "id", 80), kind = text(action, "action", 16);
            require(id.matches("[A-Za-z0-9][A-Za-z0-9_.-]*") && ids.add(id) && ACTIONS.contains(kind), "INVALID_COLLECTOR_ACTIONS");
            JsonNode value = action.path("value");
            require(kind.equals("click") || (kind.equals("check") ? value.isBoolean()
                    : value.isTextual() && value.asText().length() <= 4096), "INVALID_COLLECTOR_ACTIONS");
            String property = text(action.path("observe"), "property", 24);
            require(PROPERTIES.contains(property), "INVALID_COLLECTOR_OBSERVATION");
            actions.add(new Action(id, kind, selector(action.path("selector")), value,
                    selector(action.path("observe").path("selector")), property));
        }
        return new Contract(root, entry, hash, viewports, selectors, actions);
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        require(value.isTextual() && !value.asText().isBlank() && value.asText().length() <= max, "INVALID_COLLECTOR_REQUEST");
        return value.asText();
    }

    private static String selector(JsonNode value) {
        require(value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 512
                && value.asText().chars().noneMatch(character -> character < 32), "INVALID_COLLECTOR_SELECTORS");
        return value.asText();
    }

    private static byte[] readResource(Path root, String relative) throws IOException {
        require(!relative.isBlank() && !relative.contains("\\") && !relative.contains(":")
                && relative.chars().noneMatch(character -> character < 32), "UI_RESOURCE_PATH_REJECTED");
        Path partial = Path.of(relative);
        require(!partial.isAbsolute(), "UI_RESOURCE_PATH_REJECTED");
        for (Path part : partial) require(!part.toString().equals("..") && !part.toString().equals("."), "UI_RESOURCE_PATH_REJECTED");
        Path target = root.resolve(partial).normalize();
        require(target.startsWith(root) && !target.equals(root), "UI_RESOURCE_PATH_REJECTED");
        Path cursor = root;
        for (Path part : partial) {
            cursor = cursor.resolve(part);
            require(!Files.isSymbolicLink(cursor) && cursor.toRealPath().startsWith(root)
                    && cursor.equals(cursor.toRealPath()), "UI_RESOURCE_LINK_REJECTED");
        }
        BasicFileAttributes before = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(before.isRegularFile() && before.size() <= MAX_FILE_BYTES, "UI_RESOURCE_TOO_LARGE");
        byte[] bytes = readBounded(target, MAX_FILE_BYTES);
        BasicFileAttributes after = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(target.equals(target.toRealPath()) && !Files.isSymbolicLink(target) && after.isRegularFile()
                && before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(before.fileKey(), after.fileKey()), "UI_RESOURCE_CHANGED");
        return bytes;
    }

    private static byte[] readBounded(Path path, int maximum) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                require(output.size() + buffer.remaining() <= maximum, "COLLECTOR_INPUT_TOO_LARGE");
                output.write(buffer.array(), 0, buffer.remaining()); buffer.clear();
            }
            return output.toByteArray();
        }
    }

    private static Map<String, String> driverEnvironment() {
        Map<String, String> env = new HashMap<>();
        // The Java driver merges supplied variables with its parent environment.
        // Blank excluded values too; never forward model/JWT/account secrets.
        System.getenv().forEach((name, value) -> env.put(name, DRIVER_ENV.contains(name) ? value : ""));
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        return env;
    }

    private static String contentType(String name) {
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "html", "htm" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            default -> "application/octet-stream";
        };
    }

    private static String encodePath(String value) {
        return Arrays.stream(value.split("/", -1)).map(part -> java.net.URLEncoder.encode(part,
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")).reduce((a, b) -> a + "/" + b).orElse("");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean condition, String code) { if (!condition) throw new CollectorFailure(code); }
    private record Viewport(int width, int height) {}
    private record Action(String id, String kind, String selector, JsonNode value, String observeSelector, String property) {}
    private record Contract(Path root, String entry, String sha256, List<Viewport> viewports, List<String> selectors, List<Action> actions) {}
    public static final class CollectorFailure extends RuntimeException {
        private final String code;
        CollectorFailure(String code) { super(code); this.code = code; }
    }
}
