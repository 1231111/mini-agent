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
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Sub-agent context sandbox: switch session/role/permission/workspace on enter,
 * restore parent context on close.
 */
public final class SubagentScope implements AutoCloseable {

    private final String parentSession;
    private final String parentRole;
    private final PermissionMode parentMode;
    private final ChatModel parentChat;
    private final StreamingChatModel parentStreaming;
    private final Long parentUserId;
    private final Path parentWorkspaceRoot;
    private final String parentTaskOverride;

    private SubagentScope(String parentSession, String parentRole, PermissionMode parentMode,
                          ChatModel parentChat, StreamingChatModel parentStreaming,
                          Long parentUserId, Path parentWorkspaceRoot, String parentTaskOverride) {
        this.parentSession = parentSession;
        this.parentRole = parentRole;
        this.parentMode = parentMode;
        this.parentChat = parentChat;
        this.parentStreaming = parentStreaming;
        this.parentUserId = parentUserId;
        this.parentWorkspaceRoot = parentWorkspaceRoot;
        this.parentTaskOverride = parentTaskOverride;
    }

    public static SubagentScope enter(String subSessionId, String roleId, boolean inheritAsk) {
        String parentSid = AgentLoop.getCurrentSession();
        String parentRole = RoleContext.getRole();
        PermissionMode parentMode = PermissionContext.mode();
        ChatModel parentChat = AgentLoop.getCurrentChatModel();
        StreamingChatModel parentStreaming = AgentLoop.getCurrentStreamingModel();
        Long parentUserId = MemoryStore.getCurrentUser();
        Path parentWs = WorkspaceContext.getRootOverride();
        String parentTask = WorkspaceContext.getTaskOverride();

        SubagentScope scope = new SubagentScope(
                parentSid, parentRole, parentMode,
                parentChat, parentStreaming, parentUserId, parentWs, parentTask);

        AgentLoop.setCurrentSession(subSessionId);
        TaskTodoContext.set(subSessionId);
        SubagentContext.enter(parentSid, subSessionId);

        Optional.ofNullable(roleId)
                .filter(StringUtils::isNotBlank)
                .ifPresentOrElse(RoleContext::setRole, RoleContext::clear);

        PermissionMode childMode = PermissionMode.DEFAULT;
        if (inheritAsk && parentMode == PermissionMode.ASK) {
            childMode = PermissionMode.ASK;
        } else if (parentMode == PermissionMode.ACCEPT_EDITS) {
            childMode = PermissionMode.ACCEPT_EDITS;
        }
        PermissionContext.force(subSessionId, childMode, true);

        Path subWs = BuiltinTools.prepareSubagentWorkspace(subSessionId);
        WorkspaceContext.setRootOverride(subWs);
        WorkspaceContext.setTaskOverride("out");
        return scope;
    }

    @Override
    public void close() {
        SubagentContext.exit();
        Optional.ofNullable(parentSession).ifPresentOrElse(sid -> {
            AgentLoop.setCurrentSession(sid);
            TaskTodoContext.set(sid);
            PermissionContext.setSession(sid);
        }, () -> {
            AgentLoop.clearCurrentSession();
            TaskTodoContext.clear();
            PermissionContext.clear();
        });
        Optional.ofNullable(parentRole)
                .filter(StringUtils::isNotBlank)
                .ifPresentOrElse(RoleContext::setRole, RoleContext::clear);
        if (Objects.nonNull(parentChat) || Objects.nonNull(parentStreaming)) {
            AgentLoop.setCurrentModels(parentChat, parentStreaming);
        } else {
            AgentLoop.clearCurrentModels();
        }
        Optional.ofNullable(parentUserId)
                .ifPresentOrElse(MemoryStore::setCurrentUser, MemoryStore::clearCurrentUser);
        Optional.ofNullable(parentWorkspaceRoot)
                .ifPresentOrElse(WorkspaceContext::setRootOverride, WorkspaceContext::clearRootOverride);
        Optional.ofNullable(parentTaskOverride)
                .ifPresentOrElse(WorkspaceContext::setTaskOverride, WorkspaceContext::clearTaskOverride);
    }
}
