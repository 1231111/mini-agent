package com.miniagent.memory;

/**
 * 用户记忆三件套持久化端口。多副本部署用 DB 实现；单机可继续用文件实现。
 */
public interface MemoryBlobStore {

    record Blob(String memoryRaw, String userRaw, String midtermRaw) {
        public Blob {
            memoryRaw = memoryRaw == null ? "" : memoryRaw;
            userRaw = userRaw == null ? "" : userRaw;
            midtermRaw = midtermRaw == null ? "" : midtermRaw;
        }
    }

    Blob load(long userId);

    void saveMemory(long userId, String content);

    void saveUser(long userId, String content);

    void saveMidterm(long userId, String content);
}
