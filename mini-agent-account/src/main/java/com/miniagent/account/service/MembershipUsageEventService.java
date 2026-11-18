package com.miniagent.account.service;

import com.miniagent.account.entity.MembershipUsageEvent;
import com.miniagent.account.repository.MembershipUsageEventRepository;
import com.miniagent.account.repository.UserRepository;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** Persists account-service usage reports, with optional database-backed idempotency. */
@Service
public class MembershipUsageEventService {

    private static final int EVENT_ID_MAX_LENGTH = 128;

    private final MembershipUsageEventRepository usageEvents;
    private final UserRepository users;
    private final MembershipService membership;
    private final TransactionTemplate insertTransaction;

    @Value("${agent.quota.zone-id:Asia/Shanghai}")
    private String quotaZoneId;

    @Autowired
    public MembershipUsageEventService(MembershipUsageEventRepository usageEvents,
                                       UserRepository users,
                                       MembershipService membership,
                                       PlatformTransactionManager transactionManager) {
        this.usageEvents = usageEvents;
        this.users = users;
        this.membership = membership;
        this.insertTransaction = new TransactionTemplate(transactionManager);
        this.insertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records one report. A non-null event id is unique per user. The database
     * constraint arbitrates races; a losing concurrent insert is treated as an
     * already accepted duplicate after its failed transaction has rolled back.
     * Legacy callers may omit the id and retain the previous append-only behavior.
     *
     * @return true when a row was inserted, false when the keyed event was already recorded
     */
    public boolean recordUsage(Long userId, long inputTokens, long outputTokens,
                               int llmCalls, String eventId) {
        if (userId == null || !users.existsById(userId)) {
            throw new BusinessException(ErrorCode.MEMBER_USER_NOT_FOUND, "用户不存在");
        }

        String normalizedEventId = normalizeEventId(eventId);
        if (normalizedEventId != null
                && usageEvents.existsByUserIdAndEventId(userId, normalizedEventId)) {
            return false;
        }

        long input = Math.max(0, inputTokens);
        long output = Math.max(0, outputTokens);
        if (input == 0 && output == 0 && llmCalls <= 0) {
            return false;
        }

        String planCode = membership.current(userId)
                .map(MembershipService.MembershipView::planCode)
                .orElse("");
        if (planCode.length() > 32) {
            planCode = planCode.substring(0, 32);
        }

        MembershipUsageEvent event = new MembershipUsageEvent();
        event.setUserId(userId);
        event.setEventId(normalizedEventId);
        event.setPlanCode(planCode);
        event.setInputTokens(input);
        event.setOutputTokens(output);
        event.setReportedAt(LocalDateTime.now(ZoneId.of(quotaZoneId)));

        try {
            insertTransaction.executeWithoutResult(status -> usageEvents.saveAndFlush(event));
            return true;
        } catch (DataIntegrityViolationException duplicateOrInvalidWrite) {
            // The failed insert ran in its own transaction, so it is safe to query
            // after rollback. Only swallow a conflict for this exact idempotency key.
            if (normalizedEventId != null
                    && usageEvents.existsByUserIdAndEventId(userId, normalizedEventId)) {
                return false;
            }
            throw duplicateOrInvalidWrite;
        }
    }

    private static String normalizeEventId(String eventId) {
        if (eventId == null) {
            return null;
        }
        String normalized = eventId.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > EVENT_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("eventId must be at most " + EVENT_ID_MAX_LENGTH + " characters");
        }
        return normalized;
    }
}
