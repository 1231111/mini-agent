package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.config.ConditionalOnCloudMemory;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentSemanticFactEntity;
import com.miniagent.agent.memory.repository.AgentSemanticFactRepository;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.config.entity.User;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.memory.MemoryManager;
import com.miniagent.memory.model.MemoryScope;
import com.miniagent.memory.model.SemanticFact;
import com.miniagent.memory.contract.MemoryApi.UserFactMutation;
import com.miniagent.memory.contract.MemoryApi.UserFactView;
import com.miniagent.memory.contract.MemoryApi.UserFactSync;
import com.miniagent.memory.contract.MemoryApi.UserFactSyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 双向同步账号自己的USER 域语义事实。云端不可用时保留本地数据，下一轮完整快照会重试。
 *
 * <p>同步身份由 account 签发的 memory token 决定；请求体不包含 tenant/user ID。
 * USER 域事实只做带时间戳的云端版本同步，不交给 SOP 演进器改写。</p>
 */
@Component
@ConditionalOnCloudMemory
public class UserMemoryCloudSync {
    private static final Logger log = LoggerFactory.getLogger(UserMemoryCloudSync.class);
    private static final int BATCH_SIZE = 100;
    private static final ZoneId DB_ZONE = ZoneId.systemDefault();

    private final AgentSemanticFactRepository facts;
    private final UserRepository users;
    private final CloudMemoryClient cloud;
    private final MemoryManager memory;

    public UserMemoryCloudSync(AgentSemanticFactRepository facts, UserRepository users,
                                   CloudMemoryClient cloud, MemoryManager memory) {
        this.facts = facts;
        this.users = users;
        this.cloud = cloud;
        this.memory = memory;
    }

    /**
     * Periodic full-owner reconciliation is the durable retry mechanism: local facts are the
     * outbox, and tombstones remain in the fact history after remove/supersede.
     */
    @Scheduled(fixedDelayString = "${agent.memory.service.sync-interval-ms:15000}")
    public void synchronizeLinkedUsers() {
        for (User user : users.findAll()) {
            if (user == null || !user.isEnabled() || user.getId() == null
                    || user.getTenantId() == null || blank(user.getExternalId())
                    || blank(user.getCloudTenantId())
                    || users.findMemoryAccessTokenById(user.getId()).filter(v -> !v.isBlank()).isEmpty()) {
                continue;
            }
            try {
                synchronizeUser(user.getId(), String.valueOf(user.getTenantId()));
            } catch (RuntimeException failure) {
                // Isolate account failures; a revoked token or one malformed profile must not
                // prevent other local accounts from reconciling during this scheduled run.
                log.warn("用户偏好云同步失败 userId={} tenant={}: {}",
                        user.getId(), user.getTenantId(), failure.getMessage());
            }
        }
    }

    /** Public for login/task hooks and focused tests; safe to retry. */
    public void synchronizeUser(Long userId, String localTenantId) {
        if (userId == null || userId <= 0 || blank(localTenantId)) {
            throw new IllegalArgumentException("有效的本地用户与租户是云同步的必要条件");
        }
        try (OwnerContext.Scope ignored = OwnerContext.bind(new OwnerContext(userId, localTenantId))) {
            List<AgentSemanticFactEntity> history = facts.findByTenantIdAndScopeTypeAndScopeId(
                    localTenantId, AgentMemoryEntryEntity.ScopeType.USER, String.valueOf(userId));
            Map<FactKey, AgentSemanticFactEntity> latest = latestFacts(history);
            List<UserFactView> snapshot = cloud.userFacts();
            Map<FactKey, UserFactView> remote = indexRemote(snapshot);
            List<UserFactMutation> pending = latest.entrySet().stream()
                    .filter(entry -> needsPush(entry.getValue(), remote.get(entry.getKey())))
                    .map(entry -> toMutation(entry.getValue()))
                    .toList();

            for (int start = 0; start < pending.size(); start += BATCH_SIZE) {
                List<UserFactMutation> batch = pending.subList(start,
                        Math.min(start + BATCH_SIZE, pending.size()));
                UserFactSyncResult result = cloud.syncUserFacts(new UserFactSync(batch));
                if (result != null && result.facts() != null) {
                    for (UserFactView canonical : result.facts()) {
                        reconcileOne(userId, localTenantId, latest, canonical);
                    }
                }
            }

            // Pull cloud-only facts and cloud writes newer than the local version. This also
            // bootstraps a second device whose local database has not seen a USER fact yet.
            for (UserFactView item : snapshot == null ? List.<UserFactView>of() : snapshot) {
                reconcileOne(userId, localTenantId, latest, item);
            }
        }
    }

    private void reconcileOne(Long userId, String localTenantId,
                              Map<FactKey, AgentSemanticFactEntity> local,
                              UserFactView remote) {
        if (remote == null || blank(remote.subject()) || blank(remote.predicate())
                || remote.sourceUpdatedAt() == null) {
            return;
        }
        FactKey key = new FactKey(remote.subject(), remote.predicate());
        AgentSemanticFactEntity current = local.get(key);
        long localVersion = current == null ? Long.MIN_VALUE : sourceTimestamp(current);
        long cloudVersion = remote.sourceUpdatedAt().toEpochMilli();
        boolean sameValue = current != null
                && Objects.equals(current.getObjectValue(), remote.objectValue())
                && isDeleted(current) == remote.deleted();
        if (sameValue) {
            // Preserve the server's source timestamp on imports. Without this, a client that
            // materializes an older cloud fact would stamp it with its local wall clock and
            // repeatedly advertise a false, newer revision to every other device.
            if (cloudVersion >= localVersion) {
                markSynced(current, remote);
            }
            return;
        }
        if (cloudVersion < localVersion) return;

        if (remote.deleted()) {
            if (current != null && !isDeleted(current)) {
                memory.removeFact(localTenantId, MemoryScope.ScopeType.USER.name(),
                        String.valueOf(userId), current.getId());
                AgentSemanticFactEntity removed = facts.findByTenantIdAndScopeTypeAndScopeId(
                                localTenantId, AgentMemoryEntryEntity.ScopeType.USER,
                                String.valueOf(userId)).stream()
                        .filter(row -> Objects.equals(row.getSubject(), remote.subject())
                                && Objects.equals(row.getPredicate(), remote.predicate())
                                && row.getSupersededBy() == null)
                        .max(LOCAL_VERSION_ORDER).orElse(null);
                if (removed != null) {
                    markSynced(removed, remote);
                    local.put(key, removed);
                } else {
                    local.remove(key);
                }
            }
            return;
        }
        if (blank(remote.objectValue())) {
            return;
        }

        SemanticFact imported = new SemanticFact();
        imported.setTenantId(localTenantId);
        imported.setScope(MemoryScope.ofUser(localTenantId, String.valueOf(userId)));
        imported.setSubject(remote.subject());
        imported.setPredicate(remote.predicate());
        imported.setObjectValue(remote.objectValue());
        imported.setSource("CLOUD_SYNC");
        memory.writeFact(imported);

        // Refresh the map so a following duplicate row in this same response cannot reapply
        // the same version. The persisted row is re-read from the repository.
        AgentSemanticFactEntity saved = facts.findByTenantIdAndScopeTypeAndScopeId(
                        localTenantId, AgentMemoryEntryEntity.ScopeType.USER, String.valueOf(userId))
                .stream().filter(row -> Objects.equals(row.getSubject(), remote.subject())
                        && Objects.equals(row.getPredicate(), remote.predicate())
                        && !isDeleted(row))
                .max(LOCAL_VERSION_ORDER).orElse(null);
        if (saved != null) {
            markSynced(saved, remote);
            local.put(key, saved);
        }
    }

    private AgentSemanticFactEntity markSynced(AgentSemanticFactEntity row, UserFactView remote) {
        row.setSource("CLOUD_SYNC");
        LocalDateTime sourceAt = LocalDateTime.ofInstant(remote.sourceUpdatedAt(), DB_ZONE);
        if (remote.deleted()) row.setValidTo(sourceAt);
        else row.setValidFrom(sourceAt);
        return facts.save(row);
    }

    private static Map<FactKey, AgentSemanticFactEntity> latestFacts(
            List<AgentSemanticFactEntity> history) {
        Map<FactKey, AgentSemanticFactEntity> latest = new LinkedHashMap<>();
        if (history == null) {
            return latest;
        }
        for (AgentSemanticFactEntity row : history) {
            // A superseded row is historical evidence, never the current value or a delete
            // tombstone. Its validTo is set during ordinary replacement and may be later than
            // the successor's createdAt, so timestamp ordering alone can export a false delete.
            if (row == null || row.getSupersededBy() != null
                    || blank(row.getSubject()) || blank(row.getPredicate())) {
                continue;
            }
            FactKey key = new FactKey(row.getSubject(), row.getPredicate());
            AgentSemanticFactEntity previous = latest.get(key);
            if (previous == null || LOCAL_VERSION_ORDER.compare(row, previous) > 0) {
                latest.put(key, row);
            }
        }
        return latest;
    }

    private static UserFactMutation toMutation(AgentSemanticFactEntity row) {
        Instant updated = timestamp(row);
        return new UserFactMutation(row.getSubject(), row.getPredicate(), row.getObjectValue(),
                isDeleted(row), updated);
    }

    private static Map<FactKey, UserFactView> indexRemote(List<UserFactView> snapshot) {
        Map<FactKey, UserFactView> indexed = new LinkedHashMap<>();
        if (snapshot == null) return indexed;
        for (UserFactView row : snapshot) {
            if (row == null || blank(row.subject()) || blank(row.predicate())
                    || row.sourceUpdatedAt() == null) continue;
            FactKey key = new FactKey(row.subject(), row.predicate());
            UserFactView prior = indexed.get(key);
            if (prior == null || compareRemote(row, prior) > 0) indexed.put(key, row);
        }
        return indexed;
    }

    private static int compareRemote(UserFactView left, UserFactView right) {
        int timestamp = left.sourceUpdatedAt().compareTo(right.sourceUpdatedAt());
        return timestamp != 0 ? timestamp : Long.compare(left.revision(), right.revision());
    }

    private static boolean needsPush(AgentSemanticFactEntity local, UserFactView remote) {
        if (remote == null) return true;
        int comparison = Long.compare(sourceTimestamp(local), remote.sourceUpdatedAt().toEpochMilli());
        if (comparison != 0) return comparison > 0;
        // The server is authoritative for a conflicting equal timestamp; its revision resolves
        // the tie, so equal-time local copies should not overwrite it again on every poll.
        return false;
    }

    private static final Comparator<AgentSemanticFactEntity> LOCAL_VERSION_ORDER =
            Comparator.comparing(UserMemoryCloudSync::timestamp)
                    .thenComparing(AgentSemanticFactEntity::getId,
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    private static long sourceTimestamp(AgentSemanticFactEntity row) {
        return timestamp(row).toEpochMilli();
    }

    private static Instant timestamp(AgentSemanticFactEntity row) {
        LocalDateTime value;
        if (isDeleted(row) && row.getValidTo() != null) {
            value = row.getValidTo();
        } else if ("CLOUD_SYNC".equals(row.getSource()) && row.getValidFrom() != null) {
            value = row.getValidFrom();
        } else {
            value = row.getUpdatedAt() != null ? row.getUpdatedAt() : row.getCreatedAt();
        }
        return value == null ? Instant.EPOCH : value.atZone(DB_ZONE).toInstant();
    }

    private static boolean isDeleted(AgentSemanticFactEntity row) {
        return row.getValidTo() != null && row.getSupersededBy() == null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record FactKey(String subject, String predicate) { }
}
