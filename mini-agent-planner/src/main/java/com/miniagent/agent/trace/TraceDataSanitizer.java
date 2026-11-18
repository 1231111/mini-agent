package com.miniagent.agent.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Redacts credentials at the trace persistence boundary while retaining useful context. */
public final class TraceDataSanitizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MASK = "[REDACTED]";
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|"
                    + "password|passwd|secret|authorization|proxy[_-]?authorization|cookie|set-cookie)"
                    + "\\b\\s*[:=]\\s*)([\\\"']?)[^\\s\\\"'&,;]+(?:\\2)?");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(Bearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final Pattern URL_PASSWORD = Pattern.compile("(?i)(https?://[^/:@\\s]+:)[^/@\\s]+(@)");
    private static final Pattern TOKEN_PREFIX = Pattern.compile(
            "\\b(?:sk-[A-Za-z0-9_-]{16,}|pk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9_]{16,}|"
                    + "github_pat_[A-Za-z0-9_]{16,}|xox[baprs]-[A-Za-z0-9-]{16,}|"
                    + "AIza[A-Za-z0-9_-]{30,}|AKIA[A-Z0-9]{16})\\b");

    private TraceDataSanitizer() {}

    /** Sanitize a whole JSON value when possible; otherwise redact common inline secret forms. */
    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) return value;
        try {
            JsonNode parsed = JSON.readTree(value);
            if (parsed != null && (parsed.isObject() || parsed.isArray())) {
                redactTree(parsed);
                return JSON.writeValueAsString(parsed);
            }
        } catch (Exception ignored) {
            // Trace fields also contain prose and partial JSON; fall through to text redaction.
        }
        return sanitizeText(value);
    }

    public static String sanitizeText(String value) {
        if (value == null || value.isEmpty()) return value;
        String sanitized = BEARER.matcher(value).replaceAll("$1" + MASK);
        sanitized = URL_PASSWORD.matcher(sanitized).replaceAll("$1" + MASK + "$2");
        sanitized = SENSITIVE_ASSIGNMENT.matcher(sanitized).replaceAll("$1" + MASK);
        return TOKEN_PREFIX.matcher(sanitized).replaceAll(MASK);
    }

    private static void redactTree(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveName(field.getKey())) {
                    object.put(field.getKey(), MASK);
                } else if (field.getValue().isTextual()) {
                    object.put(field.getKey(), sanitizeText(field.getValue().asText()));
                } else {
                    redactTree(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode item = array.get(i);
                if (item.isTextual()) array.set(i, JSON.getNodeFactory().textNode(sanitizeText(item.asText())));
                else redactTree(item);
            }
        }
    }

    private static boolean isSensitiveName(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.isEmpty() || normalized.matches(".*(tokencount|inputtokens|outputtokens).*")) return false;
        return normalized.equals("key") || normalized.contains("apikey")
                || normalized.contains("accesstoken") || normalized.contains("refreshtoken")
                || normalized.contains("sessiontoken") || normalized.contains("clientsecret")
                || normalized.contains("password") || normalized.equals("passwd")
                || normalized.endsWith("secret") || normalized.contains("authorization")
                || normalized.equals("cookie") || normalized.equals("setcookie")
                || normalized.contains("privatekey") || normalized.contains("signingkey")
                || normalized.equals("credential") || normalized.endsWith("credentials");
    }
}
