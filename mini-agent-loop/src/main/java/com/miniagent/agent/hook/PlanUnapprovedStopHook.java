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

    private static final java.util.Set<String> EXEC_HINTS = java.util.Set.of(
            "write_file", "edit_file", "exec_command", "delegate_task",
            "image_generate", "comfyui_txt2img", "comfyui_img2img"
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
        return StopDecision.blockRetry(
                "【Plan 模式·StopHook】计划尚未获用户批准，禁止声称已交付。"
                        + "请完善 todo 计划并等待前端「批准计划并执行」。",
                "plan_not_approved");
    }
}
