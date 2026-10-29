package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.memory.model.Procedure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * SOP 写入的唯一入口：按身份 upsert，内容变了原地升版。
 *
 * <p><b>之前是什么样</b>：{@code writeProcedure} 无条件 new 一个实体再 save，
 * {@code procedure_version} 又恒为 1。于是同一个名字写两次就是两行 {@code (name, version=1)} ——
 * 规划器把两行当成两条不同的 Procedure 列进提示，节点结算的计数只落到其中一行。
 * 用户看到的那条 SOP 成功率永远不动，闭环没有反馈信号。</p>
 *
 * <p><b>现在的规则</b>（identity 与 content 是两个概念）：</p>
 * <ul>
 *   <li>identity = {@code (tenant_id, scope_type, scope_id, 归一化后的 name)}，一个身份只有一行。
 *       归一化是 {@code trim + lowercase(Locale.ROOT)}，在 Java 侧做，不依赖库的排序规则。</li>
 *   <li>content = description + preconditions + steps + successConditions。
 *       与库里一致时是**幂等写**：不升版本、不动计数。</li>
 *   <li>content 变了就原地升版：同一 id，{@code procedure_version + 1}，本版计数清零。
 *       旧一版的证据按版本号留在 {@code agent_procedure_applications}。</li>
 *   <li>{@code importance} 不在 content 里。它是排序权重，改它不该把这一版的成功率清掉，
 *       所以走单独的元数据更新。</li>
 *   <li>被归档（{@code ARCHIVED}）的同名 SOP 不新建行，直接复用并升版复活 ——
 *       {@code ux_ap_identity} 不允许同名两行，「删掉再加一遍」必须落回同一行。</li>
 * </ul>
 *
 * <p><b>并发怎么处理</b>：首次写入靠唯一键裁决 —— 插入放在
 * {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW} 的独立事务里，撞唯一键只回滚这个内层事务，
 * 外层转成「去升版那一行」。已有行靠 {@code procedure_version} 做 CAS，最多重试
 * {@value #MAX_UPGRADE_ATTEMPTS} 次；每次都先重读再判断内容，别人写入了完全一样的内容就直接幂等返回。
 * 重试用尽才报冲突，不静默丢写入。</p>
 */
@Service
public class ProcedureUpsertService {

    private static final Logger log = LoggerFactory.getLogger(ProcedureUpsertService.class);

    /** CAS 重试上限。冲突到这个次数说明同一身份被高频并发改写，交给调用方重试比无限自旋安全。 */
    static final int MAX_UPGRADE_ATTEMPTS = 3;

    /**
     * 同一身份下挑「当前那一行」的顺序：先 ACTIVE，再版本高，最后 id 大。
     *
     * <p>正常情况下唯一键保证每组只有一个候选，这个顺序只是为了让「迁移没跑到、历史重复行还在」
     * 的库也挑到最新那一行，而不是随机。<b>不删多余行</b>：写路径按名字匹配就自动删除，
     * 误判一次就是数据丢失，修复重复行只放在迁移里做。</p>
     */
    private static final Comparator<AgentProcedureEntity> CANONICAL_ORDER =
            Comparator.comparingInt((AgentProcedureEntity p) ->
                            p.getStatus() == AgentMemoryEntryEntity.Status.ACTIVE ? 0 : 1)
                    .thenComparing(AgentProcedureEntity::getProcedureVersion,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(AgentProcedureEntity::getId,
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    private final AgentProcedureRepository procedures;
    private final ObjectMapper objectMapper;
    private final ProcedureRevisionService revisionService;
    private final TransactionTemplate requiresNew;

    /**
     * 远端投影登记。local 模式下它是 no-op，字段可为 null（单元测试直接 new 本类）。
     *
     * <p>用字段注入而不是构造器参数，是为了不改动既有构造签名——本类有直接 {@code new}
     * 的持久化测试，加参数会让它们全部失效，而它们验证的正是升版 CAS 这类核心行为。</p>
     */
    @Autowired(required = false)
    private ProcedureDefinitionSyncService definitionSyncService;

    public ProcedureUpsertService(AgentProcedureRepository procedures, ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager, ProcedureRevisionService revisionService) {
        this.procedures = procedures;
        this.objectMapper = objectMapper;
        this.revisionService = revisionService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 写入结果。{@code created} 区分「新建一行」与「升版/幂等命中已有行」。 */
    public record Upserted(AgentProcedureEntity entity, boolean created) {
    }

    public Upserted upsert(Procedure incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException("procedure required");
        }
        if (incoming.getTenantId() == null || incoming.getTenantId().isBlank()
                || incoming.getScope() == null || incoming.getScope().scopeId() == null) {
            throw new BusinessException(ErrorCode.MEMORY_WRITE_FAILED, "SOP 缺少租户或归属域");
        }
        String key = identityKey(incoming.getName());
        if (key.isEmpty()) {
            throw new BusinessException(ErrorCode.MEMORY_EMPTY_CONTENT, "SOP 名称不能为空");
        }

        AgentMemoryEntryEntity.ScopeType scopeType =
                AgentMemoryEntryEntity.ScopeType.valueOf(incoming.getScope().scopeType().name());
        String scopeId = incoming.getScope().scopeId();

        AgentProcedureEntity current = resolveCanonical(incoming.getTenantId(), scopeType, scopeId, key);
        if (current == null) {
            AgentProcedureEntity inserted = insertIfAbsent(incoming, scopeType, scopeId);
            if (inserted != null) {
                return new Upserted(inserted, true);
            }
            // 唯一键拒绝：并发的另一个写入者先落地了同一身份，转入升版路径。
            current = resolveCanonical(incoming.getTenantId(), scopeType, scopeId, key);
            if (current == null) {
                throw new BusinessException(ErrorCode.MEMORY_WRITE_FAILED,
                        "SOP 写入冲突，请重试");
            }
        }
        return new Upserted(applyContent(current, incoming, scopeType, scopeId, key), false);
    }

    /**
     * 身份归一化。所有 identity 判定都必须走这里，不允许任何一处直接比原始 name。
     */
    static String identityKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 落库的名字去首尾空白，但保留大小写：名字是给人看的，不该因为身份判定不区分大小写
     * 就把用户写的「Deploy Checklist」改成小写。
     */
    private static String storedName(Procedure procedure) {
        return procedure.getName() == null ? "" : procedure.getName().trim();
    }

    private AgentProcedureEntity resolveCanonical(String tenantId,
                                                  AgentMemoryEntryEntity.ScopeType scopeType,
                                                  String scopeId, String key) {
        List<AgentProcedureEntity> matched = procedures
                .findByTenantIdAndScopeTypeAndScopeId(tenantId, scopeType, scopeId)
                .stream()
                .filter(p -> p.getStatus() != AgentMemoryEntryEntity.Status.DELETED)
                .filter(p -> identityKey(p.getName()).equals(key))
                .toList();
        if (matched.isEmpty()) {
            return null;
        }
        if (matched.size() > 1) {
            // 只有迁移没跑到的库会走到这里（V13 之后由 ux_ap_identity 拦住）。
            // 报出来，不要静默挑一条继续 —— 否则重复行会一直留在规划器的候选里。
            log.warn("同名 SOP 存在 {} 行，取最新一行为当前版本（应执行 V13 修复）: tenant={}, scope={}/{}, name={}",
                    matched.size(), tenantId, scopeType, scopeId, key);
        }
        return matched.stream().max(CANONICAL_ORDER).orElseThrow();
    }

    private AgentProcedureEntity insertIfAbsent(Procedure incoming,
                                                AgentMemoryEntryEntity.ScopeType scopeType,
                                                String scopeId) {
        try {
            return requiresNew.execute(status -> {
                AgentProcedureEntity entity = new AgentProcedureEntity();
                entity.setTenantId(incoming.getTenantId());
                entity.setScopeType(scopeType);
                entity.setScopeId(scopeId);
                entity.setName(storedName(incoming));
                entity.setDescription(incoming.getDescription());
                entity.setPreconditionsJson(writeJson(incoming.getPreconditions()));
                entity.setStepsJson(writeJson(incoming.getSteps()));
                entity.setSuccessConditionsJson(writeJson(incoming.getSuccessConditions()));
                entity.setImportance(importanceOf(incoming));
                entity.setProcedureVersion(1);
                entity.setStatus(AgentMemoryEntryEntity.Status.ACTIVE);
                AgentProcedureEntity saved = procedures.saveAndFlush(entity);
                revisionService.capture(saved, "CREATE", null, OwnerContext.currentUserId());
                // 与插入同事务：投影登记失败就让这一行一起回滚，
                // 不能留下「本地有 SOP、远端不认识」的半截状态。
                enqueueDefinition(saved);
                return saved;
            });
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("SOP 同身份并发写入，转入升版: tenant={}, scope={}/{}, name={}",
                    incoming.getTenantId(), scopeType, scopeId, incoming.getName());
            return null;
        }
    }

    private AgentProcedureEntity applyContent(AgentProcedureEntity canonical, Procedure incoming,
                                              AgentMemoryEntryEntity.ScopeType scopeType,
                                              String scopeId, String key) {
        AgentProcedureEntity current = canonical;
        for (int attempt = 1; attempt <= MAX_UPGRADE_ATTEMPTS; attempt++) {
            boolean active = current.getStatus() == AgentMemoryEntryEntity.Status.ACTIVE;
            if (active && sameContent(current, incoming)) {
                if (syncMetadataIfNeeded(current, incoming)) {
                    // 内容没变、只改了权重或名字拼写：不升版，但远端那份也要对齐，否则
                    // 规划排序用的 importance 会一直是第一次投影时的值。
                    enqueueDefinition(requireById(current.getId()));
                }
                return current;
            }

            int expected = versionOf(current);
            revisionService.capture(current, "UPSERT", null, OwnerContext.currentUserId());
            int updated = procedures.upgradeContent(current.getId(), current.getTenantId(),
                    expected, expected + 1, storedName(incoming), incoming.getDescription(),
                    writeJson(incoming.getPreconditions()), writeJson(incoming.getSteps()),
                    writeJson(incoming.getSuccessConditions()), importanceOf(incoming),
                    AgentMemoryEntryEntity.Status.ACTIVE);
            if (updated == 1) {
                AgentProcedureEntity promoted = requireById(current.getId());
                // 每一版都要投影：远端只认识它收到的版本，跳过中间版本会让结算拿着的版本号
                // 在服务端找不到行，统计静默丢失。
                enqueueDefinition(promoted);
                return promoted;
            }

            // CAS 未命中：别人先升了版（或把这一行状态改了）。重读后重新判断内容是否已经一致。
            AgentProcedureEntity reloaded = requireById(current.getId());
            log.debug("SOP 升版 CAS 未命中，重读后重试: id={}, expectedVersion={}, attempt={}",
                    current.getId(), expected, attempt);
            current = reloaded;
        }
        log.warn("SOP 升版连续 {} 次 CAS 未命中: tenant={}, scope={}/{}, name={}",
                MAX_UPGRADE_ATTEMPTS, canonical.getTenantId(), scopeType, scopeId, key);
        throw new BusinessException(ErrorCode.MEMORY_WRITE_FAILED, "SOP 并发升级冲突，请重试");
    }

    /** @return 是否真的改了元数据（调用方据此决定要不要重新投影） */
    private boolean syncMetadataIfNeeded(AgentProcedureEntity current, Procedure incoming) {
        Double incomingImportance = importanceOf(incoming);
        boolean sameImportance = Objects.equals(current.getImportance(), incomingImportance);
        boolean sameSpelling = Objects.equals(current.getName(), storedName(incoming));
        if (sameImportance && sameSpelling) {
            return false;
        }
        procedures.updateMetadata(current.getId(), current.getTenantId(),
                storedName(incoming), incomingImportance);
        return true;
    }

    /**
     * 内容比对用**解析后的列表**，不比 JSON 字符串：不同序列化器写出的空白、字段顺序可能不同，
     * 字符串比对会把「同一份 SOP」判成改版，白白升一个版本、清一次成功率。
     */
    private boolean sameContent(AgentProcedureEntity stored, Procedure incoming) {
        return Objects.equals(normalizeText(stored.getDescription()),
                        normalizeText(incoming.getDescription()))
                && parseList(stored.getPreconditionsJson()).equals(nullSafe(incoming.getPreconditions()))
                && parseList(stored.getStepsJson()).equals(nullSafe(incoming.getSteps()))
                && parseList(stored.getSuccessConditionsJson())
                        .equals(nullSafe(incoming.getSuccessConditions()));
    }

    private AgentProcedureEntity requireById(Long id) {
        return procedures.findById(id).orElseThrow(() -> new BusinessException(
                ErrorCode.MEMORY_WRITE_FAILED, "SOP 在写入过程中被删除，请重试"));
    }

    private static int versionOf(AgentProcedureEntity entity) {
        return entity.getProcedureVersion() == null ? 1 : entity.getProcedureVersion();
    }

    /** 把当前行交给投影队列。异常向上冒泡：宁可这次写入失败，也不要留一条永远结算不到的 SOP。 */
    private void enqueueDefinition(AgentProcedureEntity entity) {
        if (definitionSyncService != null) {
            definitionSyncService.enqueue(entity);
        }
    }

    private static Double importanceOf(Procedure procedure) {
        return procedure.getImportance() > 0 ? procedure.getImportance() : 0.5;
    }

    private static List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value;
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // 库里的历史脏数据不该让整次写入失败：当成空列表，下次内容比对自然会触发升版修正。
            log.warn("SOP JSON 解析失败，按空列表处理: {}", e.getMessage());
            return List.of();
        }
    }

    private String writeJson(List<String> values) {
        if (values == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            log.warn("SOP JSON 序列化失败，按空列表写入: {}", e.getMessage());
            return "[]";
        }
    }
}
