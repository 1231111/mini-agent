package com.miniagent.account.web;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class RedisLoginAttemptRateLimiterTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void performsOneAtomicLuaAdmissionWithGlobalAccountAndIpKeys() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(0L).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        RedisLoginAttemptRateLimiter limiter = new RedisLoginAttemptRateLimiter(redis, 10, 30, 60);

        assertThat(limiter.acquire("alice", "198.51.100.1")).isZero();
        assertThat(limiter.acquire("alice", "198.51.100.2")).isZero();

        ArgumentCaptor<RedisScript<Long>> scripts = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis, org.mockito.Mockito.times(2)).execute(scripts.capture(), keys.capture(), args.capture());

        assertThat(scripts.getAllValues()).allSatisfy(script -> {
            assertThat(script.getScriptAsString()).contains("redis.call('GET', KEYS[1])",
                    "redis.call('GET', KEYS[2])", "redis.call('INCR', KEYS[1])", "redis.call('INCR', KEYS[2])");
        });
        List<String> first = keys.getAllValues().get(0);
        List<String> second = keys.getAllValues().get(1);
        assertThat(first.get(1)).isEqualTo(second.get(1));
        assertThat(first.get(0)).isNotEqualTo(second.get(0));
        assertThat(first).allMatch(key -> key.startsWith("mini-agent:login-rate:{auth}:"));
        assertThat(args.getAllValues().get(0)).containsExactly("30", "10", "60");
    }

    @Test
    void propagatesRedisFailureSoTheRequestFilterCanFailClosed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        org.mockito.Mockito.when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis unavailable"));
        RedisLoginAttemptRateLimiter limiter = new RedisLoginAttemptRateLimiter(redis, 10, 30, 60);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> limiter.acquire("alice", "198.51.100.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis unavailable");
    }

    @Test
    void usesOnlyHashedAccountAndIpValuesInRedisKeys() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(0L).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        RedisLoginAttemptRateLimiter limiter = new RedisLoginAttemptRateLimiter(redis, 10, 30, 60);
        limiter.acquire("alice@example.com", "198.51.100.1");

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).contains(
                "mini-agent:login-rate:{auth}:account:" + sha256("alice@example.com"),
                "mini-agent:login-rate:{auth}:ip:" + sha256("198.51.100.1"));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
