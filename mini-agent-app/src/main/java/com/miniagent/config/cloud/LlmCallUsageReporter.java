package com.miniagent.config.cloud;

import com.miniagent.agent.core.LlmCallUsageSink;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把单次调用明细上报到账号服务。
 *
 * <p>为什么必须异步：这是一次跨公网的 HTTP 调用。同步做的话，每一轮对话都要多等一个
 * 网络往返（50–200 ms），而记账对用户没有任何收益。</p>
 *
 * <p>为什么用单线程有界队列：账号服务是单点，并发上报只会把它的连接池占满；
 * 队列满了直接丢弃并记一次计数，<b>不能阻塞也不能无界增长</b>。丢掉的记录由日志和
 * {@code droppedTotal} 暴露出来，可观测优先于"一条都不能少"——这里不是账单系统，
 * 真正的账单以服务商计费为准。</p>
 *
 * <p>未配置云端账号服务时（桌面默认形态）整个组件空转，不产生任何网络请求。</p>
 */
@Component
public class LlmCallUsageReporter implements LlmCallUsageSink {

    private static final Logger log = LoggerFactory.getLogger(LlmCallUsageReporter.class);
    private static final int QUEUE_CAPACITY = 512;

    private final ObjectProvider<CloudMembershipClient> clients;
    private final ConfiguredModelPricing pricing;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public LlmCallUsageReporter(ObjectProvider<CloudMembershipClient> clients,
                                ConfiguredModelPricing pricing) {
        this.clients = clients;
        this.pricing = pricing;
        this.executor = new ThreadPoolExecutor(
                1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "llm-usage-reporter");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    @Override
    public void record(CallUsage usage) {
        if (usage == null) return;
        // userId 由采集点带来：投递发生在异步队列线程，那里没有 OwnerContext。
        Long userId = usage.userId();
        if (userId == null) return;
        CloudMembershipClient client = clients.getIfAvailable();
        if (client == null || !client.available()) return;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("sessionId", usage.sessionId());
        body.put("requestId", usage.requestId());
        body.put("model", usage.model());
        body.put("reasoningEffort", usage.reasoningEffort());
        body.put("endpoint", usage.endpoint());
        body.put("streaming", usage.streaming());
        body.put("billingMode", usage.billingMode());
        body.put("apiKeyHint", usage.apiKeyHint());
        body.put("inputTokens", usage.inputTokens());
        body.put("cachedInputTokens", usage.cachedInputTokens());
        body.put("outputTokens", usage.outputTokens());
        body.put("totalTokens", usage.inputTokens() + usage.outputTokens());
        // 计价放在这里而不是 planner 里：单价是业务配置，不是执行期数据；
        // 让 planner 依赖价格表会把「怎么算钱」耦合进「怎么调用模型」。
        body.put("costMicros", pricing.costMicros(
                usage.model(), usage.inputTokens(), usage.cachedInputTokens(), usage.outputTokens()));
        body.put("ttftMillis", usage.ttftMillis());
        body.put("durationMillis", usage.durationMillis());
        body.put("userAgent", usage.userAgent());

        try {
            executor.execute(() -> send(client, body));
        } catch (RejectedExecutionException rejected) {
            // DiscardPolicy 不会抛，这里只是防御性兜底。
            dropped.incrementAndGet();
        }

        // 每个模型调用一行，量级与「记录 LLM 响应」相当。
        // cached 为 -1 时要能一眼看出来：那是"未采集"，不是"零命中"。
        log.info("调用明细上报 model={} in={} cached={} out={} ttft={}ms dur={}ms",
                usage.model(), usage.inputTokens(),
                usage.cachedInputTokens() < 0 ? "未采集" : usage.cachedInputTokens(),
                usage.outputTokens(), usage.ttftMillis(), usage.durationMillis());
    }

    private void send(CloudMembershipClient client, Map<String, Object> body) {
        try {
            client.reportCallUsage(body);
        } catch (RuntimeException failure) {
            long count = failed.incrementAndGet();
            // 只在前几次和每 100 次时打 warn：账号服务不可达会持续失败，
            // 每次都打会把日志刷满，而这个故障本身不会影响对话。
            if (count <= 3 || count % 100 == 0) {
                log.warn("调用明细上报失败（累计 {} 次）: {}", count, failure.getMessage());
            }
        }
    }

    /** 队列已满被丢弃的条数。用于确认"页面上数据比实际少"是不是这里造成的。 */
    public long droppedTotal() { return dropped.get(); }

    public long failedTotal() { return failed.get(); }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
