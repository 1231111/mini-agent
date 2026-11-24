package com.miniagent.account.repository;

import com.miniagent.account.entity.UserDailyUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserDailyUsageRepository extends JpaRepository<UserDailyUsage, Long> {

    Optional<UserDailyUsage> findByUserIdAndUsageDayAndModel(Long userId, LocalDate usageDay, String model);

    /**
     * 某用户某天的总 token 数（输入 + 输出，所有模型合计）。
     *
     * <p>额度判断只要一个数，用聚合查询而不是把当天所有行拉回来再求和 ——
     * 这个查询在每次任务开始前都会跑，不能做全量读取。</p>
     */
    @Query("select coalesce(sum(u.inputTokens + u.outputTokens), 0) from UserDailyUsage u "
            + "where u.userId = :userId and u.usageDay = :usageDay")
    long tokensOn(@Param("userId") Long userId, @Param("usageDay") LocalDate usageDay);

    /** 汇总查询按天/模型排序，让接口输出稳定，便于前端直接渲染与快照比对。 */
    List<UserDailyUsage> findByUserIdAndUsageDayBetweenOrderByUsageDayAscModelAsc(
            Long userId, LocalDate from, LocalDate to);

    /**
     * 原子累加已存在的行，返回受影响行数。
     *
     * <p>为什么用 UPDATE 而不是「查出来改再 save」：并发上报会丢更新。
     * 两个请求同时读到 100，各加 10，最后都是 110，实际应为 120。
     * 数据库的 {@code col = col + ?} 是原子的。</p>
     *
     * <p>为什么显式写 {@code updatedAt}：bulk update 不触发 {@code @PreUpdate}，
     * 不写的话这一列会停在插入时间，后面按「最后上报时间」排障就会看到错的时间。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserDailyUsage u set "
            + "u.inputTokens = u.inputTokens + :inputTokens, "
            + "u.cachedInputTokens = u.cachedInputTokens + :cachedInputTokens, "
            + "u.outputTokens = u.outputTokens + :outputTokens, "
            + "u.totalTokens = u.totalTokens + :totalTokens, "
            + "u.costMicros = u.costMicros + :costMicros, "
            + "u.eventCount = u.eventCount + :eventCount, "
            + "u.updatedAt = :now "
            + "where u.userId = :userId and u.usageDay = :usageDay and u.model = :model")
    int increment(@Param("userId") Long userId,
                  @Param("usageDay") LocalDate usageDay,
                  @Param("model") String model,
                  @Param("inputTokens") long inputTokens,
                  @Param("cachedInputTokens") long cachedInputTokens,
                  @Param("outputTokens") long outputTokens,
                  @Param("totalTokens") long totalTokens,
                  @Param("costMicros") long costMicros,
                  @Param("eventCount") long eventCount,
                  @Param("now") LocalDateTime now);
}
