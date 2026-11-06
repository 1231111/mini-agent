package com.miniagent.memoryservice.evolution;

import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.memory.SecurityScanner;
import com.miniagent.memory.contract.MemoryApi.ChangeView;
import com.miniagent.memory.contract.MemoryApi.ItemHealth;
import com.miniagent.memory.contract.MemoryApi.ItemImport;
import com.miniagent.memory.contract.MemoryApi.ItemPage;
import com.miniagent.memory.contract.MemoryApi.ItemView;
import com.miniagent.memory.contract.MemoryApi.ItemWrite;
import com.miniagent.memory.contract.MemoryApi.RevisionView;
import com.miniagent.memory.contract.MemoryApi.ScopeType;
import com.miniagent.memory.contract.ServiceToken.Principal;
import com.miniagent.memoryservice.config.EvolutionProperties;
import com.miniagent.memoryservice.store.AuditStore;
import com.miniagent.memoryservice.store.ItemStore;
import com.miniagent.memoryservice.store.ItemStore.Item;
import com.miniagent.memoryservice.store.ItemStore.Origin;
import com.miniagent.memoryservice.store.ItemStore.Reason;
import com.miniagent.memoryservice.store.ItemStore.Revision;
import com.miniagent.memoryservice.store.JobQueue;
import com.miniagent.memoryservice.store.OutcomeStore;
import com.miniagent.memoryservice.store.ProjectAccess;
import com.miniagent.memoryservice.store.ProposalState;
import com.miniagent.memoryservice.store.ProposalStore;
import com.miniagent.memoryservice.store.ProposalStore.Proposal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 条目读写。用户写永远优先（设计文档 §6.3）：编辑、删除、锁定让未终结提案转 SUPERSEDED；
 * 回退已晋升的修订让对应提案转 ROLLED_BACK，候选哈希从此进黑名单。用户编辑只过安全扫描、不过进化闸门。
 */
@Service
public class ItemService {
    private static final int MAX_NAME = 255;
    private static final int MAX_PAGE = 100;
    private static final int HISTORY_LIMIT = 200;
    /** 用户回退已晋升修订时提案的结束原因；SLO「用户回退率」按它统计。 */
    public static final String USER_REVERT = "USER_REVERT";

    private final ItemStore items;
    private final ProposalStore proposals;
    private final JobQueue jobs;
    private final OutcomeStore outcomes;
    private final ProjectAccess projects;
    private final AuditStore audit;
    private final EvolutionPolicy policy;
    private final EvolutionProperties props;
    private final Clock clock;

    public ItemService(ItemStore items, ProposalStore proposals, JobQueue jobs,
                       OutcomeStore outcomes, ProjectAccess projects, AuditStore audit,
                       EvolutionPolicy policy, EvolutionProperties props, Clock clock) {
        this.items = items;
        this.proposals = proposals;
        this.jobs = jobs;
        this.outcomes = outcomes;
        this.projects = projects;
        this.audit = audit;
        this.policy = policy;
        this.props = props;
        this.clock = clock;
    }

    /** 同名即同一条目：内容变了就追加人工修订并激活，内容没变原样返回。 */
    @Transactional
    public ItemView upsert(Principal principal, ItemWrite write) {
        requireUser(principal);
        if (write == null || write.scopeType() == null) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "SOP 缺少归属域");
        }
        String scopeId = write.scopeType() == ScopeType.USER
                ? String.valueOf(principal.userId()) : write.scopeId();
        if (write.scopeType() == ScopeType.PROJECT
                && !projects.canRead(principal.tenantId(), scopeId, principal.userId())) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, "无权写入该项目的 SOP");
        }
        return write(principal.tenantId(), principal.userId(), write.scopeType(), scopeId,
                write, Origin.USER_EDIT, actor(principal));
    }

    /** 迁移导入：按 App 本地 id 幂等，已导入过的直接返回。 */
    @Transactional
    public ItemView importItem(ItemImport request) {
        ItemWrite write = request == null ? null : request.item();
        if (write == null || write.scopeType() == null || request.tenantId() == null) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "导入请求不完整");
        }
        if (write.legacyProcedureId() != null) {
            Item existing = items.findByLegacy(request.tenantId(), write.legacyProcedureId())
                    .orElse(null);
            if (existing != null) {
                return view(existing);
            }
        }
        String scopeId = write.scopeType() == ScopeType.USER
                ? String.valueOf(request.ownerUserId()) : write.scopeId();
        return write(request.tenantId(), request.ownerUserId(), write.scopeType(), scopeId,
                write, Origin.IMPORT, "import");
    }

    public ItemPage list(Principal principal, String name, Long cursor, int limit) {
        requireUser(principal);
        int size = Math.max(1, Math.min(MAX_PAGE, limit));
        List<Item> page = items.visible(principal.tenantId(), principal.userId(),
                projects.projectsOf(principal.tenantId(), principal.userId()), name, cursor, size);
        Long next = page.size() == size ? page.getLast().id() : null;
        return new ItemPage(page.stream().map(this::view).toList(), next);
    }

    public ItemView get(Principal principal, long id) {
        return view(requireVisible(principal, id));
    }

    public List<RevisionView> revisions(Principal principal, long id) {
        requireVisible(principal, id);
        return items.revisions(id).stream().map(ItemService::revisionView).toList();
    }

    public List<ChangeView> history(Principal principal, long id) {
        requireVisible(principal, id);
        return items.history(id, HISTORY_LIMIT).stream().map(ItemService::changeView).toList();
    }

    public List<ChangeView> changes(Principal principal, Instant since) {
        requireUser(principal);
        return items.changes(principal.tenantId(), principal.userId(),
                        projects.projectsOf(principal.tenantId(), principal.userId()),
                        since, HISTORY_LIMIT)
                .stream().map(ItemService::changeView).toList();
    }

    @Transactional
    public void delete(Principal principal, long id) {
        Item item = lockVisible(principal, id);
        if (!items.changeStatus(item, ItemStore.STATUS_DELETED, Reason.ARCHIVE, actor(principal),
                clock.instant())) {
            throw new BusinessException(ErrorCode.MEMORY_ITEM_VERSION_CONFLICT);
        }
        supersede(item, "条目被删除");
    }

    /** 回退到指定修订。若回退掉的是进化晋升的修订，对应提案转 ROLLED_BACK（原因 USER_REVERT）。 */
    @Transactional
    public ItemView revert(Principal principal, long id, int revisionNo) {
        Item item = lockVisible(principal, id);
        Revision target = items.revisionByNo(id, revisionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMORY_REVISION_NOT_FOUND));
        Instant now = clock.instant();
        Long previous = item.activeRevisionId();
        if (!items.activate(item, target.id(), Reason.USER_REVERT, null, actor(principal),
                policy.period(), now)) {
            throw new BusinessException(ErrorCode.MEMORY_ITEM_VERSION_CONFLICT);
        }
        Proposal open = proposals.openForItem(id).orElse(null);
        if (open != null && open.state() == ProposalState.MONITORING
                && Objects.equals(open.candidateRevisionId(), previous)) {
            closeAsUserRevert(open, now);
        } else {
            supersede(item, "用户回退到 r" + revisionNo);
        }
        if (previous != null) {
            proposals.promotedAs(previous, ProposalState.STABLE)
                    .ifPresent(stable -> closeAsUserRevert(stable, now));
        }
        return view(items.find(id).orElseThrow());
    }

    /** 恢复被归档的条目（变更列表里的一键恢复）。 */
    @Transactional
    public ItemView restore(Principal principal, long id) {
        Item item = lockVisible(principal, id);
        if (!ItemStore.STATUS_ARCHIVED.equals(item.status())) {
            return view(item);
        }
        if (!items.changeStatus(item, ItemStore.STATUS_ACTIVE, Reason.RESTORE, actor(principal),
                clock.instant())) {
            throw new BusinessException(ErrorCode.MEMORY_ITEM_VERSION_CONFLICT);
        }
        return view(items.find(id).orElseThrow());
    }

    @Transactional
    public ItemView lock(Principal principal, long id, boolean locked) {
        Item item = lockVisible(principal, id);
        items.setLocked(id, locked, clock.instant());
        if (locked) {
            supersede(item, "用户锁定进化");
        }
        audit.audit(item.tenantId(), actor(principal), locked ? "ITEM_LOCKED" : "ITEM_UNLOCKED",
                "ITEM", id, null, clock.instant());
        return view(items.find(id).orElseThrow());
    }

    public ItemHealth health(Principal principal, long id) {
        Item item = requireVisible(principal, id);
        Instant now = clock.instant();
        Instant recentFrom = now.minus(Duration.ofDays(props.getHealth().getRecentDays()));
        Instant baselineFrom = recentFrom.minus(
                Duration.ofDays(props.getHealth().getBaselineDays()));
        OutcomeStore.Counts recent = outcomes.itemStats(id, recentFrom, now);
        OutcomeStore.Counts baseline = outcomes.itemStats(id, baselineFrom, recentFrom);
        int revisionNo = items.revision(item.activeRevisionId()).map(Revision::revisionNo).orElse(0);
        return new ItemHealth(id, revisionNo, recent.tasks(), recent.successes(),
                baseline.tasks(), baseline.successes(), item.health());
    }

    /** 用户注销：删其 USER 域条目、相关提案 / 回归案例 / 作业与其全部结果事件。 */
    @Transactional
    public int eraseUser(String tenantId, long userId) {
        List<Long> erased = items.deleteUserScope(tenantId, userId);
        Instant now = clock.instant();
        for (Long itemId : erased) {
            proposals.openForItem(itemId).ifPresent(p -> jobs.cancelForProposal(p.id(), now));
        }
        proposals.deleteForItems(erased);
        audit.deleteRegressionForItems(erased);
        outcomes.deleteUser(tenantId, userId);
        audit.audit(tenantId, "subject-erasure", "SUBJECT_ERASED", "USER", userId,
                Map.of("items", erased.size()), now);
        return erased.size();
    }

    /** 当前用户能否看到该条目（USER 域本人，PROJECT 域有授权）。 */
    public Item requireVisible(Principal principal, long id) {
        requireUser(principal);
        Item item = items.find(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMORY_NOT_FOUND));
        if (!visibleTo(item, principal.tenantId(), principal.userId())
                || ItemStore.STATUS_DELETED.equals(item.status())) {
            throw new BusinessException(ErrorCode.MEMORY_NOT_FOUND);
        }
        return item;
    }

    public boolean visibleTo(Item item, String tenantId, long userId) {
        if (!item.tenantId().equals(tenantId)) {
            return false;
        }
        return item.scopeType() == ScopeType.USER
                ? item.scopeId().equals(String.valueOf(userId))
                : projects.canRead(tenantId, item.scopeId(), userId);
    }

    public ItemView view(Item item) {
        Revision active = items.revision(item.activeRevisionId()).orElse(null);
        return new ItemView(item.id(), item.scopeType(), item.scopeId(), item.name(),
                item.status(), active == null ? 0 : active.revisionNo(),
                active == null ? null : active.content(), item.activationVersion(),
                item.evolutionLocked(), item.health(), item.updatedAt());
    }

    /** 人工改动让在途提案的基线失效：未终结提案一律 SUPERSEDED，作业撤销。 */
    public void supersede(Item item, String reason) {
        proposals.openForItem(item.id()).ifPresent(open -> {
            if (proposals.finish(open.id(), open.state(), ProposalState.SUPERSEDED, reason, null,
                    clock.instant())) {
                jobs.cancelForProposal(open.id(), clock.instant());
            }
        });
    }

    private void closeAsUserRevert(Proposal p, Instant now) {
        if (proposals.finish(p.id(), p.state(), ProposalState.ROLLED_BACK, USER_REVERT, null,
                now)) {
            jobs.cancelForProposal(p.id(), now);
        }
    }

    private ItemView write(String tenantId, long ownerUserId, ScopeType scopeType, String scopeId,
                           ItemWrite write, Origin origin, String actor) {
        validate(write, scopeId);
        if (SecurityScanner.scan(write.name() + "\n" + write.content().text()) != null) {
            throw new BusinessException(ErrorCode.MEMORY_CONTENT_REJECTED);
        }
        Instant now = clock.instant();
        Item existing = items.findByName(tenantId, scopeType, scopeId, write.name()).orElse(null);
        if (existing == null) {
            try {
                long id = items.insertItem(tenantId, scopeType, scopeId, ownerUserId, write.name(),
                        write.legacyProcedureId(), now);
                existing = items.find(id).orElseThrow();
            } catch (DuplicateKeyException concurrentCreate) {
                throw new BusinessException(ErrorCode.MEMORY_ITEM_VERSION_CONFLICT,
                        "同名 SOP 正被并发创建，请重试");
            }
        }
        Item item = items.lock(existing.id()).orElseThrow();
        Revision active = items.revision(item.activeRevisionId()).orElse(null);
        if (active != null && active.contentHash().equals(write.content().hash())
                && ItemStore.STATUS_ACTIVE.equals(item.status())) {
            return view(item);
        }
        Revision revision = items.insertRevision(item.id(), item.activeRevisionId(), origin,
                write.content(), null, actor, now);
        Reason reason = active == null ? (origin == Origin.IMPORT ? Reason.IMPORT : Reason.CREATE)
                : Reason.USER_EDIT;
        if (!items.activate(item, revision.id(), reason, null, actor, policy.period(), now)) {
            throw new BusinessException(ErrorCode.MEMORY_ITEM_VERSION_CONFLICT);
        }
        if (active != null) {
            supersede(item, "用户编辑了 SOP");
        }
        audit.audit(tenantId, actor, "ITEM_" + reason.name(), "ITEM", item.id(),
                Map.of("revisionNo", revision.revisionNo()), now);
        return view(items.find(item.id()).orElseThrow());
    }

    private Item lockVisible(Principal principal, long id) {
        requireVisible(principal, id);
        return items.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.MEMORY_NOT_FOUND));
    }

    private static void validate(ItemWrite write, String scopeId) {
        if (scopeId == null || scopeId.isBlank()) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "SOP 缺少归属域");
        }
        if (write.name() == null || write.name().isBlank() || write.name().length() > MAX_NAME) {
            throw new BusinessException(ErrorCode.MEMORY_EMPTY_CONTENT, "SOP 名称为空或过长");
        }
        if (write.content() == null || write.content().steps().isEmpty()) {
            throw new BusinessException(ErrorCode.MEMORY_EMPTY_CONTENT, "SOP 步骤不能为空");
        }
    }

    static void requireUser(Principal principal) {
        if (principal == null || !principal.hasUser()) {
            throw new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED, "缺少用户主体");
        }
    }

    static String actor(Principal principal) {
        return "user:" + principal.userId();
    }

    static RevisionView revisionView(Revision r) {
        return new RevisionView(r.id(), r.revisionNo(), r.origin(), r.content(), r.contentHash(),
                r.proposalId(), r.createdBy(), r.createdAt());
    }

    static ChangeView changeView(ItemStore.Activation a) {
        return new ChangeView(a.id(), a.itemId(), a.itemName(), a.fromRevisionNo(),
                a.toRevisionNo(), a.reason(), a.proposalId(), a.actor(), a.createdAt());
    }
}
