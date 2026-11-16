package com.miniagent.agent.todo;

import com.miniagent.common.model.EffectiveModelContext;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmJudgeAcceptanceIdentityTest {
    @AfterEach void clear() { EffectiveModelContext.clear(); }

    @Test void identityTracksActualEffectiveModelsAndInvalidatesOnAValidatorRestart() {
        ChatModel platform = mock(ChatModel.class);
        ChatModel user = mock(ChatModel.class);
        LlmJudgeTodoValidator judge = new LlmJudgeTodoValidator();
        ReflectionTestUtils.setField(judge, "chatModel", platform);
        String platformIdentity = judge.acceptanceIdentity();
        assertFalse(platformIdentity.isBlank());
        assertEquals(platformIdentity, judge.acceptanceIdentity());
        EffectiveModelContext.set(user, null, 8192);
        String userIdentity = judge.acceptanceIdentity();
        assertNotEquals(platformIdentity, userIdentity);
        EffectiveModelContext.clear();
        assertEquals(platformIdentity, judge.acceptanceIdentity());
        LlmJudgeTodoValidator restarted = new LlmJudgeTodoValidator();
        ReflectionTestUtils.setField(restarted, "chatModel", platform);
        assertNotEquals(platformIdentity, restarted.acceptanceIdentity());
        verifyNoInteractions(platform, user);
    }

    @Test void noJudgeHasNoReusableIdentity() {
        assertEquals("", new LlmJudgeTodoValidator().acceptanceIdentity());
    }
}
