package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoalCompilerCorrectionCoverageTest {
    private static final String PLAN = """
            {
              "taskType":"网页生成","objective":"生成页面",
              "constraints":["页面必须使用中文"],"successCriteria":["页面可打开"],"entities":{},
              "intent":{
                "objective":"生成页面","deliverables":[],"inputs":[],
                "constraints":["页面必须使用中文"],"exclusions":[],
                "acceptanceCriteria":["页面可打开"],"needsClarification":false,
                "clarificationQuestion":""
              },
              "nodes":[
                {"id":"page","name":"生成页面","capability":"code","dependsOn":[],
                 "inputs":[],"outputs":["页面"],"priority":50,
                 "doneWhen":{"type":"note_required","criteria":"页面可打开"}}
              ]
            }
            """;

    @Test
    void correctionPromptContainsStableExactCoverageList() {
        PlannerProperties properties = new PlannerProperties();
        properties.setCompilerRetry(0);
        GoalCompiler compiler = new GoalCompiler(properties);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(PLAN)).build());

        compiler.compileWithCorrection(model, "生成页面", TaskPlan.of("生成页面"), """
                计划验证失败，需要修正：
                - 错误类型: GOAL_GRAPH_MISMATCH
                  描述: 约束缺少可执行验收，尚未验证: 页面必须使用中文
                - 错误类型: GOAL_GRAPH_MISMATCH
                  描述: 完成条件缺少可执行验收，尚未验证: 页面不加载外网资源
                - 错误类型: GOAL_GRAPH_MISMATCH
                  描述: 约束缺少可执行验收，尚未验证: 页面必须使用中文
                """);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        String payload = ((UserMessage) request.getValue().messages().getLast()).contents().stream()
                .filter(TextContent.class::isInstance).map(TextContent.class::cast)
                .map(TextContent::text).collect(Collectors.joining("\n"));
        assertTrue(payload.contains("MUST_COVER_REQUIREMENTS"), payload);
        assertTrue(payload.contains("\"id\":\"r1\""), payload);
        assertTrue(payload.contains("\"text\":\"页面必须使用中文\""), payload);
        assertTrue(payload.contains("\"text\":\"页面不加载外网资源\""), payload);
        assertTrue(payload.indexOf("页面必须使用中文") < payload.indexOf("页面不加载外网资源"), payload);
        assertTrue(payload.contains("逐项覆盖规则"), payload);
        // Duplicate diagnostics must not create duplicate requirements.
        assertTrue(payload.split("\\\"text\\\":\\\"页面必须使用中文\\\"", -1).length - 1 == 1, payload);
    }

    @Test
    void correctionPromptSeparatesMissingCoverageFromForbiddenExclusions() {
        String prompt = "计划验证失败，需要修正：\n"
                + "- 描述: 节点动作直接命中了用户排除项: 不生成说明报告\n"
                + "- 描述: 约束缺少可执行验收，尚未验证: 本次功能验收用简洁的六模块卡片页面即可；数据在切换场景时变化即可";
        String correction = GoalCompiler.requirementCoveragePrompt(prompt);

        assertTrue(correction.contains("MUST_COVER_REQUIREMENTS"), correction);
        assertTrue(correction.contains("本次功能验收用简洁的六模块卡片页面即可；数据在切换场景时变化即可"), correction);
        assertTrue(correction.contains("MUST_NOT_HIT_EXCLUSIONS"), correction);
        assertTrue(correction.contains("不生成说明报告"), correction);
        assertTrue(correction.contains("node.name、node.capability"), correction);
    }

    @Test
    void emptyGraphCorrectionReextractsIntentAndKeepsUserExclusionsOutOfNodes() {
        PlannerProperties properties = new PlannerProperties();
        properties.setCompilerRetry(0);
        GoalCompiler compiler = new GoalCompiler(properties);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(PLAN)).build());

        compiler.compileWithCorrection(model,
                "生成六模块卡片页面，数据切换时变化；不要生成说明报告；页面必须使用中文",
                TaskPlan.of("生成六模块卡片页面"),
                "计划验证失败，需要修正：\n"
                        + "- 错误类型: EMPTY_GRAPH\n"
                        + "  描述: 任务图为空或未定义\n");

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(request.capture());
        String payload = ((UserMessage) request.getValue().messages().getLast()).contents().stream()
                .filter(TextContent.class::isInstance).map(TextContent.class::cast)
                .map(TextContent::text).collect(Collectors.joining("\n"));
        assertTrue(payload.contains("MUST_REEXTRACT_INTENT_FROM_USER"), payload);
        assertTrue(payload.contains("EMPTY_GRAPH_RECOVERY"), payload);
        assertTrue(payload.contains("生成六模块卡片页面，数据切换时变化；不要生成说明报告；页面必须使用中文"), payload);
        assertTrue(payload.contains("MUST_NOT_HIT_EXCLUSIONS"), payload);
        assertTrue(payload.contains("只能写入intent.exclusions"), payload);
        assertTrue(payload.contains("每个 constraint/acceptanceCriteria 必须"), payload);
    }
}
