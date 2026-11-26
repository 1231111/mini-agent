package com.miniagent.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The context clamp must never turn a structured tool result into invalid
 * JSON: acceptance evidence is read back from these messages.
 */
class AgentLoopToolResultClampTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void structuredResultStaysParseableAndKeepsAcceptanceEvidence() throws Exception {
        String evidence = "index.html 第6065行 dm() 构建武器视图，第6087行 h.think() 驱动敌人 AI；".repeat(20);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("success", true);
        receipt.put("action", "submit");
        receipt.put("id", 1);
        receipt.put("evidence", evidence);
        receipt.put("todo", "rendered copy ".repeat(400));
        receipt.put("items", List.of(Map.of("id", 1, "content", "x".repeat(8000))));
        String full = JSON.writeValueAsString(receipt);
        assertThat(full.length()).isGreaterThan(4000);

        String clamped = (String) clamp().invoke(null, full, 4000);

        JsonNode parsed = JSON.readTree(clamped);
        assertThat(parsed.path("evidence").asText()).isEqualTo(evidence);
        assertThat(parsed.path("_contextTruncated").asBoolean()).isTrue();
        assertThat(clamped.length()).isLessThan(full.length());
    }

    @Test
    void plainTextResultStillUsesCharacterClamp() throws Exception {
        String plain = "line\n".repeat(2000);

        assertThat(clamp().invoke(null, plain, 4000)).isNull();
    }

    private static Method clamp() throws NoSuchMethodException {
        Method method = AgentLoop.class.getDeclaredMethod(
                "clampStructuredResult", String.class, int.class);
        method.setAccessible(true);
        return method;
    }
}
