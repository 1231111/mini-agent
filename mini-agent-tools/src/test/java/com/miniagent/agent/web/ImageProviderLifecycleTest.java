package com.miniagent.agent.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class ImageProviderLifecycleTest {
    private HttpServer server;
    @AfterEach void stop() { if (server != null) server.stop(0); }

    private static class StubService extends ImageGenerationService {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        String mode;
        StubService(String mode) throws Exception {
            this.mode = mode;
            field(this, "configuredBackend", "chatanywhere");
            field(this, "chatAnywhereKey", "test-placeholder");
            field(this, "mimoKey", "test-placeholder");
            field(this, "pollinationsEnabled", false);
        }
        @Override long backendWaitSeconds(String backend) { return 1; }
        @Override protected String invokeBackend(String backend, String prompt, String size) throws Exception {
            calls.add(backend);
            started.countDown();
            if ("reject".equals(mode)) throw new BackendRejectedException("HTTP 401");
            if ("io".equals(mode)) throw new IOException("connection reset after submission");
            if ("empty".equals(mode)) return null;
            try { Thread.sleep(30_000); return "unexpected"; }
            catch (InterruptedException cancelled) { interrupted.countDown(); throw cancelled; }
        }
    }

    @Test void timeoutInterruptsActualWorkerAndDoesNotSubmitFallback() throws Exception {
        StubService service = new StubService("block");
        String result = service.generate("test", "square");
        assertThat(result).contains("OUTCOME_UNKNOWN");
        assertThat(service.calls).containsExactly("chatanywhere");
        assertThat(service.interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test void transportUnknownAndEmptySuccessDoNotCreateDuplicateGenerations() throws Exception {
        for (String mode : List.of("io", "empty")) {
            StubService service = new StubService(mode);
            assertThat(service.generate("test", "square")).contains("OUTCOME_UNKNOWN");
            assertThat(service.calls).containsExactly("chatanywhere");
        }
    }

    @Test void explicitRejectionAllowsSequentialFallback() throws Exception {
        StubService service = new StubService("reject");
        assertThat(service.generate("test", "square")).contains("DEPENDENCY_UNAVAILABLE");
        assertThat(service.calls).containsExactly("chatanywhere", "mimo");
    }

    @Test void callerCancellationPreservesInterruptAndStopsActualWorker() throws Exception {
        StubService service = new StubService("block");
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        Thread caller = Thread.ofVirtual().start(() -> {
            result.set(service.generate("test", "square"));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        assertThat(service.started.await(2, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(3000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(result.get()).contains("OUTCOME_UNKNOWN");
        assertThat(interrupted.get()).isTrue();
        assertThat(service.interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(service.calls).hasSize(1);
    }

    @Test void bodyDeadlineIncludesSlowChunkedResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write('a'); output.flush();
                try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                output.write('b');
            } catch (IOException ignored) { }
        });
        server.start();
        long start = System.nanoTime();
        assertThatThrownBy(() -> ProviderHttp.send(HttpClient.newHttpClient(), request(250), ProviderHttp.utf8(100)))
                .isInstanceOf(java.net.http.HttpTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test void declaredAndChunkedOversizeBodiesAreRejected() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = "x".repeat(1000).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, exchange.getRequestURI().getQuery() == null ? bytes.length : 0);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            catch (IOException ignored) { }
        });
        server.start();
        for (String query : List.of("", "?chunked")) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base() + query)).timeout(Duration.ofSeconds(2)).build();
            assertThatThrownBy(() -> ProviderHttp.send(HttpClient.newHttpClient(), request, ProviderHttp.bytes(100)))
                    .isInstanceOf(IOException.class).hasMessageContaining("exceeds");
        }
    }

    private HttpRequest request(int millis) {
        return HttpRequest.newBuilder(URI.create(base())).timeout(Duration.ofMillis(millis)).build();
    }
    private String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
    private static void field(Object target, String name, Object value) throws Exception {
        Field field = ImageGenerationService.class.getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
}
