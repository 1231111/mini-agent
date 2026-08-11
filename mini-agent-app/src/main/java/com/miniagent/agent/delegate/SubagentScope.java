package com.miniagent.agent.delegate;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.WorkspaceContext;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.todo.TaskTodoContext;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.memory.MemoryStore;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.nio.file.Path;

/**
 * Sub-agent context sandbox: switch session/role/permission/workspace on enter,
 * restore parent context on close.
 */
public final class SubagentScope implements AutoCloseable {

    private final String parentSession;
    private final String parentRole;
    private final PermissionMode parentMode;
    private final boolean parentPlanOk;
    private final ChatModel parentChat;
    private final StreamingChatModel parentStreaming;
    private final Long parentUserId;
    private final Path parentWorkspaceRoot;
    private final String parentTaskOverride;

    private SubagentScope(String parentSession, String parentRole,
                          PermissionMode parentMode, boolean parentPlanOk,
                          ChatModel parentChat, StreamingChatModel parentStreaming,
                          Long parentUserId, Path parentWorkspaceRoot, String parentTaskOverride) {
        this.parentSession = parentSession;
        this.parentRole = parentRole;
        this.parentMode = parentMode;
        this.parentPlanOk = parentPlanOk;
        this.parentChat = parentChat;
        this.parentStreaming = parentStreaming;
        this.parentUserId = parentUserId;
        this.parentWorkspaceRoot = parentWorkspaceRoot;
        this.parentTaskOverride = parentTaskOverride;
    }

    /**
     * Enter sub-agent sandbox.
     * @param inheritAsk if true and parent is ASK, child stays ASK; otherwise DEFAULT (unattended).
     */
    public static SubagentScope enter(String subSessionId, String roleId, boolean inheritAsk) {
        String parentSid = AgentLoop.getCurrentSession();
        String parentRole = RoleContext.getRole();
        PermissionMode parentMode = PermissionContext.mode();
        boolean parentPlanOk = PermissionContext.planApproved();
        ChatModel parentChat = AgentLoop.getCurrentChatModel();
        StreamingChatModel parentStreaming = AgentLoop.getCurrentStreamingModel();
        Long parentUserId = MemoryStore.getCurrentUser();
        Path parentWs = WorkspaceContext.getRootOverride();
        String parentTask = WorkspaceContext.getTaskOverride();

        SubagentScope scope = new SubagentScope(
                parentSid, parentRole, parentMode, parentPlanOk,
                parentChat, parentStreaming, parentUserId, parentWs, parentTask);

        AgentLoop.setCurrentSession(subSessionId);
        TaskTodoContext.set(subSessionId);
        SubagentContext.enter(parentSid, subSessionId);

        if (roleId != null && !roleId.isBlank()) {
            RoleContext.setRole(roleId);
        } else {
            RoleContext.clear();
        }

        PermissionMode childMode = PermissionMode.DEFAULT;
        if (inheritAsk && parentMode == PermissionMode.ASK) {
            childMode = PermissionMode.ASK;
        } else if (parentMode == PermissionMode.ACCEPT_EDITS) {
            childMode = PermissionMode.ACCEPT_EDITS;
        }
        PermissionContext.set(subSessionId, childMode, true);

        Path subWs = BuiltinTools.prepareSubagentWorkspace(subSessionId);
        WorkspaceContext.setRootOverride(subWs);
        WorkspaceContext.setTaskOverride("out");

        return scope;
    }

    @Override
    public void close() {
        SubagentContext.exit();
        if (parentSession != null) {
            AgentLoop.setCurrentSession(parentSession);
            TaskTodoContext.set(parentSession);
        } else {
            AgentLoop.clearCurrentSession();
            TaskTodoContext.clear();
        }
        if (parentRole != null && !parentRole.isBlank()) {
            RoleContext.setRole(parentRole);
        } else {
            RoleContext.clear();
        }
        PermissionContext.set(parentSession, parentMode, parentPlanOk);
        if (parentChat != null || parentStreaming != null) {
            AgentLoop.setCurrentModels(parentChat, parentStreaming);
        } else {
            AgentLoop.clearCurrentModels();
        }
        if (parentUserId != null) {
            MemoryStore.setCurrentUser(parentUserId);
        } else {
            MemoryStore.clearCurrentUser();
        }
        if (parentWorkspaceRoot != null) {
            WorkspaceContext.setRootOverride(parentWorkspaceRoot);
        } else {
            WorkspaceContext.clearRootOverride();
        }
        if (parentTaskOverride != null) {
            WorkspaceContext.setTaskOverride(parentTaskOverride);
        } else {
            WorkspaceContext.clearTaskOverride();
        }
    }
}
