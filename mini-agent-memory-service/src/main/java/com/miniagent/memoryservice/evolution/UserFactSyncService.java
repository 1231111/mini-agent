package com.miniagent.memoryservice.evolution;

import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.memory.contract.MemoryApi.UserFactMutation;
import com.miniagent.memory.contract.MemoryApi.UserFactSync;
import com.miniagent.memory.contract.MemoryApi.UserFactSyncResult;
import com.miniagent.memory.contract.MemoryApi.UserFactView;
import com.miniagent.memory.contract.ServiceToken.Principal;
import com.miniagent.memoryservice.store.UserFactStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned, idempotent cloud snapshot synchronization for authenticated USER facts. */
@Service
public class UserFactSyncService {
    public static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_SUBJECT_LENGTH = 256;
    private static final int MAX_PREDICATE_LENGTH = 256;
    private static final int MAX_VALUE_LENGTH = 8192;

    private final UserFactStore facts;
    private final Clock clock;

    public UserFactSyncService(UserFactStore facts, Clock clock) {
        this.facts = facts;
        this.clock = clock;
    }

    public List<UserFactView> list(Principal principal) {
        requireUser(principal);
        return facts.list(principal.tenantId(), principal.userId());
    }

    @Transactional
    public UserFactSyncResult synchronize(Principal principal, UserFactSync request) {
        requireUser(principal);
        if (request == null || request.facts() == null
                || request.facts().size() > MAX_BATCH_SIZE) {
            throw invalid("user fact batch must contain at most " + MAX_BATCH_SIZE + " entries");
        }

        Set<String> keys = new HashSet<>();
        for (UserFactMutation mutation : request.facts()) {
            validate(mutation, clock.instant());
            if (!keys.add(mutation.subject() + "\u0000" + mutation.predicate())) {
                throw invalid("a user fact key may occur only once per batch");
            }
        }

        Instant now = clock.instant();
        List<UserFactView> canonical = new ArrayList<>(request.facts().size());
        for (UserFactMutation mutation : request.facts()) {
            facts.upsert(principal.tenantId(), principal.userId(), mutation, now);
            canonical.add(facts.find(principal.tenantId(), principal.userId(),
                    mutation.subject(), mutation.predicate()).orElseThrow(() ->
                    new IllegalStateException("user fact upsert returned no canonical row")));
        }
        return new UserFactSyncResult(List.copyOf(canonical));
    }

    private static void requireUser(Principal principal) {
        if (principal == null || !principal.hasUser()
                || !"app".equals(principal.role())) {
            throw new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED,
                    "a user-bound app token is required for user fact synchronization");
        }
    }

    private static void validate(UserFactMutation mutation, Instant now) {
        if (mutation == null || blankOrLong(mutation.subject(), MAX_SUBJECT_LENGTH)
                || blankOrLong(mutation.predicate(), MAX_PREDICATE_LENGTH)
                || mutation.objectValue() == null
                || mutation.objectValue().length() > MAX_VALUE_LENGTH
                || (!mutation.deleted() && mutation.objectValue().isBlank())
                || mutation.sourceUpdatedAt() == null
                || mutation.sourceUpdatedAt().isAfter(now.plusSeconds(300))) {
            throw invalid("invalid user fact mutation");
        }
    }

    private static boolean blankOrLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max
                || !value.equals(value.trim());
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, message);
    }
}
