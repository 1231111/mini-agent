# 记忆服务自己的迁移（不是执行侧的）

**这里的 `V1__`… 与 `mini-agent-app` 的 `V1..` 是两条独立的链，指向两个不同的库**
（compose 里是 `mini_agent_memory` 与 `mini_agent`）。

| 谁 | 迁移目录 | Flyway locations |
|---|---|---|
| 桌面端 agent（`mini-agent-app`，compose 不启动） | `mini-agent-app/src/main/resources/db/migration` + `com/miniagent/migration` | `classpath:db/migration,classpath:com/miniagent/migration` |
| 云端记忆（`mini-agent-memory-service`，自己的 jar） | 本目录 | `classpath:db/migration-memory` |
| 账号（`mini-agent-account`，自己的 jar） | `mini-agent-account/src/main/resources/db/migration` | `classpath:db/migration` |

## 为什么目录名不是 `db/migration`

执行侧的 Flyway locations 是 `classpath:db/migration`（再加 `com/miniagent/migration`）。
本目录若改成 `db/migration`，一旦和执行侧打进同一个 classpath，两边的 V1 会撞上。
账号服务在自己的 jar 里，用的就是 `db/migration`，不在这里。

## 版本

- V1–V10：v1 原型的表（`agent_procedures`、`memory_evolution_*` 等）。v2 代码不再读写，
  其中 `memory_project_authorizations` / `memory_project_members`（V2）仍由 v2 用于项目授权。
- V11：v2 进化闭环（`mem_item`、`mem_revision`、`mem_activation`、`outcome_event`、`evo_*`、`audit_log`）。

已发布的迁移文件不要修改内容（校验和会变）；新增变更追加新版本号。
