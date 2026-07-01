package com.miniagent.memory;

/**
 * 记忆层级：短期（会话）、中期（当日概要）、长期（跨会话知识）。
 */
public enum MemoryType {

    SHORT_TERM("short-term"),
    MID_TERM("mid-term"),
    LONG_TERM("long-term");

    private final String directory;

    MemoryType(String directory) {
        this.directory = directory;
    }

    public String getDirectory() {
        return directory;
    }
}
