package com.miniagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具执行专用线程池配置。
 * <p>
 * 之前使用 ForkJoinPool.commonPool()，其线程数 = CPU核数-1，
 * 当多个工具并发执行（尤其是 I/O 密集型的 web_search/web_extract）时容易成为瓶颈。
 * 现在改为独立的 ThreadPoolExecutor，允许更多并发工具调用。
 */
@Configuration
public class ToolExecutorConfig {

    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 8;
    private static final long KEEP_ALIVE_SECONDS = 60L;
    private static final int QUEUE_CAPACITY = 64;

    /**
     * 工具执行专用线程池。
     * <ul>
     *   <li>核心线程 4，最大 8，空闲 60s 回收</li>
     *   <li>有界队列（64），防止任务无限堆积</li>
     *   <li>CallerRunsPolicy：队列满时由调用线程执行，提供背压</li>
     *   <li>命名线程 "tool-worker-N"，方便 jstack 排查</li>
     * </ul>
     */
    @Bean("toolExecutor")
    public ExecutorService toolExecutor() {
        AtomicInteger threadNum = new AtomicInteger(0);
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "tool-worker-" + threadNum.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
