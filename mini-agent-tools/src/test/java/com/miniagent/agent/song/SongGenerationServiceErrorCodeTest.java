package com.miniagent.agent.song;

import com.miniagent.agent.tool.ToolErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTTP 状态码 → 错误码的映射。
 *
 * <p>这张表不是给人看的，是给上游 {@code RecoveryEngine} 选恢复档用的：
 * 判成 {@code RATE_LIMITED} / {@code DEPENDENCY_UNAVAILABLE} 才会被当成
 * 「可重试的资源类失败」，判成 {@code PERMISSION_DENIED} 则该换方案而不是重试。
 * 映射错了，LLM 收到的重规划方向就是错的 —— 所以要一条条钉住。</p>
 */
class SongGenerationServiceErrorCodeTest {

    @Test
    void 限流判成RATE_LIMITED() {
        assertEquals(ToolErrorCode.RATE_LIMITED, SongGenerationService.errorCodeFor(429));
    }

    @Test
    void 鉴权失败判成PERMISSION_DENIED() {
        assertEquals(ToolErrorCode.PERMISSION_DENIED, SongGenerationService.errorCodeFor(401));
        assertEquals(ToolErrorCode.PERMISSION_DENIED, SongGenerationService.errorCodeFor(403));
    }

    @Test
    void 参数错判成INVALID_ARGUMENT() {
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, SongGenerationService.errorCodeFor(400));
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, SongGenerationService.errorCodeFor(422));
    }

    @Test
    void 服务端异常判成DEPENDENCY_UNAVAILABLE() {
        assertEquals(ToolErrorCode.DEPENDENCY_UNAVAILABLE, SongGenerationService.errorCodeFor(500));
        assertEquals(ToolErrorCode.DEPENDENCY_UNAVAILABLE, SongGenerationService.errorCodeFor(502));
        assertEquals(ToolErrorCode.DEPENDENCY_UNAVAILABLE, SongGenerationService.errorCodeFor(503));
    }

    @Test
    void 路由不存在判成NOT_FOUND() {
        assertEquals(ToolErrorCode.NOT_FOUND, SongGenerationService.errorCodeFor(404));
    }

    @Test
    void 其余归EXECUTION_FAILED() {
        assertEquals(ToolErrorCode.EXECUTION_FAILED, SongGenerationService.errorCodeFor(418));
        assertEquals(ToolErrorCode.EXECUTION_FAILED, SongGenerationService.errorCodeFor(302));
    }

    @Test
    void 默认模型是Music2_0官方枚举() {
        assertEquals("senseaudio-music-2.0-260626", SongGenerationService.DEFAULT_MODEL);
    }
}
