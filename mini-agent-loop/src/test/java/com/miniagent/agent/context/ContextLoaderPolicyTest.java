package com.miniagent.agent.context;

import com.miniagent.agent.task.TaskSignals;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ContextLoader} 在基础策略之上按指代做的微调。
 */
class ContextLoaderPolicyTest {

    private final ContextLoader loader = new ContextLoader();

    @Test
    void question无指代不打开长期与中期() {
        ContextLoadPolicy p = loader.resolvePolicy(
                TaskSignals.parse("question"), ContextReferenceDecision.none());
        assertFalse(p.injectMemory());
        assertFalse(p.injectMidterm());
        assertTrue(p.injectUser());
    }

    @Test
    void question有指代打开长期记忆() {
        ContextReferenceDecision ref =
                new ContextReferenceDecision(true, 0.9, List.of("那份报告"), false);
        ContextLoadPolicy p = loader.resolvePolicy(TaskSignals.parse("question"), ref);
        assertTrue(p.injectMemory());
        assertFalse(p.injectMidterm());
    }

    /** 动手轮不受指代影响：本来就已经打开长期记忆。 */
    @Test
    void 动手轮的指代不改变策略() {
        ContextLoadPolicy plain = loader.resolvePolicy(
                TaskSignals.parse("file"), ContextReferenceDecision.none());
        ContextLoadPolicy withRef = loader.resolvePolicy(
                TaskSignals.parse("file"),
                new ContextReferenceDecision(true, 0.9, List.of("那个文件"), false));
        assertEquals(plain, withRef);
    }
}
