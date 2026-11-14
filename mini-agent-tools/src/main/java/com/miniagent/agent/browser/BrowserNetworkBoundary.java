package com.miniagent.agent.browser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.*;
import com.miniagent.agent.security.NetworkGuard;

import java.net.URI;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Actor-owned Chromium request boundary. Playwright 1.49 routing alone does not intercept redirects. */
final class BrowserNetworkBoundary {
    private final BrowserContext context;
    private final NetworkGuard guard;
    private final BiConsumer<String, String> denied;
    private final Map<Page, CDPSession> pages = new IdentityHashMap<>();
    private final Map<Frame, CDPSession> remoteFrames = new IdentityHashMap<>();

    BrowserNetworkBoundary(BrowserContext context, NetworkGuard guard, BiConsumer<String, String> denied) {
        this.context = context;
        this.guard = guard;
        this.denied = denied;
    }

    void install() {
        if (guard == null) throw new IllegalStateException("浏览器请求级网络策略未配置");
        // The initial request is already in flight when route fires. Install response interception while the new
        // page is still about:blank, before navigation; attaching only from request() would miss its redirect chain.
        context.onPage(this::ensurePage);
        context.route("**/*", this::request);
        context.routeWebSocket("**/*", this::webSocket);
        context.onClose(ignored -> { pages.clear(); remoteFrames.clear(); });
    }

    private void request(Route route) {
        String url = route.request().url();
        try {
            if (guard.validateUrl(url) != null) { block(route, "request", url); return; }
            Frame frame;
            try { frame = route.request().frame(); }
            catch (PlaywrightException provisional) {
                if (!route.request().isNavigationRequest()) throw provisional;
                singleResponse(route, url, "provisional_navigation");
                return;
            }
            ensurePage(frame.page());
            // Cross-site iframes can have their own target; never mistake an ancestor's CDP boundary for coverage.
            for (Frame current = frame; current != null && current.parentFrame() != null; current = current.parentFrame()) {
                if (isRemoteFrame(current)) {
                    // Enabling Fetch after the request was paused is too late for its response in an OOPIF.
                    singleResponse(route, url, "out_of_process_frame");
                    return;
                }
            }
            route.resume();
        } catch (Exception failed) {
            // A missing/closed target or a failed guard must not fall through to an unguarded network request.
            block(route, "request_boundary_unavailable", url);
        }
    }

    /** Never resume an unchecked redirect chain when no response boundary was installed before this request. */
    private void singleResponse(Route route, String url, String surface) {
        String method = route.request().method();
        if (!("GET".equals(method) || "HEAD".equals(method))) {
            block(route, surface + "_method_unsupported", url);
            return;
        }
        APIResponse response = null;
        try {
            // The intercepted request has not been sent. fetch dispatches it once, with no retry or redirect replay.
            response = route.fetch(new Route.FetchOptions().setMaxRedirects(0).setMaxRetries(0).setTimeout(15000));
            if (response.status() >= 300 && response.status() < 400 && response.status() != 304) {
                block(route, surface + "_redirect_unsupported", url);
                return;
            }
            route.fulfill(new Route.FulfillOptions().setResponse(response));
        } finally { if (response != null) response.dispose(); }
    }

    private void webSocket(WebSocketRoute route) {
        try {
            if (guard.validateWebSocketUrl(route.url()) == null) { route.connectToServer(); return; }
        } catch (Exception unavailable) { /* Fail closed before connecting to the remote endpoint. */ }
        denied.accept("websocket", origin(route.url()));
        try { route.close(new WebSocketRoute.CloseOptions().setCode(1008).setReason("Network policy denied")); }
        catch (Exception alreadyClosed) { /* The page may have closed while the guard was checking. */ }
    }

    private void ensurePage(Page page) {
        if (pages.containsKey(page)) return;
        CDPSession session = context.newCDPSession(page);
        enable(session);
        pages.put(page, session);
        page.onClose(ignored -> pages.remove(page));
        page.onFrameDetached(frame -> remoteFrames.remove(frame));
    }

    private boolean isRemoteFrame(Frame frame) {
        CDPSession session = remoteFrames.get(frame);
        if (session != null) {
            try { session.send("Page.getFrameTree"); return true; }
            catch (PlaywrightException detached) { remoteFrames.remove(frame); }
        }
        try { session = context.newCDPSession(frame); }
        catch (PlaywrightException localFrame) {
            // This exact condition means the frame shares its already-guarded ancestor's renderer target.
            if (localFrame.getMessage() != null && localFrame.getMessage().contains("does not have a separate CDP session")) return false;
            throw localFrame;
        }
        remoteFrames.put(frame, session);
        return true;
    }

    private void enable(CDPSession session) {
        session.on("Fetch.requestPaused", event -> response(session, event));
        JsonObject pattern = new JsonObject();
        pattern.addProperty("urlPattern", "*");
        pattern.addProperty("requestStage", "Response");
        JsonArray patterns = new JsonArray(); patterns.add(pattern);
        JsonObject params = new JsonObject(); params.add("patterns", patterns);
        session.send("Fetch.enable", params);
    }

    private void response(CDPSession session, JsonObject event) {
        String requestId = event.get("requestId").getAsString();
        String url = event.getAsJsonObject("request").get("url").getAsString();
        try {
            if (event.has("responseStatusCode")) {
                int status = event.get("responseStatusCode").getAsInt();
                if (status >= 300 && status < 400 && status != 304 && event.has("responseHeaders")) {
                    for (var raw : event.getAsJsonArray("responseHeaders")) {
                        var header = raw.getAsJsonObject();
                        if (!"location".equalsIgnoreCase(header.get("name").getAsString())) continue;
                        String target = URI.create(url).resolve(header.get("value").getAsString().trim()).toString();
                        if (guard.validateUrl(target) != null) { fail(session, requestId, "redirect", target); return; }
                    }
                }
            }
            JsonObject params = new JsonObject(); params.addProperty("requestId", requestId);
            session.send("Fetch.continueRequest", params);
        } catch (Exception unavailable) {
            fail(session, requestId, "redirect_boundary_unavailable", url);
        }
    }

    private void block(Route route, String surface, String url) {
        denied.accept(surface, origin(url));
        try { route.abort("blockedbyclient"); }
        catch (Exception alreadyClosed) { /* Never continue a failed guard. */ }
    }

    private void fail(CDPSession session, String requestId, String surface, String url) {
        denied.accept(surface, origin(url));
        JsonObject params = new JsonObject();
        params.addProperty("requestId", requestId); params.addProperty("errorReason", "BlockedByClient");
        try { session.send("Fetch.failRequest", params); }
        catch (Exception alreadyClosed) { /* A destroyed target cannot issue the pending redirect. */ }
    }

    private static String origin(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? "invalid-origin" : uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (Exception invalid) { return "invalid-origin"; }
    }
}
