package com.miniagent.agent.scope;

/**
 * 任务作用域持久化端口；应用模块负责用共享存储实现。
 */
public interface TaskScopePersistence {

    record State(
            long currentTaskId,
            Long pausedTaskId,
            long sequence,
            long version) {
        public State {
            long paused = pausedTaskId == null ? 0L : pausedTaskId;
            if (currentTaskId < 0L || paused < 0L || version < 0L) {
                throw new IllegalArgumentException("Task scope state cannot be negative");
            }
            if (sequence < Math.max(currentTaskId, paused)) {
                throw new IllegalArgumentException(
                        "Task scope sequence cannot trail issued task ids");
            }
        }
    }

    State loadOrCreate(String sessionId);

    boolean compareAndSet(String sessionId, State expected, State next);

    /** 仅在会话被永久删除时调用。 */
    void deleteScope(String sessionId);
}
