package com.miniagent.agent.scope;

import org.apache.commons.lang3.StringUtils;
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
 * <p><b>不用正则猜语义。</b>边界取自<b>已经被持久化、且本来就是任务生命周期所有者</b>的那个 store ——
 * {@code TaskTodoStore}。它在动手轮释放上一任务的工作集（{@code suspendActive}），
 * 这个动作有两个出口，含义都是「上一任务不再活跃」：</p>
 * <ul>
 *   <li><b>挂起</b>：上一任务没做完，清单被压成 {@code .suspended.json} 留着等用户说「继续」；</li>
 *   <li><b>归档清空</b>：上一任务全部终态（完成/取消），没有可恢复的东西。</li>
 * </ul>
 * <p>两者都开新任务：挂起时旧图留在旧键上，用户说「继续」由 {@link TaskBoundary#RESUME} 回来拿；
 * 归档时旧图已经没有意义，自然被弃用。</p>
 *
 * <p>反向的一步是 {@code TaskTodoStore.resumeSuspended} 成功（用户明确说「继续」）→
 * {@link TaskBoundary#RESUME}，把当前指针弹回被挂起的那个任务。</p>
 *
 * <p>这样定边界的结果是：<b>任务边界不再由「AI 觉得用户是不是换话题了」决定</b>，
 * 而由「上一交付的工作集是被挂起还是被归档」决定 —— 一个可持久化、可复核、
 * 且与用户唯一能表达「接着做」的方式（回复「继续」）自洽的事实。</p>
 *
 * <h2>已知边界</h2>
 *
 * <p>作用域登记在进程内存里，重启或多副本时同会话的另一份进程会从零号任务重新开始。
 * 影响范围限于「已切过任务、且被中断」的会话：它的图会留在旧键上而新进程只读零号槽位，
 * 表现为「继续」捞得回清单但捞不回图。要闭环需要把这个登记落进已持久化的会话维度存储
 * （{@code SessionTodoPersistence.State} 加一列），属于一次带 DDL 的改动，单独做。</p>
 */
@Component
public class TaskScopeRegistry {

    /** 惰性创建时使用的原因标记，便于在日志里区分「系统兜底」与「真实判定」。 */
    static final String LAZY_REASON = "lazy";

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
        TaskScope current;
        /** 最近一次被离开的任务；{@link TaskBoundary#RESUME} 时与 current 互换。 */
        TaskScope paused;

        Holder(String sessionId) {
            this.current = TaskScope.initial(sessionId, LAZY_REASON);
        }
    }

    private final Map<String, Holder> bySession = new ConcurrentHashMap<>();

    private Holder holder(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return null;
        }
        return bySession.computeIfAbsent(sessionId, Holder::new);
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
        Holder h = holder(sessionId);
        if (h == null) {
            return null;
        }
        synchronized (h) {
            switch (boundary == null ? TaskBoundary.SAME : boundary) {
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

    /**
     * 任务级状态的 key。没有登记过作用域的会话由 {@link #current} 兜底创建，因此永不返回 null。
     */
    public String scopeKey(String sessionId) {
        TaskScope scope = current(sessionId);
        return scope == null ? sessionId : scope.scopeKey();
    }

    /**
     * 会话被删除时释放登记。
     *
     * <p>注意：这里只清「当前是哪个任务」的登记，
     * <b>不清任务级状态本身</b> —— 那些状态的 key 已经是作用域键，
     * 会话重建后会从头开始递增，天然读不到旧数据。</p>
     */
    public void forget(String sessionId) {
        if (StringUtils.isNotBlank(sessionId)) {
            bySession.remove(sessionId);
        }
    }

    /** 已登记的会话数，健康检查与测试用。 */
    public int activeSessionCount() {
        return bySession.size();
    }
}
