package com.miniagent.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunExecutorConfigurationTest {

    @Test
    void agentRunsUseAnUnqueuedExecutorThatRejectsOverloadInsteadOfRunningOnRequestThreads() {
        ThreadPoolExecutor executor = assertInstanceOf(ThreadPoolExecutor.class,
                new ToolExecutorConfig().agentRunExecutor());
        try {
            assertTrue(executor.getMaximumPoolSize() > 0);
            assertEquals(0, executor.getQueue().remainingCapacity());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, executor.getRejectedExecutionHandler());
        } finally {
            executor.shutdownNow();
        }
    }
}
