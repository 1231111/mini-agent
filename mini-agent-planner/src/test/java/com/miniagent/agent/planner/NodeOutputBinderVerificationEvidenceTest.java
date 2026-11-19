package com.miniagent.agent.planner;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import com.miniagent.agent.tool.CapabilityRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NodeOutputBinderVerificationEvidenceTest {

    @Test
    void retainsRequiredFilesAndDistinctHtmlRangesAfterRepeatedExploration() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ToolExecutionResultMessage.from("first", "read_file",
                "[read_file path=C:/workspace/index.html line_offset=1 char_offset=0 partial=true]\nshader-start"));
        for (int i = 0; i < 20; i++) {
            messages.add(ToolExecutionResultMessage.from("retry-" + i, "read_file",
                    "[read_file path=C:/workspace/index.html line_offset=1 char_offset=0 partial=true]\nshader-start"));
        }
        messages.add(ToolExecutionResultMessage.from("benchmark", "read_file",
                "[read_file path=C:/workspace/BENCHMARK_TASK.md line_offset=1 char_offset=0 partial=false]\n"
                        + "要求：创建 LAN 服务，验证双客户端同步、房间隔离、畸形输入与断线清理。"));
        messages.add(ToolExecutionResultMessage.from("context", "read_file",
                "[read_file path=C:/workspace/GAME_CONTEXT.md line_offset=1 char_offset=0 partial=false]\n"
                        + "游戏由 render/level/input/player/combat/weapon/enemies/HUD/game 模块组成。"));
        for (int offset : List.of(0, 8_000, 16_000, 24_000, 32_000, 40_000)) {
            messages.add(ToolExecutionResultMessage.from("html-" + offset, "read_file",
                    "[read_file path=C:/workspace/index.html line_offset=399 char_offset=" + offset
                            + " partial=true]\n代码范围 " + offset + " 包含 player/combat 状态逻辑。"));
        }

        String evidence = NodeOutputBinder.verificationToolEvidence(messages);

        assertTrue(evidence.contains("BENCHMARK_TASK.md"), evidence);
        assertTrue(evidence.contains("双客户端同步"), evidence);
        assertTrue(evidence.contains("GAME_CONTEXT.md"), evidence);
        assertTrue(evidence.contains("player/combat"), evidence);
        assertTrue(evidence.contains("char_offset=40000"), "应覆盖长文件后段范围：" + evidence);
        assertTrue(evidence.length() <= 12_000, "验收材料必须受上下文预算约束");
    }

    @Test
    void marksLongReceiptCompactionInsteadOfPresentingItAsComplete() {
        String longReceipt = "[read_file path=C:/workspace/GAME_CONTEXT.md line_offset=1 char_offset=0 partial=false]\n"
                + "核心证据。".repeat(800);
        String evidence = NodeOutputBinder.verificationToolEvidence(List.of(
                ToolExecutionResultMessage.from("context", "read_file", longReceipt)));

        assertTrue(evidence.contains("本条中段已截断"), evidence);
    }

    @Test
    void judgeBindingUsesCurrentReadReceiptsAndIgnoresModelClaimsAboutEarlierReads() {
        TaskNode node = new TaskNode("inspect", "确认游戏逻辑与验收要求", "file_read",
                List.of(), List.of(), List.of(), TaskNodeStatus.RUNNING, 50,
                DoneWhen.judge("检查需求与现有游戏逻辑"), "", "", 0, "");
        List<ChatMessage> messages = List.of(
                ToolExecutionResultMessage.from("benchmark", "read_file",
                        "[read_file path=C:/workspace/BENCHMARK_TASK.md line_offset=1 char_offset=0 partial=false]\n"
                                + "7项验收要求"),
                ToolExecutionResultMessage.from("context", "read_file",
                        "[read_file path=C:/workspace/GAME_CONTEXT.md line_offset=1 char_offset=0 partial=false]\n"
                                + "玩家状态与游戏模块摘要"),
                ToolExecutionResultMessage.from("todo", CapabilityRegistry.FALLBACK_TOOL,
                        "提交说明：此前已经读取 index.html 的状态逻辑"));

        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(node, "", messages, "", null, "");

        assertTrue(bound.evidence().contains("BENCHMARK_TASK.md"), bound.evidence());
        assertTrue(bound.evidence().contains("GAME_CONTEXT.md"), bound.evidence());
        assertFalse(bound.evidence().contains("index.html"), "todo prose is not a tool receipt");
    }
}
