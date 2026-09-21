package com.miniagent.agent.hook;

import com.miniagent.common.ErrorCode;
import com.miniagent.common.MessageConstants;
import org.springframework.stereotype.Component;

/**
 * 直跑 Loop 的计划完整性：缺清单催 todo.set，未完成则拦收尾。
 * 规划器持有 TaskGraph 时放行，验收由 StepEvaluator 做。
 */
@Component
public class TodoPlanStopHook implements StopHook {

    static final int MAX_NUDGES = 2;

    @Override
    public String name() {
        return "todo_plan";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public StopDecision evaluate(StopContext context) {
        if (context == null || context.lightQa() || context.plannerOwned()) {
            return StopDecision.proceed();
        }
        if (context.mediaDelivered()) {
            return StopDecision.proceed();
        }
        if (context.requiresStructuredPlan() && !context.hasTodoPlan()
                && context.missingPlanNudges() < MAX_NUDGES) {
            return StopDecision.blockRetry(
                    MessageConstants.STOP_NEED_TODO_PLAN,
                    ErrorCode.TODO_STOP_MISSING_PLAN.getCode());
        }
        if (context.hasTodoPlan() && context.runnableIncomplete()
                && context.incompleteNudges() < MAX_NUDGES) {
            String body = MessageConstants.STOP_INCOMPLETE_TODO;
            if (!context.todoRender().isBlank()) {
                body = body + "\n" + context.todoRender();
            }
            return StopDecision.blockRetry(
                    body, ErrorCode.TODO_STOP_INCOMPLETE.getCode());
        }
        return StopDecision.proceed();
    }
}
