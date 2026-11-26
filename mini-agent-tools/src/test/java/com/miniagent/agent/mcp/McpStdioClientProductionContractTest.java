package com.miniagent.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpStdioClientProductionContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void configuredRequestDeadlineIsBoundedToTheSameRangeUsedByTheClientProfile() {
        McpProperties.Server server = new McpProperties.Server();

        server.setRequestTimeoutSeconds(-5);
        assertThat(server.getRequestTimeoutSeconds()).isEqualTo(1);
        server.setRequestTimeoutSeconds(2_000);
        assertThat(server.getRequestTimeoutSeconds()).isEqualTo(600);
    }

    @Test
    void listToolsFollowsCursorsAndAggregatesTheWholeAllowlist() throws Exception {
        List<String> cursorsSeen = new ArrayList<>();
        FakeMcpProcess process = new FakeMcpProcess(request -> {
            String method = request.path("method").asText();
            if (!"tools/list".equals(method)) return;
            String cursor = request.path("params").path("cursor").asText("");
            cursorsSeen.add(cursor);
            if (cursor.isEmpty()) {
                ObjectNode result = JSON.createObjectNode();
                result.set("tools", JSON.createArrayNode().add(tool("read_page_one")));
                result.put("nextCursor", "page-2");
                processReply(request, result);
            } else {
                ObjectNode result = JSON.createObjectNode();
                result.set("tools", JSON.createArrayNode().add(tool("write_page_two")));
                processReply(request, result);
            }
        });

        try (McpStdioClient client = new McpStdioClient("paging", process, 1024 * 1024, 2, 2)) {
            List<McpStdioClient.McpToolDef> tools = client.listTools();
            assertThat(tools).extracting(McpStdioClient.McpToolDef::name)
                    .containsExactly("read_page_one", "write_page_two");
            assertThat(cursorsSeen).containsExactly("", "page-2");
        }
    }

    @Test
    void repeatedCursorFailsClosedInsteadOfLoopingOrSilentlyTruncatingToolCatalog() throws Exception {
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        FakeMcpProcess process = new FakeMcpProcess(request -> {
            if (!"tools/list".equals(request.path("method").asText())) return;
            calls.updateAndGet(value -> value + 1);
            ObjectNode result = JSON.createObjectNode();
            result.set("tools", JSON.createArrayNode().add(tool("one")));
            result.put("nextCursor", "same-cursor");
            processReply(request, result);
        });

        try (McpStdioClient client = new McpStdioClient("cursor-loop", process, 1024 * 1024, 2, 2)) {
            assertThatThrownBy(client::listTools)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("重复返回分页游标");
            assertThat(calls.get()).isEqualTo(2);
        }
    }

    @Test
    void timeoutSendsCancellationNotificationThenInvalidatesUnresponsiveServer() throws Exception {
        CountDownLatch callReceived = new CountDownLatch(1);
        FakeMcpProcess process = new FakeMcpProcess(request -> {
            String method = request.path("method").asText();
            if ("tools/call".equals(method)) callReceived.countDown();
        });

        McpStdioClient client = new McpStdioClient("cancel", process, 1024 * 1024, 1, 2);
        try {
            assertThatThrownBy(() -> client.callTool("write", "{}"))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class)
                    .hasMessageContaining("cancellation was sent")
                    .hasMessageContaining("remote side effects may already have happened");
            assertThat(callReceived.await(1, TimeUnit.SECONDS)).isTrue();
            // MCP cancellation is a notification, so there is no remote
            // acknowledgement to wait for. The production contract is that
            // the complete framed notification is flushed by the single writer
            // before the connection is retired; assert those bytes and order
            // deterministically at the transport boundary instead of requiring
            // an unobservable server-side handler race to win.
            assertThat(process.cancellationFrameFlushed).isTrue();
            assertThat(process.destroyedAfterCancellation).isTrue();
            assertThat(process.isAlive()).isFalse();
            assertThat(client.isHealthy()).isFalse();
        } finally {
            client.close();
        }
    }

    @Test
    void recordingTransportParsesDifferentLengthFramesAcrossOneAndMultipleFlushes() throws Exception {
        AtomicBoolean cancellationSeen = new AtomicBoolean();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        FakeMcpProcess.RecordingOutputStream recording =
                new FakeMcpProcess.RecordingOutputStream(sink, cancellationSeen);
        JsonNode call = JSON.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"arguments\":\"long-payload\"}}");
        JsonNode cancellation = JSON.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1,\"reason\":\"timeout\"}}");

        writeTransportFrame(recording, call);
        writeTransportFrame(recording, cancellation);
        recording.flush();
        assertThat(cancellationSeen).isTrue();

        AtomicBoolean splitCancellationSeen = new AtomicBoolean();
        ByteArrayOutputStream splitSink = new ByteArrayOutputStream();
        FakeMcpProcess.RecordingOutputStream splitRecording =
                new FakeMcpProcess.RecordingOutputStream(splitSink, splitCancellationSeen);
        writeTransportFrame(splitRecording, call);
        splitRecording.flush();
        assertThat(splitCancellationSeen).isFalse();
        byte[] cancellationBody = JSON.writeValueAsBytes(cancellation);
        byte[] cancellationHeader = ("Content-Length: " + cancellationBody.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        splitRecording.write(cancellationHeader);
        splitRecording.write(cancellationBody, 0, cancellationBody.length / 2);
        splitRecording.flush();
        assertThat(splitCancellationSeen).isFalse();
        splitRecording.write(cancellationBody, cancellationBody.length / 2,
                cancellationBody.length - cancellationBody.length / 2);
        splitRecording.flush();
        assertThat(splitCancellationSeen).isTrue();
    }

    @Test
    void blockedStdioWriteIsBoundedAndKillsTheUnresponsiveProcess() {
        BlockingOutputProcess process = new BlockingOutputProcess();
        long started = System.nanoTime();
        try (McpStdioClient client = new McpStdioClient("blocked-write", process,
                1024 * 1024, 2, 1)) {
            assertThatThrownBy(() -> client.listTools())
                    .isInstanceOf(java.util.concurrent.TimeoutException.class)
                    .hasMessageContaining("写入超时");
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMillis).isLessThan(3_000);
        assertThat(process.destroyed.get()).isTrue();
    }

    private static ObjectNode tool(String name) {
        ObjectNode tool = JSON.createObjectNode();
        tool.put("name", name);
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", JSON.createObjectNode());
        tool.set("inputSchema", schema);
        return tool;
    }

    private static void writeTransportFrame(OutputStream output, JsonNode message) throws Exception {
        byte[] body = JSON.writeValueAsBytes(message);
        output.write(("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.write(body);
    }

    private static void processReply(JsonNode request, JsonNode result) {
        // The server callback is bound to FakeMcpProcess.serverOutput through a thread-local helper.
        FakeMcpProcess.replyCurrent(request, result);
    }

    private static class FakeMcpProcess extends Process {
        private static final ConcurrentLinkedQueue<FakeMcpProcess> PROCESSES = new ConcurrentLinkedQueue<>();
        private final PipedInputStream clientInput = new PipedInputStream(64 * 1024);
        private final PipedOutputStream serverOutput;
        private final PipedInputStream serverInput = new PipedInputStream(64 * 1024);
        private final RecordingOutputStream clientOutput;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean cancellationFrameFlushed = new AtomicBoolean();
        private final AtomicBoolean destroyedAfterCancellation = new AtomicBoolean();
        private final Thread server;
        private final Consumer<JsonNode> handler;

        FakeMcpProcess(Consumer<JsonNode> handler) throws Exception {
            this.handler = handler;
            serverOutput = new PipedOutputStream(clientInput);
            clientOutput = new RecordingOutputStream(new PipedOutputStream(serverInput),
                    cancellationFrameFlushed);
            PROCESSES.add(this);
            server = Thread.ofVirtual().name("fake-mcp-server").start(() -> {
                try {
                    while (alive.get()) {
                        JsonNode request = McpStdioClient.readOneMessage(serverInput, 1024 * 1024);
                        if (request == null) break;
                        this.handler.accept(request);
                    }
                } catch (Exception ignored) {
                    // Closing either pipe is the fake process' normal shutdown path.
                }
            });
        }

        static void replyCurrent(JsonNode request, JsonNode result) {
            FakeMcpProcess process = PROCESSES.peek();
            if (process == null || process.server != Thread.currentThread()) {
                process = PROCESSES.stream().filter(p -> p.server == Thread.currentThread())
                        .findFirst().orElseThrow(() -> new IllegalStateException("fake server thread not bound"));
            }
            ObjectNode response = JSON.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            response.set("result", result);
            try {
                byte[] body = JSON.writeValueAsBytes(response);
                process.serverOutput.write(("Content-Length: " + body.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                process.serverOutput.write(body);
                process.serverOutput.flush();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public OutputStream getOutputStream() { return clientOutput; }
        @Override public InputStream getInputStream() { return clientInput; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException { server.join(); return alive.get() ? 0 : 1; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            server.join(unit.toMillis(timeout)); return !server.isAlive();
        }
        @Override public int exitValue() { if (alive.get()) throw new IllegalThreadStateException(); return 1; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() {
            if (alive.compareAndSet(true, false)) {
                if (cancellationFrameFlushed.get()) destroyedAfterCancellation.set(true);
                try { clientOutput.close(); } catch (Exception ignored) {}
                try { serverOutput.close(); } catch (Exception ignored) {}
                try { serverInput.close(); } catch (Exception ignored) {}
                try { clientInput.close(); } catch (Exception ignored) {}
                server.interrupt();
            }
            return this;
        }

        /** Records the actual framed bytes handed to the child pipe. */
        private static final class RecordingOutputStream extends OutputStream {
            private static final byte[] HEADER_SEPARATOR = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            private final OutputStream delegate;
            private final AtomicBoolean cancellationFrameFlushed;
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            private RecordingOutputStream(OutputStream delegate,
                                          AtomicBoolean cancellationFrameFlushed) {
                this.delegate = delegate;
                this.cancellationFrameFlushed = cancellationFrameFlushed;
            }

            @Override
            public synchronized void write(int value) throws java.io.IOException {
                delegate.write(value);
                bytes.write(value);
            }

            @Override
            public synchronized void write(byte[] source, int offset, int length)
                    throws java.io.IOException {
                delegate.write(source, offset, length);
                bytes.write(source, offset, length);
            }

            @Override
            public synchronized void flush() throws java.io.IOException {
                delegate.flush();
                byte[] frameBytes = bytes.toByteArray();
                int frameStart = 0;
                int separator = indexOf(frameBytes, HEADER_SEPARATOR, frameStart);
                while (separator >= 0) {
                    String header = new String(frameBytes, frameStart,
                            separator - frameStart, StandardCharsets.US_ASCII);
                    int contentLength = contentLength(header);
                    int bodyStart = separator + HEADER_SEPARATOR.length;
                    if (contentLength < 0 || frameBytes.length - bodyStart < contentLength) return;
                    try {
                        JsonNode message = JSON.readTree(frameBytes, bodyStart, contentLength);
                        if ("notifications/cancelled".equals(message.path("method").asText())
                                && message.path("params").path("requestId").isIntegralNumber()) {
                            cancellationFrameFlushed.set(true);
                        }
                    } catch (Exception malformed) {
                        return;
                    }
                    int next = bodyStart + contentLength;
                    if (next >= frameBytes.length) return;
                    frameStart = next;
                    separator = indexOf(frameBytes, HEADER_SEPARATOR, frameStart);
                }
            }

            @Override
            public void close() throws java.io.IOException {
                delegate.close();
            }

            private static int contentLength(String header) {
                for (String line : header.split("\\r?\\n")) {
                    if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                        try { return Integer.parseInt(line.substring(15).trim()); }
                        catch (NumberFormatException ignored) { return -1; }
                    }
                }
                return -1;
            }

            private static int indexOf(byte[] haystack, byte[] needle, int from) {
                outer: for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
                    for (int j = 0; j < needle.length; j++) {
                        if (haystack[i + j] != needle[j]) continue outer;
                    }
                    return i;
                }
                return -1;
            }
        }
    }

    private static final class BlockingOutputProcess extends Process {
        final AtomicBoolean destroyed = new AtomicBoolean();
        final CountDownLatch release = new CountDownLatch(1);
        final InputStream input = new InputStream() {
            @Override public int read() throws java.io.IOException {
                try {
                    release.await();
                    return -1;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("reader stopped", interrupted);
                }
            }
        };
        final OutputStream output = new OutputStream() {
            @Override public void write(int b) throws java.io.IOException {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Simulate an OS pipe write that does not honor Java interruption.
                    try { release.await(); } catch (InterruptedException ignoredAgain) {
                        throw new java.io.IOException("interrupted after process termination");
                    }
                }
                throw new java.io.IOException("process pipe closed");
            }
        };
        @Override public OutputStream getOutputStream() { return output; }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return destroyed.get() ? 1 : 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return destroyed.get(); }
        @Override public int exitValue() { if (!destroyed.get()) throw new IllegalThreadStateException(); return 1; }
        @Override public boolean isAlive() { return !destroyed.get(); }
        @Override public void destroy() { destroyForcibly(); }
        @Override public Process destroyForcibly() { destroyed.set(true); release.countDown(); return this; }
    }
}
