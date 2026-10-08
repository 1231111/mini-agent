package com.miniagent.agent.core;

import com.miniagent.agent.delegate.RoleContext;
import com.miniagent.agent.delegate.SubagentContext;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.todo.TaskTodoContext;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.common.model.EffectiveModelContext;
import com.miniagent.memory.MemoryStore;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次运行的身份快照。并行工具 / 子代理拷贝这一份，再 {@link #bind()} 到工作线程。
 * 工具内部仍可读 ThreadLocal；新增身份字段加在这里，禁止再开 ThreadLocal。
 */
public record RunScope(
        String sessionId,
        MemoryStore.OwnerContext owner,
        LoopTurnPolicy turnPolicy,
        PermissionMode permissionMode,
        boolean planApproved,
        boolean permissionForced,
        boolean subagent,
        String parentSessionId,
        String role,
        String taskName,
        String writeTask,
        Path workspaceRoot,
        ExecutionTurnContext.Scope execution,
        ChatModel chatModel,
        StreamingChatModel streamingModel,
        int contextWindowTokens
) {
    @FunctionalInterface
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }

    public RunScope {
        owner = owner == null ? new MemoryStore.OwnerContext(null, null) : owner;
        turnPolicy = turnPolicy == null ? LoopTurnPolicy.NONE : turnPolicy;
        permissionMode = permissionMode == null ? PermissionMode.DEFAULT : permissionMode;
        parentSessionId = parentSessionId == null ? "" : parentSessionId;
        role = role == null ? "" : role;
        taskName = taskName == null || taskName.isBlank() ? "default" : taskName;
    }

    public static RunScope capture() {
        return new RunScope(
                AgentLoop.getCurrentSession(),
                MemoryStore.captureOwnerContext(),
                LoopTurnContext.current(),
                PermissionContext.mode(),
                PermissionContext.planApproved(),
                PermissionContext.isForced(),
                SubagentContext.isActive(),
                SubagentContext.parentSessionId(),
                RoleContext.getRole(),
                BuiltinTools.currentTaskName(),
                WorkspaceContext.getTaskOverride(),
                WorkspaceContext.getRootOverride(),
                ExecutionTurnContext.current(),
                AgentLoop.getCurrentChatModel(),
                AgentLoop.getCurrentStreamingModel(),
                EffectiveModelContext.currentContextWindow());
    }

    public RunScope withSession(String sid) {
        return new RunScope(
                sid, owner, turnPolicy, permissionMode, planApproved, permissionForced,
                subagent, parentSessionId, role, taskName, writeTask, workspaceRoot,
                execution, chatModel, streamingModel, contextWindowTokens);
    }

    /**
     * 派生子代理作用域。
     *
     * <p><b>planApproved 必须继承，不能置真。</b>此前这里硬编码 {@code planApproved=true}，
     * 于是"计划尚未获用户批准"的父会话可以派生出一个立刻能写文件的子代理 ——
     * 委派成了绕过 Plan 闸门的通道。现在子代理继承父的批准状态：
     * 父没批准，子也没批准。</p>
     *
     * <p>{@code permissionForced=true} 保留（子会话在权限存储里没有自己的记录，
     * 必须显式安装模式），但模式由调用方按"只降不升"算好传入。</p>
     */
    public RunScope asSubagent(
            String subSessionId, String roleId, PermissionMode childMode, Path subWorkspace) {
        String parent = sessionId == null ? "" : sessionId;
        PermissionMode mode = childMode == null ? permissionMode : childMode;
        return new RunScope(
                subSessionId, owner, LoopTurnPolicy.NONE,
                mode,
                planApproved, true, true, parent,
                roleId == null ? "" : roleId, taskName, "out", subWorkspace,
                null, chatModel, streamingModel, contextWindowTokens);
    }

    /**
     * 安装到当前线程。关闭时精确恢复进入前的快照（支持嵌套）。
     */
    public Binding bind() {
        RunScope previous = capture();
        MemoryStore.OwnerContextScope owners = MemoryStore.bindOwnerContext(owner);
        applyLocals();
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                previous.applyLocals();
                owners.close();
            }
        };
    }

    void applyLocals() {
        if (sessionId != null && !sessionId.isBlank()) {
            AgentLoop.setCurrentSession(sessionId);
            TaskTodoContext.set(sessionId);
        } else {
            AgentLoop.clearCurrentSession();
            TaskTodoContext.clear();
        }
        if (permissionForced) {
            PermissionContext.force(sessionId, permissionMode, planApproved);
        } else if (sessionId != null && !sessionId.isBlank()) {
            PermissionContext.setSession(sessionId);
        } else {
            PermissionContext.clear();
        }
        LoopTurnContext.set(turnPolicy);
        if (subagent) {
            SubagentContext.enter(parentSessionId, sessionId);
        } else {
            SubagentContext.exit();
        }
        if (role.isBlank()) {
            RoleContext.clear();
        } else {
            RoleContext.setRole(role);
        }
        BuiltinTools.restoreTaskName(taskName);
        if (writeTask != null) {
            WorkspaceContext.setTaskOverride(writeTask);
        } else {
            WorkspaceContext.clearTaskOverride();
        }
        if (workspaceRoot != null) {
            WorkspaceContext.setRootOverride(workspaceRoot);
        } else {
            WorkspaceContext.clearRootOverride();
        }
        ExecutionTurnContext.set(execution);
        if (chatModel != null || streamingModel != null) {
            // 窗口一并恢复：并行工具/子代理线程上的辅助 LLM 调用与压缩阈值都要用它，
            // 只恢复模型会让"该不该压缩"在这条路径上退回全局常量。
            EffectiveModelContext.set(chatModel, streamingModel, contextWindowTokens);
        } else {
            AgentLoop.clearCurrentModels();
        }
    }
}
