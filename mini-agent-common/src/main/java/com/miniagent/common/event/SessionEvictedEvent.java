package com.miniagent.common.event;

/**
 * 会话离场事件：一个会话不再需要保留进程内状态了。
 *
 * <p><b>为什么用事件而不是让各 store 各自判断</b>：按 sessionId 挂着的进程内状态散落在
 * 多个组件里（记忆窗口、压缩摘要、用量统计、任务作用域缓存、执行租约）。如果每个组件
 * 各自写"什么时候该清"，判定会散落且口径不一；而且清理点有两个（用户删会话 / 长时间没活动），
 * 组合起来就是 N×2 处逻辑。这里只发布一次事实，订阅方各自执行<b>自己已有的</b> evict 方法。</p>
 *
 * <p><b>两种原因必须区分</b>，因为"删掉"和"闲置"该清的深度不一样：</p>
 * <ul>
 *   <li>{@link Reason#DELETED} —— 会话被永久删除：进程内状态与持久化的会话级指针都可以清；</li>
 *   <li>{@link Reason#IDLE} —— 长时间没活动：只清进程内缓存。用户走开两小时回来还要能
 *       "继续上次任务"，所以持久化的任务指针、记忆内容都不能动。</li>
 * </ul>
 */
public record SessionEvictedEvent(String sessionId, Reason reason) {

    public enum Reason {
        /** 会话被永久删除（用户主动删 / 重置）。 */
        DELETED,
        /** 长时间无活动，仅回收进程内缓存。 */
        IDLE
    }

    public static SessionEvictedEvent deleted(String sessionId) {
        return new SessionEvictedEvent(sessionId, Reason.DELETED);
    }

    public static SessionEvictedEvent idle(String sessionId) {
        return new SessionEvictedEvent(sessionId, Reason.IDLE);
    }

    public boolean isDeleted() {
        return reason == Reason.DELETED;
    }
}
