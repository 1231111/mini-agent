package com.miniagent.agent.context;

import com.miniagent.agent.task.TaskSignals;
import com.miniagent.memory.model.MemoryReadPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本轮上下文加载策略：判据是命中的事实信号，不再是一个类别。
 *
 * <p>这些用例守住的是「一处信号不足以整体降级」——旧实现里一个类别同时决定
 * 历史条数与记忆槽，选错就同时丢两样；现在只有 {@code lightTurn} 会削减记忆槽，
 * 而 {@code lightTurn} 要求问答且无任何动手信号。</p>
 */
class ContextLoadPolicyTest {

    @Test
    void 纯问答轮只带用户偏好摘要() {
        MemoryReadPolicy p = ContextLoadPolicy
                .forSignals(TaskSignals.parse("question"))
                .memoryPolicy();
        assertFalse(p.working());
        assertFalse(p.longTerm());
        assertFalse(p.midterm());
        assertTrue(p.user());
        assertEquals(200, p.userMaxChars());
    }

    @Test
    void 动手轮打开全部检索槽并挂起旧计划() {
        ContextLoadPolicy p = ContextLoadPolicy.forSignals(TaskSignals.parse("file"));
        MemoryReadPolicy m = p.memoryPolicy();
        assertTrue(m.working());
        assertTrue(m.longTerm());
        assertTrue(m.user());
        assertFalse(m.midterm());
        assertTrue(p.suspendActiveTodo());
        assertFalse(p.resumeSuspendedTodo());
    }

    @Test
    void 续任务轮恢复挂起清单() {
        ContextLoadPolicy p = ContextLoadPolicy.forSignals(TaskSignals.parse("continue"));
        assertTrue(p.resumeSuspendedTodo());
        assertFalse(p.suspendActiveTodo());
        assertTrue(p.memoryPolicy().working());
    }

    /** 问答叠加动手信号就不再是轻问答，按动手轮加载。 */
    @Test
    void 问答叠加动手信号不再是轻问答() {
        TaskSignals s = TaskSignals.parse("question,file");
        assertFalse(s.lightTurn());
        assertTrue(ContextLoadPolicy.forSignals(s).memoryPolicy().longTerm());
    }

    /**
     * 历史兜底条数不能是 0。
     *
     * <p>0 会让 {@code selectHistory} 直接返回空，模型看不到上一轮在做什么，
     * 跨轮追问必然答错。这条下限必须由策略本身守住，不能只靠配置。</p>
     */
    @Test
    void 历史兜底条数不为零() {
        for (String s : new String[]{"none", "question", "continue", "file", "web,file"}) {
            assertNotEquals(0, ContextLoadPolicy.forSignals(TaskSignals.parse(s))
                    .historyMaxMessages(), s);
        }
    }

    /** 空信号按动手轮处理：宁可信其要动手，也不要把上下文砍到 0。 */
    @Test
    void 空信号不降级为轻问答() {
        assertFalse(TaskSignals.NONE.lightTurn());
        assertTrue(ContextLoadPolicy.forSignals(TaskSignals.NONE).memoryPolicy().longTerm());
    }
}
