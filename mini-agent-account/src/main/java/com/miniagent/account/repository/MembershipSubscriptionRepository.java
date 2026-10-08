package com.miniagent.account.repository;

import com.miniagent.account.entity.MembershipSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
