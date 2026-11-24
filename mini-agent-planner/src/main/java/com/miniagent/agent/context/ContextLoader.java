package com.miniagent.agent.context;

import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionPolicy;
import com.miniagent.agent.scope.TaskBoundary;
import com.miniagent.agent.scope.TaskScopeRegistry;
import com.miniagent.common.RunStatus;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.trace.TraceRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 每轮应用结构化任务边界、按作用域加载任务计划，并把 system prompt 交给 {@link ContextBuilder}。
 */
@Component
@Slf4j
public class ContextLoader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TRACE_MEMORY_CONTEXT_MAX_CHARS = 3200;

    @Autowired
    private TaskTodoStore taskTodoStore;
    @Autowired
    private TaskScopeRegistry taskScopeRegistry;
    @Autowired(required = false)
    private TraceRecorder traceRecorder;
    @Autowired
    private ContextHistorySelector historySelector;
    @Autowired
    private ContextBuilder contextBuilder;

    /**
     * 指代场景下历史的相关度精捞上限。
     *
     * <p>这里必须是个正数。给 0 会把整段历史清空，模型连上一轮在谈什么都不知道，
     * 跨轮追问必然答错。历史没带够只会让回答变浅，带成 0 是直接答不出来。</p>
     */
    @Value("${agent.context.history.question-with-ref:6}")
    private int historyQuestionWithRef;
    @Value("${agent.context.history.ref-scan-max:48}")
    private int historyRefScanMax;
    @Value("${agent.context.history.ref-pronoun-anchor:4}")
    private int historyRefPronounAnchor;

    /**
     * @param hasMedia 本轮用户消息是否带图片/音视频。只影响 system prompt 里的媒体相关说明，
     *                 不影响历史条数、todo 挂起与工具清单。
     */
    static long parseTaskId(String scopeKey, String sessionId) {
        if (scopeKey == null || sessionId == null || scopeKey.equals(sessionId)) {
            return 0L;
        }
        String prefix = sessionId + "#";
        if (!scopeKey.startsWith(prefix)) {
            throw new IllegalArgumentException("Task scope key does not belong to session");
        }
        try {
            long taskId = Long.parseLong(scopeKey.substring(prefix.length()));
            if (taskId <= 0) {
                throw new IllegalArgumentException("Task scope id must be positive");
            }
            return taskId;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid task scope key", e);
        }
    }

    /** Current identity for routing traces emitted before this turn's context is loaded. */
    public long currentTaskId(String sessionId) {
        if (sessionId == null) return 0L;
        var scope = taskScopeRegistry.current(sessionId);
        return scope == null ? 0L : parseTaskId(scope.scopeKey(), sessionId);
    }

    public LoadedContext load(String sessionId, String query, boolean hasMedia,
                              List<ChatMessage> memMsgs) {
        return load(sessionId, query, hasMedia, memMsgs, null);
    }

    /** Resolve an explicit semantic task boundary before building task-scoped context. */
    public LoadedContext load(String sessionId, String query, boolean hasMedia,
                              List<ChatMessage> memMsgs, TaskBoundary requestedBoundary) {
        ContextReferenceDecision ref = ContextReference.detect(query);
        ContextLoadPolicy policy = resolvePolicy(ref);

        // 策略路由器可在本轮开始前明确给出 NEW；未完成旧任务仍保留在 paused scope。
        // 若路由没有要求切换，已全部终结的清单仍按客观状态自动开启新 scope。
        boolean closed = false;
        TaskBoundary boundary = TaskBoundary.SAME;
        String boundaryReason = "same-task";
        if (Objects.nonNull(sessionId) && requestedBoundary == TaskBoundary.NEW) {
            boundary = TaskBoundary.NEW;
            boundaryReason = "semantic-new-task";
        } else if (Objects.nonNull(sessionId) && requestedBoundary == TaskBoundary.RESUME) {
            boundary = TaskBoundary.RESUME;
            boundaryReason = "semantic-resume-task";
        } else if (Objects.nonNull(sessionId)) {
            boolean waitingHuman = taskTodoStore.hasAwaitingConfirm(sessionId);
            if (waitingHuman) {
                log.info("ContextLoader: 等待人工确认，保持当前任务 session={}", sessionId);
            } else if (taskTodoStore.hasOnlyTerminal(sessionId)) {
                // 清单全终态 = 这个任务做完了，下一轮开新作用域。
                // 注意这里**不清空**旧清单：它留在自己的作用域键上，
                // 用户之后用 todo action=resume 回来还能看见做过什么。
                // 清空等于「换任务即丢历史」，和 resume 直接冲突。
                closed = true;
                boundary = TaskBoundary.NEW;
                boundaryReason = "prev-plan-terminal";
            }
        }
        String scopeKey = Objects.isNull(sessionId)
                ? null
                : taskScopeRegistry.resolve(sessionId, boundary, boundaryReason).scopeKey();
        long taskId = scopeKey == null ? 0L : parseTaskId(scopeKey, sessionId);

        List<ChatMessage> history = selectHistory(sessionId, memMsgs, query, policy, ref);
        // 全自动只在「编辑模式 + 自动确认」同时成立时为真。这里读的是运行作用域里的实时值，
        // 用户在任务跑到一半时打开两个开关，下一步拼出来的提示词就已经是放行口径。
        boolean fullAuto = PermissionPolicy.isFullAuto(
                PermissionContext.mode(), PermissionContext.confirmPolicy());
        ContextBuildResult builtContext = contextBuilder.buildDetailed(new ContextBuildContext(
                sessionId, query, policy, Set.of(), ref, hasMedia, fullAuto));
        String systemPrompt = builtContext.systemPrompt();

        Map<String, Object> info = new HashMap<>();
        info.put("hasMedia", hasMedia);
        info.put("hasReference", ref.hasReference());
        info.put("referenceNegated", ref.isNegated());
        info.put("referenceConfidence", ref.confidence());
        info.put("referenceCandidates", ref.candidates());
        info.put("historyMessages", history.size());
        info.put("historyMax", policy.historyMaxMessages());
        info.put("injectTodo", policy.injectTodo());
        info.put("fullAuto", fullAuto);
        info.put("injectMemory", policy.injectMemory());
        info.put("injectUser", policy.injectUser());
        String memoryContext = builtContext.textFor(ContextSlot.MEMORY);
        info.put("memoryContext", truncate(memoryContext, TRACE_MEMORY_CONTEXT_MAX_CHARS));
        info.put("memoryContextTruncated", memoryContext.length() > TRACE_MEMORY_CONTEXT_MAX_CHARS);
        info.put("injectSkills", policy.injectSkills());
        info.put("todoClosed", closed);
        info.put("scopeKey", scopeKey);
        info.put("taskBoundary", boundary.name());
        info.put("boundaryReason", boundaryReason);
        info.put("toolGuidanceCount", 0);

        if (Objects.nonNull(traceRecorder) && Objects.nonNull(sessionId)) {
            try {
                traceRecorder.recordNode(sessionId, 0, "CONTEXT_LOAD",
                        JSON.writeValueAsString(info), RunStatus.SUCCESS.name(), 0);
            } catch (Exception e) {
                log.debug("CONTEXT_LOAD trace skip: {}", e.getMessage());
            }
        }

        log.info("ContextLoader: ref={}/neg={}/conf={}, "
                        + "history={}/{}, todoInject={}, closed={}, "
                        + "scope={}/{}, tools={}",
                ref.hasReference(), ref.isNegated(), ref.confidence(),
                history.size(), policy.historyMaxMessages(),
                policy.injectTodo(), closed,
                scopeKey, boundaryReason, 0);

        return new LoadedContext(systemPrompt, history, policy, scopeKey, taskId, info);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text == null ? "" : text;
        return text.substring(0, maxChars) + "…";
    }

    /**
     * 基础策略 + 指代场景下的历史条数覆盖。
     *
     * <p>本方法不再接收「有没有挂起清单」这类入参，也不再返回不同的清单动作 ——
     * 清单归属不在这一层。见 {@link ContextLoadPolicy} 的类注释。</p>
     */
    ContextLoadPolicy resolvePolicy(ContextReferenceDecision ref) {
        ContextLoadPolicy base = ContextLoadPolicy.defaults();
        boolean loadRef = ref != null && ref.shouldLoadPriorHistory();
        if (!loadRef) {
            return base;
        }
        return base.withHistoryMaxMessages(historyQuestionWithRef).withInjectMemory(true);
    }

    List<ChatMessage> selectHistory(String sessionId, List<ChatMessage> all, String query,
                                    ContextLoadPolicy policy,
                                    ContextReferenceDecision ref) {
        int maxKeep = policy.historyMaxMessages();
        if (Objects.isNull(all) || all.isEmpty() || maxKeep == 0) {
            return List.of();
        }
        if (ref != null && ref.shouldLoadPriorHistory() && maxKeep > 0) {
            return historySelector.selectRelevant(
                    sessionId, all, query, ref, maxKeep, historyRefScanMax,
                    historyRefPronounAnchor);
        }
        // 任务级隔离现在由作用域键负责（规划图/压缩摘要按 scopeKey 取），
        // 这里不再按「任务边界」截断历史。原因：跨轮追问必须看得见上一轮在谈什么，
        // 把历史砍掉是拿「答不出来」换「不污染」，方向错了 ——
        // 硬污染（用户的新问题被旧规划图接管）由 Planner 侧的接管判据解决。
        if (maxKeep < 0 || maxKeep >= all.size()) {
            return new ArrayList<>(all);
        }
        int start = all.size() - maxKeep;
        while (start < all.size() && !(all.get(start) instanceof UserMessage)) {
            start++;
        }
        if (start >= all.size()) {
            start = all.size() - maxKeep;
        }
        return new ArrayList<>(all.subList(start, all.size()));
    }
}
