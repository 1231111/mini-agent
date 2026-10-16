package com.miniagent.agent.memory.lifecycle;

import com.miniagent.agent.memory.ChatMemoryConfig;
import com.miniagent.application.ChatStreamingService;
import com.miniagent.common.event.SessionEvictedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 空闲会话回收：把长时间没人用的会话的<b>进程内</b>副本还回去。
 *
 * <h2>它补的是哪个缺口</h2>
 *
 * <p>「会话被删除」有明确入口（{@code ConversationService} 会广播事件），但桌面客户端的
 * 常态不是删除 —— 是<b>用户点 × 把窗口藏进托盘，进程一直不退出</b>。会话对象此时一个都不算废，
 * 可是也没人会再去用；不主动回收的话，进程里就一直是「所有聊过的会话都在内存里」，
 * 单调上涨直到被系统杀掉。</p>
 *
 * <h2>为什么不用跨实例锁</h2>
 *
 * <p>同目录的 {@code EventDrivenConsolidationWorker} 用 {@code MemoryTaskLock} 抢一个
 * 分布式锁，因为「处理未处理事件」这件事<b>全局做一次就够</b>，两个实例都做是重复劳动。
 * 这里正相反：{@link SessionEvictedEvent} 是 Spring 进程内事件，
 * 每个实例只清得掉<b>自己</b>那份内存。若也去抢锁，等于「谁抢到谁清」，
 * 另外几个实例的内存反而永远清不掉 —— 锁在这里不是防重复，是制造泄漏。</p>
 *
 * <h2>回收什么、不回收什么</h2>
 *
 * <p>走的是和「删除会话」同一条事件通道，因此各订阅方按自己的语义处置；
 * 队列里的任务作用域、执行租约这类属于当前行程的状态只清进程内副本，
 * 持久化的任务指针保留（用户回来还要能接着上一任务做）。</p>
 */
@Component
public class SessionIdleReaper {

    private static final Logger log = LoggerFactory.getLogger(SessionIdleReaper.class);

    @Autowired
    private ChatMemoryConfig chatMemoryConfig;

    /**
     * 判定「这个会话上没有活儿在跑」，避免把正在执行的一轮连内存一起摘掉。
     *
     * <p>刻意是可选依赖：后台任务不能因为某个装配档里没有 SSE 组件就每轮空指针。
     * 拿不到探测口时退化成「不跳过」，即仍按空闲阈值回收。</p>
     */
    @Autowired(required = false)
    private ChatStreamingService chatStreamingService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /** 多久没被取用算空闲。默认 1 小时——要明显大于单轮对话的耗时。 */
    @Value("${agent.chat-memory.idle-ttl-ms:3600000}")
    private long idleTtlMs;

    /** 单轮最多回收多少个，防一次扫描卡在成千上万个会话上。 */
    @Value("${agent.chat-memory.idle-reap-batch:500}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${agent.chat-memory.idle-scan-interval-ms:600000}")
    public void reapIdleSessions() {
        if (idleTtlMs <= 0) {
            // 允许运维用 0 或负数显式关掉这个后台动作。
            return;
        }
        long now = System.currentTimeMillis();
        List<String> idle = chatMemoryConfig.idleSessions(now, idleTtlMs);
        if (idle.isEmpty()) {
            return;
        }
        int reaped = 0;
        int skipped = 0;
        for (String sessionId : idle) {
            if (reaped >= batchSize) {
                log.debug("空闲会话回收达到单轮上限 {}，其余留到下一轮", batchSize);
                break;
            }
            if (chatStreamingService != null && chatStreamingService.isTaskRunning(sessionId)) {
                // 上一轮还在跑：它的 lastAccess 只在取记忆窗口时刷新，
                // 一次长任务可能超过 TTL。有活儿就不动它。
                skipped++;
                continue;
            }
            try {
                eventPublisher.publishEvent(SessionEvictedEvent.idle(sessionId));
                reaped++;
            } catch (Exception e) {
                log.warn("回收空闲会话失败 sessionId={}: {}", sessionId, e.getMessage());
            }
        }
        if (reaped > 0 || skipped > 0) {
            log.info("空闲会话回收：释放 {} 个，跳过 {} 个（仍在执行），阈值 {} ms",
                    reaped, skipped, idleTtlMs);
        }
    }
}
