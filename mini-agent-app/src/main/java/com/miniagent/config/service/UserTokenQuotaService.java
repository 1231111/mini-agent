package com.miniagent.config.service;

import com.miniagent.config.entity.User;
import com.miniagent.config.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 会员套餐落地到本地用户表的两件事：额度快照与并发上限。
 *
 * <p>token 额度本身不再由本地累计判定 —— 明细与已用量都在账号服务，
 * 任务能否发起由 {@code TokenQuotaFilter} 在 HTTP 入口按云端结果拦截。
 * 这里保留的是「本地必须知道的部分」：并发上限决定能不能同时跑多个任务，
 * 这个判断每次都要用，不能走网络。</p>
 *
 * <p>租户级 token 额度已移除：额度只按会员套餐计算，不再按租户分摊。</p>
 */
@Service
public class UserTokenQuotaService {

    @Autowired private UserRepository users;

    @Value("${agent.quota.zone-id:Asia/Shanghai}")
    private String quotaZoneId;

    /** 会员开通/续期时把套餐额度写进用户快照，供管理端展示与并发判定使用。 */
    @Transactional
    public void synchronizeMembership(Long userId, long dailyLimit, int maxConcurrentTasks,
                                      LocalDateTime expiresAt) {
        if (dailyLimit < 0) {
            throw new IllegalArgumentException("membership daily token limit must be >= 0");
        }
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        user.setMembershipDailyTokenLimit(dailyLimit);
        user.setMembershipMaxConcurrentTasks(maxConcurrentTasks);
        user.setMembershipExpiresAt(expiresAt);
        users.save(user);
    }

    /** Resolve the active membership cap, falling back to the service configuration. */
    @Transactional(readOnly = true)
    public int effectiveConcurrentLimit(Long userId, int fallback) {
        User user = users.findById(userId).orElse(null);
        if (user == null || user.getMembershipMaxConcurrentTasks() == null) return fallback;
        LocalDateTime now = LocalDateTime.now(ZoneId.of(quotaZoneId));
        if (user.getMembershipExpiresAt() != null && !now.isBefore(user.getMembershipExpiresAt())) {
            return fallback;
        }
        int cap = user.getMembershipMaxConcurrentTasks();
        return cap <= 0 ? Integer.MAX_VALUE : cap;
    }
}
