package com.miniagent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefaultMemoryServiceTest {

    @Test
    void 结构化已含的blob行被丢掉() {
        String structured = "## 记忆\n用户偏好:\n- 回复使用中文，默认 JDK 21\n";
        String blob = "════\n相关记忆\n════\n回复使用中文，默认 JDK 21\n环境用 Windows\n";
        String kept = DefaultMemoryService.dropOverlappingBlobLines(blob, structured);
        assertFalse(kept.contains("JDK 21"));
        assertTrue(kept.contains("环境用 Windows"));
    }

    @Test
    void 无结构化时blob原样返回() {
        String blob = "一条非结构化笔记";
        assertEquals(blob, DefaultMemoryService.dropOverlappingBlobLines(blob, ""));
    }
}
