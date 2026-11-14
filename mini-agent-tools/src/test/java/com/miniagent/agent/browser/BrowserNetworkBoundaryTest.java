package com.miniagent.agent.browser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.*;
import com.miniagent.agent.security.NetworkGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BrowserNetworkBoundaryTest {
    private BrowserContext context;
    private NetworkGuard guard;
    private Consumer<Route> requests;
    private Consumer<WebSocketRoute> sockets;
    private final List<String> denials = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void install() {
        context = mock(BrowserContext.class);
        guard = mock(NetworkGuard.class);
        new BrowserNetworkBoundary(context, guard, (surface, origin) -> denials.add(surface + " " + origin)).install();
        verify(context).onPage(any());
        ArgumentCaptor<Consumer<Route>> http = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(context).route(eq("**/*"), http.capture()); requests = http.getValue();
        ArgumentCaptor<Consumer<WebSocketRoute>> ws = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(context).routeWebSocket(eq("**/*"), ws.capture()); sockets = ws.getValue();
    }

    @Test
    void disallowedSubresourceIsAbortedBeforeConnectingAndDoesNotLeakQueryTokens() {
        Route route = route("http://127.0.0.1/private?secret=token");
        when(guard.validateUrl(anyString())).thenReturn("private denied");
        requests.accept(route);
        verify(route).abort("blockedbyclient"); verify(route, never()).resume();
        assertEquals(List.of("request http://127.0.0.1"), denials);
        verify(context, never()).newCDPSession(any(Page.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void redirectIsValidatedBeforeResponseIsReleasedAndAllowedRedirectsRemainNative() {
        Route route = route("https://public.test/start");
        Page page = mock(Page.class); Frame frame = mock(Frame.class); CDPSession session = mock(CDPSession.class);
        when(route.request().frame()).thenReturn(frame); when(frame.page()).thenReturn(page);
        when(context.newCDPSession(page)).thenReturn(session);
        requests.accept(route);
        verify(session).send(eq("Fetch.enable"), any()); verify(route).resume();
        ArgumentCaptor<Consumer<JsonObject>> events = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(session).on(eq("Fetch.requestPaused"), events.capture());
        when(guard.validateUrl("http://127.0.0.1/private")).thenReturn("private denied");
        events.getValue().accept(response("https://public.test/start", "http://127.0.0.1/private"));
        verify(session).send(eq("Fetch.failRequest"), argThat(params -> "BlockedByClient".equals(params.get("errorReason").getAsString())));
        verify(session, never()).send(eq("Fetch.continueRequest"), any());
        events.getValue().accept(response("https://public.test/start", "/allowed"));
        verify(guard).validateUrl("https://public.test/allowed");
        verify(session).send(eq("Fetch.continueRequest"), any());
    }

    @Test
    void missingRequestBoundaryOrGuardFailureNeverResumesTheRequest() {
        Route route = route("https://public.test/resource");
        when(guard.validateUrl(anyString())).thenThrow(new IllegalStateException("resolver unavailable"));
        requests.accept(route);
        verify(route).abort("blockedbyclient"); verify(route, never()).resume();
    }

    @Test
    void deniedWebSocketDoesNotConnectAndAllowedOneRetainsNormalForwarding() {
        WebSocketRoute denied = mock(WebSocketRoute.class);
        when(denied.url()).thenReturn("ws://127.0.0.1/private");
        when(guard.validateWebSocketUrl(denied.url())).thenReturn("private denied");
        sockets.accept(denied);
        verify(denied, never()).connectToServer(); verify(denied).close(any());
        WebSocketRoute allowed = mock(WebSocketRoute.class); when(allowed.url()).thenReturn("wss://public.test/stream");
        sockets.accept(allowed); verify(allowed).connectToServer();
    }

    @Test
    void provisionalPopupNeverFollowsRedirectsOrDispatchesUnsupportedWrites() {
        Route redirect = route("https://public.test/popup");
        when(redirect.request().frame()).thenThrow(new PlaywrightException("Frame not created"));
        when(redirect.request().isNavigationRequest()).thenReturn(true);
        when(redirect.request().method()).thenReturn("GET");
        APIResponse response = mock(APIResponse.class); when(response.status()).thenReturn(302);
        when(redirect.fetch(any(Route.FetchOptions.class))).thenReturn(response);
        requests.accept(redirect);
        verify(redirect).fetch(argThat(options -> options.maxRedirects == 0 && options.maxRetries == 0));
        verify(redirect).abort("blockedbyclient"); verify(redirect, never()).resume();
        verify(response).dispose();
        Route post = route("https://public.test/popup-post");
        when(post.request().frame()).thenThrow(new PlaywrightException("Frame not created"));
        when(post.request().isNavigationRequest()).thenReturn(true); when(post.request().method()).thenReturn("POST");
        requests.accept(post);
        verify(post, never()).fetch(any()); verify(post, never()).resume(); verify(post).abort("blockedbyclient");
    }

    private Route route(String url) {
        Route route = mock(Route.class); Request request = mock(Request.class);
        when(route.request()).thenReturn(request); when(request.url()).thenReturn(url);
        return route;
    }

    private JsonObject response(String url, String location) {
        JsonObject event = new JsonObject(); event.addProperty("requestId", "request-1");
        JsonObject request = new JsonObject(); request.addProperty("url", url); event.add("request", request);
        event.addProperty("responseStatusCode", 302);
        JsonArray headers = new JsonArray(); JsonObject header = new JsonObject();
        header.addProperty("name", "Location"); header.addProperty("value", location); headers.add(header);
        event.add("responseHeaders", headers); return event;
    }
}
