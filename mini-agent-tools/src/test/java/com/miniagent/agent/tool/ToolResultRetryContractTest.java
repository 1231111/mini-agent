package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultRetryContractTest {
    @Test void explicitRetryPoliciesSurviveRepeatedLegacyNormalization() {
        for (ToolErrorCode code : new ToolErrorCode[]{ToolErrorCode.TIMEOUT, ToolErrorCode.RATE_LIMITED,
                ToolErrorCode.DEPENDENCY_UNAVAILABLE}) {
            for (boolean retry : new boolean[]{true, false}) {
                ToolResult result = ToolResult.failure(code, "diagnostic", retry);
                for (int round = 0; round < 3; round++) {
                    result = ToolResult.fromLegacy(result.legacyText());
                    assertThat(result.errorCode()).isEqualTo(code);
                    assertThat(result.retriable()).isEqualTo(retry);
                    assertThat(result.data().path("retriable").asBoolean()).isEqualTo(retry);
                }
            }
        }
    }

    @Test void unknownOutcomeAlwaysRemainsUnknownAndNeverBecomesRetriable() {
        for (String value : new String[]{
                "{\"success\":false,\"errorCode\":\"OUTCOME_UNKNOWN\",\"error\":\"remote timeout\",\"retriable\":true}",
                "{\"success\":false,\"error_code\":\"OUTCOME_UNKNOWN\",\"error\":\"remote timeout\"}",
                ToolResult.failure(ToolErrorCode.OUTCOME_UNKNOWN, "remote timeout", true).legacyText()}) {
            ToolResult result = ToolResult.fromLegacy(value);
            assertThat(result.status()).isEqualTo(ToolStatus.UNKNOWN);
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.OUTCOME_UNKNOWN);
            assertThat(result.retriable()).isFalse();
            assertThat(result.data().path("status").asText()).isEqualTo("unknown");
            assertThat(result.data().path("retriable").asBoolean()).isFalse();
        }
    }

    @Test void legacyWithoutBooleanKeepsCompatibilityButTextDoesNotOverrideExplicitCode() {
        assertThat(ToolResult.fromLegacy("{\"success\":false,\"errorCode\":\"TIMEOUT\",\"error\":\"slow\"}").retriable()).isTrue();
        assertThat(ToolResult.fromLegacy("{\"success\":false,\"errorCode\":\"EXECUTION_FAILED\",\"error\":\"contains timeout\",\"retriable\":false}").retriable()).isFalse();
        assertThat(ToolResult.success("timeout 429 error").isSuccess()).isTrue();
    }
}
