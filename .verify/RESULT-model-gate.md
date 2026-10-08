# `agent.models.custom-base-url-enabled` 闸门缺失 —— 诊断、修复与实测

日期：2026-09-28
范围：`mini-agent-app`，桌面档（`profile=desktop`）与 prod 档
结论：**已修复并三段实测通过（12 + 5 + 7 = 24 项断言，0 失败）**

---

## 一、结论先说

配置里写着 `agent.models.custom-base-url-enabled: false`（`application-desktop.yml` 与 `application-prod.yml` 都写了），
但**运行时没有任何代码执行这条禁令**。

用户在 `/api/model` 页面填一个自定义 `baseUrl`、`apiKey` 留空，请求就会带着**出厂的全局 API key**
发到那个地址。不需要知道密钥内容，只要改一个地址。

修复方式是把闸门放进业务代码（`UserModelConfigService`），而不是继续依赖那个只检查"配置填得对不对"的启动断言。

---

## 二、这个洞为什么存在

### 2.1 唯一的读取者是一个启动断言

全项目搜 `custom-base-url-enabled`，修复前只有一处代码读它：

```
mini-agent-app/src/main/java/com/miniagent/config/security/ProductionReadinessValidator.java:78
    if (environment.getProperty("agent.models.custom-base-url-enabled", Boolean.class, true)) {
        errors.add("custom model Base URLs must be disabled in production");
    }
```

这个类的语义是**启动断言**：它在应用启动时检查"你有没有按规矩填配置"，填错了就拒绝启动。

它的触发条件（`ProductionReadinessValidator:68-104`）：

- 只在 `prod` 档运行
- 还要求 `ddl-auto=validate`、`spring.flyway.enabled=true`、`agent.replica.mode=redis`

桌面档一条都不满足 → **这个类一次都不会执行**。

### 2.2 它检查的是"配置"，不是"行为"

即使 `prod` 档下这个断言真的跑了，它做的也只是：

> 看到配置里这个键是 `false` → 认为安全 → 放行启动

它**不会**在运行时拦住那次 PUT。所以这是个"检查了配置值、没检查实际行为"的洞 ——
在任何档下，只要用户通过 API 提交自定义 `baseUrl`，`UserModelConfigService` 都会照单全收。

### 2.3 攻击路径为什么成立：两个字段是分别回退的

`UserModelConfigService.resolve()` 里，`baseUrl` 与 `apiKey` 走的是**两条独立**的回退链：

```java
String baseUrl = base.baseUrl();     // 预设值 → 全局 langchain4j.open-ai.chat-model.base-url
String apiKey  = base.apiKey();      // 预设值 → 全局 langchain4j.open-ai.chat-model.api-key
...
if (notBlank(row.getCustomBaseUrl())) {
    baseUrl = row.getCustomBaseUrl().trim();     // ← 自定义值只覆盖 baseUrl
}
if (notBlank(row.getCustomApiKey())) {
    apiKey = row.getCustomApiKey().trim();       // ← customApiKey 为空时，apiKey 保持全局值
}
```

于是：

| 用户提交 | 结果 |
|---|---|
| `baseUrl = https://attacker.example.com/v1` | `baseUrl` 变成攻击者地址 |
| `apiKey = ""`（空串 = 不覆盖） | `apiKey` **仍是出厂的全局密钥** |

这一条在实测里有直接证据：闸门打开后 PUT 被拒，响应里 `hasApiKey` 依然为 `true`、
`apiKeyMasked` 仍是 `***0001` —— 说明密钥**确实**在那个位置上等着被引走。

---

## 三、改动清单

### 3.1 `UserModelConfigService.java`（闸门本体）

新增开关字段与启动日志：

```java
@Value("${agent.models.custom-base-url-enabled:true}")
private boolean customBaseUrlEnabled;

@PostConstruct
void logBaseUrlPolicy() {
    log.info("自定义模型 Base URL: {}（agent.models.custom-base-url-enabled={}）",
            customBaseUrlEnabled ? "允许" : "禁止", customBaseUrlEnabled);
}
```

**默认值保持 `true`** —— 原有开发行为不变（下面阶段 C 专门做了回归验证）。

`save()` 里加写入口闸门，并且**不静默**：

```java
boolean baseUrlRejected = false;
String wantedBaseUrl = blankToNull(baseUrl);
if (Objects.nonNull(wantedBaseUrl) && !customBaseUrlEnabled) {
    baseUrlRejected = true;
    log.warn("拒绝保存自定义模型 baseUrl（agent.models.custom-base-url-enabled=false）: userId={}", userId);
    row.setCustomBaseUrl(null);
} else {
    row.setCustomBaseUrl(wantedBaseUrl);
}
...
Map<String, Object> out = getView(userId);
out.put("customBaseUrlRejected", baseUrlRejected);
return out;
```

为什么要把 `customBaseUrlRejected` 回给前端：静默忽略会变成
「用户以为自己换到自有网关了，实际请求还打在我们的端点上」—— 这种"成功但没用"最难排查。

`resolve()` 里加读取口闸门（**关键**，只堵 `save()` 是不够的）：

```java
if (customBaseUrlEnabled && notBlank(row.getCustomBaseUrl())) {
    baseUrl = row.getCustomBaseUrl().trim();
} else if (!customBaseUrlEnabled && notBlank(row.getCustomBaseUrl())) {
    log.warn("忽略已存的自定义 baseUrl（agent.models.custom-base-url-enabled=false），"
            + "回退到全局配置: userId={}", row.getUserId());
}
```

为什么必须有这一半：开关是**后加的**。开关置 `false` 之前写进 `user_model_config.custom_base_url`
的历史行不会自己消失。只堵 `save()`，存量行就是一条后门。

`getView()` 里把这个策略回给前端：

```java
// 前端据此决定要不要让用户编辑 Base URL 输入框。
out.put("customBaseUrlAllowed", customBaseUrlEnabled);
```

### 3.2 `application-desktop.yml`（注释纠错）

原注释只有一句「防止出厂包里的 base-url 被改到任意地址」，读起来像已经防住了。
实际当时它是空的。改成写明**闸门的执行者是谁**、`ProductionReadinessValidator` 为什么指望不上、
以及**为什么这个洞值钱**（baseUrl 与 apiKey 分别回退那段）。

同时把文件头的「尚未落地」清单从 3 条补到 5 条，新增：

- 工具里三处外调宿主命令行（`RenderDiagramTool:158` 的 `npx` 无守卫、
  `AgentEnvironmentTool:129` 的 `sh -c` 吞异常返回空串、`BrowserService` 反而处理得当）
- `agent_token_usage` 表恒空（`TokenUsageTracker` 是静态内存 Map，`DbTokenUsagePersistence` 是孤儿 bean）

---

## 四、实测证据

环境：`java -jar mini-agent-app-0.0.1-SNAPSHOT.jar --spring.profiles.active=desktop --server.port=18083`
`MINI_AGENT_HOME=.verify/home-gate`（空的独立数据目录）
显式传一把假出厂 key：`--langchain4j.open-ai.chat-model.api-key=sk-FACTORY-FAKE-KEY-0001`

### 阶段 A：写入口闸门（`save()`）—— 12 通过 / 0 失败

脚本 `.verify/e2e_model_gate.py`，日志 `.verify/gate-phaseA.log`

| 断言 | 结果 |
|---|---|
| `GET /api/model` 返回 `customBaseUrlAllowed == false` | OK |
| 该字段确实存在（不是缺省） | OK，top-level keys = `['current','customBaseUrlAllowed','defaultPreset','presets','success']` |
| 改动前生效 `baseUrl` = 全局出厂值 | OK，`https://token-plan-cn.xiaomimimo.com/v1` |
| `PUT` 恶意 `baseUrl` → `customBaseUrlRejected == true` | OK |
| 生效 `baseUrl` 未被改动 | OK，仍是 `https://token-plan-cn.xiaomimimo.com/v1` |
| `customBaseUrl` 未落库（回显空串） | OK |
| 密钥状态改动前后完全一致 | OK，`hasApiKey=True mask='***0001'` → `hasApiKey=True mask='***0001'` |
| 重新 `GET` 复核：拒绝是持久的 | OK（两次 GET 结果一致，说明不是响应修饰） |
| 反证：只改 `modelName`、不传 `baseUrl` → `customBaseUrlRejected == false` | OK（没传就不该报拒绝） |

启动日志与拒绝日志（`.verify/gate-log-phaseA.txt`）：

```
19:  INFO  UserModelConfigService : 自定义模型 Base URL: 禁止（agent.models.custom-base-url-enabled=false）
58:  WARN  UserModelConfigService : 拒绝保存自定义模型 baseUrl（agent.models.custom-base-url-enabled=false）: userId=1
```

### 阶段 B：读取口闸门（`resolve()` 与存量行）—— 5 通过 / 0 失败

脚本 `.verify/e2e_model_gate_legacy.py`，日志 `.verify/gate-phaseB.log`

做法：停掉应用 → 用 `.verify/ModelGateRow.java` 直接往 `user_model_config` 插一行
`custom_base_url = https://legacy-attacker.example.com/v1`（模拟开关置 `false` 之前留下的历史行）
→ 重启应用 → 只读一次 `GET /api/model`。

| 断言 | 结果 |
|---|---|
| 测试前提：库里的 `custom_base_url` 确实是那个恶意值 | OK，回显 `https://legacy-attacker.example.com/v1` |
| 生效 `baseUrl` 已回退到全局出厂值，不含攻击者域名 | OK，`https://token-plan-cn.xiaomimimo.com/v1` |
| `customBaseUrlAllowed` 仍为 `false` | OK |

这一条同时说明「回显恶意值但生效值不是它」—— 排除了"库里压根没写进去"的假通过。

日志（`.verify/gate-log-phaseB.txt`）：

```
54:  WARN  UserModelConfigService : 忽略已存的自定义 baseUrl（agent.models.custom-base-url-enabled=false），回退到全局配置: userId=1
```

### 阶段 C：回归 —— 开关打开时原有行为必须恢复 —— 7 通过 / 0 失败

脚本 `.verify/e2e_model_gate_open.py`，日志 `.verify/gate-phaseC.log`
启动时追加 `--agent.models.custom-base-url-enabled=true` 覆盖档内配置。

| 断言 | 结果 |
|---|---|
| `customBaseUrlAllowed == true` | OK |
| `PUT` 自定义 `baseUrl` → `customBaseUrlRejected == false` | OK |
| 生效 `baseUrl` 就是自定义值 | OK，`https://my-own-gateway.internal/v1` |
| `customBaseUrl` 已落库并回显 | OK |
| 收尾 `PUT {"reset":true}` → `baseUrl` 回到全局出厂值 | OK |

这一步的意义：证明闸门是**配置驱动**而不是硬编码。将来要做企业版
（允许用户指向自有网关）只需改一行配置，不用改代码；同时也证明本次改动
没有把 `:true` 这个默认值的行为改坏。

日志（`.verify/gate-log-phaseC.txt`）：

```
19:  INFO  UserModelConfigService : 自定义模型 Base URL: 允许（agent.models.custom-base-url-enabled=true）
```

---

## 五、仍未覆盖 / 残留

1. **`getEffective()` 与 `getView()` 共用 `resolve()`**，所以阶段 B 的只读断言已经覆盖对话路径。
   但没有在真实对话的建连处打印实际 `baseUrl` —— 那需要一次真实的 LLM 调用，本次没引入该外部依赖。
   如果要更强的证据，可在 `EffectiveModelSettings` 出厂时打一行 `INFO`（含 `presetId` + `baseUrl`，不含 key）。
2. **多副本场景未测**：本环境是 `agent.replica.mode=local`。`customBaseUrlEnabled` 是
   `@Value` 注入的本地开关，与副本模式无关，理论上不需要额外验证，但没有实测。
3. **`agent_token_usage` 仍是空表**（与本闸门无关）：`TokenUsageTracker` 是纯静态内存 Map
   （`@Component` 是摆设），唯一写库的 `DbTokenUsagePersistence` 是孤儿 bean
   （`TokenUsagePersistence` 接口全项目无注入点）。这是既有接线缺失，已记进
   `application-desktop.yml` 文件头的遗留清单第 5 条。

---

## 六、复现方式

```bash
# 0. 打包
cd /d/AI/miniagent
./mvnw -B package -DskipTests -pl mini-agent-app

# 1. 准备空数据目录
rm -rf .verify/home-gate && mkdir -p .verify/home-gate

# 2. 启动（阶段 A）
JWT_SECRET='gate-verification-secret-please-ignore-0001' \
MINI_AGENT_HOME='D:/AI/miniagent/.verify/home-gate' \
java -jar mini-agent-app/target/mini-agent-app-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=desktop --server.port=18083 \
  --langchain4j.open-ai.chat-model.api-key=sk-FACTORY-FAKE-KEY-0001 \
  > .verify/gate-run1.log 2>&1 &

# 3. 阶段 A
"C:/Users/abc/.workbuddy/binaries/python/versions/3.13.12/python.exe" \
  .verify/e2e_model_gate.py http://127.0.0.1:18083 gate 'Gate#2026!abc'

# 4. 停应用后注入存量行（H2 文件模式单进程独占，必须先停）
cd .verify && java -cp "C:/Users/abc/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar;." \
  ModelGateRow.java 'D:/AI/miniagent/.verify/home-gate/data/miniagent' gate \
  'https://legacy-attacker.example.com/v1'

# 5. 重启（同上，日志改 gate-run2.log）→ 阶段 B
python .verify/e2e_model_gate_legacy.py http://127.0.0.1:18083 gate 'Gate#2026!abc'

# 6. 停应用 → 带 --agent.models.custom-base-url-enabled=true 重启（日志 gate-run3.log）→ 阶段 C
python .verify/e2e_model_gate_open.py http://127.0.0.1:18083 gate 'Gate#2026!abc'
```

---

## 七、受影响文件

| 文件 | 状态 |
|---|---|
| `mini-agent-app/src/main/java/com/miniagent/config/service/UserModelConfigService.java` | 修改（闸门本体） |
| `mini-agent-app/src/main/resources/application-desktop.yml` | 修改（注释纠错 + 遗留清单补全） |
| `.verify/e2e_model_gate.py` | 新增（阶段 A） |
| `.verify/e2e_model_gate_legacy.py` | 新增（阶段 B） |
| `.verify/e2e_model_gate_open.py` | 新增（阶段 C 回归） |
| `.verify/ModelGateRow.java` | 新增（存量行注入探针） |
| `.verify/gate-run{1,2,3}.log`、`gate-phase{A,B,C}.log`、`gate-log-phase{A,B,C}.txt` | 新增（原始证据，已被 `.gitignore` 覆盖） |
