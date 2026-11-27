package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.CapabilityRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NodeOutputBinderJudgeEvidenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CURRENT_TODO_ID = 3;
    private static final String CLAIMED_EVIDENCE = "五步信息架构：采集含批次面板与 batch_id 字段；清洗含规则面板与 retention_rate；"
            + "标注含多视角播放器与 action_curves；质检含缺陷看板与 pass_rate；交付含版本列表与 checksum。";

    @Test
    void judgeReceivesExactSubmittedEvidenceInsteadOfAssistantSelfReport() throws Exception {
        TaskNode node = judgeNode();
        String receipt = JSON.writeValueAsString(Map.of(
                "success", true,
                "action", "submit",
                "id", CURRENT_TODO_ID,
                "evidence", CLAIMED_EVIDENCE));
        List<ChatMessage> messages = List.of(
                UserMessage.from("Complete the five-step architecture"),
                ToolExecutionResultMessage.from("submit-1", CapabilityRegistry.FALLBACK_TOOL, receipt));

        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(
                node, "", messages, "I completed the task successfully.", null, "", CURRENT_TODO_ID);

        assertThat(JSON.readTree(bound.evidence()).path("claims").asText()).isEqualTo(CLAIMED_EVIDENCE);
        assertThat(JSON.readTree(bound.evidence()).path("observations").isEmpty()).isTrue();
        assertThat(bound.evidence()).doesNotContain("I completed the task successfully");
    }

    @Test
    void mismatchedTodoIdCannotSubstituteForCurrentJudgeEvidenceOrAssistantClaim() throws Exception {
        TaskNode node = judgeNode();
        String staleReceipt = JSON.writeValueAsString(Map.of(
                "success", true,
                "action", "submit",
                "id", CURRENT_TODO_ID - 1,
                "evidence", CLAIMED_EVIDENCE));
        List<ChatMessage> messages = List.of(
                UserMessage.from("Complete the five-step architecture"),
                ToolExecutionResultMessage.from("submit-old", CapabilityRegistry.FALLBACK_TOOL, staleReceipt));

        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(
                node, "", messages, "I completed the task successfully.", null, "", CURRENT_TODO_ID);

        assertThat(bound.evidence()).isEmpty();
    }

    @Test
    void missingTodoIdMappingCannotFallBackToAssistantSelfReport() {
        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(
                judgeNode(), CLAIMED_EVIDENCE,
                List.of(UserMessage.from("Complete the five-step architecture")),
                "PASS\nAll checks look good.", null, "", 0);

        assertThat(bound.evidence()).isEmpty();
        assertThat(bound.bindings()).isEmpty();
    }

    @Test
    void failedOrNonSubmitTodoReceiptCannotBecomeJudgeEvidence() throws Exception {
        for (String action : List.of("submit", "update")) {
            String receipt = JSON.writeValueAsString(Map.of(
                    "success", false,
                    "action", action,
                    "id", CURRENT_TODO_ID,
                    "evidence", CLAIMED_EVIDENCE));
            NodeOutputBinder.Bound bound = NodeOutputBinder.bind(
                    judgeNode(), "", List.of(ToolExecutionResultMessage.from(
                            "failed-" + action, CapabilityRegistry.FALLBACK_TOOL, receipt)),
                    "PASS", null, "", CURRENT_TODO_ID);

            assertThat(bound.evidence()).isEmpty();
        }
    }

    @Test
    void semanticCheckNodeBindsSubmittedEvidenceNotTheLastToolReceipt() throws Exception {
        Map<String, Object> receipt = new java.util.LinkedHashMap<>();
        receipt.put("success", true);
        receipt.put("action", "submit");
        receipt.put("id", CURRENT_TODO_ID);
        receipt.put("evidence", CLAIMED_EVIDENCE);
        List<ChatMessage> messages = List.of(
                ToolExecutionResultMessage.from("submit-1", CapabilityRegistry.FALLBACK_TOOL,
                        JSON.writeValueAsString(receipt)),
                ToolExecutionResultMessage.from("read-1", "read_file",
                        "[read_file path=C:\\ws\\package.json line_offset=1 char_offset=0 "
                                + "char_limit=4000 lines_returned=14 partial=false]\n1|{\"name\":\"demo\"}"));

        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(
                semanticNode(), "", messages, "", null, "", CURRENT_TODO_ID, CLAIMED_EVIDENCE);

        assertThat(JSON.readTree(bound.evidence()).path("claims").asText()).isEqualTo(CLAIMED_EVIDENCE);
        assertThat(JSON.readTree(bound.evidence()).path("unpairedResultCount").asInt()).isEqualTo(1);
        assertThat(bound.evidence()).doesNotContain("package.json");
    }

    @Test
    void truncatedSubmitReceiptIsSalvagedInsteadOfFallingBackToLastReceipt() throws Exception {
        Map<String, Object> receipt = new java.util.LinkedHashMap<>();
        receipt.put("success", true);
        receipt.put("action", "submit");
        receipt.put("id", CURRENT_TODO_ID);
        receipt.put("evidence", CLAIMED_EVIDENCE);
        receipt.put("items", List.of(Map.of("content", "x".repeat(4000))));
        String full = JSON.writeValueAsString(receipt);
        String truncated = full.substring(0, full.indexOf("\"items\"") + 20);
        assertThat(truncated).doesNotEndWith("}");

        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(
                judgeNode(), "", List.of(ToolExecutionResultMessage.from(
                        "truncated-submit", CapabilityRegistry.FALLBACK_TOOL, truncated)),
                "PASS", null, "", CURRENT_TODO_ID, "");

        assertThat(JSON.readTree(bound.evidence()).path("claims").asText()).isEqualTo(CLAIMED_EVIDENCE);
    }

    private static TaskNode semanticNode() {
        return new TaskNode("research", "确认原单机玩法结构与联机验收要求", "research",
                List.of(), List.of(), List.of(), TaskNodeStatus.RUNNING, 0,
                new DoneWhen("note", "", "", List.of("列出启动、移动和渲染循环的函数名与行号")),
                "", "", 0, "");
    }

    private static TaskNode judgeNode() {
        return new TaskNode("architecture", "梳理五步信息架构", "research",
                List.of(), List.of(), List.of(), TaskNodeStatus.RUNNING, 0,
                DoneWhen.parseWire("llm_judge:五步均须有界面模块、关键字段和主要交互"),
                "", "", 0, "");
    }
}
