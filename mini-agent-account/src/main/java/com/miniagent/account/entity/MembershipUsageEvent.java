package com.miniagent.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次 LLM 调用的用量。一行是一次上报，不是某一天的合计。 */
@Entity
@Data
@Table(name = "membership_usage_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_usage_user_event", columnNames = {"user_id", "event_id"})
})
public class MembershipUsageEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Optional client-generated idempotency key; null preserves the legacy report behavior. */
    @Column(name = "event_id", length = 128)
    private String eventId;

    @Column(name = "plan_code", nullable = false, length = 32)
    private String planCode;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    /** 按配额时区记下的上报时间，用来切「今天」。 */
    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt;
}
