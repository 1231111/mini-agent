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

    void delete(String sessionId);
}
