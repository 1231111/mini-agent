# 桌面档版本化升级能力 · 验证记录（2026-09-29）

架构师视角第一步：数据层 —— 让桌面客户端具备版本化升级能力。
本文是实测结论存档，所有断言均有实测出处，不含推断。

## 改动清单

| 文件 | 改动 |
|---|---|
| `application-desktop.yml` | `flyway.enabled: true` + `baseline-version: 8`；V1~V8 归 `ddl-auto: update`，Flyway 只管 8 之后的增量 |
| `application.yml` | `spring.flyway.locations` 加 `classpath:com/miniagent/migration`（Flyway 按 location 当包名扫 Java 迁移，默认扫不到） |
| `com.miniagent.migration.V9__BackfillIndexAndDropIntentTables` | 新增。BaseJavaMigration：删 6 张 `intent_*` 孤儿表 + 补 `idx_chat_task_user_deleted_session_created`，全程元数据守卫 |
| `com.miniagent.config.flyway.DesktopFlywayConfig` | 新增。`@Profile("desktop")` 的 `FlywayMigrationStrategy`：无历史表 → `baseline()` → `migrate()`；另清 `success=0` 失败行 |
| `config.entity.ChatTask` | 补 `@Index`（新装机上 Flyway 先于 Hibernate 跑、表还不存在，索引只能由实体建） |

## 关键前置事实（Flyway 10.20.1 + H2 2.3.232 实测）

| 场景 | 行为 |
|---|---|
| 空库 + `baseline-on-migrate` + `baseline-version: 8` | **不打 baseline，从最小版本起全跑**（实测跑了 V8+V9）→ 纯 yml 配置救不了新装机 |
| 非空库、无历史表 | 打 baseline=8，只跑 8 之后的 ✓ |
| 空库上显式 `baseline()` 再 `migrate()` | baseline 允许（"Successfully baselined schema with version: 8"），然后只跑 8 之后的 ✓ |

→ 因此 baseline 必须由代码显式调用（DesktopFlywayConfig），且只挂 desktop 档：
prod 的空 MySQL 要跑全量 V1~V8（prod 是 `ddl-auto: validate`，不建表），baseline=8 对它是错的。

另两个已知坑（本次再次实证）：
- H2 不支持 MySQL 用户变量（`SET @has_col := 1` 直接语法报错）→ V9 必须是 Java 迁移
- 桌面档 `logging.level.root: WARN` 压掉 Flyway INFO → 成功与否只能查库

## 验收断言与结果

### A. 新装机（空 MINI_AGENT_HOME）

- 迁移历史：`<< Flyway Baseline >>` v=8 + V9 `success=true`，**V1~V8 一条未跑** ✓
- 26 张表（25 业务 + 历史表），无 `intent_*` 表 ✓
- `chat_tasks` 索引 `IDX_CHAT_TASK_USER_DELETED_SESSION_CREATED cols=USER_ID,DELETED,SESSION_ID,CREATED_AT`（Hibernate 按实体建出）✓

### B. 老机升级（真实老库：25 张表、无 flyway 历史、chat_tasks 无二级索引、含真实数据）

- 迁移历史：`<< Flyway Baseline >>` v=8 + V9 `success=true`（只跑增量）✓
- **数据零丢失**：users 1→1、tenants 1→1、chat_tasks 1→1、chat_conversations 1→1、chat_messages 2→2、auth_sessions 1→1 ✓
- 索引被补上（V9 建）✓

### C. 结构指纹对比（A vs B）

归一化前 diff 仅两类噪音：H2 自动约束名的对象 ID 后缀（`PRIMARY_KEY_65` vs `PRIMARY_KEY_6`、
`…_INDEX_4` vs `…_INDEX_6`）、`users.EXTERNAL_ID` 列物理顺序（老库后补列排表尾，不影响 SQL）。
归一化后（表名序 + 列名序 + 自动名后缀抹平）：**0 处差异，26 表 / 257 列定义 / 88 索引+主键完全一致** ✓

### D. MySQL 侧回归（一次性 scratch 库 v9probe，跑完全链后已删除，未碰真实库）

- V1→V9 全 `success=true`，V9 以 `[JDBC]`（Java 迁移）执行，20ms ✓
- 第二次 migrate `executed=0`（幂等）✓
- `intent_*` 表 0 张、索引列序 `(user_id, deleted, session_id, created_at)` 完好 → **V9 在真实库状态机上是纯 no-op** ✓

## 遗留（后续步骤）

1. **双写残留**：`ddl-auto: update` 仍会把实体变化直接应用到库上。纪律「结构变更只走 V9 之后的迁移」，
   待实体稳定后桌面档切 `validate` + 全量基线迁移收口。
2. **prod 全量启动回归未做**（需要 `MODEL_CONFIG_ENCRYPTION_KEY` 等 8 个密钥）：
   当前 `user_model_config` 4 条凭据是**明文**（上次 binlog 恢复后未再跑过 prod），
   跑 prod 会触发 `ModelConfigSecretMigrator` 加密 —— 必须先把密钥持久化到固定位置再跑，
   否则重演"换 key 解不开"事故。密钥落点待与斌哥定。
3. 上一步遗留依旧：`models-v1` 物料发布、CI Release 步骤、产物身份（build-info + sha256）、
   更新检查、密钥轮换能力、老用户租户迁移口径。
