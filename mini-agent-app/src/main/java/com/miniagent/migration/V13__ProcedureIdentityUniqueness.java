package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * V13：把「一个归属域里同名 SOP 只能有一行」变成库层面的约束（H2 / MySQL 通用）。
 *
 * <p><b>为什么需要它</b>：写入路径历史上无条件 insert，{@code procedure_version} 又恒为 1，
 * 同一个名字写两次就有两行 (name, version=1)。两行都 ACTIVE、都会被
 * {@code findForCurrentOwner} 收进规划提示，而节点结算只把计数加到其中一行 ——
 * 闭环的反馈信号因此被拆散。代码侧已经改成按身份 upsert（{@code ProcedureUpsertService}），
 * 这个迁移负责把**存量**修成同一套规则能成立的前提。</p>
 *
 * <p><b>三件事</b>：</p>
 * <ol>
 *   <li>{@code status} 为 NULL 的历史行统一置为 {@code ACTIVE}（V1 的该列可空且无默认值）。</li>
 *   <li>按 {@code (tenant_id, scope_type, scope_id, lower(trim(name)))} 分组，重复组只留一行：
 *       优先 ACTIVE，其次 id 最大（= 最近一次写入）。其余行**不删除**，改名为
 *       {@code <原名> #archived-<id>} 并置 {@code ARCHIVED} —— 内容和它自己的计数都还在，
 *       只是不再进候选。<b>不把被合并行的计数累加到保留行</b>：{@code agent_procedure_applications}
 *       里的应用事实仍按原 procedure_id 记账，把计数搬过去会让「统计可由事实重算」这条不变量失效。</li>
 *   <li>建唯一索引 {@code ux_ap_identity}，与 {@code AgentProcedureEntity} 上的
 *       {@code @UniqueConstraint} 同名同列（桌面档的库结构由 {@code ddl-auto: update} 反推，
 *       prod 档 {@code ddl-auto: validate} 不校验唯一键，两边只能靠这个索引对齐）。</li>
 * </ol>
 *
 * <p><b>表不存在就跳过</b>：全新桌面安装时 Flyway 先于 Hibernate 建表执行，此时
 * {@code agent_procedures} 还不存在；表由 {@code ddl-auto: update} 按实体建，
 * 唯一键来自实体注解。跳过不等于漏掉。</p>
 */
public class V13__ProcedureIdentityUniqueness extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V13__ProcedureIdentityUniqueness.class);

    private static final String TABLE = "agent_procedures";
    private static final String UNIQUE_INDEX = "ux_ap_identity";
    private static final int NAME_MAX_LENGTH = 256;

    /** 分组内挑保留行的顺序：ACTIVE 优先，其次 ARCHIVED，最后 DELETED；同类里取 id 最大。 */
    private static final Comparator<Row> KEEP_ORDER =
            Comparator.comparingInt((Row r) -> r.statusRank())
                    .thenComparingLong(Row::id);

    private record Row(long id, String name, String status) {
        int statusRank() {
            return switch (status) {
                case "ACTIVE" -> 0;
                case "ARCHIVED" -> 1;
                default -> 2;
            };
        }
    }

    @Override
    public String getDescription() {
        return "enforce one procedure row per (tenant, scope, name) identity";
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTableName(connection, TABLE);
        if (table == null) {
            return;
        }

        normalizeNullStatus(connection, table);
        mergeDuplicateIdentities(connection, table);

        if (!hasIndex(connection, table, UNIQUE_INDEX)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE UNIQUE INDEX " + UNIQUE_INDEX + " ON " + table
                        + " (tenant_id,scope_type,scope_id,name)");
            }
        }
    }

    private void normalizeNullStatus(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + table + " SET status = 'ACTIVE' WHERE status IS NULL");
        }
    }

    /**
     * 合并重复身份的行。
     *
     * <p>改过数据就必须留下可追溯的记录：这行 SOP 的原名、id、保留行 id 全部落到日志，
     * 否则用户只会发现「我的一条 SOP 不见了」而查不到原因。</p>
     */
    private void mergeDuplicateIdentities(Connection connection, String table) throws Exception {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT id, tenant_id, scope_type, scope_id, name, status FROM " + table)) {
            while (rows.next()) {
                String status = rows.getString("status");
                Row row = new Row(rows.getLong("id"), rows.getString("name"),
                        status == null ? "ACTIVE" : status);
                groups.computeIfAbsent(identityKey(rows.getString("tenant_id"),
                                rows.getString("scope_type"), rows.getString("scope_id"), row.name()),
                        key -> new ArrayList<>()).add(row);
            }
        }

        for (List<Row> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            Row keep = group.stream().max(KEEP_ORDER).orElseThrow();
            for (Row row : group) {
                if (row.id() == keep.id()) {
                    continue;
                }
                String archivedName = archivedName(row.name(), row.id());
                renameArchived(connection, table, row, archivedName);
                log.warn("V13 合并重复 SOP: 保留 id={}, 归档 id={}, 原名={} -> {}",
                        keep.id(), row.id(), row.name(), archivedName);
            }
        }
    }

    private void renameArchived(Connection connection, String table, Row row,
                                String archivedName) throws Exception {
        // 只改名不动状态时保留原状态（DELETED 的行不要因为合并被复活）
        String status = "DELETED".equals(row.status()) ? "DELETED" : "ARCHIVED";
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + table + " SET name = ?, status = ? WHERE id = ?")) {
            update.setString(1, archivedName);
            update.setString(2, status);
            update.setLong(3, row.id());
            update.executeUpdate();
        }
    }

    /**
     * 改名而不是删除：内容、自己的计数、{@code agent_procedure_applications} 里对它的引用都还能追溯。
     * 后缀带上 id，既保证组内不撞名，也说明这一行是被哪次合并归档的。
     */
    private static String archivedName(String name, long id) {
        String suffix = " #archived-" + id;
        String base = name == null ? "" : name;
        int room = NAME_MAX_LENGTH - suffix.length();
        if (base.length() > room) {
            base = base.substring(0, Math.max(room, 0));
        }
        return base + suffix;
    }

    /**
     * 身份归一化必须与 {@code ProcedureUpsertService#identityKey} 一致：
     * MySQL 的默认排序规则本身大小写不敏感，H2 不敏感，所以统一用 lower 分组，
     * 让两种库上「同名」的判定相同。
     */
    private static String identityKey(String tenantId, String scopeType, String scopeId, String name) {
        return (tenantId == null ? "" : tenantId) + '\u0000'
                + (scopeType == null ? "" : scopeType) + '\u0000'
                + (scopeId == null ? "" : scopeId) + '\u0000'
                + (name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
    }

    private String findTableName(Connection connection, String logicalName) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(connection.getCatalog(), connection.getSchema(),
                "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String schema = tables.getString("TABLE_SCHEM");
                if (schema != null && schema.toLowerCase(Locale.ROOT).equals("information_schema")) {
                    continue;
                }
                String name = tables.getString("TABLE_NAME");
                if (name != null && name.equalsIgnoreCase(logicalName)) {
                    return name;
                }
            }
        }
        return null;
    }

    private boolean hasIndex(Connection connection, String table, String index) throws Exception {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), connection.getSchema(), table, false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name != null && name.equalsIgnoreCase(index)) {
                    return true;
                }
            }
        }
        return false;
    }
}
