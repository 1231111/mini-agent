# 嵌入式数据库选型实测报告

> 目的：给 MiniAgent 的「出厂桌面客户端」形态（`--spring.profiles.active=desktop`）选定一个
> 客户机上零外部依赖的数据库，并给出改造量。
>
> 本报告所有数字都来自 `.verify/` 下的探针脚本实际执行输出，脚本与原始输出都留在同目录
> （`a232.txt` / `b232.txt` / `probe-c.txt` / `lob2.txt` / `schema.txt` / `tables.txt`），可逐条复核。
> 复现命令见文末。

---

## 1. 结论

**选 H2，并启用 `MODE=MySQL` 兼容模式。**

三条候选路线在同一批 SQL 上的实测得分：

| 用例 | H2 + `MODE=MySQL` | H2 默认模式 | SQLite 3.49 |
|---|---|---|---|
| `TS_COL`: `datetime(6) not null default current_timestamp(6)` | 通过 | 通过 | **失败** |
| `columnDefinition` 里的 `LONGTEXT` / `TEXT` | 通过 | 通过 | 通过 |
| 反引号列名 `` `user` `` | 通过 | 通过 | 通过 |
| `auto_increment`（`@GeneratedValue(IDENTITY)`） | 通过 | 通过 | **失败** |
| `ON DUPLICATE KEY UPDATE` ×2（两处 upsert） | 通过 | **失败** | **失败** |
| `UPDATE ... INNER JOIN ... SET` | **失败** | **失败** | **失败** |
| 迁移脚本 `ENGINE=InnoDB` / `KEY` / `UNIQUE KEY` 内联索引 | 通过 | **失败** | **失败** |
| **合计** | **19 通过 / 1 失败** | 12 / 7 | 6 / 13 |

同一份探针在 H2 **2.2.224** 与 **2.3.232**（Spring Boot 3.4.5 托管、实际打进包的那个版本）
上结果完全一致，所以上表不受 H2 小版本影响。

选 H2 的三条理由，按权重排序：

1. **改造量最小且可控** —— 19/20 的现有 MySQL 方言 SQL 原样通过，只需要改写 1 处
   （`AuthSessionRepository.revokeAllForTenant` 的多表 UPDATE）。SQLite 路线 13/19 失败，
   连两个实体基类里的 `TS_COL` 常量都建不出表 —— 那两个基类覆盖几乎所有表，等于要全面重写实体层。
2. **纯 Java，不带平台 native 库** —— `h2-2.3.232.jar` 体积 2.59 MB，内含原生库 **0 个**。
   `sqlite-jdbc-3.49.1.0.jar` 体积 13.7 MB，内含 **24 个** `.dll` / `.so` / `.dylib`。
   桌面客户端要和 jlink 裁出的 JRE、electron-builder 的安装包打在一起，
   多一批平台原生库就多一批跨平台打包与加载失败的可能。
3. **不引入新的方言依赖** —— SQLite 需要额外引入 `org.hibernate.orm:hibernate-community-dialects`
   （`SQLiteDialect` 在里面，本机 `.m2` 里原本没有）。

---

## 2. `MODE=MySQL` 不是可选优化项

对照组（H2 2.3.232，只差这一个开关）说明了它的作用范围：

| 去掉 `MODE=MySQL` 之后挂掉的 | 报错 |
|---|---|
| `AgentTokenUsageRepository.increment` | `Syntax error ... near "ON DUPLICATE KEY UPDATE"` |
| `TenantDailyUsageRepository.increment` | 同上 |
| `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` | `expected "identifier"` |
| `UNIQUE KEY uk_x (col)` | `expected "NULLS, ("` |
| `KEY idx_x (a,b)` | `expected "identifier"` |

两处 upsert 是 `@Modifying @Query(nativeQuery = true)`，Hibernate 对 native query **不做任何改写**，
原样发给驱动，所以只能由数据库自己认。

**为什么不用 SQL 层重写来消掉这两处 native query**：`ON DUPLICATE KEY UPDATE` 没有等价的
ANSI/JPQL 写法。Hibernate 没有 upsert，改写成「先 select 再 insert 或 update」会丢掉
单语句的原子性 —— 并发下会双插。保留它、让 `MODE=MySQL` 兜住，比为了可移植性牺牲原子性划算。

---

## 3. 语句级探针覆盖不到、单独验过的四项

### 3.1 `TS_COL` 常量：**不需要改**

`BaseEntity:14-15` 与 `BaseMemoryEntity:14-15` 里有同一个常量：

```java
private static final String TS_COL =
        "datetime(6) not null default current_timestamp(6)";
```

它被 `@Column(name = "created_at", nullable = false, columnDefinition = TS_COL)` 引用，
`columnDefinition` 是原样拼进 DDL 的字符串，Hibernate 不翻译。H2（两种模式都）接受这个写法，
建表实测通过。

### 3.2 `@Lob` 与 `columnDefinition` 成对出现 —— 真正验的是 **JDBC 绑定类型**

全项目 `@Lob` 恒与 `columnDefinition` 同时出现（如 `AgentEpisodeEntity:41-42`、
`AgentSessionPlanner:23-24`、`ChatTask:15`）。这两者管的是不同的事：

- `columnDefinition` 只管 **DDL 文本**；
- `@Lob` 决定 Hibernate 用哪个 **JDBC 绑定类型**（`Clob`）。

所以 DDL 建表成功不等于运行时写得进去。实测（`.verify/LobProbe2.java`）：

```
===== chat_tasks.question =====
  [OK]   setClob 写入 300005 字符
  [OK]   getString 读回 length=300005 期望=300005 完全一致=true
  [OK]   getClob 读回 length=300005
```

H2 把 `LONGTEXT` 落成了 `CHARACTER VARYING(1000000000)`（见 `.verify/lob.txt`），
不是 CLOB，但接受 `setClob` 绑定并原样读回 —— 30 万字符含中文往返一致。**结论：可写。**

### 3.3 两处 upsert 的**累加语义**（不是只看语法通过）

`ON DUPLICATE KEY UPDATE` 语法通过不代表走了 UPDATE 分支 —— 如果被当成普通 INSERT，
第二次会主键冲突而不是累加。实测连续执行两次后校验：

```
  [OK]   累加语义: input=2 output=4 version=1  (期望 2 / 4 / 1)
```

数值正确，说明确实走了 UPDATE 分支。

### 3.4 改写后 JPQL 生成的 SQL，在真实表上跑过

`revokeAllForTenant` 改写后 Hibernate 会生成（`.verify/CaseQuery.java` 里手工执行了同一形状）：

```sql
update auth_sessions set revoked=true where revoked=false
  and user_id in (select u.id from users u where u.tenant_id = 777)
```

实测 `[OK]`。语义与原 `INNER JOIN` 一致：只有 `user_id` 能在 `users` 里命中的会话会被吊销。

---

## 4. 两个刻意没加的 H2 参数

### 4.1 `DATABASE_TO_LOWER` / `CASE_INSENSITIVE_IDENTIFIERS`：**不能加**

一度加过，理由是「让 H2 贴近 MySQL 的 `lower_case_table_names=1`」。实测发现它落进库文件元数据：

```
===== 带 DATABASE_TO_LOWER + CASE_INSENSITIVE_IDENTIFIERS =====
  schemas: information_schema public          ← 默认 schema 被改名成小写
===== 只带 MODE=MySQL =====
  [FAIL] Schema "public" not found             ← 去掉参数后连不上原来的库
```

**这个参数在库文件创建时就固化了，之后去掉会让所有存量用户的数据库打不开。**
收益不值这个代价。去掉后重建库，实测一切正常：

- schema 回到 `PUBLIC`，39 张表全部大写入库（`USERS` / `CHAT_TASKS` / …）
- Hibernate 生成的小写未加引号标识符（`select id, username from users`）正确解析到 `USERS`
- `/actuator/health` = `UP`，端到端 6/6 通过

H2 对未加引号的标识符本来就大小写不敏感（统一折成大写），所以 Hibernate 的语句
和 H2 的存储之间不需要这两个开关来搭桥。

### 4.2 `AUTO_SERVER=TRUE`：没加，改用单实例锁

H2 文件模式是**单进程独占**的。实测第二个进程连同一个 `.mv.db`：

```
Database may be already in use: "...miniagent.mv.db"
Caused by: MVStoreException: The file is locked
```

而 `main.js` 的 `resolvePort()` 会先探首选端口的 `/actuator/health`，探不通就认为端口空闲、
另起一个后端起在别的端口上。后端起完要十几秒，所以双开客户端时第二次探测大概率失败，
结果是**在另一个端口拉起第二个后端、然后撞库文件锁启动失败、弹一个看不懂的错**。

修法是 `app.requestSingleInstanceLock()`（`main.js`），拿不到锁的进程直接退出并把已有窗口拉前台。
比 `AUTO_SERVER` 好：不额外开监听端口，也顺带解决托盘图标重复、两个窗口抢后端的问题。

---

## 5. 桌面档为什么 `flyway.enabled: false`

项目里 `db/migration/` 下有 7 个迁移脚本（V1~V7，共 525 行 / 34 KB），是 MySQL 方言：
`ENGINE=InnoDB`、`DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`、内联 `KEY` / `UNIQUE KEY`。
不带 `MODE=MySQL` 时这些全部报错（见第 2 节表格）。

关键事实是**这些脚本本来就不会被跑到**：

| 档 | `spring.flyway.enabled` | `spring.jpa.hibernate.ddl-auto` | 建表由谁做 |
|---|---|---|---|
| 默认（无 profile） | `false` | `update` | Hibernate 从实体反推 |
| `prod` | `true` | `validate` | Flyway 跑 V1~V7 |
| `desktop`（本次新增） | `false` | `update` | Hibernate 从实体反推 |

所以桌面档不继承 `prod` 的迁移能力，也不需要移植那 7 个脚本，
**改造面因此收敛到「实体上的 `columnDefinition` 字符串」和「3 处 nativeQuery」**。

代价要写清：桌面档没有版本化迁移能力，将来改表结构只能靠 `ddl-auto: update` 推断或手写升级脚本。
一旦有人给 desktop 档打开 flyway，这 7 个脚本在 H2 上会按第 2 节的表格报错。

---

## 6. 会话存储对 Redis 的硬依赖（同一批改造里解决）

### 6.1 问题

`JwtSessionService:65` 是 `@Autowired private StringRedisTemplate redis`（**没有** `required=false`），
`issueToken` / `resolveUserIdAndRefresh` / `logout` 三处直接调它，且 `catch` 只覆盖
`JwtException | IllegalArgumentException` —— `RedisConnectionFailureException` 会冒泡成 500。

### 6.2 为什么不能靠「redis 是不是 null」判断

`RedisAutoConfiguration` 只判断 redis 相关 class 在不在 classpath 上，**不看服务端是否可达**。
classpath 上有 `spring-boot-starter-data-redis`，它就一定给出一个非 null 的 `StringRedisTemplate`。
于是这些判断全部失效：

| 位置 | 判断 | 后果 |
|---|---|---|
| `MemoryTaskLock:55` | `if (redis != null)` | 真 → 每次加锁先连 Redis |
| `WorkingMemoryManager:143` | `if (redisTemplate == null)` | 假 → 继续走 Redis |
| `TraceSseHub:168` | `isRedisMode() && redis != null` | 安全（多判了模式） |
| `SessionEventCenter:82` | 同上 | 安全 |

前两处有 `try/catch` 兜底不会崩，但 `spring.data.redis.timeout` 是 3s，
所以每次都要先白等一次连接超时。`MemoryTaskLock` 在记忆巩固路径上，这个卡顿会被用户直接感觉到。

### 6.3 做法

1. 抽出 `SessionStore` 接口（`config/security/`），两个实现按 `agent.replica.mode` 选：
   - `redis` → `RedisSessionStore`，键 `session:jwt:{sha256(token)}`
   - `local`（默认，含 desktop）→ `DbSessionStore`，落 `auth_sessions` 表
   条件用**环境属性**而不是 `@ConditionalOnBean`：Spring Boot 的自动配置是
   `DeferredImportSelector`、在用户配置**之后**才处理，所以在普通 `@Component` 上
   `@ConditionalOnMissingBean(StringRedisTemplate.class)` 永远判得中 —— 结果是
   Redis 模式下也悄悄用了数据库后端。这个坑写在 `SessionStore` 的类注释里了。
2. 键由 `SessionKeys.digest(token)` 统一派生（SHA-256 → 64 位十六进制）。
   两个后端共用一条派生规则，所以接口只收 token 原文、不需要知道 JWT 内部结构，
   而且 `auth_sessions.token_hash` 这一列正好对上它原本的定义（"Only a SHA-256 hash of
   the browser token is persisted"）—— 实体语义没有被改动。
3. 桌面档用 `spring.autoconfigure.exclude` 排掉 `RedisAutoConfiguration` +
   `RedisRepositoriesAutoConfiguration`，让 `redis == null` 成为**真实信号**，
   上面那两处已有的降级分支才会被直接命中。

### 6.4 为什么选数据库而不是「只信 JWT」

另一个选项是干脆不要服务端会话记录，只靠 JWT 的 `exp`（默认 7 天）。没有选它，因为那会：
- 丢掉**空闲超时** —— `agent.auth.jwt-ttl-seconds`（默认 1800 秒）变成一句空话；
- 丢掉**登出立即吊销** —— 用户点了退出，那个 token 还能用满 7 天。

本项目已经把 httpOnly cookie 换成了 sessionStorage（拿「防 XSS 窃取」换「防 CSRF」，
见 `JwtSessionService` 类注释）。在此基础上再让登出不生效，等于连续削弱两层。
`auth_sessions` 表本来就在 schema 里、字段正好是 `token_hash / user_id / expires_at /
revoked / last_seen_at`，而且**在此之前没有任何代码往里写过** —— 用它不产生迁移成本。

写放大控制：`validateAndTouch` 只在「距上次活动已走过半个 TTL」时才 UPDATE，
所以绝大多数鉴权请求是只读的。最多晚半个 TTL（默认 15 分钟）生效空闲超时。

---

## 7. 端到端验证（桌面档 + H2 + 无 Redis）

`.verify/e2e_session.py`，对着 `--spring.profiles.active=desktop --server.port=18082` 的真进程跑：

```
== 0. 等待后端就绪 ==
  [OK]   GET /actuator/health = UP
== 1. 注册（会写 users + auth_sessions 两张表）==
  [OK]   POST /api/users 拿到 token  -> token 长度 185
== 2. 带 token 访问鉴权接口（走 sessionStore.validateAndTouch）==
  [OK]   GET /api/tokens/current 已认证  -> {"authenticated": true, "userId": 1, "username": "bin", "displayName": "斌哥"}
== 3. 不带 token 应为匿名 ==
  [OK]   无 token 时 anonymous  -> {"authenticated": false}
== 4. 登出（sessionStore.revoke）==
  [OK]   DELETE /api/tokens 成功  -> status=200 body={"success": true}
== 5. 同一 token 登出后应立即失效 ==
  [OK]   登出后 token 失效（匿名）  -> {"authenticated": false}

小计: 6 通过 / 0 失败
```

停掉进程后直接读库（`.verify/Sessions.java`）核对会话真的落到了数据库：

```
-- users 表 --
  id=1 username=bin display=斌哥 tenant=1
    created=2026-09-24 23:58:21.655456 updated=2026-09-24 23:58:21.655456
-- auth_sessions 表（会话记录真相源）--
  token_hash=a28fce0b3f3fd6bc... (len=64) user=1 revoked=true
     expires_at=2026-09-25 00:28:21.812732  last_seen_at=2026-09-24 23:58:21.812732
  行数: 1
```

- `len=64` → 存的是摘要，不是 token 原文
- `expires_at - last_seen_at = 30 分钟` → `jwt-ttl-seconds` 生效
- `revoked=true` → 因为最后执行了登出，`revoke` 也落库了
- 中文 `斌哥` 往返正确
- 时间戳由 `@PrePersist` 填充，说明 `BaseEntity` 在 H2 上正常

启动日志里**全文没有一条 Redis / Lettuce 输出**，只有两行新的装配行：

```
INFO  c.m.config.security.DbSessionStore    : 会话存储后端: 数据库（表 auth_sessions）
INFO  c.m.config.security.JwtSessionService : 会话存储后端: db(auth_sessions)（空闲超时 1800 秒，JWT exp 上限 604800 秒）
```

---

## 8. 本次改动的文件清单

| 文件 | 改动 |
|---|---|
| `mini-agent-app/pom.xml` | 新增 `com.h2database:h2`（`runtime`，版本由 Spring Boot 托管为 2.3.232） |
| `mini-agent-app/.../resources/application-desktop.yml` | 新增 `spring.datasource`（H2 file + `MODE=MySQL`）、`spring.jpa.properties.hibernate.dialect: H2Dialect`、`spring.flyway.enabled: false`、`spring.autoconfigure.exclude` 排 Redis |
| `mini-agent-app/.../config/repository/AuthSessionRepository.java` | `revokeAllForTenant` 从 nativeQuery 多表 UPDATE 改为 JPQL 子查询；`deleteExpiredOrRevoked` 补 `clearAutomatically` / `flushAutomatically` |
| `mini-agent-app/.../config/security/SessionStore.java` | **新增**，会话存储接口 |
| `mini-agent-app/.../config/security/RedisSessionStore.java` | **新增**，`agent.replica.mode=redis` 时装配 |
| `mini-agent-app/.../config/security/DbSessionStore.java` | **新增**，`agent.replica.mode=local`（含缺省）时装配 |
| `mini-agent-app/.../config/security/SessionKeys.java` | **新增**，键派生（SHA-256） |
| `mini-agent-app/.../config/security/JwtSessionService.java` | 去掉 `StringRedisTemplate` 直接依赖，改走 `SessionStore`；`init()` 增加会话后端日志 |
| `mini-agent-desktop/main.js` | 新增 `app.requestSingleInstanceLock()` 与 `second-instance` 处理；启动体抽成 `startUp()` |

三处 nativeQuery 的最终处置：**改 1 处、留 2 处**。

| 位置 | 处置 | 理由 |
|---|---|---|
| `AuthSessionRepository.revokeAllForTenant` | 改写为 JPQL | H2 两种模式都不支持 `UPDATE ... INNER JOIN`；JPQL 两边方言通用 |
| `AgentTokenUsageRepository.increment` | 保留 | H2 `MODE=MySQL` 原生支持且累加语义已验证 |
| `TenantDailyUsageRepository.increment` | 保留 | 同上 |

---

## 9. 尚未解决 / 需要拍板的

1. **embedding 走哪条路** —— 仓库自带 `embedding-server/` 有 2.5 GB（`Yuan-embedding-2.0-zh`，
   dimension 1792），塞不进安装包。桌面档现设 `codebase.embedding-enabled: false`，
   语义检索退化为词法匹配。走云 API 则需同步改 `embedding-model` 与向量维度。
2. **`tools.exec-enabled: false`** —— 出厂默认不放行免批执行命令。若客户端要保留执行能力需改回 `true`。
3. **出厂包 400 MB+** —— fat jar 里含 Milvus SDK 等单机档用不到的依赖，尚未做依赖裁剪。
4. **外调工具链** —— `BrowserService:929`（`mvn exec:java`）、`:890`（`npx playwright`）、
   `RenderDiagramTool:158`（`npx mermaid`）、`AgentEnvironmentTool:129`（`sh -c`）
   要求客户机有 Maven / Node / sh，出厂形态下会失败。
5. **`registration-enabled: true`** —— 单机没有账号时若关闭注册将无法登录，
   等「首启引导创建本地账号」落地后改 `false`。
6. **`assets/` 为空** —— 安装包用 Electron 默认图标。

---

## 10. 复现方式

```bash
# 兼容性探针（三组对照）。H2 jar 用出厂版本 2.3.232
H2=C:/Users/abc/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar
cd .verify
java -cp "$H2" DbCompatProbe.java "jdbc:h2:mem:p1;MODE=MySQL" sa ""    # → 19 OK / 1 FAIL
java -cp "$H2" DbCompatProbe.java "jdbc:h2:mem:p2" sa ""               # → 12 OK / 7 FAIL

SQLITE=C:/Users/abc/.m2/repository/org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar
java -cp "$SQLITE" DbCompatProbe.java "jdbc:sqlite:.verify/probe-sqlite.db" sa ""   # → 6 OK / 13 FAIL

# 起一个隔离的桌面档后端（不污染 ~/.miniagent）
mkdir -p .verify/home-e2e && cp ~/.miniagent/config.yml .verify/home-e2e/
MINI_AGENT_HOME=$PWD/.verify/home-e2e java -jar \
  mini-agent-app/target/mini-agent-app-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=desktop --server.port=18082

# 端到端会话验证
python .verify/e2e_session.py http://127.0.0.1:18082 bin 'BinAgent#2026!'
```

探针脚本清单：

| 脚本 | 验什么 |
|---|---|
| `DbCompatProbe.java` | 3 处 nativeQuery + `TS_COL` + 列类型 + 迁移脚本写法，接受 jdbc-url 参数 |
| `LobProbe2.java` | `@Lob` + `columnDefinition` 共存时的 Clob 绑定读写 |
| `CaseQuery.java` | 去掉 case 参数后，小写未引号标识符 + 改写后 JPQL 形状的 SQL |
| `SchemaProbe.java` | `DATABASE_TO_LOWER` 对默认 schema 名称的不可逆影响 |
| `Sessions.java` | 直接读库核对 `users` / `auth_sessions` / `agent_token_usage` |
| `e2e_session.py` | 注册 → 鉴权 → 登出 → 复用 token 的 HTTP 端到端 |
