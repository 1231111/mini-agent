package com.miniagent.agent.context;

import java.util.Set;

/**
 * 一轮 system prompt 组装入参。贡献者只读此对象。
 *
 * @param hasMedia 本轮用户消息是否带图片/音视频。只用于决定媒体相关的说明要不要拼进去，
 *                 <b>不参与</b>工具清单、历史条数、执行路径的判定 ——
 *                 那些资源一旦按它收窄就是不可恢复的。
 * @param fullAuto 会话是否处于全自动（{@code ACCEPT_EDITS} + {@code ConfirmPolicy.AUTO}）。
 *                 只影响提示词措辞：全自动下不再要求模型向用户讨确认，
 *                 否则工具闸门已经放开、模型却还在问，用户看到的就是"明明开了自动还在弹框"。
 *                 与 {@link com.miniagent.agent.permission.PermissionPolicy#isFullAuto} 同源，
 *                 不要在这里另立判定。
 */
public record ContextBuildContext(
        String sessionId,
        String query,
        ContextLoadPolicy policy,
        Set<String> toolNames,
        ContextReferenceDecision reference,
        boolean hasMedia,
        boolean fullAuto
) {
}
