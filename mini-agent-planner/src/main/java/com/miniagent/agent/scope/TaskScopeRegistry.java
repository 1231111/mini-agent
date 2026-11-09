package com.miniagent.agent.scope;

import com.miniagent.common.event.SessionEvictedEvent;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前任务作用域的唯一事实源。
 *
 * <h2>它解决什么</h2>
 *
 * <p>任务级状态分散在多个 store 里（规划图、压缩摘要、任务清单）。
 * 以前每个 store 各自判断「要不要复用上一轮的状态」，判定散落、口径不一，
 * 于是出现「清单被释放了、图还在跑，图又把清单写回来」这类不一致。</p>
 *
 * <p>现在只有一个地方决定「当前是哪个任务」，其余所有 store 只是读它。
 * 判定一次，全体生效。</p>
 *
 * <h2>任务边界判据</h2>
 *
 * <p><b>不用正则猜语义，也不看用户这句话像不像新任务。</b>边界只有一个来源：
 * {@code TaskTodoStore.hasOnlyTerminal(sessionId)} —— 当前清单存在、且每一条都是
 * completed / cancelled。这是库里的客观状态，查一下就能确认。</p>
 *
 * <p>未完成的清单<b>不</b>触发新任务：它留在当前任务里，要不要放弃由模型显式
 * {@code task.clear} / {@code task.set} 表达。这里删掉过一版相反的实现：
 * 用「会话里有没有被挂起的清单」当边界（{@code suspendActive} / {@code resumeSuspended}），
 * 那个条件一旦成立就恒成立，于是每一轮都把旧清单捞回来，
 * 新任务被上一个任务的计划接管。</p>
 *
 * <p>{@link TaskBoundary#RESUME} 保留给「模型显式要求回到上一个任务」这种入口；
 * 当前没有任何代码会自己触发它 —— 自动触发就等于又回到了猜。</p>
 *
 * <h2>恢复与多副本</h2>
 *
 * <p>应用提供 {@link TaskScopePersistence} 时，当前任务、最近离开的任务和发号序列
 * 通过版本 CAS 写入共享存储。每次解析边界都先读取共享状态，所以重启和跨副本不会退回
 * 零号任务。无持久化实现时才回退到进程内 Map，供纯单元测试使用。</p>
 */
@Component
public class TaskScopeRegistry {

    /** 惰性创建时使用的原因标记，便于在日志里区分「系统兜底」与「真实判定」。 */
    static final String LAZY_REASON = "lazy";
    static final String RESTORED_REASON = "restored";
    private static final int MAX_CAS_ATTEMPTS = 5;

    /**
     * 一个会话的任务指针：当前任务 + 最近离开的那个任务。
     *
     * <p>为什么只有「一个」被离开的任务，而不是一个栈：任务级状态的键只有两种是<b>可达</b>的
     * —— 当前任务，以及最近刚离开的那个（用户说「继续」时要去的就是它）。
     * 再往前的任务，用户没有任何办法表达「回到倒数第二个」，留一个栈只是留下不可达的状态。</p>
     */
    private static final class Holder {
        /**
         * 单调递增的任务号发放器。
         *
         * <p>必须和 {@link #current} 分开维护：{@link TaskBoundary#RESUME} 会把指针换回
         * 一个较小的号，如果新任务号取「当前号 + 1」，换回之后就会重新发出已经用过的号，
         * 于是新任务和某个被离开的任务撞进同一个键 —— 隔离当场失效。</p>
         */
        long seq;
        long version;
        TaskScope current;
        /** 最近一次被离开的任务；{@link TaskBoundary#RESUME} 时与 current 互换。 */
        TaskScope paused;

        Holder(String sessionId) {
            this.current = TaskScope.initial(sessionId, LAZY_REASON);
        }

        Holder(
                String sessionId,
                TaskScopePersistence.State state,
                String currentReason) {
            this.seq = state.sequence();
            this.version = state.version();
            this.current = restoredScope(
                    sessionId, state.currentTaskId(), currentReason);
            this.paused = state.pausedTaskId() == null
                    ? null
                    : restoredScope(
                            sessionId,
                            state.pausedTaskId(),
                            RESTORED_REASON);
        }

        private static TaskScope restoredScope(
                String sessionId, long taskId, String reason) {
            return new TaskScope(
                    sessionId, taskId, System.currentTimeMillis(), reason);
        }
    }

    private final Map<String, Holder> bySession = new ConcurrentHashMap<>();
    private final TaskScopePersistence persistence;

    public TaskScopeRegistry() {
        this(null);
    }

    @Autowired
    public TaskScopeRegistry(
            @Autowired(required = false) TaskScopePersistence persistence) {
        this.persistence = persistence;
    }

    private Holder holder(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }
        if (persistence == null) {
            return bySession.computeIfAbsent(sessionId, Holder::new);
        }
        TaskScopePersistence.State state = persistence.loadOrCreate(sessionId);
        return cache(sessionId, state, RESTORED_REASON);
    }

    private Holder cache(
            String sessionId,
            TaskScopePersistence.State state,
            String currentReason) {
        return bySession.compute(sessionId, (key, cached) -> {
            if (cached != null && cached.version == state.version()) {
                return cached;
            }
            return new Holder(sessionId, state, currentReason);
        });
    }

    /**
     * 取得该会话当前的任务作用域，没有就创建 {@code taskId == 0} 的初始作用域。
     *
     * <p>惰性创建是刻意的：任何在 {@code ContextLoader} 之前读取作用域的调用
     * （UI 查询、直跑路径、单测）都应拿到一个稳定且一致的值，
     * 而不是「null 就退回 sessionId」这种第二套 key —— 那会让状态分裂成两份。</p>
     */
    public TaskScope current(String sessionId) {
        Holder h = holder(sessionId);
        return h == null ? null : h.current;
    }

    /**
     * 按本轮判定的边界推进作用域。
     *
     * @param reason 写日志与轨迹用，便于排查「为什么这里换了任务」。
     * @return 本轮所属的任务作用域；sessionId 为空时返回 null。
     */
    public TaskScope resolve(String sessionId, TaskBoundary boundary, String reason) {
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }
        TaskBoundary effective = boundary == null ? TaskBoundary.SAME : boundary;
        if (persistence != null) {
            return resolvePersistent(sessionId, effective, reason);
        }
        return resolveInMemory(sessionId, effective, reason);
    }

    private TaskScope resolveInMemory(
            String sessionId, TaskBoundary boundary, String reason) {
        Holder h = holder(sessionId);
        synchronized (h) {
            switch (boundary) {
                case NEW -> {
                    h.paused = h.current;
                    // 用单调计数器发号，而不是「当前号 + 1」：RESUME 会把指针换回旧号，
                    // 从旧号加一就会把已经发过的号再发一次，新任务与被离开的任务撞键。
                    h.seq++;
                    h.current = new TaskScope(sessionId, h.seq,
                            System.currentTimeMillis(), reason);
                }
                case RESUME -> {
                    if (h.paused != null) {
                        TaskScope back = h.paused;
                        h.paused = h.current;
                        h.current = new TaskScope(sessionId, back.taskId(),
                                System.currentTimeMillis(), reason);
                    }
                    // paused 为空表示从未离开过任务（比如用户直接说「继续」但一直在一个任务里），
                    // 当前指针不动，等价于 SAME
                }
                case SAME -> { }
            }
            return h.current;
        }
    }

    private TaskScope resolvePersistent(
            String sessionId, TaskBoundary boundary, String reason) {
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            TaskScopePersistence.State current =
                    persistence.loadOrCreate(sessionId);
            TaskScopePersistence.State next = transition(current, boundary);
            if (next == current) {
                return cache(sessionId, current, RESTORED_REASON).current;
            }
            if (persistence.compareAndSet(sessionId, current, next)) {
                return cache(sessionId, next, reason).current;
            }
        }
        throw new IllegalStateException(
                "Task scope CAS conflict after " + MAX_CAS_ATTEMPTS
                        + " attempts for session " + sessionId);
    }

    private TaskScopePersistence.State transition(
            TaskScopePersistence.State current, TaskBoundary boundary) {
        return switch (boundary) {
            case NEW -> {
                long nextTaskId = current.sequence() + 1L;
                yield new TaskScopePersistence.State(
                        nextTaskId,
                        current.currentTaskId(),
                        nextTaskId,
                        current.version() + 1L);
            }
            case RESUME -> {
                if (current.pausedTaskId() == null) {
                    yield current;
                }
                yield new TaskScopePersistence.State(
                        current.pausedTaskId(),
                        current.currentTaskId(),
                        current.sequence(),
                        current.version() + 1L);
            }
            case SAME -> current;
        };
    }

    /**
     * 任务级状态的 key。没有登记过作用域的会话由 {@link #current} 兜底创建，因此永不返回 null。
     */
    public String scopeKey(String sessionId) {
        TaskScope scope = current(sessionId);
        return scope == null ? sessionId : scope.scopeKey();
    }

    /** 会话被永久删除时释放缓存及持久化的任务指针。 */
    public void forget(String sessionId) {
        if (StringUtils.isNotBlank(sessionId)) {
            bySession.remove(sessionId);
            if (persistence != null) {
                persistence.deleteScope(sessionId);
            }
        }
    }

    /**
     * 只释放进程内缓存，保留持久化的任务指针。
     *
     * <p>用于「会话长期空闲」：进程内对象该还回去，但用户走开回来点开这个会话时
     * 仍应停在离开时的那个任务上（还能说「继续」）。指针一旦在这里删掉，
     * 用户回来就被当成全新会话，上一任务变成不可达 —— 那是功能退化，不是回收。</p>
     */
    public void forgetCached(String sessionId) {
        if (StringUtils.isNotBlank(sessionId)) {
            bySession.remove(sessionId);
        }
    }

    /**
     * 会话被删除或判定空闲时回收。
     *
     * <p>删除与空闲的处置<b>刻意不同</b>：删除是不可逆的，连持久化指针一起丢；
     * 空闲只是内存回收，任务指针必须留着，否则用户回来说「继续」会找不到上一任务。</p>
     */
    @EventListener
    public void onSessionEvicted(SessionEvictedEvent event) {
        if (event.isDeleted()) {
            forget(event.sessionId());
        } else {
            forgetCached(event.sessionId());
        }
    }

    /** 已登记的会话数，健康检查与测试用。 */
    public int activeSessionCount() {
        return bySession.size();
    }
}
