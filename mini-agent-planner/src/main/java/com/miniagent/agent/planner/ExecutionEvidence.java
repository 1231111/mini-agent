package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.trace.TraceDataSanitizer;
import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Structured observations captured from one node's current execution turn.
 * Model claims are retained separately and cannot manufacture a tool receipt.
 */
public record ExecutionEvidence(List<Observation> observations, String modelClaims) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ENVELOPE_CHARS = 11_200;
    private static final int MAX_CLAIMS_CHARS = 8_000;
    private static final int MAX_OBSERVATION_CHARS = 1_600;

    public ExecutionEvidence {
        observations = observations == null ? List.of() : List.copyOf(observations);
        modelClaims = modelClaims == null ? "" : modelClaims;
        if (observations.size() > 128 || modelClaims.length() > 24_000) {
            throw new IllegalArgumentException("execution evidence exceeds bounds");
        }
    }

    public ExecutionEvidence(List<Observation> observations) {
        this(observations, "");
    }

    /** Compatibility adapter: legacy text is a claim, never an observation. */
    public static ExecutionEvidence claims(String text) {
        return new ExecutionEvidence(List.of(), text);
    }

    /** Build observations only from paired current-turn assistant calls/results. */
    static ExecutionEvidence fromMessages(String claims, List<ChatMessage> messages) {
        return capture(claims, messages).evidence();
    }

    private static Capture capture(String claims, List<ChatMessage> messages) {
        List<ChatMessage> current = currentTurn(messages);
        Map<String, Call> calls = new LinkedHashMap<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> duplicateIds = new HashSet<>();
        List<Observation> observations = new ArrayList<>();
        int sequence = 0;
        int batch = 0;
        int messageIndex = 0;
        int callCount = 0;
        int unmatchedResults = 0;
        for (ChatMessage message : current) {
            if (message instanceof AiMessage assistant && assistant.hasToolExecutionRequests()) {
                for (ToolExecutionRequest request : assistant.toolExecutionRequests()) {
                    if (request == null || StringUtils.isBlank(request.id())
                            || CapabilityRegistry.FALLBACK_TOOL.equals(request.name())) continue;
                    callCount++;
                    if (!seenIds.add(request.id())) {
                        duplicateIds.add(request.id());
                        calls.remove(request.id());
                        continue;
                    }
                    calls.put(request.id(), new Call(sequence++, batch, messageIndex, request));
                }
                batch++;
            } else if (message instanceof ToolExecutionResultMessage result
                    && !CapabilityRegistry.FALLBACK_TOOL.equals(result.toolName())) {
                Call call = calls.remove(result.id());
                if (call == null || duplicateIds.contains(result.id())
                        || !StringUtils.equals(call.request().name(), result.toolName())) {
                    unmatchedResults++;
                    messageIndex++;
                    continue;
                }
                String raw = Objects.requireNonNullElse(result.text(), "");
                ToolResult parsed = ToolResult.fromLegacy(raw);
                boolean truncated = sanitized(raw).length() > MAX_OBSERVATION_CHARS;
                observations.add(new Observation(call.request().name(),
                        bounded(call.request().arguments(), 900), parsed.status().name(),
                        bounded(raw, MAX_OBSERVATION_CHARS), call.sequence(), call.batch(),
                        call.messageIndex(), messageIndex, parsed.errorCode().name(),
                        parsed.isSuccess(), truncated, sha256(raw)));
            }
            messageIndex++;
        }
        observations.sort(java.util.Comparator.comparingInt(Observation::sequence));
        // The record itself remains the compact API consumed by recovery planning;
        // the judge envelope below carries exact counts and explicit incompleteness.
        return new Capture(new ExecutionEvidence(observations, bounded(claims, 24_000)),
                callCount, Math.max(0, callCount - observations.size()), unmatchedResults);
    }

    /** Build a bounded, valid JSON envelope for semantic acceptance. */
    public static String build(String claims, List<ChatMessage> messages) {
        Capture captured = capture(claims, messages);
        ExecutionEvidence evidence = captured.evidence();
        List<Observation> observations = new ArrayList<>(evidence.observations());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 1);
        envelope.put("kind", "EXECUTION_EVIDENCE");
        envelope.put("claims", bounded(claims, MAX_CLAIMS_CHARS));
        envelope.put("claimsSha256", sha256(claims));
        envelope.put("claimsTruncated", sanitized(claims).length() > MAX_CLAIMS_CHARS);
        envelope.put("observations", observations);
        envelope.put("callCount", captured.calls());
        envelope.put("pairedCount", observations.size());
        envelope.put("unpairedCallCount", captured.unpairedCalls());
        envelope.put("unpairedResultCount", captured.unpairedResults());
        envelope.put("omittedObservationCount", 0);
        envelope.put("complete", !observations.isEmpty() && captured.unpairedCalls() == 0
                && captured.unpairedResults() == 0
                && !Boolean.TRUE.equals(envelope.get("claimsTruncated"))
                && observations.stream().noneMatch(Observation::truncated));
        String encoded = serialize(envelope);
        if (encoded.length() <= MAX_ENVELOPE_CHARS) return encoded;
        envelope.put("complete", false);
        envelope.put("truncated", true);
        while (observations.size() > 1 && serialize(envelope).length() > MAX_ENVELOPE_CHARS) {
            observations.remove(observations.size() - 1);
        }
        envelope.put("omittedObservationCount", evidence.observations().size() - observations.size());
        if (serialize(envelope).length() > MAX_ENVELOPE_CHARS) {
            envelope.put("observations", List.of());
            envelope.put("omittedObservationCount", evidence.observations().size());
        }
        return serialize(envelope);
    }

    private record Call(int sequence, int batch, int messageIndex, ToolExecutionRequest request) {}
    private record Capture(ExecutionEvidence evidence, int calls, int unpairedCalls, int unpairedResults) {}

    /** Metadata lets a judge verify invocation and result order, not array order alone. */
    public record Observation(String toolName, String arguments, String status, String receipt,
                              int sequence, int batch, int callMessageIndex, int resultMessageIndex,
                              String errorCode, boolean success, boolean truncated, String resultSha256) {
        public Observation {
            toolName = Objects.requireNonNullElse(toolName, "").trim();
            arguments = Objects.requireNonNullElse(arguments, "");
            status = Objects.requireNonNullElse(status, "").trim();
            receipt = Objects.requireNonNullElse(receipt, "");
            errorCode = Objects.requireNonNullElse(errorCode, "").trim();
            resultSha256 = Objects.requireNonNullElse(resultSha256, "").trim();
            if (StringUtils.isBlank(toolName) || toolName.length() > 120
                    || arguments.length() > 8_000 || status.length() > 64
                    || receipt.length() > 24_000 || errorCode.length() > 64) {
                throw new IllegalArgumentException("invalid execution observation");
            }
        }

        /** Backwards-compatible constructor for existing planner callers. */
        public Observation(String toolName, String arguments, String status, String receipt) {
            this(toolName, arguments, status, receipt, -1, -1, -1, -1, "",
                    "SUCCESS".equalsIgnoreCase(status), false, sha256(receipt));
        }
    }

    private static List<ChatMessage> currentTurn(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        int start = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) start = i + 1;
        }
        return List.copyOf(messages.subList(start, messages.size()));
    }

    private static String bounded(String value, int max) {
        String safe = sanitized(value);
        if (safe.length() <= max) return safe;
        return safe.substring(0, Math.max(0, max - 82))
                + "\n[execution evidence truncated; sha256=" + sha256(safe) + "]";
    }

    private static String sanitized(String value) {
        return TraceDataSanitizer.sanitize(value == null ? "" : value);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String serialize(Map<String, Object> value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("cannot serialize execution evidence", e); }
    }
}
