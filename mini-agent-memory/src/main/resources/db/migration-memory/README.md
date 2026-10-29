# 记忆服务自己的迁移（不是执行侧的）

**这里的 `V1__`、`V2__` 与 `mini-agent-app` 的 `V1..V8` 是两条独立的链，指向两个不同的库。**

## 为什么放在 `db/migration-memory/` 而不是 `db/migration/`

`mini-agent-memory` 既是独立记忆服务的代码，又被 `mini-agent-app` 当作依赖打进 fat jar。
而 app 的 `spring.flyway.locations` 含有 `classpath:db/migration`（递归扫描），
所以只要这些文件待在 `db/migration/` 下，app 启动就会同时看到两份 V1：

```
FlywayException: Found more than one migration with version 1
  -> BOOT-INF/classes/db/migration/V1__baseline.sql
  -> BOOT-INF/lib/mini-agent-memory-*.jar!/db/migration/V1__procedure_settlement_schema.sql
```

Flyway 的版本号是**全局唯一**的，跨目录也一样，不存在"按 location 划版本区间"的机制。
两条链又要各自从 1 开始（两个库各管各的历史），唯一可行的隔离方式是**路径隔离**。

## 约定

| 谁 | 迁移目录 | Flyway locations |
|---|---|---|
| 执行侧（app，含桌面端） | `mini-agent-app/src/main/resources/db/migration` + `com/miniagent/migration` | `classpath:db/migration,classpath:com/miniagent/migration` |
| 记忆服务 | 本目录 | `classpath:db/migration-memory`（见 `application-memory-service.yml`） |

**不要把本目录的文件搬回 `db/migration/`**，也不要让 app 的 locations 增加 `db/migration-memory` ——
两者都会立刻引发版本冲突。
