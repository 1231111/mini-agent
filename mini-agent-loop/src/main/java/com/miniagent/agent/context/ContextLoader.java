package com.miniagent.agent.context;

import com.miniagent.agent.scope.TaskBoundary;
import com.miniagent.agent.scope.TaskScopeRegistry;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
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
 * 每轮按本轮观察到的事实裁历史、释放上一任务的活动计划，
 * 并把 system prompt 交给 {@link ContextBuilder}。
 */
@Component
@Slf4j
public class ContextLoader {

    private static final ObjectMapper JSON = new ObjectMapper();

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
     * 问答轮（无动手信号）保留的历史条数。
     *
     * <p>这里必须是个正数。给 0 会把整段历史清空，模型连上一轮在谈什么都不知道，
     * 跨轮追问必然答错。历史没带够只会让回答变浅，带成 0 是直接答不出来。</p>
     */
    @Value("${agent.context.history.question:6}")
    private int historyQuestion;
    @Value("${agent.context.history.question-with-ref:6}")
    private int historyQuestionWithRef;
    @Value("${agent.context.history.ref-scan-max:48}")
    private int historyRefScanMax;
    @Value("${agent.context.history.ref-pronoun-anchor:4}")
    private int historyRefPronounAnchor;

    /**
     * @param hasMedia 本轮用户消息是否带图片/音视频。只影响「点评轮」那段附加提示词，
     *                 不影响历史条数、todo 挂起与工具清单。
     */
    public LoadedContext load(String sessionId, String query, boolean hasMedia,
                              TaskPlan taskPlan, List<ChatMessage> memMsgs) {
        TaskSignals signals = Objects.isNull(taskPlan) ? TaskSignals.NONE : taskPlan.signals();
        ContextReferenceDecision ref = ContextReference.detect(query);
        ContextLoadPolicy policy = resolvePolicy(signals, ref);

        boolean suspended = false;
        boolean resumed = false;
        TaskBoundary boundary = TaskBoundary.SAME;
        String boundaryReason = "same-task";
        if (Objects.nonNull(sessionId)) {
            // 轻问答不碰 todo；正在等人工确认时也不释放，否则用户答复会被自己的挂起吃掉
            boolean waitingHuman = taskTodoStore.hasAwaitingConfirm(sessionId)
                    && !signals.lightTurn();
            if (waitingHuman) {
                log.info("ContextLoader: 跳过 suspend，保留 awaiting_confirm session={}",
                        sessionId);
            } else {
                if (policy.resumeSuspendedTodo()) {
                    resumed = taskTodoStore.resumeSuspended(sessionId);
                }
                if (policy.suspendActiveTodo()) {
                    suspended = taskTodoStore.suspendActive(sessionId);
                }
            }
            if (resumed) {
                // 用户明确说「继续」→ 回到被挂起的那个任务，图按它自己的键取得到
                boundary = TaskBoundary.RESUME;
                boundaryReason = "todo-resumed";
            } else if (suspended) {
                // 上一任务的工作集被释放 → 开新任务。挂起的旧任务留在自己的键上，
                // 用户说「继续」还能回来；归档的旧任务已终态，没有可回的东西。
                boundary = TaskBoundary.NEW;
                boundaryReason = taskTodoStore.hasSuspended(sessionId)
                        ? "prev-plan-suspended" : "prev-plan-archived";
            }
        }
        String scopeKey = Objects.isNull(sessionId)
                ? null
                : taskScopeRegistry.resolve(sessionId, boundary, boundaryReason).scopeKey();

        List<ChatMessage> history = selectHistory(sessionId, memMsgs, query, policy, ref);
        String systemPrompt = contextBuilder.build(new ContextBuildContext(
                sessionId, query, policy, Set.of(), ref, taskPlan, hasMedia));

        Map<String, Object> info = new HashMap<>();
        info.put("signals", signals.describe());
        info.put("lightTurn", signals.lightTurn());
        info.put("hasMedia", hasMedia);
        info.put("hasReference", ref.hasReference());
        info.put("referenceNegated", ref.isNegated());
        info.put("referenceConfidence", ref.confidence());
        info.put("referenceCandidates", ref.candidates());
        info.put("historyMessages", history.size());
        info.put("historyMax", policy.historyMaxMessages());
        info.put("injectTodo", policy.injectTodo());
        info.put("injectMidterm", policy.injectMidterm());
        info.put("injectMemory", policy.injectMemory());
        info.put("injectUser", policy.injectUser());
        info.put("injectSkills", policy.injectSkills());
        info.put("todoSuspended", suspended);
        info.put("todoResumed", resumed);
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

        log.info("ContextLoader: signals=[{}], lightTurn={}, ref={}/neg={}/conf={}, "
                        + "history={}/{}, todoInject={}, suspended={}, resumed={}, "
                        + "scope={}/{}, tools={}",
                signals.describe(), signals.lightTurn(),
                ref.hasReference(), ref.isNegated(), ref.confidence(),
                history.size(), policy.historyMaxMessages(),
                policy.injectTodo(), suspended, resumed,
                scopeKey, boundaryReason, 0);

        return new LoadedContext(systemPrompt, history, policy, scopeKey, info);
    }

    ContextLoadPolicy resolvePolicy(TaskSignals signals, ContextReferenceDecision ref) {
        TaskSignals s = signals == null ? TaskSignals.NONE : signals;
        ContextLoadPolicy base = ContextLoadPolicy.forSignals(s);
        if (!s.lightTurn()) {
            return base;
        }
        boolean loadRef = ref != null && ref.shouldLoadPriorHistory();
        ContextLoadPolicy p = base.withHistoryMaxMessages(
                loadRef ? historyQuestionWithRef : historyQuestion);
        return loadRef ? p.withInjectMemory(true) : p;
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
