package com.miniagent.agent.todo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** A verdict about one immutable acceptance criterion, never permission to change it. */
public record AcceptanceFeedback(int schemaVersion, String criterionId, Verdict verdict, FailureKind kind,
                                 String reason, List<String> missingEvidence, NextAction nextAction,
                                 Protocol protocol) {
    public enum Verdict { PASS, FAIL }
    public enum FailureKind { NONE, MISSING_EVIDENCE, ARTIFACT_DEFECT, JUDGE_UNAVAILABLE, INVALID_RESPONSE }
    public enum NextAction { NONE, COLLECT_EVIDENCE, FIX_ARTIFACT, WAIT_FOR_JUDGE }
    public enum Protocol { STRUCTURED, LEGACY, SYSTEM }
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> FIELDS = Set.of("schemaVersion", "criterionId", "verdict", "kind",
            "reason", "missingEvidence", "nextAction");

    public AcceptanceFeedback {
        if (schemaVersion != 1 || criterionId == null || verdict == null || kind == null
                || reason == null || reason.isBlank() || reason.length() > 2000
                || nextAction == null || protocol == null || missingEvidence == null)
            throw new IllegalArgumentException("Invalid acceptance feedback envelope");
        missingEvidence = List.copyOf(missingEvidence);
        if (missingEvidence.size() > 16 || missingEvidence.stream().anyMatch(s ->
                s == null || s.isBlank() || s.length() > 512))
            throw new IllegalArgumentException("Invalid missing-evidence list");
        if (verdict == Verdict.PASS && (kind != FailureKind.NONE || nextAction != NextAction.NONE
                || !missingEvidence.isEmpty())) throw new IllegalArgumentException("Conflicting PASS feedback");
        if (verdict == Verdict.FAIL && (kind == FailureKind.NONE
                || kind == FailureKind.MISSING_EVIDENCE && (missingEvidence.isEmpty() || nextAction != NextAction.COLLECT_EVIDENCE)
                || kind == FailureKind.ARTIFACT_DEFECT && (nextAction != NextAction.FIX_ARTIFACT || !missingEvidence.isEmpty())
                || (kind == FailureKind.JUDGE_UNAVAILABLE || kind == FailureKind.INVALID_RESPONSE)
                && (nextAction != NextAction.WAIT_FOR_JUDGE || !missingEvidence.isEmpty())))
            throw new IllegalArgumentException("Conflicting failure kind/action");
    }

    public boolean passed() { return verdict == Verdict.PASS; }
    public boolean repairableEvidence() {
        return verdict == Verdict.FAIL && kind == FailureKind.MISSING_EVIDENCE
                && protocol != Protocol.LEGACY;
    }
    public boolean infrastructureFailure() {
        return kind == FailureKind.JUDGE_UNAVAILABLE || kind == FailureKind.INVALID_RESPONSE;
    }
    public String legacyError() { return passed() ? null : "LLM 语义验收 FAIL [" + kind + "]：" + reason; }
    public String fingerprint() {
        List<String> normalized = missingEvidence.stream().map(String::trim).distinct().sorted().toList();
        // Wording changes in the free-form reason must not create fresh repair allowances.
        return digest(criterionId + "\n" + kind + "\n" + String.join("\n", normalized));
    }
    public static String criterionId(String frozenCriterion) {
        return "criterion_" + digest(frozenCriterion == null ? "" : frozenCriterion.trim()).substring(0, 24);
    }
    public static AcceptanceFeedback pass(String criterion, String reason, Protocol protocol) {
        return new AcceptanceFeedback(1, criterionId(criterion), Verdict.PASS, FailureKind.NONE,
                reason, List.of(), NextAction.NONE, protocol);
    }
    public static AcceptanceFeedback missing(String criterion, String reason, List<String> missing) {
        return new AcceptanceFeedback(1, criterionId(criterion), Verdict.FAIL, FailureKind.MISSING_EVIDENCE,
                reason, missing, NextAction.COLLECT_EVIDENCE, Protocol.SYSTEM);
    }
    public static AcceptanceFeedback unavailable(String criterion, String reason) {
        return new AcceptanceFeedback(1, criterionId(criterion), Verdict.FAIL, FailureKind.JUDGE_UNAVAILABLE,
                bounded(reason), List.of(), NextAction.WAIT_FOR_JUDGE, Protocol.SYSTEM);
    }
    public static AcceptanceFeedback invalid(String criterion, String reason) {
        return new AcceptanceFeedback(1, criterionId(criterion), Verdict.FAIL, FailureKind.INVALID_RESPONSE,
                bounded(reason), List.of(), NextAction.WAIT_FOR_JUDGE, Protocol.SYSTEM);
    }

    /** Strict JSON is preferred. The exact historical two-line protocol remains compatible. */
    public static AcceptanceFeedback parse(String response, String frozenCriterion) {
        String text = response == null ? "" : response.trim();
        List<String> lines = text.lines().toList();
        if (!text.startsWith("{") && lines.size() == 2
                && ("PASS".equals(lines.get(0)) || "FAIL".equals(lines.get(0)))
                && !lines.get(1).isBlank() && lines.get(1).length() <= 2000) {
            if ("PASS".equals(lines.get(0))) return pass(frozenCriterion, lines.get(1), Protocol.LEGACY);
            // Legacy prose cannot authorize a read-only evidence repair, even if it says "missing".
            return new AcceptanceFeedback(1, criterionId(frozenCriterion), Verdict.FAIL,
                    FailureKind.ARTIFACT_DEFECT, lines.get(1), List.of(), NextAction.FIX_ARTIFACT, Protocol.LEGACY);
        }
        try {
            JsonNode root = JSON.readTree(text);
            if (root == null || !root.isObject() || root.size() != FIELDS.size())
                throw new IllegalArgumentException("feedback requires the complete versioned schema");
            var names = root.fieldNames();
            while (names.hasNext()) if (!FIELDS.contains(names.next())) throw new IllegalArgumentException("unknown feedback field");
            if (!root.path("schemaVersion").isIntegralNumber() || root.path("schemaVersion").asInt() != 1
                    || !root.path("criterionId").isTextual()
                    || !criterionId(frozenCriterion).equals(root.path("criterionId").asText()))
                throw new IllegalArgumentException("criterion identity/version mismatch");
            for (String field : List.of("verdict", "kind", "reason", "nextAction"))
                if (!root.path(field).isTextual()) throw new IllegalArgumentException("feedback field is not text: " + field);
            if (!root.path("missingEvidence").isArray()) throw new IllegalArgumentException("missingEvidence is not an array");
            List<String> missing = new ArrayList<>();
            for (JsonNode entry : root.path("missingEvidence")) {
                if (!entry.isTextual()) throw new IllegalArgumentException("missingEvidence contains non-text");
                missing.add(entry.textValue());
            }
            return new AcceptanceFeedback(1, root.path("criterionId").asText(),
                    Verdict.valueOf(root.path("verdict").asText()), FailureKind.valueOf(root.path("kind").asText()),
                    root.path("reason").asText(), missing, NextAction.valueOf(root.path("nextAction").asText()), Protocol.STRUCTURED);
        } catch (Exception invalid) {
            return invalid(frozenCriterion, "LLM 语义验收回复格式无效，尚未验证：" + invalid.getMessage());
        }
    }

    private static String bounded(String value) {
        String reason = value == null || value.isBlank() ? "评判结果不可用" : value;
        return reason.length() <= 2000 ? reason : reason.substring(0, 2000);
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
