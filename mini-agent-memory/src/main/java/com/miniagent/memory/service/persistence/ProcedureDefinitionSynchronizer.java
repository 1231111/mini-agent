package com.miniagent.memory.service.persistence;

import com.miniagent.memory.service.web.ProcedureDefinitionRequest;
import com.miniagent.memory.service.web.ProcedureDefinitionResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 把执行侧写入的 SOP 版本投影到记忆服务的表。
 *
 * <p>投影是「可追赶的镜像」，不是历史账本：它只需要收敛到最新内容，不承担保存每一版的职责
 * （版本历史在执行侧 {@code agent_procedure_revisions}）。因此规则是：</p>
 * <ul>
 *   <li>行不存在 → 建行，计数为 0。该版还没被任何节点用过。</li>
 *   <li>版本相同、内容相同 → 什么都不做（幂等重投）。</li>
 *   <li>版本相同、内容不同 → {@code CONFLICT}。同一版本号对应两份内容，说明两个写入者
 *       对「这一版是什么」达成了不同结论，覆盖任何一方都会让已结算的计数失去口径。</li>
 *   <li>投递版本更旧 → {@code STALE}。这是乱序投递或重试的正常结果，调用方应视为已收敛。</li>
 *   <li>投递版本更新 → 覆盖内容并<b>清零计数</b>。旧计数属于旧内容，不能记到新内容头上。</li>
 * </ul>
 *
 * <p><b>为什么不用一条 UPDATE 里的 {@code CASE WHEN procedure_version < ?}</b>：
 * MySQL 的单表 UPDATE 按赋值列表从左到右求值，右侧读到的是已被前面赋值改过的值；
 * H2 各版本行为并不一致。一旦 {@code procedure_version=?} 写在 {@code CASE} 之前，
 * 判断就变成「新版本 < 新版本」，永远为假，计数永远不清零。拆成两条语句后语义与库无关。</p>
 */
@Service
@Profile("memory-service")
public class ProcedureDefinitionSynchronizer {
    private final JdbcTemplate jdbc;

    public ProcedureDefinitionSynchronizer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ProcedureDefinitionResponse synchronize(ProcedureDefinitionRequest request) {
        if (!ProcedureScopeRules.projectionAccepted(request.scopeType(), request.scopeId(), request.userId())) {
            // USER 级归属必须与 userId 一致；不一致说明调用侧把别人的 SOP 挂到了当前用户名下。
            return new ProcedureDefinitionResponse(ProcedureDefinitionResponse.Status.CONFLICT, null);
        }
        // 按 id + tenant 查，不把 scope 放进 where：id 存在但 scope 不同是「ID 被别的归属占用」，
        // 必须报冲突，而不是当成不存在去插入（那会撞主键，错误信息还看不出真正原因）。
        var existing = jdbc.query("SELECT scope_type, scope_id, procedure_version, name, description, "
                        + "preconditions_json, steps_json, success_conditions_json, importance, status "
                        + "FROM agent_procedures WHERE id = ? AND tenant_id = ?",
                (rs, rowNum) -> new Existing(rs.getString("scope_type"), rs.getString("scope_id"),
                        rs.getInt("procedure_version"), rs.getString("name"),
                        rs.getString("description"), rs.getString("preconditions_json"),
                        rs.getString("steps_json"), rs.getString("success_conditions_json"),
                        rs.getDouble("importance"), rs.getString("status")),
                request.procedureId(), request.tenantId());

        if (existing.isEmpty()) {
            return insert(request);
        }

        Existing current = existing.getFirst();
        if (!current.belongsTo(request)) {
            return new ProcedureDefinitionResponse(ProcedureDefinitionResponse.Status.CONFLICT,
                    current.version());
        }
        if (request.procedureVersion() == current.version()) {
            if (current.sameContent(request)) {
                // 内容没变但重要度可能被单独改过（执行侧改权重不升版），顺手对齐，不动计数。
                current.syncImportance(jdbc, request, current.version());
                return new ProcedureDefinitionResponse(ProcedureDefinitionResponse.Status.UNCHANGED,
                        current.version());
            }
            return new ProcedureDefinitionResponse(ProcedureDefinitionResponse.Status.CONFLICT,
                    current.version());
        }
        if (request.procedureVersion() < current.version()) {
            return new ProcedureDefinitionResponse(ProcedureDefinitionResponse.Status.STALE, current.version());
        }

        int updated = jdbc.update("UPDATE agent_procedures SET name=?, description=?, preconditions_json=?, "
                        + "steps_json=?, success_conditions_json=?, importance=?, status=?, procedure_version=?, "
                        + "updated_at=CURRENT_TIMESTAMP "
                        + "WHERE id=? AND tenant_id=? AND scope_type=? AND scope_id=? AND procedure_version=?",
                request.name(), nullToEmpty(request.description()), nullToEmpty(request.preconditionsJson()),
                request.stepsJson(), nullToEmpty(request.successConditionsJson()),
                request.importance() == null ? current.importance() : request.importance(), request.status(),
                request.procedureVersion(), request.procedureId(), request.tenantId(),
                request.scopeType(), request.scopeId(), current.version());
        if (updated != 1) {
            // 并发下另一个实例刚改过这一行。调用方重投即可，下次会走 STALE / UNCHANGED 分支。
            return new ProcedureDefinitionResponse(ProcedureDefinitionResponse.Status.CONFLICT,
                    current.version());
        }
        clearSettlementCounters(request.procedureId(), request.tenantId(),
                request.scopeType(), request.scopeId(), request.procedureVersion());
        return new ProcedureDefinitionResponse(ProcedureDefinitionResponse.Status.UPDATED,
                request.procedureVersion());
    }

    private ProcedureDefinitionResponse insert(ProcedureDefinitionRequest request) {
        jdbc.update("INSERT INTO agent_procedures "
                        + "(id, tenant_id, scope_type, scope_id, name, description, preconditions_json, steps_json, "
                        + "success_conditions_json, usage_count, success_rate, procedure_version, success_count, "
                        + "attempt_count, importance, status, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,0,0,?,0,0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                request.procedureId(), request.tenantId(), request.scopeType(), request.scopeId(),
                request.name(), nullToEmpty(request.description()), nullToEmpty(request.preconditionsJson()),
                request.stepsJson(), nullToEmpty(request.successConditionsJson()), request.procedureVersion(),
                request.importance() == null ? 0.5 : request.importance(), request.status());
        return new ProcedureDefinitionResponse(ProcedureDefinitionResponse.Status.CREATED,
                request.procedureVersion());
    }

    /**
     * 新版本没有使用记录，计数必须从 0 开始。
     *
     * <p>与主 UPDATE 分开执行，是为了不依赖单语句内的赋值求值顺序（见类注释）。</p>
     *
     * <p><b>不要加 {@code AND attempt_count = 0} 这类“保护”</b>：升版的一瞬间，
     * 新版本号刚刚写入，而结算按版本号精确匹配，旧版本的结算只可能落在旧版本号上。
     * 此时 {@code attempt_count} 带着的必然是旧内容留下的数，用它当守卫只会让清零永不生效——
     * 实测就是这个结果：升到 v2 后 success_count 仍是 v1 的 1。</p>
     */
    private void clearSettlementCounters(long procedureId, String tenantId,
                                         String scopeType, String scopeId, int version) {
        jdbc.update("UPDATE agent_procedures SET success_count=0, attempt_count=0, usage_count=0, success_rate=0.0 "
                        + "WHERE id=? AND tenant_id=? AND scope_type=? AND scope_id=? AND procedure_version=?",
                procedureId, tenantId, scopeType, scopeId, version);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Existing(String scopeType, String scopeId, int version, String name, String description,
                            String preconditions, String steps, String successConditions,
                            double importance, String status) {

        boolean belongsTo(ProcedureDefinitionRequest request) {
            return scopeType != null && scopeType.equalsIgnoreCase(request.scopeType())
                    && scopeId != null && scopeId.equals(request.scopeId());
        }

        boolean sameContent(ProcedureDefinitionRequest request) {
            return Objects.equals(name, request.name())
                    && nullToEmpty(description).equals(nullToEmpty(request.description()))
                    && nullToEmpty(preconditions).equals(nullToEmpty(request.preconditionsJson()))
                    && nullToEmpty(steps).equals(request.stepsJson())
                    && nullToEmpty(successConditions).equals(nullToEmpty(request.successConditionsJson()))
                    && status != null && status.equalsIgnoreCase(request.status());
        }

        /** 重要度是排序权重，不是 SOP 内容：改它既不升版，也不清计数。 */
        void syncImportance(JdbcTemplate jdbc, ProcedureDefinitionRequest request, int currentVersion) {
            if (request.importance() == null || request.importance() == importance) {
                return;
            }
            jdbc.update("UPDATE agent_procedures SET importance=?, updated_at=CURRENT_TIMESTAMP "
                            + "WHERE id=? AND tenant_id=? AND scope_type=? AND scope_id=? AND procedure_version=?",
                    request.importance(), request.procedureId(), request.tenantId(),
                    request.scopeType(), request.scopeId(), currentVersion);
        }
    }
}
