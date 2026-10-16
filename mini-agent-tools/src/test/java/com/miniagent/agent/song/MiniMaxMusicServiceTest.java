package com.miniagent.agent.song;

import com.miniagent.agent.tool.ToolErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MiniMaxMusicServiceTest {

    @Test
    void 业务码映射() {
        assertEquals(ToolErrorCode.RATE_LIMITED, MiniMaxMusicService.errorCodeFor(1002));
        assertEquals(ToolErrorCode.PERMISSION_DENIED, MiniMaxMusicService.errorCodeFor(1004));
        assertEquals(ToolErrorCode.PERMISSION_DENIED, MiniMaxMusicService.errorCodeFor(2049));
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, MiniMaxMusicService.errorCodeFor(2013));
        assertEquals(ToolErrorCode.EXECUTION_FAILED, MiniMaxMusicService.errorCodeFor(1008));
    }

    @Test
    void hex解码() {
        assertArrayEquals(new byte[] {(byte) 0xff, 0x00}, MiniMaxMusicService.decodeHex("ff00"));
        assertNull(MiniMaxMusicService.decodeHex("abc"));
        assertNull(MiniMaxMusicService.decodeHex("zz"));
    }
}
