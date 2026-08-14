package com.miniagent.agent.context;

import com.miniagent.agent.intent.IntentType;

/**
 * 按意图决定本轮上下文加载什么。字段名即含义。
 * 具体条数由 {@link ContextLoader} 按配置与 {@link ContextReferenceDecision} 覆盖；
 * 指代场景下历史为相关度精捞上限，而非盲目最近 N 条。
 */
public record ContextIntentPolicy(
        int historyMaxMessages,
        boolean injectTodo,
        boolean injectMidterm,
        boolean injectMemory,
        boolean injectUser,
        int userMaxChars,
        boolean injectSkills,
        boolean suspendActiveTodo,
        boolean resumeSuspendedTodo
) {
    /** 基线策略（历史条数可被 Loader 用配置覆盖） */
    public static ContextIntentPolicy forIntent(IntentType intent) {
        IntentType t = intent == null ? IntentType.NEW_TASK : intent;
        return switch (t) {
            case QUESTION -> new ContextIntentPolicy(
                    0, false, false, false, true, 200, false, false, false);
            case REVIEW -> new ContextIntentPolicy(
                    4, false, false, false, false, 0, false, false, false);
            case CONTINUE_TASK -> new ContextIntentPolicy(
                    -1, true, true, true, true, 0, true, false, true);
            case NEW_TASK -> new ContextIntentPolicy(
                    6, false, true, true, true, 0, true, true, false);
            case IMAGE_GENERATION -> new ContextIntentPolicy(
                    -1, true, true, true, true, 0, true, false, false);
            case HISTORY_REFERENCE -> new ContextIntentPolicy(
                    8, false, true, true, true, 0, false, false, false);
            default -> new ContextIntentPolicy(
                    -1, true, true, true, true, 0, true, false, false);
        };
    }

    public ContextIntentPolicy withHistoryMaxMessages(int n) {
        return new ContextIntentPolicy(n, injectTodo, injectMidterm, injectMemory, injectUser,
                userMaxChars, injectSkills, suspendActiveTodo, resumeSuspendedTodo);
    }

    public ContextIntentPolicy withInjectMidterm(boolean v) {
        return new ContextIntentPolicy(historyMaxMessages, injectTodo, v, injectMemory, injectUser,
                userMaxChars, injectSkills, suspendActiveTodo, resumeSuspendedTodo);
    }

    public ContextIntentPolicy withInjectMemory(boolean v) {
        return new ContextIntentPolicy(historyMaxMessages, injectTodo, injectMidterm, v, injectUser,
                userMaxChars, injectSkills, suspendActiveTodo, resumeSuspendedTodo);
    }
}
