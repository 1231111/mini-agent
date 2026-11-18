package com.miniagent.account.repository;

import com.miniagent.account.entity.MembershipSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MembershipSubscriptionRepository extends JpaRepository<MembershipSubscription, Long> {

    /**
     * 取当前生效的订阅。
     *
     * <p>用 {@code findFirstBy...OrderByIdDesc} 而不是 {@code findOneBy...}：
     * 万一历史数据里存在多条 ACTIVE（例如人工直接改库留下的），
     * {@code findOneBy} 会抛 {@code IncorrectResultSizeDataAccessException}，
     * 把一次"查我的会员等级"变成 500。取最新一条是能自愈的行为。
     */
    Optional<MembershipSubscription> findFirstByUserIdAndStatusOrderByIdDesc(
            Long userId, MembershipSubscription.Status status);

    List<MembershipSubscription> findByUserIdOrderByIdDesc(Long userId);

    /** 生效新订阅前把旧的置为 SUPERSEDED。 */
    List<MembershipSubscription> findByUserIdAndStatus(Long userId, MembershipSubscription.Status status);

    /** Mark finite active subscriptions expired at the inclusive expiry boundary. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update MembershipSubscription s set s.status = :expired "
            + "where s.userId = :userId and s.status = :active "
            + "and s.expireAt is not null and s.expireAt <= :now")
    int expireActiveSubscriptions(@Param("userId") Long userId,
                                  @Param("active") MembershipSubscription.Status active,
                                  @Param("expired") MembershipSubscription.Status expired,
                                  @Param("now") LocalDateTime now);
}
