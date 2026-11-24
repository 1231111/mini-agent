package com.miniagent.config.cloud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务级别的 token 额度闸门。
 *
 * <h2>为什么在任务级而不是每次模型调用</h2>
 *
 * <p>一个任务可能调几十次模型。逐次远程查询会加几十个网络往返，而额度判定只需要
 * 「今天还剩多少」这一个粗粒度数字。粒度定在任务级，代价是可能有一个任务的超支窗口 ——
 * 这个窗口用任务级 token 上限兜住，不需要靠逐次检查。</p>
 *
 * <h2>为什么是 5 分钟缓存</h2>
 *
 * <p>同一个任务里连续发起的多轮会在窗口内复用结果，不会每轮打一次账号服务；
 * 窗口过后重新判定，最多 5 分钟的超支延迟。</p>
 *
 * <h2>为什么查不到时放行（fail-open）</h2>
 *
 * <p>额度是防滥用，不是防攻击。账号服务抖动时让所有人干不了活，代价远大于「多放行几分钟」。
 * 放行时记 warn，便于发现账号服务长期不可达。要改成 fail-closed 只需把
 * {@link #rejectionReason} 里 catch 分支的返回值从 null 换成一句拒绝文案。</p>
 */
@Component
public class TokenEntitlementGate {

    private static final Logger log = LoggerFactory.getLogger(TokenEntitlementGate.class);
    private static final long TTL_MILLIS = 5 * 60 * 1000L;
    /** 缓存上限：防止用户数增长时无限堆积。超出直接清空，重新查一次比淘汰策略更简单。 */
    private static final int MAX_ENTRIES = 5_000;

    private record Cached(String rejectionReason, long expiresAtMillis) {
        boolean fresh(long now) { return now < expiresAtMillis; }
    }

    private final ObjectProvider<CloudMembershipClient> clients;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicLong failOpenCount = new AtomicLong();

    public TokenEntitlementGate(ObjectProvider<CloudMembershipClient> clients) {
        this.clients = clients;
    }

    /**
     * @return null 表示允许发起任务；非 null 是可以直接展示给用户的拒绝原因
     */
    public String rejectionReason(Long userId) {
        if (userId == null) return null;
        CloudMembershipClient client = clients.getIfAvailable();
        // 桌面形态默认不配云端账号服务：没有额度体系，直接放行。
        if (client == null || !client.available()) return null;

        long now = System.currentTimeMillis();
        Cached cached = cache.get(userId);
        if (cached != null && cached.fresh(now)) return cached.rejectionReason();

        String reason;
        try {
            CloudMembershipClient.EntitlementView view = client.entitlement(userId);
            reason = view == null || view.allowed() ? null : safeReason(view);
        } catch (RuntimeException unreachable) {
            long count = failOpenCount.incrementAndGet();
            // 只在前几次和每 100 次打 warn：账号服务长期不可达会持续失败，
            // 每次都打会把日志刷满，而这个故障本身不阻断任务。
            if (count <= 3 || count % 100 == 0) {
                log.warn("额度查询失败，按放行处理（累计 {} 次）: {}", count, unreachable.getMessage());
            }
            // 失败不写缓存：否则一次抖动会让这个用户在 5 分钟内都被当成「已放行」，
            // 而实际上我们什么都没查到。
            return null;
        }

        if (cache.size() >= MAX_ENTRIES) cache.clear();
        cache.put(userId, new Cached(reason, now + TTL_MILLIS));
        return reason;
    }

    /** 额度变化后（例如用户刚充值）主动失效，让下一次任务立刻用新状态判定。 */
    public void invalidate(Long userId) {
        if (userId != null) cache.remove(userId);
    }

    public long failOpenCount() { return failOpenCount.get(); }

    private static String safeReason(CloudMembershipClient.EntitlementView view) {
        String reason = view.reason();
        if (reason != null && !reason.isBlank()) return reason;
        // 服务端没给文案时的兜底：至少说明是哪条额度、什么时候恢复，别让用户只看到「失败」。
        return "今日 token 额度已用完（" + view.usedTokens() + " / " + view.dailyLimitTokens()
                + "），" + (view.resetAt() == null ? "次日" : view.resetAt()) + " 后恢复。";
    }
}
