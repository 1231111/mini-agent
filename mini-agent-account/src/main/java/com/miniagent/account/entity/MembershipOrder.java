package com.miniagent.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 充值订单。
 *
 * <p>金额存"分"（整数）。{@code amount_cents} 一旦写过就不改 ——
 * 改价、退款都产生新记录或新状态，不覆写原值。对账时"这一笔当时收了多少"
 * 必须能从这一行读出来，而不是从当前价目表推。
 *
 * <p>{@code status} 的流转是单向的，不允许回退：{@code PENDING → PAID} 或
 * {@code PENDING → FAILED/CANCELED}，{@code PAID → REFUNDED}。
 * 支付回调可能重复投递，所以 {@code PAID → PAID} 必须被当作幂等成功而不是异常 ——
 * 见 {@link com.miniagent.account.service.MembershipService#markPaid}。
 */
@Entity
@Data
@Table(name = "membership_orders")
public class MembershipOrder extends BaseEntity {

    public enum Status {
        PENDING,
        PAID,
        FAILED,
        CANCELED,
        REFUNDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外单号。支付渠道回调时带的就是它，必须有唯一索引详见 migration。 */
    @Column(name = "order_no", nullable = false, unique = true, length = 64)
    private String orderNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plan_code", nullable = false, length = 32)
    private String planCode;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 8)
    private String currency = "CNY";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    /** 支付渠道标识：wechat / alipay / manual（人工开通）。 */
    @Column(length = 32)
    private String channel;

    /** 渠道侧的交易号。用于与渠道对账，不参与业务判断。 */
    @Column(name = "channel_txn_id", length = 128)
    private String channelTxnId;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;
}
