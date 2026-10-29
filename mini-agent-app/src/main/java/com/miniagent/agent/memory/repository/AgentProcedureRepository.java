package com.miniagent.agent.memory.repository;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentProcedureRepository extends JpaRepository<AgentProcedureEntity, Long> {

    List<AgentProcedureEntity> findByTenantIdAndScopeTypeAndScopeIdAndStatus(
        String tenantId, AgentMemoryEntryEntity.ScopeType scopeType,
        String scopeId, AgentMemoryEntryEntity.Status status);

    List<AgentProcedureEntity> findByTenantIdAndScopeTypeAndScopeIdAndNameContainingAndStatus(
        String tenantId, AgentMemoryEntryEntity.ScopeType scopeType,
        String scopeId, String name, AgentMemoryEntryEntity.Status status);

    /**
     * 取某个归属域下的全部 SOP，**不按 status 过滤**。
     *
     * <p>写入路径要靠它做身份解析：删过又重建、被归档后又重用同名，都必须命中原来那一行，
     * 否则会撞 {@code ux_ap_identity}。有 status 过滤的版本做不到这件事。</p>
     *
     * <p>刻意不在 SQL 里比 name：MySQL 的 utf8mb4_unicode_ci 让 {@code where name = ?} 大小写不敏感，
     * H2（桌面档）默认大小写敏感。同一个身份规则在两种库上得出两种结果，是最难查的一类漂移，
     * 统一在 Java 侧按同一套归一化比较。集合规模由「一个归属域里的 SOP 条数」界定。</p>
     */
    List<AgentProcedureEntity> findByTenantIdAndScopeTypeAndScopeId(
        String tenantId, AgentMemoryEntryEntity.ScopeType scopeType, String scopeId);

    long countByTenantId(String tenantId);

    /**
     * 原地升版：内容替换 + 版本 +1 + 本版计数清零，靠 {@code procedure_version} 做 CAS。
     *
     * <p><b>为什么计数要清零</b>：{@code attempt_count}/{@code success_count} 描述的是
     * 「**这一版内容**被用过几次、成了几次」。内容变了还沿用旧计数，等于把 v1 的成绩记在 v2 头上，
     * 成功率就不再是这一版的可靠性。旧版本的证据没丢 —— 它按 {@code procedure_version} 留在
     * {@code agent_procedure_applications} 里。</p>
     *
     * <p><b>为什么计数清零不会误伤在跑的任务</b>：{@code bumpSettlement} 的 where 带
     * {@code procedure_version = :version}。升版后，还持有旧版本号的节点结算会命中 0 行，
     * 只跳过统计（应用事实早已单独落库），既不会把 v1 的结果算进 v2，也不会让任务失败。</p>
     *
     * <p>id 保持不变：已经发出去、指向这条 SOP 的引用仍然解析到它，只是版本落后于当前。</p>
     *
     * <p>{@code clearAutomatically}/{@code flushAutomatically} 是必须的：批量 UPDATE 绕过了
     * 持久化上下文，不清就会让随后 {@code findById} 读到内存里的旧版本号。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentProcedureEntity p set p.procedureVersion = :nextVersion, "
            + "p.name = :name, p.description = :description, "
            + "p.preconditionsJson = :preconditions, p.stepsJson = :steps, "
            + "p.successConditionsJson = :successConditions, p.importance = :importance, "
            + "p.status = :status, "
            + "p.successCount = 0, p.attemptCount = 0, p.successRate = 0.0, p.usageCount = 0 "
            + "where p.id = :id and p.tenantId = :tenant and p.procedureVersion = :expectedVersion")
    int upgradeContent(@Param("id") Long id, @Param("tenant") String tenant,
                       @Param("expectedVersion") Integer expectedVersion,
                       @Param("nextVersion") Integer nextVersion,
                       @Param("name") String name,
                       @Param("description") String description,
                       @Param("preconditions") String preconditions,
                       @Param("steps") String steps,
                       @Param("successConditions") String successConditions,
                       @Param("importance") Double importance,
                       @Param("status") AgentMemoryEntryEntity.Status status);

    /**
     * 只同步元数据（当前是重要度），不动版本也不动计数。
     *
     * <p>重要度是排序权重，不是 SOP 内容。若把它算进「内容变了」，用户只想把某条 SOP 调前一点，
     * 就会连带清空这一版的成功率 —— 这是把权重编辑和内容改版混为一谈。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentProcedureEntity p set p.name = :name, p.importance = :importance "
            + "where p.id = :id and p.tenantId = :tenant")
    int updateMetadata(@Param("id") Long id, @Param("tenant") String tenant,
                       @Param("name") String name, @Param("importance") Double importance);

    /**
     * 自动治理：只有当前仍是该版本、仍为 ACTIVE、且结算数和成功率仍满足判据时才归档。
     *
     * <p>更新条件在数据库里重验，不能在 Java 读完数值后直接 setStatus/save：另一个线程可能刚升版、
     * 人工编辑或已处理同一条。CAS 的版本号和状态守卫让过期判定最多更新 0 行。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AgentProcedureEntity p set p.status = :archived "
            + "where p.id = :id and p.tenantId = :tenant and p.scopeType = :scope "
            + "and p.scopeId = :owner and p.procedureVersion = :version "
            + "and p.status = :active and p.attemptCount >= :minAttempts "
            + "and p.successRate <= :maxSuccessRate")
    int archiveIfStillUnderperforming(@Param("id") Long id, @Param("tenant") String tenant,
                                     @Param("scope") AgentMemoryEntryEntity.ScopeType scope,
                                     @Param("owner") String owner, @Param("version") Integer version,
                                     @Param("minAttempts") Long minAttempts,
                                     @Param("maxSuccessRate") Double maxSuccessRate,
                                     @Param("active") AgentMemoryEntryEntity.Status active,
                                     @Param("archived") AgentMemoryEntryEntity.Status archived);

    /** 读取仍可治理的 ACTIVE Procedure。 */

    /**
     * 只累加原始计数，不算比率。
     *
     * <p><b>为什么不把 {@code successRate} 写在同一条语句里</b>：MySQL 的单表 UPDATE
     * 按赋值列表从左到右求值，右侧读到的是**已被前面赋值改过**的值；H2 各版本行为并不一致。
     * 于是 {@code successRate = (p.successCount + :delta) * 1.0 / (p.attemptCount + 1)}
     * 一旦排在 {@code successCount = p.successCount + :delta} 之后，`successCount` 已经加过一次，
     * 再 {@code + :delta} 就是**双倍计入**（首个成功就得到 2/1 = 2.0 的"成功率"）。
     * 拆成两条语句后，比率由下一条语句读已提交的计数重算，跨库语义确定。</p>
     */
    @Modifying
    @Query("update AgentProcedureEntity p set p.successCount = p.successCount + :successDelta, "
            + "p.attemptCount = p.attemptCount + 1, "
            + "p.usageCount = p.usageCount + 1 "
            + "where p.id = :id and p.tenantId = :tenant and p.scopeType = :scope "
            + "and p.scopeId = :owner and p.procedureVersion = :version")
    int bumpSettlement(@Param("id") Long id, @Param("tenant") String tenant,
                       @Param("scope") AgentMemoryEntryEntity.ScopeType scope,
                       @Param("owner") String owner, @Param("version") Integer version,
                       @Param("successDelta") int successDelta);

    /**
     * 由累计计数重算成功率。必须在上一条之后、同一事务内调用，读到的才是新计数。
     *
     * <p>用 CASE 而不是直接相除：`attemptCount = 0` 时除零在 MySQL 返回 NULL、
     * 在 H2 抛异常，两种都不是我们想要的 0。</p>
     */
    @Modifying
    @Query("update AgentProcedureEntity p set p.successRate = "
            + "case when p.attemptCount > 0 then p.successCount * 1.0 / p.attemptCount else 0.0 end "
            + "where p.id = :id and p.tenantId = :tenant")
    int refreshSuccessRate(@Param("id") Long id, @Param("tenant") String tenant);
}
