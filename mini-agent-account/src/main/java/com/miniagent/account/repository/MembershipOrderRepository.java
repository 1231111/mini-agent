package com.miniagent.account.repository;

import com.miniagent.account.entity.MembershipOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MembershipOrderRepository extends JpaRepository<MembershipOrder, Long> {

    Optional<MembershipOrder> findByOrderNo(String orderNo);

    /**
     * 支付回调处理用。同一笔订单可能被渠道重复投递，也可能两次回调真的是并发到达 ——
     * 后者必须串行化，否则两条线程都读到 {@code PENDING}、都去开订阅，
     * 结果是同一笔钱开了两次会员。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from MembershipOrder o where o.orderNo = :orderNo")
    Optional<MembershipOrder> findByOrderNoForUpdate(@Param("orderNo") String orderNo);

    List<MembershipOrder> findByUserIdOrderByIdDesc(Long userId);
}
