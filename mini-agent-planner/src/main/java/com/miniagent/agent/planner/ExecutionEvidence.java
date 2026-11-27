package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
public record ExecutionEvidence(List<Observation> observations, String modelClaims, Completeness completeness) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final int MAX_ENVELOPE_CHARS = 11_200;
    private static final int MAX_CLAIMS_CHARS = 8_000;
    private static final int MAX_OBSERVATION_CHARS = 1_600;
    private static final int MAX_OBSERVATIONS = 128;
    private static final int MAX_PARSE_CHARS = 48_000;

    public ExecutionEvidence {
        observations = observations == null ? List.of() : List.copyOf(observations);
        modelClaims = modelClaims == null ? "" : modelClaims;
        completeness = completeness == null ? Completeness.unknown() : completeness;
        if (observations.size() > MAX_OBSERVATIONS || modelClaims.length() > 24_000) {
            throw new IllegalArgumentException("execution evidence exceeds bounds");
        }
    }

    public ExecutionEvidence(List<Observation> observations, String modelClaims) {
        this(observations, modelClaims, Completeness.unknown());
    }

    public ExecutionEvidence(List<Observation> observations) {
        this(observations, "");
    }

    /** Compatibility adapter: legacy text is a claim, never an observation. */
    public static ExecutionEvidence claims(String text) {
        return new ExecutionEvidence(List.of(), bounded(text, 24_000),
                new Completeness(false, sanitized(text).length() > 24_000, 0, 0, 0, 0, 0));
    }

    /**
     * Read an envelope persisted by NodeOutputBinder, not arbitrary model output.
     * The caller owns that provenance boundary. Invalid/legacy JSON is retained
     * only as a claim and can never manufacture observations. Parse the whole
     * bounded document or none of it; never truncate JSON before parsing it.
     */
    public static ExecutionEvidence fromEnvelope(String encoded) {
        if (encoded == null || encoded.length() > MAX_PARSE_CHARS) return claims(encoded);
        try {
            JsonNode root = JSON.reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(encoded);
            if (root == null || !root.isObject() || !root.path("schemaVersion").isIntegralNumber()
                    || root.path("schemaVersion").asInt() != 1
                    || !"EXECUTION_EVIDENCE".equals(root.path("kind").asText())
                    || !root.path("claims").isTextual() || !root.path("observations").isArray()
                    || !root.path("complete").isBoolean()) return claims(encoded);
            int calls = counter(root, "callCount"), paired = counter(root, "pairedCount");
            int unpairedCalls = counter(root, "unpairedCallCount"), unpairedResults = counter(root, "unpairedResultCount");
            int omitted = counter(root, "omittedObservationCount");
            if (calls < paired || paired < root.path("observations").size()
                    || omitted != paired - root.path("observations").size()) return claims(encoded);
            List<Observation> observations = new ArrayList<>();
            Set<Integer> sequences = new HashSet<>();
            boolean truncated = root.path("truncated").asBoolean(false) || root.path("claimsTruncated").asBoolean(false);
            for (JsonNode item : root.path("observations")) {
                Observation observation = JSON.treeToValue(item, Observation.class);
                if (observation.sequence() < 0 || observation.batch() < 0 || observation.callMessageIndex() < 0
                        || observation.resultMessageIndex() <= observation.callMessageIndex()
                        || CapabilityRegistry.FALLBACK_TOOL.equals(observation.toolName())
                        || !sequences.add(observation.sequence())) return claims(encoded);
                if (observations.size() < MAX_OBSERVATIONS) observations.add(observation);
                else { omitted++; truncated = true; }
                truncated |= observation.truncated();
            }
            String claims = bounded(root.path("claims").asText(), 24_000);
            boolean complete = root.path("complete").asBoolean() && !truncated
                    && !observations.isEmpty() && omitted == 0 && unpairedCalls == 0 && unpairedResults == 0;
            return new ExecutionEvidence(observations, claims,
                    new Completeness(complete, truncated, calls, paired, unpairedCalls, unpairedResults, omitted));
        } catch (Exception invalid) {
            return claims(encoded);
        }
    }

    private static int counter(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
            throw new IllegalArgumentException("invalid evidence count");
        return value.intValue();
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
        int pairedCount = 0;
        boolean truncatedCapture = false;
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
                pairedCount++;
                if (observations.size() >= MAX_OBSERVATIONS) {
                    truncatedCapture = true;
                    messageIndex++;
                    continue;
                }
                ToolResult parsed = ToolResult.fromLegacy(raw);
                boolean truncated = sanitized(raw).length() > MAX_OBSERVATION_CHARS
                        || sanitized(call.request().arguments()).length() > 900;
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
        int unpairedCalls = Math.max(0, callCount - pairedCount);
        boolean truncated = truncatedCapture || observations.stream().anyMatch(Observation::truncated)
                || sanitized(claims).length() > 24_000;
        Completeness completeness = new Completeness(!observations.isEmpty() && !truncated
                && unpairedCalls == 0 && unmatchedResults == 0, truncated,
                callCount, pairedCount, unpairedCalls, unmatchedResults, pairedCount - observations.size());
        return new Capture(new ExecutionEvidence(observations, bounded(claims, 24_000), completeness),
                callCount, pairedCount, unpairedCalls, unmatchedResults);
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
        envelope.put("pairedCount", captured.paired());
        envelope.put("unpairedCallCount", captured.unpairedCalls());
        envelope.put("unpairedResultCount", captured.unpairedResults());
        envelope.put("omittedObservationCount", evidence.completeness().omittedObservationCount());
        envelope.put("complete", !observations.isEmpty() && captured.unpairedCalls() == 0
                && captured.unpairedResults() == 0
                && !Boolean.TRUE.equals(envelope.get("claimsTruncated"))
                && evidence.completeness().complete());
        if (evidence.completeness().truncated()) envelope.put("truncated", true);
        String encoded = serialize(envelope);
        if (encoded.length() <= MAX_ENVELOPE_CHARS) return encoded;
        envelope.put("complete", false);
        envelope.put("truncated", true);
        while (observations.size() > 1 && serialize(envelope).length() > MAX_ENVELOPE_CHARS) {
            observations.remove(observations.size() - 1);
        }
        envelope.put("omittedObservationCount", captured.paired() - observations.size());
        if (serialize(envelope).length() > MAX_ENVELOPE_CHARS) {
            envelope.put("observations", List.of());
            envelope.put("omittedObservationCount", captured.paired());
        }
        // Escaping quotes/control characters can double a bounded claim's JSON
        // size. Shrink the text field, never the encoded JSON document itself.
        while (serialize(envelope).length() > MAX_ENVELOPE_CHARS) {
            String previous = envelope.get("claims").toString();
            if (previous.isEmpty()) throw new IllegalStateException("evidence metadata exceeds bound");
            envelope.put("claims", previous.substring(0, previous.length() / 2));
            envelope.put("claimsTruncated", true);
        }
        return serialize(envelope);
    }

    private record Call(int sequence, int batch, int messageIndex, ToolExecutionRequest request) {}
    private record Capture(ExecutionEvidence evidence, int calls, int paired, int unpairedCalls, int unpairedResults) {}

    /** A complete-looking subset must not hide missing, unpaired, or truncated receipts. */
    public record Completeness(boolean complete, boolean truncated, int callCount, int pairedCount,
                               int unpairedCallCount, int unpairedResultCount, int omittedObservationCount) {
        public Completeness {
            if (callCount < 0 || pairedCount < 0 || unpairedCallCount < 0 || unpairedResultCount < 0
                    || omittedObservationCount < 0) throw new IllegalArgumentException("negative evidence count");
        }
        static Completeness unknown() { return new Completeness(false, false, 0, 0, 0, 0, 0); }
    }

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
        String marker = "\n[execution evidence truncated; sha256=" + sha256(safe) + "]";
        return safe.substring(0, Math.max(0, max - marker.length())) + marker;
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
