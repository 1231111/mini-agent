package com.miniagent.agent.todo;

import com.miniagent.common.model.EffectiveModelContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmJudgeTodoValidatorContractTest {

    @AfterEach
    void clearModelContext() {
        EffectiveModelContext.clear();
    }

    @Test
    void judgePromptTreatsFrozenCriteriaAsTheOnlyAcceptanceContract() {
        String frozenDoneWhen = "llm_judge:文件内容说明五步页面链路和主要交互";
        TaskTodoStore.TodoItem item = new TaskTodoStore.TodoItem(7,
                "完成并验收页面文件，说明五步页面链路和主要交互",
                TaskTodoStore.Status.in_progress, "", frozenDoneWhen,
                "页面内容已检查，包含五步链路和主要交互。没有收集文件修改时间。",
                "", List.of(), 0, 0);
        AtomicReference<List<ChatMessage>> submitted = new AtomicReference<>();
        ChatModel model = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from("PASS\n证据满足冻结的页面内容标准"));
        when(model.chat(anyList())).thenAnswer(invocation -> {
            submitted.set(invocation.getArgument(0));
            return response;
        });
        LlmJudgeTodoValidator validator = new LlmJudgeTodoValidator();
        ReflectionTestUtils.setField(validator, "chatModel", model);

        String error = validator.validate(item, item.evidence());

        assertThat(error).isNull();
        String systemPrompt = submitted.get().get(0).toString();
        String untrustedPayload = submitted.get().get(1).toString();
        String frozenCriterion = frozenDoneWhen.substring("llm_judge:".length());
        assertThat(systemPrompt).contains("冻结的验收契约", "criteria/checks", "追加条件",
                "修改时间/mtime", "行数");
        assertThat(untrustedPayload).contains(frozenCriterion, "五步页面链路和主要交互")
                .doesNotContain("llm_judge:");
        assertThat(item.doneWhen()).isEqualTo(frozenDoneWhen);
        assertThat(item.doneWhen()).doesNotContain("mtime", "修改时间", "行数");
    }

    @Test
    void aJudgeFailureReasonCannotRewriteThePersistedAcceptanceContract() {
        String frozenDoneWhen = "llm_judge:报告给出三项结论";
        TaskTodoStore.TodoItem item = new TaskTodoStore.TodoItem(3, "生成报告",
                TaskTodoStore.Status.in_progress, "", frozenDoneWhen,
                "报告包含三项结论。", "", List.of(), 0, 0);
        ChatModel model = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from("FAIL\n缺少文件修改时间"));
        when(model.chat(anyList())).thenReturn(response);
        LlmJudgeTodoValidator validator = new LlmJudgeTodoValidator();
        ReflectionTestUtils.setField(validator, "chatModel", model);

        String error = validator.validate(item, item.evidence());

        assertThat(error).contains("缺少文件修改时间");
        assertThat(item.doneWhen()).isEqualTo(frozenDoneWhen);
    }
}
