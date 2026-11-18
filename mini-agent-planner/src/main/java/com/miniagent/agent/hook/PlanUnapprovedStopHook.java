package com.miniagent.agent.hook;

import com.miniagent.agent.permission.PermissionMode;
import org.springframework.stereotype.Component;
import java.util.Objects;

/**
 * Plan 未批准时：若本轮已调用过写/执行类工具痕迹（toolsInvoked），禁止「已完成」式收尾。
 * 纯探索问答仍可正常结束。
 */
@Component
public class PlanUnapprovedStopHook implements StopHook {

    static final int MAX_NUDGES = 2;

    private static final java.util.Set<String> EXEC_HINTS = java.util.Set.of(
            "write_file", "edit_file", "exec_command", "delegate_task",
            "image_generate"
    );

    @Override
    public String name() {
        return "plan_unapproved";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public StopDecision evaluate(StopContext context) {
        if (context.permissionMode() != PermissionMode.PLAN || context.planApproved()) {
            return StopDecision.proceed();
        }
        boolean claimedExec = Objects.nonNull(context.toolsInvoked())
                && context.toolsInvoked().stream().anyMatch(EXEC_HINTS::contains);
        if (!claimedExec && !context.writeFileSucceeded() && !context.mediaDelivered()) {
            return StopDecision.proceed();
        }
        if (context.planUnapprovedNudges() >= MAX_NUDGES) {
            return StopDecision.preventWithAnswer(
                    "计划尚未获批准，且本轮出现了写入或执行工具调用。为避免误报完成，已停止；"
                            + "请批准计划后继续，并核验已发生的操作。",
                    "plan_not_approved");
        }
        return StopDecision.blockRetry(
                "【Plan 模式·StopHook】计划尚未获用户批准，禁止声称已交付。"
                        + "请完善 todo 计划并等待前端「批准计划并执行」。",
                "plan_not_approved");
    }
}
