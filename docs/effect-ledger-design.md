# Effect Ledger 设计稿

**状态**：设计，未实现
**日期**：2026-11-23
**目标**：消除「执行结果未知」这个状态，让节点状态从世界事实派生，而不是由执行器写入

---

## 一、要解决的是什么

### 1.1 现有链路

```
工具执行 → ToolResult → 写进 TaskNode.status → 下次读图决定能否继续
```

「世界到底变了没有」这个事实，只存在于工具返回值里。值一丢，事实永久丢失，只剩一个 `OUTCOME_UNKNOWN`。

### 1.2 为什么这是根上的问题

`OUTCOME_UNKNOWN` 描述的不是世界，是**系统自己的无知**。把它写进任务状态、再用它阻止后续执行，等于让一次观测失败永久污染整个任务。

现有代码里三个症状都是同一个病：

| 症状 | 位置 | 本质 |
|---|---|---|
| 中断后节点停在 RUNNING，下次续跑被判死 | `PlanningLoop.stopUnknownOutcome` | 状态由执行器写，执行器死了就没人写 |
| 判断「未知」时把 RUNNING 也算进去 | 同上 | 分不清「世界未知」和「我还没去查」 |
| 只能靠人核验才能继续 | `UnknownOutcomeReconciler` | 没有观测器，系统不会自己查 |

---

## 二、目标架构：四条不变量

### 不变量 1：状态从事实派生，不由执行器写入

```java
nodeStatus = derive(node.goal(), effectsOf(node))
```

`effects` 来自只追加的账本，记的是**观测到的世界**，不是「工具说它成功了」。

只要状态是派生的，就不存在「没写进去」的时刻——任何时候都能从账本重算。

### 不变量 2：每个副作用必须可观测，否则不许执行

执行前先回答：**做完之后，我从哪里能看出它做完了？**

| 动作 | 事实源 |
|---|---|
| 写文件 | 文件内容 SHA-256 |
| 执行命令 | 退出码 + 产物 hash + 目标目录快照 |
| Git 操作 | commit hash / 分支状态 |
| 数据库写入 | 主键存在性 + 行版本 |
| 外部 HTTP | 对方返回的资源 ID（必须落库） |
| 发消息 | message-id |

答不出来的动作，标记为 `UNOBSERVABLE`，进人工队列，不允许「执行完就记成功」。

### 不变量 3：幂等键来自意图，不来自次数

```
idempotencyKey = sha256(tenantId | sessionId | nodeId | effectType | target | intentSignature)
```

同一个意图重复执行，键相同，副作用只发生一次。于是「没观测到结果」可以直接重放，不需要人。

与现有实现的区别：现有幂等键按工具参数算，覆盖面有限；按**意图**算才能跨重试、跨进程、跨会话命中。

### 不变量 4：人工是降级路径，不是一种状态

账本只允许三种观测结论：

| outcome | 含义 | 后续动作 |
|---|---|---|
| `APPLIED` | 观测到世界已变且符合预期 | 节点完成 |
| `NOT_APPLIED` | 观测到世界未变 | 节点回到待执行，安全重试 |
| `UNOBSERVABLE` | 事实源不可查 | 进人工队列，带 SLA |

**没有「未知」**。只有「还没观测」和「观测不到」。

---

## 三、数据模型

### 3.1 表结构

```sql
CREATE TABLE IF NOT EXISTS agent_effects (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    owner_id        BIGINT       NOT NULL,
    session_id      VARCHAR(64)  NOT NULL,
    execution_id    VARCHAR(64)  NOT NULL,
    plan_version    BIGINT       NOT NULL,
    node_id         VARCHAR(64)  NOT NULL,

    effect_type     VARCHAR(24)  NOT NULL,   -- FILE_WRITE / EXEC / HTTP / BROWSER / GIT
    target          VARCHAR(1024) NOT NULL,  -- 路径 / URL / 命令摘要
    intent_hash     CHAR(64)     NOT NULL,   -- 意图签名
    idempotency_key VARCHAR(128) NOT NULL,   -- 幂等唯一键

    pre_observation  TEXT,                   -- JSON：执行前世界
    post_observation TEXT,                   -- JSON：执行后世界
    outcome          VARCHAR(16) NOT NULL,   -- APPLIED / NOT_APPLIED / UNOBSERVABLE / PENDING
    observation_note VARCHAR(512),           -- 不可观测时的原因

    observed_at     TIMESTAMP    NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ae_idempotency
    ON agent_effects (tenant_id, session_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_ae_session_node
    ON agent_effects (tenant_id, session_id, node_id, observed_at);
CREATE INDEX IF NOT EXISTS idx_ae_outcome
    ON agent_effects (tenant_id, outcome, observed_at);
```

**为什么 `idempotency_key` 做唯一索引**：幂等不是靠应用层判断，是靠库拒绝重复插入。并发重试时只有一个能成功。

**为什么保留 `pre_observation`**：没有执行前快照，就无法回答「变化是不是我造成的」。一次 `NOT_APPLIED` 的判定必须基于「执行前是什么样」。

### 3.2 Observation 的 JSON 形态

按 effect 类型不同，`pre_observation` / `post_observation` 结构不同：

```json
// FILE_WRITE
{ "exists": true, "size": 8412, "sha256": "a3f...", "mtime": "2026-11-23T15:01:02Z" }

// EXEC
{ "cwdDigest": "b71...", "exitCode": 0, "outputSha256": "c92...", "artifacts": ["dist/app.jar"] }

// HTTP
{ "status": 201, "resourceId": "ord_8812", "responseSha256": "d10..." }

// BROWSER
{ "url": "https://...", "snapshotSha256": "e55...", "selectorState": {"#submit":"enabled"} }
```

---

## 四、接口设计

### 4.1 观测器

```java
package com.miniagent.agent.effect;

public interface EffectObserver {
    /** 唯一标识，落库到 effect_type 的细分维度 */
    String effectType();

    /** 这个观测器是否负责该意图；多个观测器按 order 取第一个 matches 的 */
    boolean supports(EffectIntent intent);

    /** 观测当前世界。允许抛异常，由上层记为 UNOBSERVABLE，不许吞成「没事」 */
    Observation observe(EffectIntent intent);

    /** 比较前后观测，给出结论 */
    EffectOutcome classify(EffectIntent intent, Observation pre, Observation post);
}
```

关键约束：**`observe` 抛异常时不能返回「空观测」**。现在的 `Provider.check` 超时就 `return List.of()`，导致「超时」和「通过」无法区分——同一个错误不能在 Effect Ledger 里重演。

### 4.2 账本端口（跨模块）

模块依赖是硬的：`mini-agent-tools` 不能依赖 `mini-agent-app`。所以端口定义在下层：

```java
// 放在 mini-agent-tools 或 mini-agent-common
public interface EffectLedgerPort {
    EffectRecord record(EffectIntent intent, Observation pre);

    EffectRecord observe(EffectRecord record, Observation post, EffectOutcome outcome);

    List<EffectRecord> byNode(String sessionId, String nodeId);
}
```

实现放 `mini-agent-app`（JPA + 上面那张表）。`mini-agent-tools` 只依赖端口。

### 4.3 派生器

```java
public interface NodeStatusDeriver {
    Derived derive(TaskNode node, List<EffectRecord> effects);
}

public enum Derived {
    SATISFIED,     // 所有必需 effect 都是 APPLIED
    PENDING,       // 存在 NOT_APPLIED，可以重试
    UNOBSERVABLE,  // 存在 UNOBSERVABLE，需要人
    NOT_STARTED;   // 没有 effect 记录，从未执行
}
```

派生规则（严格按顺序判断）：

1. 无任何 effect 记录 → `NOT_STARTED`
2. 存在 `UNOBSERVABLE` 且无 `APPLIED` 覆盖同一 target → `UNOBSERVABLE`
3. 全部必需 effect 为 `APPLIED` → `SATISFIED`
4. 存在 `NOT_APPLIED` → `PENDING`

**注意第 2 条**：`UNOBSERVABLE` 只在「无法确认是否已生效」时才升级。如果同一 target 后来有一条 `APPLIED`，就以前者为准——这避免了「一次查询失败永久卡死」。

---

## 五、与现有代码的映射

| 现有 | 处置 | 说明 |
|---|---|---|
| `FileChangeLedger`（进程内 Map，TTL 24h） | **升级** | 保留 `capture`/`undoLast`，把 `Change` 同时写进 `agent_effects`；内存结构降级为缓存 |
| `FileActionJournal`（jsonl，记动作状态） | **收敛** | 记录 intent 的职责并入 `EffectIntent`；状态机（RUNNING/DONE/UNKNOWN）由 ledger 的 outcome 取代 |
| `UnknownOutcomeReconciler` | **降级** | 只处理 `UNOBSERVABLE`，不再承担「猜」。`reconcile` 的入参语义从「你告诉我做没做」变成「确认这条自动观测结论」 |
| `PlanningLoop.stopUnknownOutcome` | **删除** | 前提「存在未知状态」不成立 |
| `TaskNode.status` | **改为派生视图** | 写入口保留给调度器标记 SCHEDULED，终态不再由执行器写 |
| `TaskOutcomeReviewController` | **保留并扩展** | 增加 `UNOBSERVABLE` 列表查询、SLA 超时、放弃接口 |

---

## 六、迁移路径（四步，每步可独立上线、可回滚）

这是大改，必须能对比、能回滚。所以分四步，前两步不改变任何现有行为。

### 阶段 1：只写不读（双写）

- 新增表 `V41__EffectLedger`
- 新增 `EffectIntent` / `Observation` / `EffectLedgerPort` 及 JPA 实现
- `ToolPipeline` 在写类工具执行前后调用 `record`，**同时**保持现有 `TaskNode.status` 写入不变
- 端点：新增管理查询接口，能按 session 看 effect 列表

**验收**：现有 2395 项测试全绿；随机抽 20 个会话，effect 记录数与实际工具调用数一致。

**回滚**：删表、移除双写调用。行为零变化。

### 阶段 2：影子派生

- 新增 `NodeStatusDeriver` 及文件类观测器
- 每次 `PlanningLoop` 恢复时，**额外**算一遍派生状态，写进 trace 的 `EFFECT_DERIVED` 节点，但不使用
- 统计：派生结果与真实 `TaskNode.status` 的一致率

**验收**：一致率 ≥ 99%；不一致的每一条都能解释（要么是派生器 bug，要么是现有 status 确实错了）。

**回滚**：关掉影子计算。

### 阶段 3：切读

- `PlanningLoop` 恢复时用派生状态，`OUTCOME_UNKNOWN` 的判定改为 `UNOBSERVABLE`
- `stopUnknownOutcome` 删除
- 旧 `OUTCOME_UNKNOWN` 数据在迁移时按派生规则重算一次

**验收**：故障注入测试——在任意工具执行中断进程（`kill -9`），重启后派生结论必须等于真实世界状态。

**回滚**：保留旧判定分支，用一个配置开关切回。

### 阶段 4：清理

- 移除 `FileActionJournal` 的重复状态字段
- `UnknownOutcomeReconciler` 收敛为 `UNOBSERVABLE` 处理器
- 补 SLA：`UNOBSERVABLE` 超过 24 小时按保守假设收敛（视为未生效并回滚，或标记完成并告警）

---

## 七、必须有的测试

| 测试 | 验的是什么 |
|---|---|
| 幂等重放 | 同一 `idempotencyKey` 连续插入 10 次，表里只有 1 条，副作用只发生 1 次 |
| 中断恢复 | 在 `record(pre)` 之后、`observe(post)` 之前 kill 进程，重启后派生为 `NOT_STARTED` 或 `PENDING`，不得是「未知」 |
| 观测失败 | 观测器抛异常 → 记为 `UNOBSERVABLE`，不是「通过」 |
| 覆盖判定 | 先 `UNOBSERVABLE` 后 `APPLIED`（同一 target），派生为 `SATISFIED` |
| 不可观测降级 | 第三方 API 无查询接口 → 进人工队列，有 SLA 计时 |
| 不变量回归 | 任意中断点重启，派生结论 == 真实世界状态（属性测试） |

---

## 八、代价与边界

**要动的范围**：planner 状态层（`PlannerStateStore` / `TaskNode.status` / 恢复路径）是核心，牵连 `TodoStateProjector` 和前端读状态的接口。四步走是为了让每一步都可验收。

**无法消灭的部分**：第三方系统不提供查询接口、只返回 200 但无法回查。这类只能落到 `UNOBSERVABLE`，方案让它显式、可计数、有 SLA，但不会消失。

**性能**：派生要查文件系统。需要按 target 缓存观测结果，并且只对「本轮涉及」的节点派生，不做全图扫描。

**与 `undo` 的关系**：`FileChangeLedger` 的 `before` 内容保留在原处（撤销需要内容），`agent_effects` 只存 hash 与元数据，不重复存文件体。

---

## 九、如果只做一件事

先做阶段 1 的**表 + 观测器接口**，不做任何行为变更。

因为「每个 effect 怎么观测」必须先定义清楚——这件事定不下来，后面的派生器和迁移都是空中楼阁。反过来，一旦观测方式定死了，派生器只是几十行纯函数。
