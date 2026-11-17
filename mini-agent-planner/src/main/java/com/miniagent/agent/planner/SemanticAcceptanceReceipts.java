package com.miniagent.agent.planner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Durable verdict provenance, never a cache keyed only by a successful node status. */
final class SemanticAcceptanceReceipts {
    static final String EXECUTION_KEY = "semanticAcceptanceReceiptsV1";

    private SemanticAcceptanceReceipts() { }

    record Proof(String nodeId, String contractSha256, String evidenceSha256,
                 String artifactPathSha256, String judgeIdentity) {
        boolean complete() {
            return nodeId != null && !nodeId.isBlank()
                    && sha256Value(contractSha256) && sha256Value(evidenceSha256)
                    && sha256Value(artifactPathSha256) && judgeIdentity != null && !judgeIdentity.isBlank();
        }
    }

    static Proof proof(TaskNode node, String evidence, String artifactPath, String judgeIdentity) {
        if (node == null || evidence == null || artifactPath == null || judgeIdentity == null) return null;
        try {
            // These are exactly the task name and complete contract consumed by the semantic judge.
            String contract = PlannerStateJson.MAPPER.writeValueAsString(
                    Map.of("name", node.name(), "capability", node.capability(), "doneWhen", node.doneWhen()));
            Proof proof = new Proof(node.id(), sha256(contract), sha256(evidence), sha256(artifactPath), judgeIdentity);
            return proof.complete() ? proof : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    static boolean matches(StateSnapshot snapshot, TaskNode node, Proof proof) {
        if (!usableScope(snapshot) || node == null || proof == null || !proof.complete()
                || !node.id().equals(proof.nodeId())
                || !(snapshot.execution().get(EXECUTION_KEY) instanceof Map<?, ?> receipts)
                || !(receipts.get(node.id()) instanceof Map<?, ?> receipt)) return false;
        return "1".equals(receipt.get("schema"))
                && snapshot.sessionId().equals(receipt.get("sessionId"))
                && snapshot.executionId().equals(receipt.get("executionId"))
                && Long.toString(snapshot.planVersion()).equals(receipt.get("planVersion"))
                && proof.nodeId().equals(receipt.get("nodeId"))
                && proof.contractSha256().equals(receipt.get("contractSha256"))
                && proof.evidenceSha256().equals(receipt.get("evidenceSha256"))
                && proof.artifactPathSha256().equals(receipt.get("artifactPathSha256"))
                && proof.judgeIdentity().equals(receipt.get("judgeIdentity"));
    }

    /** Called only to build the same CAS payload as the node terminal verdict. */
    static StateSnapshot withSettledProofs(StateSnapshot expected, StateSnapshot next,
                                           Set<String> settledNodeIds, Map<String, Proof> proofs) {
        if (!usableScope(expected) || !usableScope(next)
                || !expected.sessionId().equals(next.sessionId())
                || !expected.executionId().equals(next.executionId())
                || expected.planVersion() != next.planVersion()) return next;
        Map<String, Object> receipts = new LinkedHashMap<>();
        if (next.execution().get(EXECUTION_KEY) instanceof Map<?, ?> previous) {
            previous.forEach((key, value) -> {
                if (key instanceof String id && next.graph().byId(id) != null && value instanceof Map<?, ?> receipt
                        && next.sessionId().equals(receipt.get("sessionId"))
                        && next.executionId().equals(receipt.get("executionId"))
                        && Long.toString(next.planVersion()).equals(receipt.get("planVersion"))) receipts.put(id, value);
            });
        }
        for (String id : settledNodeIds) {
            receipts.remove(id);
            Proof proof = proofs.get(id);
            TaskNode node = next.graph().byId(id);
            if (node == null || node.status() != TaskNodeStatus.SUCCESS || proof == null
                    || !proof.complete() || !id.equals(proof.nodeId())) continue;
            receipts.put(id, Map.of("schema", "1", "sessionId", next.sessionId(),
                    "executionId", next.executionId(), "planVersion", Long.toString(next.planVersion()),
                    "nodeId", id, "contractSha256", proof.contractSha256(),
                    "evidenceSha256", proof.evidenceSha256(), "artifactPathSha256", proof.artifactPathSha256(),
                    "judgeIdentity", proof.judgeIdentity()));
        }
        Map<String, Object> execution = new LinkedHashMap<>(next.execution());
        if (receipts.isEmpty()) execution.remove(EXECUTION_KEY);
        else execution.put(EXECUTION_KEY, Map.copyOf(receipts));
        return next.withExecution(execution);
    }

    private static boolean usableScope(StateSnapshot snapshot) {
        return snapshot != null && snapshot.sessionId() != null && !snapshot.sessionId().isBlank()
                && snapshot.executionId() != null && !snapshot.executionId().isBlank();
    }

    private static boolean sha256Value(String value) {
        return value != null && value.length() == 64 && value.chars().allMatch(c ->
                c >= '0' && c <= '9' || c >= 'a' && c <= 'f');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
