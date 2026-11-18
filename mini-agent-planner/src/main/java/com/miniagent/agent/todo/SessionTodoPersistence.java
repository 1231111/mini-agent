package com.miniagent.agent.todo;

/**
 * 会话 todo 持久化端口。DB 实现供多副本；文件实现供测试/单机回退。
 */
public interface SessionTodoPersistence {

    record State(String activeJson, String suspendedJson) {
        public State {
            activeJson = activeJson == null || activeJson.isBlank() ? "[]" : activeJson;
            suspendedJson = suspendedJson == null || suspendedJson.isBlank() ? null : suspendedJson;
        }
    }

    State load(String sessionId);

    void save(String sessionId, String activeJson, String suspendedJson);

    /**
     * Replace one task partition without overwriting partitions changed by another replica.
     * Shared-store implementations should merge the partition atomically. The complete
     * snapshot is provided only as a compatibility fallback for simple stores. A null
     * partition removes that task's partition.
     */
    default void saveTaskPartition(
            String sessionId, long taskId, String partitionJson, String fullSnapshotJson) {
        save(sessionId, fullSnapshotJson, null);
    }

    void delete(String sessionId);
}
