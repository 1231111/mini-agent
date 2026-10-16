package com.miniagent.agent.planner;

import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「要不要进规划器」两档判据的对照。
 *
 * <p>同一句话、同一个 plan，只差 {@code modelDecides} 一个入参，
 * 用来钉死模型判档相对词表判档**只放宽不收紧**这一条不变量。</p>
 */
class PlanningLoopGateTest {

    private static TaskPlan plan(String goal, String signals, boolean structured) {
        return TaskPlan.of(goal, structured, TaskSignals.parse(signals));
    }

    /**
     * 核心一条：模型判档下，命中「要动手」就进 —— 哪怕词表说这轮不需要结构化计划。
     *
     * <p>反例句必须**不含 URL、不含文件名**，否则
     * {@link DecompositionPolicy#hasGraphSignal} 这条加宽会在两档都命中，
     * 盖住两档真正的差异。纯生图正是这样的句子：要动手，但词表认为不必结构化计划。</p>
     */
    @Test
    void modelModeAdmitsActionBearingTurnThatLexiconWouldSkip() {
        String text = "画一只猫";
        TaskPlan p = plan(text, "pureImage", false);
        assertFalse(PlanningLoop.wantsPlanning(p, text, false));
        assertTrue(PlanningLoop.wantsPlanning(p, text, true));
    }

    /** 反过来：词表判为 true 的轮，模型判档同样进 —— 放宽不收紧。 */
    @Test
    void lexiconPlannedTurnIsAlsoAdmittedInModelMode() {
        TaskPlan p = plan("帮我查文档并整理成 api.md", "web,file", true);
        assertTrue(PlanningLoop.wantsPlanning(p, "帮我查文档并整理成 api.md", false));
        assertTrue(PlanningLoop.wantsPlanning(p, "帮我查文档并整理成 api.md", true));
    }

    /** 纯问答轮两档都不进 —— 防止上一轮残留的旧图劫持本轮。 */
    @Test
    void lightTurnIsRejectedInBothModes() {
        TaskPlan p = plan("你好", "question", false);
        assertFalse(PlanningLoop.wantsPlanning(p, "你好", true));
        assertFalse(PlanningLoop.wantsPlanning(p, "你好", false));
    }

    /** 入口净化留下的空信号：系统控制语不构成新目标，两档都不该拉起规划。 */
    @Test
    void purifiedControlMessageIsRejectedInBothModes() {
        String text = "【系统】已批准「exec_command」，请继续执行。";
        TaskPlan p = plan(text, "none", false);
        assertFalse(PlanningLoop.wantsPlanning(p, text, true));
        assertFalse(PlanningLoop.wantsPlanning(p, text, false));
    }

    /** URL / 文件名这条加宽两档都保留：文本里点名了 URL，本身就编得成可调度图。 */
    @Test
    void graphSignalWideningKeptInBothModes() {
        String url = "看看 https://example.com 说了什么";
        TaskPlan pUrl = plan(url, "none", false);
        assertTrue(PlanningLoop.wantsPlanning(pUrl, url, true));
        assertTrue(PlanningLoop.wantsPlanning(pUrl, url, false));
    }

    /**
     * 同上，换成文件名：它既是词表判的入口，也是模型判的入口 —— 两档都进。
     * 换句话说「写一个 hello.txt」这类句子在改动前后都进规划器，
     * 两档真正的差异只体现在**既没 URL 也没文件名**的动手句上。
     */
    @Test
    void fileNameWideningKeptInBothModes() {
        String text = "写一个 hello.txt";
        TaskPlan p = plan(text, "file,simpleFile", false);
        assertTrue(PlanningLoop.wantsPlanning(p, text, true));
        assertTrue(PlanningLoop.wantsPlanning(p, text, false));
    }

    @Test
    void nullPlanIsRejected() {
        assertFalse(PlanningLoop.wantsPlanning(null, "写一个 hello.txt", true));
        assertFalse(PlanningLoop.wantsPlanning(null, "写一个 hello.txt", false));
    }

    @Test
    void outerRoundLimitIncludesExactBoundary() {
        assertTrue(PlanningLoop.outerRoundsExhausted(24, 24));
        assertTrue(PlanningLoop.outerRoundsExhausted(25, 24));
        assertFalse(PlanningLoop.outerRoundsExhausted(23, 24));
        assertFalse(PlanningLoop.outerRoundsExhausted(1, 0));
    }
}
