package com.miniagent.memoryservice.eval;

import com.miniagent.agent.planner.DoneWhen;
import com.miniagent.agent.planner.GoalCompiler;
import com.miniagent.agent.planner.PlannerProperties;
import com.miniagent.agent.planner.ProcedurePromptInput;
import com.miniagent.agent.planner.TaskNode;
import com.miniagent.memory.contract.ProcedureContent;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 离线回放：用客户端同一个 {@link GoalCompiler}，只把可复用 Procedure 换成待比较的那一版 SOP，
 * 为案例里的用户消息编一张任务图。不执行任何工具，只比较计划。
 */
@Component
public class PlanReplayer {
    /** 回放里只有一条 Procedure，id / 版本只用于让规划器能引用它。 */
    private static final long REPLAY_PROCEDURE_ID = 1L;
    private static final int REPLAY_VERSION = 1;

    public record Replay(boolean compiled, String plan) {
    }

    private final PlannerProperties plannerProperties = new PlannerProperties();

    public Replay replay(ChatModel planner, String itemName, ProcedureContent sop,
                         String userMessage) {
        GoalCompiler compiler = new GoalCompiler(plannerProperties);
        ProcedurePromptInput procedure = new ProcedurePromptInput(REPLAY_PROCEDURE_ID,
                REPLAY_VERSION, itemName, sop.description(), sop.preconditions(), sop.steps(),
                sop.successConditions(), null, null, null, 0);
        compiler.attachProcedurePromptPort(query -> List.of(procedure));
        GoalCompiler.CompileResult result = compiler.compile(planner, userMessage, null);
        if (result.degraded() || result.graph().isEmpty()) {
            return new Replay(false, result.fallbackReason());
        }
        return new Replay(true, render(result));
    }

    static String render(GoalCompiler.CompileResult result) {
        String nodes = result.graph().nodes().stream().map(PlanReplayer::render)
                .collect(Collectors.joining("\n"));
        return "目标：" + result.goal().objective() + "\n" + nodes;
    }

    private static String render(TaskNode node) {
        DoneWhen done = node.doneWhen();
        return node.id() + " [" + node.capability() + "] " + node.name()
                + (node.dependsOn().isEmpty() ? "" : " ← " + String.join(",", node.dependsOn()))
                + (node.outputs().isEmpty() ? "" : " → " + String.join(",", node.outputs()))
                + (done == null ? "" : " 验收=" + done.type()
                + (blank(done.path()) ? "" : " " + done.path())
                + (blank(done.criteria()) ? "" : " " + done.criteria()))
                + (node.procedureId() == null ? "" : " 按SOP执行");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
