package com.miniagent.replica;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.TaskExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisExecutionSignalStoreTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void anotherReplicaCanObserveActualPausedCheckpointAndResumeIt() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        Map<String, Map<Object, Object>> values = new ConcurrentHashMap<>();
        CountDownLatch paused = new CountDownLatch(1);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.get(anyString(), any())).thenAnswer(call ->
                values.computeIfAbsent(call.getArgument(0), ignored -> new ConcurrentHashMap<>()).get(call.getArgument(1)));
        doAnswer(call -> {
            values.computeIfAbsent(call.getArgument(0), ignored -> new ConcurrentHashMap<>())
                    .put(call.getArgument(1), call.getArgument(2));
            if ("pausedAtCheckpoint".equals(call.getArgument(1)) && "1".equals(call.getArgument(2))) paused.countDown();
            return null;
        }).when(hashes).put(anyString(), any(), any());
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        RedisExecutionSignalStore signals = new RedisExecutionSignalStore();
        ReflectionTestUtils.setField(signals, "redis", redis);
        ExecutionControl workerReplica = new ExecutionControl(60_000, 10, 1000, Optional.of(signals), Optional.empty());
        ExecutionControl apiReplica = new ExecutionControl(60_000, 10, 1000, Optional.of(signals), Optional.empty());
        workerReplica.start("s1", null, "run-1");
        apiReplica.pause("s1", "run-1");
        try (var workers = Executors.newSingleThreadExecutor()) {
            var checkpoint = workers.submit(() -> {
                try (var ignored = TaskExecutionContext.bindRun("run-1")) {
                    return workerReplica.checkpoint("s1");
                }
            });
            assertTrue(paused.await(3, TimeUnit.SECONDS));
            assertTrue(apiReplica.isPausedAtCheckpoint("s1", "run-1"), "Redis must expose the worker checkpoint");
            assertFalse(checkpoint.isDone());

            apiReplica.resume("s1", "run-1");

            assertEquals(ExecutionControl.StopReason.NONE, checkpoint.get(3, TimeUnit.SECONDS));
            assertFalse(apiReplica.isPauseRequested("s1", "run-1"));
            assertFalse(apiReplica.isPausedAtCheckpoint("s1", "run-1"));
        } finally {
            workerReplica.finish("s1", "run-1");
        }
    }
}
