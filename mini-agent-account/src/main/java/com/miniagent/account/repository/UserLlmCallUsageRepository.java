package com.miniagent.account.repository;

import com.miniagent.account.entity.UserLlmCallUsage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UserLlmCallUsageRepository extends JpaRepository<UserLlmCallUsage, Long> {

    boolean existsByRequestId(String requestId);

    /** 明细页：按时间倒序，最近的在最上面，和上游控制台一致。 */
    Page<UserLlmCallUsage> findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long userId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    /**
     * 区间延迟统计。
     *
     * <p>返回 {@code [callCount, avgTtft, avgDuration, maxDuration, p95Approx]}。
     * 平均值用 SQL 算而不是取回全部行再平均——明细表会到百万级，不能为了一个平均值
     * 把整段记录拉进内存。</p>
     *
     * <p>{@code ttftMillis > 0} 只统计流式调用：非流式没有首 token 的概念，
     * 把 0 计入平均会把结果拉低。</p>
     */
    @Query("select count(u), "
            + "coalesce(avg(case when u.ttftMillis > 0 then u.ttftMillis else null end), 0), "
            + "coalesce(avg(u.durationMillis), 0), "
            + "coalesce(max(u.durationMillis), 0), "
            + "coalesce(sum(case when u.ttftMillis > 0 then 1 else 0 end), 0) "
            + "from UserLlmCallUsage u "
            + "where u.userId = :userId and u.createdAt between :from and :to")
    List<Object[]> latencyStats(@Param("userId") Long userId,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);

    /** 同一会话内的调用，用于把一次任务的每一轮串起来看。 */
    Page<UserLlmCallUsage> findBySessionIdOrderByCreatedAtAsc(String sessionId, Pageable pageable);
}
