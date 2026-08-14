package com.miniagent.agent.planner;

/**
 * 规划外环续期会话锁。实现在应用层，避免规划器依赖 config。
 */
public interface SessionLock {

    /**
     * @return false 表示锁已丢，调用方应中止
     */
    boolean renewSessionLock(String sessionId);
}
