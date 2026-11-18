# mini-agent-account

云端账号与会员服务：登录、注册、会员等级、充值。

它是一个独立的 Spring Boot 应用（端口 8081），库是 MySQL `mini_agent`，与 agent 共用 `tenants`、`users` 和会员数据表；会员用量另存于 `membership_usage_events`。Agent 桌面端使用本机 H2，compose 不启动桌面客户端。

---

## 1. 为什么要拆出来

拆之前，注册/登录/会员逻辑都长在 agent 里，于是有一个绕不开的矛盾：

- 云端 agent 的 prod 档被 `ProductionReadinessValidator` 硬断言 **「公开注册必须关闭」**（`registration-enabled: false`）；
- 而「注册放在 web 端」需要一个能开注册的地方。

拆出本服务之后这条断言一行都不用动：`registration-enabled` 管的是**各进程自己的注册入口**，账号服务的暴露面只有账号域，注册是它的核心功能，不该关。

附带的好处是客户机的本地后端只需要连这个「只做账号」的轻量服务，不必去连一个完整的 agent 实例——后者会顺带把对话、工具执行等能力暴露出来。

## 2. 怎么跑

### 只部署账号服务

宿主机需要已有 MySQL（3306）。生产需要注入数据库凭据及两把服务密钥：

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS mini_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

./mvnw -pl mini-agent-account -am -DskipTests package

export DB_URL='jdbc:mysql://db.example.internal:3306/mini_agent?sslMode=VERIFY_IDENTITY&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8'
export DB_USERNAME=miniagent
export DB_PASSWORD='<strong database password of at least 32 bytes>'
# Generate service keys with scripts/gen-memory-service-keys.sh. Keep them identical
# in Account, memory-service, and any app instance that calls Account.
export ACCOUNT_INTERNAL_KEY='<generated service-to-service key>'
export ACCOUNT_MEMORY_TOKEN_KEY='<Base64 encoding of 32 random bytes>'

ACCOUNT_JAR=mini-agent-account/target/mini-agent-account-0.0.1-SNAPSHOT.jar \
  ./scripts/start-account.sh --foreground
```

生产服务默认只监听 loopback，且要求 MySQL 证书链与主机名验证通过。请通过 HTTPS 反向代理发布注册页；不要把 8081 直接暴露到公网。空库由本服务自己的 V1、V2 建表。

本地开发请显式使用 `SPRING_PROFILES_ACTIVE=dev` 和 `.env` 中的本机数据库值；启动脚本默认使用 `prod`，开发 `.env` 不应作为生产密钥配置。

桌面端把 `CLOUD_AUTH_BASE_URL` 和 `MINIAGENT_PORTAL_URL` 都设成同一个地址。

### 和云端记忆一起

两个库先建好，然后分别起两个 jar：

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS mini_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS mini_agent_memory CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

./mvnw -pl mini-agent-memory-service -am -DskipTests package

export ACCOUNT_BASE_URL='http://127.0.0.1:8081'
MEMORY_SERVICE_JAR=mini-agent-memory-service/target/mini-agent-memory-service-0.0.1-SNAPSHOT.jar \
  MEMORY_SERVICE_ROLE=all MEMORY_SERVICE_PORT=8091 \
  ./scripts/start-memory-service.sh --foreground
```

Memory-service uses the same `DB_PASSWORD`, `ACCOUNT_INTERNAL_KEY`, and `ACCOUNT_MEMORY_TOKEN_KEY` values as Account.
All production values must be injected, and the service checks them during startup.

`start-account.sh` defaults to the `prod` profile. The production validator rejects missing, short, or previously shipped credentials.

### 本地开发

```bash
set -a
. ./.env
set +a
SPRING_PROFILES_ACTIVE=dev ./mvnw -pl mini-agent-account -am spring-boot:run
```

The `dev` profile reads credentials from the local gitignored `.env` file or IDE environment; the repository contains no usable default credentials. The service launch scripts load `.env` automatically. Do not use `dev` for network-accessible deployments; production launch scripts activate `prod` by default.

验证它活着：

```bash
curl -s http://127.0.0.1:8081/actuator/health
# {"status":"UP"}
```

## 3. 未实现：不要把 agent 的 Flyway 指到账号库

账号服务在空库上用自己的 V1、V2 建表，历史表是 `flyway_schema_history_account`。

Agent 的 `V2__production_security_and_usage.sql` 里是裸 `CREATE TABLE tenants`。那句不能改成 `IF NOT EXISTS`，改了会变已执行库的 checksum。桌面端走自己的 H2，不碰这个库。若把 agent 的 prod 档指到同一 MySQL，V2 会撞上 "table already exists"，agent 起不来。

## 4. 端点

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/tokens` | 无 | 登录。只回答「用户名+密码对应哪个身份」，**不返回 token** |
| POST | `/api/users` | 无 | 注册。每用户建独立 tenant，并落一条 `free` 订阅 |
| GET | `/api/membership/plans` | 服务间密钥 | 可售套餐 |
| GET | `/api/membership/current?userId=` | 服务间密钥 | 某人当前生效的会员 |
| GET | `/api/membership/orders?userId=` | 服务间密钥 | 本人的待处理订单（最多 20 笔，用于刷新后恢复结账） |
| POST | `/api/membership/orders` | 服务间密钥 | 下单，只落一条 `PENDING` 订单 |
| POST | `/api/membership/orders/cancel` | 服务间密钥 | 取消本人的待支付订单 |
| POST | `/api/membership/simulate-payment` | 服务间密钥 + 双端显式开启 | 本地演示结算，不扣款，支付后开通会员 |
| POST | `/api/membership/pay-callback` | 服务间密钥 | 当前 fail-closed（HTTP 503）；接入真实渠道签名、支付状态、金额与币种校验前不会开通会员 |
| POST | `/api/membership/usage` | 服务间密钥 | 幂等上报一次 LLM 调用，用 eventId 去重 |
| GET | `/`、`/login`、`/register`、`/account` | 无 | 账号网页：注册、登录、套餐、用量、订单 |
| POST | `/api/portal/session` | 无 | 网页登录，写浏览器会话，不返回 token |
| POST | `/api/portal/users` | 无 | 网页注册，并写浏览器会话 |
| POST | `/api/portal/logout` | 浏览器会话 | 退出网页 |
| GET | `/api/portal/me` | 浏览器会话 | 当前网页登录用户 |
| GET | `/api/portal/plans` | 浏览器会话 | 可售套餐 |
| GET | `/api/portal/membership` | 浏览器会话 | 当前会员 |
| GET | `/api/portal/usage` | 浏览器会话 | 今日合计，以及最近 31 天内每次上报（最多 100 条） |
| GET | `/api/portal/orders` | 浏览器会话 | 本人订单 |
| GET | `/api/portal/payment-mode` | 浏览器会话 | 返回模拟支付是否启用 |
| POST | `/api/portal/orders` | 浏览器会话 | 下单，只落 PENDING |
| POST | `/api/portal/orders/{orderNo}/confirm` | 浏览器会话 + CSRF | 本人订单模拟支付确认；仅在模拟支付开启时可用 |
| POST | `/api/portal/orders/{orderNo}/cancel` | 浏览器会话 + CSRF | 取消本人的待支付订单 |

两类鉴权的分界：

- **登录注册不设防**——客户机的注册流程要在「用户还没有任何凭证」时就能调通。
- **`/api/membership/**` 要求 `X-Account-Internal-Key`**——本服务不签 token（见第 5 节），所以它没有「当前登录用户」这个概念，自己做不了用户级鉴权。于是分工是：调用方先验掉用户自己的 token、拿到 `userId`，再带密钥转发过来。

`userId` 是**调用方担保的**。这一点在排查「为什么能查到别人的会员」时必须先想到：那说明调用方的鉴权漏了，不是这里放行了。

> 密钥未配置时（`agent.account.internal-key` 为空），会员接口返回 **503 而不是放行**。放行等于说：一个忘了配密钥的部署会把所有人的会员与订单数据暴露给任何能访问到这个端口的人，而且没有任何迹象表明出了事。

## 5. 与 agent 的边界

### 本服务不签发 token

响应里没有 token 字段，这不是缺项。token 由桌面端自己签。本服务只回答「这个用户名+密码对应哪个身份」（`data.userId` / `username` / `displayName` / `role`）。

### 会员额度同步到 agent

`MembershipService.applyPlan` activates the subscription and persists its token allowance, concurrency cap, and expiry on the shared user row in the same transaction. The plan allowance does not overwrite the tenant-wide cap. Effective daily user quota is administrator override, active membership allowance, then tenant default. The tenant-wide daily cap remains a hard ceiling. In standalone desktop mode, the authenticated agent proxy syncs the current entitlement to its local shadow user after membership reads and simulated checkout.

agent 本地有效用户额度的优先级是：系统管理员设置的单用户额度覆盖、有效会员套餐额度、租户默认单用户额度。租户每日总额度始终是硬上限。账号库的租户行不是 agent 执行时读取的配额来源；同步后的本地用户记录才是。

### 用量明细

`membership_usage_events` stores one idempotent event per completed model call (`event_id`, input/output tokens, and report time). The agent writes a local durable outbox in the same transaction as its user/tenant quota debit, then retries account delivery with capped backoff until accepted; account enforces the event key to prevent duplicate totals. Outbox scheduler timestamps use UTC, while the daily usage window uses `agent.quota.zone-id`. This is token-count quota accounting, not currency-based provider billing. The portal usage history can lag while its outbox is retrying; the agent quota page reads the authoritative local daily counters.

日切时区是 `agent.quota.zone-id`，默认 `Asia/Shanghai`，和客户机配额同一天。门户 `GET /api/portal/usage` 返回今天的合计，以及含今天在内最近 31 天的每次上报，最多 100 条，新的在前。今天的合计不受这 100 条限制。

客户机没配 `ACCOUNT_INTERNAL_KEY`（或和账号服务不一致）时，上报直接跳过，门户数字停在 0。本地注册、没有 `external_id` 的用户也不会上报。

### `shared-database`

`application.yml` 里 `agent.auth.cloud.shared-database: true`。桌面端是另一个库，保持默认 `false`，登录后在本地造一条影子用户。

若有人把 agent 的 prod 档和账号指到同一个库，却把这个开关关掉，会再插一条影子行：

```
users 表（同一个库）
  id=1  username=alice  external_id=NULL    tenant_id=<u-alice 租户>   ← 本服务建的
  id=2  username=alice  external_id=1       tenant_id=<system 租户>    ← agent 建的第二条
```

后果是同一人两条记录，而会员配额写在 **id=1 的租户**上、agent 读的是 **id=2 的租户**——症状是「买了会员但额度没变」，且日志里一切正常。

`application.yml` 里因此写着：

```yaml
agent:
  auth:
    cloud:
      base-url: ${ACCOUNT_BASE_URL:http://account:8081}
      shared-database: true      # ← 同库，不许再造影子行
      internal-key: ${ACCOUNT_INTERNAL_KEY:}
```

共享库模式下 `materialize` 直接用账号服务返回的 `userId` 读 `users` 那一行本身，**读不到就报错**（`AUTH.03.03`），不做兜底新建——那正是要防的重复行。配错库时会在登录那一刻就暴露，而不是静默地写坏数据。

客户机本地后端则相反：它和账号库是**两个库**，必须造影子行，否则本地鉴权链（`SignedSessionFilter` 每个请求都要 `findById`）没有落点。所以那里保持 `shared-database: false`（默认）。

## 6. 配置

| 键 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `server.port` | `ACCOUNT_SERVER_PORT` | 8081 | 监听端口 |
| `spring.datasource.url` | `DB_URL` | `jdbc:mysql://127.0.0.1:3306/mini_agent?...` | 账号库。桌面端 agent 不连它 |
| `spring.datasource.username` | `DB_USERNAME` | root | |
| `spring.datasource.password` | `DB_PASSWORD` | required in prod (at least 32 bytes) | Local development value exists only in `dev` |
| `agent.account.registration-enabled` | — | `true`（prod 也是 `true`） | 与 agent 的 prod 档相反，见第 1 节 |
| `agent.account.password-min-length` | — | 8（prod 12） | prod 与 agent 侧对齐，见下 |
| `agent.account.bcrypt-strength` | — | 10（prod 12） | |
| `agent.account.internal-key` | `ACCOUNT_INTERNAL_KEY` | required in prod (at least 32 bytes) | Same value in agent and memory-service |
| `agent.account.memory-token-key` | `ACCOUNT_MEMORY_TOKEN_KEY` | required in prod (Base64 decoding to 32 bytes) | Same value in memory-service; rotation invalidates issued memory tokens |

密码策略在 prod 档刻意与 agent 对齐（12 位、bcrypt 12 轮）——拆分不等于降标准。两个入口的强度不一致会出现「在 A 能注册、在 B 注册失败」这种没人能解释的现象。

## 7. 数据与迁移

| 脚本 | 内容 |
| --- | --- |
| `V1__account_baseline.sql` | `tenants` / `users` 基础结构，全部 `CREATE TABLE IF NOT EXISTS`，与 agent 侧基础列兼容；账号侧增量列由后续迁移增加 |
| `V2__membership.sql` | `membership_plans` / `membership_subscriptions` / `membership_orders`，并插入 `free` / `pro` 两个种子套餐 |
| `V3__membership_usage.sql` | 每次模型调用的 token 用量明细表 |
| `V4__memory_refresh_credential.sql` | 用户的记忆服务刷新凭证摘要 |
| `V5__membership_usage_event_id.sql` | 用 eventId 对调用用量做幂等去重 |
| `V6__membership_order_expiry.sql` | 订单支付截止时间和过期索引；存量待支付订单按创建时间补 15 分钟期限 |
| `V7__membership_entitlement_snapshot.sql` | 在共享 `users` 表保存会员 token 上限、并发上限和到期时间；迁移时回填当前有效订阅，之后每次充值在同一事务同步，供 agent 执行侧立即生效 |

两点要留意：

- `V1` 里 `users.external_id` **在本服务的实体中不映射**（那是客户机侧的映射键，账号服务里 `users.id` 本身就是云端身份），但表里必须有这一列——它由 agent 的 V8 建，`V1` 的定义是给「account 单独起在一个空库上」的情形兜底。
- `V2` **刻意不建到 `users` / `tenants` 的外键**：跨服务写顺序有依赖，而且财务记录不该因为账号行被删就级联消失。

## 8. 待办

| # | 事项 | 为什么现在没做 |
| --- | --- | --- |
| 1 | 抽 `mini-agent-common` 的契约子模块 | 本服务只需要 `ApiResponse` 与 `ErrorCode`，却因此背上 onnxruntime(92MB) + tokenizers(18MB) + milvus-sdk-java。**实测：本服务的 fat jar 是 303,414,054 B（289 MB）** —— 一个只做账号的服务不该有这个体积。修法会影响 common 的包结构、波及全部 7 个模块 |
| 2 | 接入真实支付渠道 | 当前只有明确标注、默认关闭的模拟支付流程；真实支付回调仍 fail-closed 并返回 503。接入渠道后必须校验签名、订单、金额和币种，再调用内部入账服务 |

## 模拟会员支付

`agent.account.membership.simulated-payment-enabled` 默认关闭，本地 `dev` 配置默认开启，可用 `ACCOUNT_SIMULATED_PAYMENT_ENABLED` 覆盖；生产启动校验会拒绝启用该开关。门户会明确标记“模拟支付，不扣款”，且只允许登录用户确认自己的订单；只有未过期的待支付订单会被开通，重复确认已支付订单保持幂等。用户刷新后可在账号门户或 agent 会员页恢复待处理订单，取消也只影响本人的待支付订单。订单使用固定的 `SIMULATED` 渠道标记。结算与会员订阅、token/并发额度快照在同一事务提交；agent 的共享库部署立即可见，本地桌面模式通过会员代理查询同步额度。真实支付回调 `/api/membership/pay-callback` 仍然 fail-closed。
