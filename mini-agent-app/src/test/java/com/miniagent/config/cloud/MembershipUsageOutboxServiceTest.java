package com.miniagent.config.cloud;

import com.miniagent.config.entity.MembershipUsageOutbox;
import com.miniagent.config.repository.MembershipUsageOutboxRepository;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.service.DatabaseConversationStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MembershipUsageOutboxServiceTest {

    @Test
    void usageDeliveryRemainsRetryableAfterTwentyFailures() {
        MembershipUsageOutboxRepository outbox = mock(MembershipUsageOutboxRepository.class);
        DatabaseConversationStore conversations = mock(DatabaseConversationStore.class);
        UserRepository users = mock(UserRepository.class);
        CloudMembershipClient membership = mock(CloudMembershipClient.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        when(membership.available()).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("account temporarily unavailable"))
                .when(membership).reportUsage(eq("usage-event"), eq(42001L), eq(4L), eq(6L), eq(1));

        MembershipUsageOutbox row = new MembershipUsageOutbox();
        ReflectionTestUtils.setField(row, "id", 9L);
        row.setEventId("usage-event");
        row.setCloudUserId(42001L);
        row.setInputTokens(4L);
        row.setOutputTokens(6L);
        row.setAttempts(20);
        row.setStatus(MembershipUsageOutbox.Status.PENDING);
        row.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        when(outbox.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                eq(MembershipUsageOutbox.Status.PENDING), any())).thenReturn(List.of(row));
        when(outbox.claim(eq(9L), eq(MembershipUsageOutbox.Status.PENDING),
                eq(MembershipUsageOutbox.Status.PROCESSING), any(), any(), anyString())).thenReturn(1);
        when(outbox.findById(9L)).thenReturn(Optional.of(row));

        MembershipUsageOutboxService service = new MembershipUsageOutboxService(
                outbox, conversations, users, membership, transactions);
        ReflectionTestUtils.setField(service, "leaseSeconds", 120L);

        service.drain();

        verify(outbox).failAttempt(eq(9L), eq(MembershipUsageOutbox.Status.PROCESSING),
                eq(MembershipUsageOutbox.Status.PENDING), eq(21), any(), any(), anyString());
    }
}
