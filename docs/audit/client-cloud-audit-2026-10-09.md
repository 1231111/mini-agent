# 客户端 & 云端缺陷分册（2026-10-09）

- **审计对象**：出厂桌面形态（Electron 壳 + 内嵌 JRE + fat jar + H2 本地库 + `application-desktop.yml`）与云端形态（`mini-agent-account` 账号/会员服务 + prod 档多副本部署）
- **方法**：先读 `docs/agent-production-gap-audit.md`（8 P0 / 18 P1 / 45 P2-P3）与 `docs/agent-severe-bugs-recheck-2026-10-08.md`（12 条新发现）做 novelty diff，再**回源码逐条核对**（不采信子代理结论）
- **三态标注**：✅ 已修（本轮反向验证）／📌 已记未修（注明记在哪）／🆕 文档未记录
- **基线**：HEAD `f318131` + 本轮 Bug1/2/3 的未提交改动

---

## 0. 结论速览

| 编号 | 形态 | 级别 | 缺陷 | 状态 |
|---|---|---|---|---|
| **C-1** | 客户端 | **P0 交付阻断** | 出厂包在全新机器上起不来：`JWT_SECRET` 没有任何来源 | 🆕 |
| **Y-2** | 云端 | **P1 可用性** | 限流 map 攒满 1 万 key → 所有新用户永久 429（上限把防 OOM 变成 DoS 放大器） | 🆕（E-1 只记了"不淘汰"） |
| **Y-1** | 云端 | **P1 涉及钱** | `applyPlan` 锁序错误 → 同一用户两条 ACTIVE 订阅 | 📌 10-08 #6 记（主报告未记），复核成立 |
| C-2 | 客户端 | P1 产品级 | 点 × = 隐藏到托盘，且无任何提示 | 🆕（待办项，未实现） |
| Y-3 | 云端 | P2 | `DesktopLoginTicketStore.issue()` 远程可触发的无界增长 | 📌 10-08 #9 记，复核成立 + 补两层放大 |
| Y-5 | 云端 | P3 | app 侧 `AuthService.register` 漏了账号服务已修的用户名长度校验 | 🆕 |
| **Y-4** | 云端 | — | **推翻** 10-08 报告 #5「孤儿租户」；真实缺陷降级为 P3 错码 | 纠正 |
| C-3 | 客户端 | P2 | 桌面档 `ddl-auto: update` 与 Flyway 双写残留 | 📌 yml 自述 + `detailed-design.md:687`，审计报告未列 |
| C-4 | 客户端 | P2 | 未签名/未公证（SmartScreen 与 Gatekeeper 阻断） | 🆕 |

---

## 1. 客户端

### 🆕 C-1 · 出厂包在全新客户机上起不来（P0 交付阻断）

**跑起来看到什么**：全新机器双击 `MiniAgent.exe` → 弹"后端启动失败"，正文是日志尾 12 行，内容是 Spring 的 `IllegalStateException: agent.auth.jwt-secret must be set (env JWT_SECRET；旧名 COOKIE_SECRET 仍可回退)`。

**为什么（代码在做什么）** —— 四段证据链，逐条可核：

| 环节 | 事实 | 出处 |
|---|---|---|
| 1 | 桌面档把签名密钥写成"必须由外部提供"：`jwt-secret: ${JWT_SECRET:}` | `application-desktop.yml:165` |
| 2 | 空值 → `@PostConstruct` 抛异常 → Bean 创建失败 → **进程拒绝启动** | `JwtSessionService.java:84-89` |
| 3 | 壳 spawn 后端时**没有注入 `JWT_SECRET`** —— 只注 4 个 env：`MINI_AGENT_DESKTOP_NONCE`、`CLOUD_AUTH_BASE_URL`、`PLAYWRIGHT_BROWSERS_PATH`、`MINI_AGENT_MODELS_DIR` | `main.js:194-215` |
| 4 | 出厂包不含 `config.yml`，也不含模板：`extraResources` 只有 jar/jre/browsers/models，`files` 只有 main.js/preload.js/src/assets | `package.json` build 段 |

补充两条：
- 打包钩子只替换 ffmpeg，不生成任何配置：`after-pack.cjs`。
- 外部配置靠 Spring 的可选导入 `optional:file:${MINI_AGENT_HOME:${user.home}/.miniagent}/config.yml`（`application.yml:7`）；但 `config.example.yml` 里 `JWT_SECRET` 是**注释掉的**（`:51-56` 还专门解释了"留空的危害"），模板复制过来也没有可用值。`QUICKSTART.md:101` 写的是"用户自己把模板复制到 `~/.miniagent/config.yml`"。

**不修会怎样**：交付给任何非技术用户的安装包 = 双击即失败，且错误信息里只有环境变量名，**没有自助修复路径**。客服只能远程指导客户手写 YAML + 自己生成 64 位随机串。

**为什么这不算"已修"**：上一轮验收的是"新装机数据库路径"（Flyway baseline v8 + V9、26 表、索引齐全），**没有覆盖密钥落点**。09-29 工作记忆的遗留②写着"必须先把 `MODEL_CONFIG_ENCRYPTION_KEY` 持久化到固定位置，密钥落点待与斌哥定" —— 同一条根因，但**没有进 `docs/` 的任何审计报告**。

**怎么修（不硬编码，保持"每个客户一把密钥"）**：
1. 壳在首启时生成并落盘：`crypto.randomBytes(48).toString('base64')` → 写入 `${MINI_AGENT_HOME}/config.yml`（`mode: 0o600`），**已存在则不覆盖**（否则每次启动换密钥 = 全部会话失效）。
2. `JWT_SECRET` 与 `MODEL_CONFIG_ENCRYPTION_KEY` 一并生成（后者是 Base64 的 32 字节）。
3. 把 `config.example.yml` 加进 `extraResources`，出问题时用户手边有模板可对照。
4. 密码学随机数已在用（`main.js:3,702`），不需要新依赖。

> 精度说明：`MODEL_CONFIG_ENCRYPTION_KEY` **不是**启动硬阻断 —— `SecretCryptoService.init()` 在空值时把 `key = null` 直接返回（`:34-37`），只在"有密文要解密"时才失败（`:83`）。硬阻断的只有 `JWT_SECRET`。

---

### 🆕 C-2 · 关闭按钮语义 = 隐藏到托盘，且全程无提示（P1 产品级）

**跑起来看到什么**：点窗口右上角 × → 窗口消失，**看起来像退出了**。任务栏图标和托盘图标都还在，但没有气泡、没有通知、没有任何文字说"还在后台运行"。只有右键托盘菜单里才有「退出」。

**为什么**：`main.js:469-474` 的 `close` 处理是 `e.preventDefault() + mainWindow.hide()`（仅当 `app.isQuitting` 为真才真关）；托盘菜单的「退出」才设 `app.isQuitting = true`（`:508-510`）。全文搜索"仍在后台运行 / displayBalloon / Notification"**零命中**。另外因为窗口只是 hide 而不是 destroy，`window-all-closed`（`:744-748`）永不触发，进程必然常驻。

**不修会怎样**：JVM + 内嵌 H2 + Chromium（Playwright 那套）+ 内嵌 ONNX 推理常驻内存与端口。这也正是上轮"内存泄漏成为慢性问题"的前提 —— 泄漏修了，但**"用户以为退出了其实没退"这个根因还在**。

**怎么修**：首次隐藏时提示一次（Windows `tray.displayBalloon()`、mac/Linux `new Notification`），托盘 tooltip 写明"仍在后台运行"；并把关闭行为做成配置项（`closeAction: 'tray' | 'quit'`），不写死。

---

### 📌 C-3 · 桌面档 DDL 双写残留（P2）

`application-desktop.yml:116` `ddl-auto: update` 与 `:123` 的 Flyway 并存。实体注解一改就直接改库，绕过迁移链 → 结构演进没有审计轨迹。

- 已记：yml `:143` 自述"已知的双写残留（后续步骤要收口）"，`docs/detailed-design.md:687-689`。**审计报告第 4 节未列**。
- 收口方向：桌面档切 `validate`，新装机走 `DesktopFlywayConfig`（`@Profile("desktop")`，已存在）显式 baseline 后再 migrate。
- 注意：上一轮实测证明"纯 yml 救不了新装机"（空库 + `baseline-on-migrate` 不打 baseline，会从最小版本全跑），所以 baseline 必须代码显式调 —— 这个结论已在 `DesktopFlywayConfig` 里落地。

### 🆕 C-4 · 未签名 / 未公证（P2，分发阻断的下一环）

- `package.json`：`win.signAndEditExecutable: false`、`mac.identity: null`。
- `win.target` 只有 `["portable"]`，所以同文件里的 `nsis` 段是**死配置**（portable target 不读它）。
- 后果：Windows 上 SmartScreen 警告"未知发布者"；macOS 上 Gatekeeper 直接拒开（"已损坏，无法打开，你应该将它移到废纸篓"）。
- `docs/` 全文搜索"签名 / Gatekeeper / 公证"**零命中** → 文档未记录。内部自用可接受，对外分发必须先解决。

### 📌 客户端相关的已记未修（来自主报告）

| 编号 | 缺陷 | 对客户端的实际影响 |
|---|---|---|
| P1-13 | 客户端断开不取消运行（全仓无 `executionControl.cancel` 调用点）；主聊天 SSE 无心跳 / 无 `Last-Event-ID`；`SessionEventCenter` 在事件线程同步 `client.send` 无背压 | 窗口隐藏后仍在烧 token、写轨迹、跑巩固；单次长工具调用（生图 540s、LLM readTimeout 1200s）期间被中间层按 60s 默认值切断 |
| ~~P1-4 剩余~~ **已修 2026-10-09** | ~~`SessionEventCenter.SessionChannel` 的 `think`/`answer` 是**裸 `StringBuilder`**：原 `:467,472` 由 agent 循环线程 append，原 `:202,214` 由 HTTP（重连）线程 `toString()`~~ → 两个缓冲的 append / `setLength` / 「读 `length()` → `delete`」/ 读端快照已全部纳入新增的 `SessionChannel.bufferLock` | 🆕 这半条文档未记 —— 主报告 `:328` 只记了"`answer` 无上限"。并发 append+toString 会撕裂字符串或抛 `IndexOutOfBoundsException`（**实测**为 `ArrayIndexOutOfBoundsException: arraycopy: last destination index 1800 out of bounds for byte[1614]`，出在 `AbstractStringBuilder.delete` 的 `System.arraycopy`），且异常被 `sendEvent` 吞掉后会 `clients.remove(client)`，把**刚重连成功的客户端误摘** |
| P1-14 | 会话列表 N+1（逐条 `countByConversationId`）+ 无分页；客户端可控 `size` 无上限 | 大历史会话打开慢、内存涨 |
| P1-16 | 持久化历史只存 user 消息与最终答案，工具调用/结果全丢 | 重启后"回复「继续」可接着上次进度做"名不副实，模型必须重新探索 |
| P1-11 | 桌面默认走本地注册（`cloud.base-url` 默认空）→ 共用 `system` 租户，`dailyTokenLimit` 未设 → 0 → **无限额** | 单机单用户影响低；**云端自托管多用户**部署是真问题 |
| E-16 | 前端 185KB 单模板 + 39 处 `innerHTML`，无构建/lint/前端测试 | 前端改动只能靠手测回归 |

---

## 2. 云端

### 🆕 Y-2 · 限流器攒满 1 万 key 后，所有新用户永久 429（P1 可用性，且是自造故障）

**跑起来看到什么**：服务跑到一定规模后，**新**用户/新 IP 的所有请求返回 `429 {"error":"rate limit exceeded"}`，重启进程才恢复。老用户不受影响（他们的 key 已在 map 里）。

**为什么**：`RateLimitFilter:37-68`。

```java
private static final int MAX_WINDOWS = 10_000;
...
if (windows.size() >= MAX_WINDOWS && !windows.containsKey(key)) {
    response.setStatus(429); ...; return;      // ← 超限即拒新 key
}
Deque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
```

全类**没有 `remove()`**（逐行确认）→ `windows` 只增不减。所以 `size()` 一旦达到 1 万，就**永远 ≥ 1 万**。

**不修会怎样** —— 比"内存涨"严重得多：
1. 正常流量下，累计出现过 1 万个不同 key 之后，**新用户永久不可用**（这是可用性故障，不是资源问题）。
2. **上限本身把"防 OOM"变成了 DoS 放大器**：攻击者只需用 1 万个不同源 IP 发请求把 map 灌满，就能让全体新用户不可用 —— 原来只是内存单调增长，现在是一个外部可轻易触发的拒绝服务。加了 `MAX_WINDOWS` 反而让性质变坏了。

**主报告记了什么**：E-1 记了"`windows` 永不淘汰、超 10000 条后新 key 永久 429、按 `getRemoteAddr` 取 IP、无 `Retry-After`、`RedisRateLimiter` 零调用者"。**"上限把防 OOM 变成 DoS 放大器"这一层没记** → 标 🆕（升级理解，不是新代码位置）。

**怎么修（不写死数字）**：
1. 淘汰而不是拒绝：超限时按"最早窗口过期时间"淘汰一批（`windows.entrySet().removeIf(q -> q.isEmpty() || 队首 < cutoff)`），上限只做兜底熔断。
2. 兜底熔断触发时要**告警**，不能静默 429 —— 静默 429 让人误判成攻击流量而实际是自己把自己锁了。
3. `rateKey` 换 `X-Forwarded-For` 的最左可信跳（配 `server.forward-headers-strategy`），或直接接 `RedisRateLimiter` 把"多副本各自计数"变成共享计数。
4. 429 响应补 `Retry-After`。

---

### 🆕 Y-1 · `applyPlan` 锁序错误 → 同一用户两条 ACTIVE 订阅（P1，涉及钱与配额）

**为什么**：`MembershipService.applyPlan:334-358` 的动作顺序是

```
① findByUserIdAndStatus(userId, ACTIVE)   ← 普通查询，不加锁
② 把 ① 读到的旧订阅置 SUPERSEDED          ← 脏检查，提交时才 flush
③ subscriptions.save(新订阅 ACTIVE)
④ tenants.findByIdForUpdate(tenantId)      ← 最后才拿 tenant 行锁（PESSIMISTIC_WRITE）
```

并发场景（同一用户两笔**不同**订单同时确认支付）：
- `markPaid` 第一句是 `orders.findByOrderNoForUpdate(orderNo)` —— 锁的是**各自那一行订单**，两笔订单不同行，**互不阻塞**。
- 于是两条线程都执行到 ①，在各自快照里都看不到对方尚未提交的订阅 → 都执行 ③ → **两条 ACTIVE**。
- ④ 的 tenant 锁只在**最后**才互相串行化，此时两条新订阅都已插好。

**代码自述的矛盾**：`applyPlan` 的 javadoc 写"调用方必须已在事务内…… 拆成多个事务会出现'订阅是新档、配额还是旧档'的中间态"—— 它意识到了要在同一事务，但**没意识到锁拿得太晚**，事务内一样能并发读到旧快照。

**不修会怎样**：
- `current(userId)` 用 `findFirstByUserIdAndStatusOrderByIdDesc` 取 id 最大那条 → 会员等级、额度上限在两条之间**不确定地摆动**；用户看到"我买了贵的，额度却是便宜那个"（`TenantRepository.findByIdForUpdate` 的注释正是想防这个，但没防住）。
- 更隐蔽：`applyPlan` 打的是 `active.size()`（读到几条旧订阅），并发路径下会是 1 而不是 2 —— **日志反过来掩盖了问题**，事后排查会被误导。

**状态**：10-08 复核报告 #6 已记（P2）；**主报告未记**。本轮复核成立 —— 判据是"读锁序"（先读后锁），属静态可判定，无需复现。

**怎么修（二选一，建议都做）**：
1. **把 tenant 行锁提到读订阅之前**：先 `findByIdForUpdate(tenantId)` 拿到锁，再 `findByUserIdAndStatus`。同一用户的所有 `applyPlan` 就被 tenant 行串行化 —— 后面读到的必然是最新。
2. 兜底约束：给 `membership_subscriptions` 加 `(user_id, status)` 的部分唯一索引（仅 `status='ACTIVE'`），把不变式从"类注释承诺"变成"DB 保证"。

> 顺带：`markPaid` 的幂等对**同一笔订单**是有效的（`findByOrderNoForUpdate` + `status==PAID` 短路），这部分没问题。

---

### 📌 Y-3 · `DesktopLoginTicketStore.issue()` 远程可触发的无界增长（P2）

**为什么**：`DesktopLoginTicketStore:26-30` 的 `issue()` 只 `put`，**只有 `consume()` 才 `remove`**，没有任何定时/惰性清理（TTL 只在消费时判）。触发点是 `PortalController.desktopTicket`（`POST /api/portal/desktop-ticket`，`:105-111`）—— 任何有门户会话的用户都能反复调。

**不修会怎样**：调用方不 consume，记录就**永不释放**（2 分钟 TTL 形同虚设）。放大层有两级，都是本轮补充的：
1. **账号服务零限流** —— 全模块只有一个 `InternalKeyFilter`，而且它只拦 `/api/membership/`，登录/门户/票据端点没有任何速率限制。
2. 单次调用只写一条小记录，裸循环即可累积到内存可见的量级。

**怎么修**：`issue()` 里顺便做一次惰性清扫（按 `expiresAtMillis` 淘汰），或换 `ConcurrentHashMap` + 项目既有的 `@Scheduled(fixedDelayString = "${...:默认}")` 惯例周期清扫；账号服务补一层账号/IP 维度限流。

---

### 🆕 Y-5 · app 侧 `AuthService.register` 漏了账号服务已经修的东西（P3，同源分叉）

**为什么**：`mini-agent-app/.../config/service/AuthService.java:60-99` 的 `register` **没有用户名长度校验**，而它的迁移版 `AccountAuthService` 补了 `USERNAME_MAX_LENGTH = 50` 并在存在性检查**之前**判（注释里解释了为什么顺序不能换）。

app 侧的后果：超长用户名 → `saveAndFlush` 撞列宽 → `DataIntegrityViolationException` → 被 `catch` 成 `AUTH_USER_EXISTS`「用户名已被占用」→ **用户明明没重名，却被告知重名**，反复改也改不好（改短就好了，但提示指向了错误方向）。

**为什么值得单独记**：同一段逻辑在两处存在，账号服务修了、agent 侧没修 —— 这是**迁移遗漏**，不是新 bug。判据：`AuthService.java` 里 `MAX_LENGTH|USERNAME_MAX|name.length()` 全部**零命中**（只有密码长度校验在 `:70`），而 `AccountAuthService` 有 `USERNAME_MAX_LENGTH` 并在 `:98-101` 判、且在存在性检查**之前**判。

**怎么修**：把长度校验从 `AccountAuthService` 提到两处共用（`mini-agent-common` 的常量 + 校验方法），消灭第二份口径。

---

### ✅ Y-4 · 我推翻了 10-08 报告的一条结论：「孤儿租户」不成立（必须纠正，否则会去修不存在的 bug）

**报告原文**（10-08 #5）：`AccountAuthService.register` 的 `@Transactional` 内先 `createPersonalTenant(name)` 落库，随后 catch `DataIntegrityViolationException` **直接 return 不 rethrow** → Spring 不回滚 → 并发重名注册留下孤儿租户 `u-<username>`。

**回源复核：孤儿租户不成立。** 关键是报告漏看的这把锁：

```sql
-- V1__account_baseline.sql:35
UNIQUE KEY uk_tenants_slug (slug)
```

于是并发重名注册的真实路径是：
1. 请求 A、B 都通过 `existsByUsername`（此时对方未提交）。
2. A `createPersonalTenant("alice")` → `saveAndFlush` 成功。
3. B `createPersonalTenant("alice")` → `saveAndFlush` **撞 `uk_tenants_slug` 抛异常**，而这个调用**不在任何 try 内** → 异常冒泡 → **整个事务回滚** → B 的租户不会留下。

**真实缺陷（降级为 P3）**：并发重名注册对外返回 **500（错误码错）**，而不是 `AUTH_USER_EXISTS`。而且不止一条路径能产生这个错码：
- slug 撞唯一索引 → 异常未被捕获 → 500；
- 即便走的是 `users.username`（catch 内那条），Hibernate 在约束冲突后会**把会话标成 rollback-only**，`return` 出去提交时变成 `UnexpectedRollbackException` → 仍是 500。

**为什么必须写进文档**：按原结论去修，会去给 slug 加判重 —— 而 slug 的判重已经有了，改了也没用。正确的修法是"把 `createPersonalTenant` 纳入同一个 try（或改成先显式判 slug 再插），并在两种冲突下都返回 `AUTH_USER_EXISTS`"。

---

### 📌 云端相关的已记未修（主报告，本轮抽验）

| 编号 | 缺陷 | 本轮核到的关键事实 |
|---|---|---|
| P1-11 | 本地注册把所有人塞进**同一个无限额** `system` 租户 | 三处都核过：`app/AuthService.java:77-85` 建/复用 `system` 租户且**不设** `dailyTokenLimit`；`V2__production_security_and_usage.sql:8` `DEFAULT 0`；`DbTenantTokenQuota.check/consume` 里 `limit <= 0 → Decision.unlimited` |
| E-6 | 账号模块**完全没有 Spring Security** | `mini-agent-account/pom.xml` 只有 `spring-security-crypto`（不是 starter-security）；全模块 `SameSite`/`csrf`/`Cookie` 搜索**零命中**。会话靠 `HttpSession` + 服务端 `portalUserId` 属性（`PortalController:212-221`） |
| E-5 | 无 TLS；账号服务会话 Cookie 未显式设 `Secure`/`SameSite` | 零命中（未显式配置）。**精度说明**：现代浏览器默认 `SameSite=Lax` 已能挡住跨站 POST 携带 Cookie，所以"CSRF 可达性"我标 **UNVERIFIED**，不夸大成"已在被利用" |
| E-3 | 无优雅停机 | 全仓 `server.shutdown` **零命中** → 用 Tomcat 默认 immediate；发布/缩容会掐断在途运行与 SSE 长连接 |
| E-14 | 付费套餐的 `maxConcurrentTasks` 只展示不生效 | `TaskRunService.java:30` 读静态 `agent.concurrency.max-tasks-per-user:2`；`MembershipPlan.java:41` 注释**自述**"那边仍是配置里的固定值" |
| E-19 | 租户配额"先用后扣"，可超发一次完整请求 | `ExecutionControl.afterModelTokens` 调用后才 `consume`；`consume(t, 0)` 仍走 `SELECT … FOR UPDATE`（每轮一次行锁） |
| P1-17 | Redis 故障策略分裂 | 桌面档已排除 Redis（`application-desktop.yml:89-90` 排除 `RedisAutoConfiguration`）→ **只有云端形态可达** |
| P1-12 | 追踪断链 / 审计节点不落 / trace 未脱敏 / 无保留策略 / 无 Prometheus registry | — |
| P1-14 | 无界查询与 N+1 | — |
| P1-18 | 无行为 eval 门禁 / 无 README 与运维手册（CI 脚本缺失那条已修） | — |
| D-1~D-4 | `agent_events` 不清剪、Outbox `DONE` 不清理、`@PostConstruct` 全量重置 `PROCESSING`、巩固吞异常 | — |
| E-7 / E-8 / E-9 / E-15 | 上传面无嗅探与 AV、迁移 `MODIFY NOT NULL` 锁表且无回滚、非 prod 不安全默认靠 profile 兜底、`.env` 未进 `.dockerignore` | — |

### ✅ 已修（做了反向验证，不只采信文档）

| 编号 | 反向验证方式 | 结论 |
|---|---|---|
| P0-7 自助支付确认 | 读 `PortalController.java:187-199` | `@Value("${agent.account.portal-confirm-enabled:false}")` **默认 false**，未开则抛 `AUTH_FORBIDDEN`。✅ 真的落地了 |
| P1-4 泄漏簇 | 本轮改动 + 全量回归 | ✅ 事件驱动 + 空闲兜底已落地，`mvnw test` 530 例 0 失败 |
| P2-1 junction 逃逸 | 本轮修好了失效用例并实测真 junction | ✅ `PathGuard.isAllowed` 返回 false（**此前该用例从未真正建出链接**，见 `agent-severe-bugs-recheck-2026-10-08.md` 7.6） |
| P1-4 并发撕裂（`think`/`answer` 裸 `StringBuilder`） | ① 新增 `SessionEventCenterConcurrencyTest` 反射并发压测**真实类**的两个私有读写点，2 例全绿；② 同压力对照实验（6 写 4 读 × 4000 轮，200 字符/块）：裸 `StringBuilder` 6 轮里 **3 轮抛 `ArrayIndexOutOfBoundsException`（累计 23 次）**，加锁版本 **6 轮 0 次** | ✅ 真的修掉了 |
| P0-8 compose 默认口令 | 本轮**未复验** | 按文档口径视为已修，**未逐条重查** compose |

---

## 3. 优先级建议

| 序 | 项 | 理由 |
|---|---|---|
| 1 | **C-1** 出厂起不来 | 交付物在干净环境下不可用。不修则这版包发给任何非技术用户都等于零 |
| 2 | **Y-2** 新用户永久 429 | 云端上线当天就可能全站不可用，且**外部可轻易触发**（1 万个源 IP 即可），上限本身放大了后果 |
| 3 | **Y-1** 双 ACTIVE 订阅 | 涉及钱与配额；日志会掩盖问题，事后极难判 |
| 4 | **C-2** × 语义 + 无提示 | 产品级体验 + 资源常驻；也是上轮泄漏成为慢性的前提 |
| 5 | Y-3 / Y-5 / C-3 / C-4 | 资源与一致性收口、分发合规 |

---

## 4. 口径说明（未证实项，别当已发生）

1. **E-5 / E-6 的 CSRF 可达性**：账号服务零 `SameSite` 配置，但浏览器默认 `Lax` 已能挡住跨站 POST 携带 Cookie。我没有实测浏览器行为，故标 UNVERIFIED —— 危害成立的前提是"Cookie 被带上"，这一点未证实。
2. **Y-1 并发**：按"读锁序"静态判定（先读后锁），未做并发复现。属结构性缺陷，不需要复现即可判定。
3. **C-1 的"起不来"**：由 `@PostConstruct` 抛异常 + `main.js` 不注入 env + 包内无 config.yml 三段静态推定；**未在真机全新环境跑一遍**（现有验证环境里 `MINI_AGENT_HOME` 下已有 config.yml）。
4. **Y-2 的"1 万 key"**：`MAX_WINDOWS = 10_000` 是代码常量，未做压测确认达到所需时长的流量形态。
5. 主报告第 3 节标"未逐条复验"的项，按既有文档口径视为存在，**不要**据本分册认为它们已修。
