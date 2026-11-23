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

        assertThat(bound.evidence()).isEqualTo(CLAIMED_EVIDENCE);
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

    private static TaskNode judgeNode() {
        return new TaskNode("architecture", "梳理五步信息架构", "research",
                List.of(), List.of(), List.of(), TaskNodeStatus.RUNNING, 0,
                DoneWhen.parseWire("llm_judge:五步均须有界面模块、关键字段和主要交互"),
                "", "", 0, "");
    }
}
