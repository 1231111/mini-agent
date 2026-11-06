# mini-agent-account

云端账号与会员服务：登录、注册、会员等级、充值。

它是一个独立的 Spring Boot 应用（端口 8081），库是 MySQL `mini_agent`，只碰五张表：`tenants`、`users`、`membership_plans`、`membership_subscriptions`、`membership_orders`。Agent 在桌面端，用本机 H2，compose 不启动它。

---

## 1. 为什么要拆出来

拆之前，注册/登录/会员逻辑都长在 agent 里，于是有一个绕不开的矛盾：

- 云端 agent 的 prod 档被 `ProductionReadinessValidator` 硬断言 **「公开注册必须关闭」**（`registration-enabled: false`）；
- 而「注册放在 web 端」需要一个能开注册的地方。

拆出本服务之后这条断言一行都不用动：`registration-enabled` 管的是**各进程自己的注册入口**，账号服务的暴露面只有账号域，注册是它的核心功能，不该关。

附带的好处是客户机的本地后端只需要连这个「只做账号」的轻量服务，不必去连一个完整的 agent 实例——后者会顺带把对话、工具执行等能力暴露出来。

## 2. 怎么跑

### 只部署账号服务

```bash
docker compose -f docker-compose.account.yml up -d --build
```

这一份只起 MySQL 和 `mini-agent-account`。注册页是 `http://<主机>:8081/login`，套餐和订单是 `http://<主机>:8081/account`。空库由本服务自己的 V1、V2 建表。

桌面端把 `CLOUD_AUTH_BASE_URL` 和 `MINIAGENT_PORTAL_URL` 都设成同一个地址。

### 和云端记忆一起

```bash
docker compose up -d --build
```

这一份起 MySQL、账号（8081）、记忆服务（127.0.0.1:8091）。没有 agent 容器。

### 本地

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/mini_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8'
export DB_USERNAME=root
export DB_PASSWORD=...
export ACCOUNT_INTERNAL_KEY=随便一串够长的随机值

./mvnw -pl mini-agent-account -am spring-boot:run
```

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
| POST | `/api/membership/orders` | 服务间密钥 | 下单，只落一条 `PENDING` 订单 |
| POST | `/api/membership/pay-callback` | 服务间密钥 | 支付回调，**幂等** |
| POST | `/api/membership/usage` | 服务间密钥 | 上报一次 LLM 调用。一次调用一行 |
| GET | `/`、`/login`、`/register`、`/account` | 无 | 账号网页：注册、登录、套餐、用量、订单 |
| POST | `/api/portal/session` | 无 | 网页登录，写浏览器会话，不返回 token |
| POST | `/api/portal/users` | 无 | 网页注册，并写浏览器会话 |
| POST | `/api/portal/logout` | 浏览器会话 | 退出网页 |
| GET | `/api/portal/me` | 浏览器会话 | 当前网页登录用户 |
| GET | `/api/portal/plans` | 浏览器会话 | 可售套餐 |
| GET | `/api/portal/membership` | 浏览器会话 | 当前会员 |
| GET | `/api/portal/usage` | 浏览器会话 | 今日合计，以及最近 31 天内每次上报（最多 100 条） |
| GET | `/api/portal/orders` | 浏览器会话 | 本人订单 |
| POST | `/api/portal/orders` | 浏览器会话 | 下单，只落 PENDING |
| POST | `/api/portal/orders/{orderNo}/confirm` | 浏览器会话 | 本人确认到账。未接支付渠道 |

两类鉴权的分界：

- **登录注册不设防**——客户机的注册流程要在「用户还没有任何凭证」时就能调通。
- **`/api/membership/**` 要求 `X-Account-Internal-Key`**——本服务不签 token（见第 5 节），所以它没有「当前登录用户」这个概念，自己做不了用户级鉴权。于是分工是：调用方先验掉用户自己的 token、拿到 `userId`，再带密钥转发过来。

`userId` 是**调用方担保的**。这一点在排查「为什么能查到别人的会员」时必须先想到：那说明调用方的鉴权漏了，不是这里放行了。

> 密钥未配置时（`agent.account.internal-key` 为空），会员接口返回 **503 而不是放行**。放行等于说：一个忘了配密钥的部署会把所有人的会员与订单数据暴露给任何能访问到这个端口的人，而且没有任何迹象表明出了事。

## 5. 与 agent 的边界

### 本服务不签发 token

响应里没有 token 字段，这不是缺项。token 由桌面端自己签。本服务只回答「这个用户名+密码对应哪个身份」（`data.userId` / `username` / `displayName` / `role`）。

### 会员等级 → agent 的翻译只有一行

`MembershipService.applyPlan` 把 `membership_plans.daily_token_limit` 写进**用户自己那个 tenant** 的 `tenants.daily_token_limit`。

agent 侧的配额闸门 `DbTenantTokenQuota.check/consume(tenantId)` 只读这一列，它不知道会员、订单、套餐的存在——**agent 侧一行代码都不用改**，而本服务的表结构和业务语义可以任意演进。

替代方案是让 agent 也认识「会员等级」然后自己按等级算配额。那样每加一个等级维度（并发数、可用模型）都要改 agent 的配额代码，而且配额在两侧各算一遍，迟早会出现「界面显示一个值、实际卡在另一个值」。

### 用量明细

`membership_usage_events` 每次 LLM 调用一行：`input_tokens`、`output_tokens`、`reported_at`。请求次数是行数，不按天合并。客户机每次模型调用成功后，用影子用户的 `users.external_id`（云端用户 id）异步 `POST /api/membership/usage`。本服务不读 agent 的 `tenant_daily_usage`。

日切时区是 `agent.quota.zone-id`，默认 `Asia/Shanghai`，和客户机配额同一天。门户 `GET /api/portal/usage` 返回今天的合计，以及含今天在内最近 31 天的每次上报，最多 100 条，新的在前。今天的合计不受这 100 条限制。

客户机没配 `ACCOUNT_INTERNAL_KEY`（或和账号服务不一致）时，上报直接跳过，门户数字停在 0。本地注册、没有 `external_id` 的用户也不会上报。

### `shared-database`

`application-prod.yml` 里 `agent.auth.cloud.shared-database: true`。当前 compose 不启动这个档。桌面端是另一个库，保持默认 `false`，登录后在本地造一条影子用户。

若有人把 agent 的 prod 档和账号指到同一个库，却把这个开关关掉，会再插一条影子行：

```
users 表（同一个库）
  id=1  username=alice  external_id=NULL    tenant_id=<u-alice 租户>   ← 本服务建的
  id=2  username=alice  external_id=1       tenant_id=<system 租户>    ← agent 建的第二条
```

后果是同一人两条记录，而会员配额写在 **id=1 的租户**上、agent 读的是 **id=2 的租户**——症状是「买了会员但额度没变」，且日志里一切正常。

`application-prod.yml` 里因此写着：

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
| `spring.datasource.password` | `DB_PASSWORD`（回退 `MYSQL_PASSWORD`） | 空 | |
| `agent.account.registration-enabled` | — | `true`（prod 也是 `true`） | 与 agent 的 prod 档相反，见第 1 节 |
| `agent.account.password-min-length` | — | 8（prod 12） | prod 与 agent 侧对齐，见下 |
| `agent.account.bcrypt-strength` | — | 10（prod 12） | |
| `agent.account.internal-key` | `ACCOUNT_INTERNAL_KEY` | 空 | 与 agent 侧同值。**同一个变量名**，就是为了不出现「改了一个忘了另一个」 |

密码策略在 prod 档刻意与 agent 对齐（12 位、bcrypt 12 轮）——拆分不等于降标准。两个入口的强度不一致会出现「在 A 能注册、在 B 注册失败」这种没人能解释的现象。

## 7. 数据与迁移

| 脚本 | 内容 |
| --- | --- |
| `V1__account_baseline.sql` | `tenants` / `users`，全部 `CREATE TABLE IF NOT EXISTS`，列定义是 agent 侧 V1+V2+V8 叠加后的最终形状 |
| `V2__membership.sql` | `membership_plans` / `membership_subscriptions` / `membership_orders`，并插入 `free` / `pro` 两个种子套餐 |
| `V3__membership_usage.sql` | `membership_usage_events`，一次 LLM 调用一行 |

两点要留意：

- `V1` 里 `users.external_id` **在本服务的实体中不映射**（那是客户机侧的映射键，账号服务里 `users.id` 本身就是云端身份），但表里必须有这一列——它由 agent 的 V8 建，`V1` 的定义是给「account 单独起在一个空库上」的情形兜底。
- `V2` **刻意不建到 `users` / `tenants` 的外键**：跨服务写顺序有依赖，而且财务记录不该因为账号行被删就级联消失。

## 8. 待办

| # | 事项 | 为什么现在没做 |
| --- | --- | --- |
| 1 | `membership_plans.max_concurrent_tasks` **尚未接线** | 列已落库，但 agent 侧的 `agent.concurrency.max-tasks-per-user` 仍是配置里的固定值。接线要在 agent 侧读它，属于独立一轮 |
| 2 | 客户机的会员配额下发 | 客户机的影子用户挂在**本地** `system` 租户上，而会员配额写在**云端库**的租户里——两者不在一个库，配额传不过去。要让 account 的登录响应带上 `dailyTokenLimit`，agent 侧改为「每个云端身份建一个本地租户」并写入该值。改动面涉及 `ShadowUserService` 的租户策略与既有数据 |
| 3 | 抽 `mini-agent-common` 的契约子模块 | 本服务只需要 `ApiResponse` 与 `ErrorCode`，却因此背上 onnxruntime(92MB) + tokenizers(18MB) + milvus-sdk-java。**实测：本服务的 fat jar 是 303,414,054 B（289 MB）** —— 一个只做账号的服务不该有这个体积。修法会影响 common 的包结构、波及全部 7 个模块 |
| 4 | 接入真实支付渠道 | 网页「确认支付」直接调用 `confirmPortalPayment`，渠道名是 `portal`。接真实渠道后应改为验签回调，浏览器不能再自己把订单标成已支付 |
| 5 | 单元测试 | `AccountAuthService` 的注册边界、`MembershipService` 的幂等与 `applyPlan` 不变量 |
