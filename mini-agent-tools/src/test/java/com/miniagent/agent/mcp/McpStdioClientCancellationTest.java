package com.miniagent.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpStdioClientCancellationTest {
    @Test
    @Timeout(15)
    void repeatedCancellationDoesNotExhaustTheConnectionRequestCapacity() throws Exception {
        SilentProcess server = new SilentProcess();
        try (McpStdioClient client = new McpStdioClient("test", server)) {
            // More cancellations than the connection's 256 pending-request limit.
            // The server stays connected but never answers the cancelled calls.
            for (int i = 0; i < 260; i++) {
                Call call = startCall(client);
                assertNotNull(server.requests.poll(1, TimeUnit.SECONDS),
                        "cancelled requests must not block subsequent calls: " + i);
                call.thread().interrupt();
                call.thread().join(1_000);
                assertFalse(call.thread().isAlive());
                assertInstanceOf(InterruptedException.class, call.error().get());
            }

            Call next = startCall(client);
            Long requestId = server.requests.poll(1, TimeUnit.SECONDS);
            assertNotNull(requestId);
            server.reply(requestId, "ok");
            next.thread().join(1_000);
            assertFalse(next.thread().isAlive());
            assertNull(next.error().get());
            assertEquals("ok", next.result().get());
        }
    }

    @Test
    @Timeout(10)
    void cancellationLeavesOtherRequestsAndLateResponsesIsolated() throws Exception {
        SilentProcess server = new SilentProcess();
        try (McpStdioClient client = new McpStdioClient("test", server)) {
            Call cancelled = startCall(client);
            Long cancelledId = server.requests.poll(1, TimeUnit.SECONDS);
            assertNotNull(cancelledId);
            Call other = startCall(client);
            Long otherId = server.requests.poll(1, TimeUnit.SECONDS);
            assertNotNull(otherId);

            cancelled.thread().interrupt();
            cancelled.thread().join(1_000);
            assertInstanceOf(InterruptedException.class, cancelled.error().get());
            server.reply(cancelledId, "late result for cancelled request");
            server.reply(otherId, "other result");
            other.thread().join(1_000);

            assertNull(other.error().get());
            assertEquals("other result", other.result().get());
            assertNull(cancelled.result().get());
        }
    }

    @Test
    @Timeout(10)
    void protocolNullResultCannotBeReportedAsSuccessfulToolOutput() throws Exception {
        SilentProcess server = new SilentProcess();
        try (McpStdioClient client = new McpStdioClient("test", server)) {
            Call call = startCall(client);
            Long id = server.requests.poll(1, TimeUnit.SECONDS);
            assertNotNull(id);
            server.respond(SilentProcess.JSON.createObjectNode().put("jsonrpc", "2.0")
                    .put("id", id).putNull("result"));
            call.thread().join(1_000);

            assertFalse(call.thread().isAlive());
            assertNull(call.result().get());
            assertInstanceOf(IllegalStateException.class, call.error().get());
        }
    }

    private static Call startCall(McpStdioClient client) {
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                result.set(client.callTool("submit", "{}"));
            } catch (Throwable failure) {
                error.set(failure);
            }
        });
        return new Call(thread, result, error);
    }

    private record Call(Thread thread, AtomicReference<String> result, AtomicReference<Throwable> error) {}

    /** In-memory stdio peer: receives frames but only responds when the test asks. */
    private static final class SilentProcess extends Process {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final LinkedBlockingQueue<Long> requests = new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<Integer> responses = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        private final InputStream input = new InputStream() {
            @Override public int read() throws IOException {
                try {
                    return responses.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return 0;
                int first = read();
                if (first == -1) return -1;
                bytes[offset] = (byte) first;
                int count = 1;
                while (count < length) {
                    Integer next = responses.poll();
                    if (next == null || next == -1) break;
                    bytes[offset + count++] = next.byteValue();
                }
                return count;
            }
        };
        private final OutputStream output = new OutputStream() {
            @Override public void write(int value) { frame.write(value); }
            @Override public void write(byte[] bytes) throws IOException { frame.write(bytes); }
            @Override public void flush() throws IOException {
                String content = frame.toString(StandardCharsets.UTF_8);
                frame.reset();
                JsonNode message = JSON.readTree(content.substring(content.indexOf("\r\n\r\n") + 4));
                requests.add(message.get("id").asLong());
            }
        };

        void reply(long id, String value) throws IOException {
            ObjectNode result = JSON.createObjectNode();
            result.putArray("content").addObject().put("type", "text").put("text", value);
            ObjectNode message = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id);
            message.set("result", result);
            respond(message);
        }

        void respond(ObjectNode message) throws IOException {
            for (byte valueByte : (JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8)) {
                responses.add(Byte.toUnsignedInt(valueByte));
            }
        }

        @Override public OutputStream getOutputStream() { return output; }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { responses.add(-1); }
        @Override public Process destroyForcibly() { destroy(); return this; }
    }
}
