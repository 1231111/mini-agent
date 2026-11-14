# Agent quality gates

默认执行离线门禁，不启动 MiniAgent、不读取个人数据库、不使用模型 Key，也不调用付费 API：

```powershell
node scripts/agent-eval/run-offline-checks.mjs
```

CI 在 Windows/Linux 执行已有 Node 客户端回归、窗口边界回归和业务验收器正反向 fixture；四平台桌面发包也先执行同一门禁。**fixture 通过仅证明验收器能识别这些样例，不代表真实 Agent、真实模型或安装包已经通过任务验收。** UI 验收器检查可用性和布局；它没有提供与参考图的审美/像素相似度评分。

## 真实任务入口

真实任务可能产生模型费用，必须显式启用。没有参数/没有 `-AllowPaid` 时，入口在导入适配器、访问服务器、创建运行目录之前非零退出。

仓库内置 `miniagent-adapter.mjs`，目前支持**生成自包含 HTML 页面**这一类真实任务。可直接使用下方示例；其他业务需要另行提供经过审查的适配器。

也可以直接调用 `run-eval.mjs --live --allow-paid --manifest ... --adapter ... --model-identity ...`。`-Tier`、`-Category`、`-Ids`、`-SmokeOnly` 支持筛选；请求任何未选中的 ID、重复 ID 或空集合都会失败。退出码：0 全部通过，1 任务/业务/SLO/漏跑失败，2 参数或预检错误。

旧的 `cases.json`、`cases-v20260911-eval.json` 和 GAIA 资料保留为历史样本，旧的“包含某字串/写出一个文件即成功”判据不能作为新入口的发布验收。原入口依赖的 `agent-api.ps1` 和 prompt 目录未随当前仓库提供，新入口使用新启动的隔离后端，不连接操作者已有客户端。

## 内置 UI 任务适配器

准备含 `com.miniagent.evaluation.ArtifactUiCollector` 的当前版本 Spring Boot fat JAR、Java 21+、Node.js 和与该 JAR 中 Playwright 版本匹配的 Chromium。已有旧 JAR 不会因为脚本更新而自动获得采集器。适配器不自动编译、安装浏览器或读取用户设置中的模型。

先编辑 [`examples/model-identity.example.json`](examples/model-identity.example.json)，将 `modelId` 改为测试端点实际支持的模型名称；该值也用于真实调用。也可以复制后编辑，并调整命令中的 `-ModelIdentity` 路径。`providerId` 和 `configurationRevision` 只保存非秘密标识。然后在专门的测试环境中注入以下变量；示例的 `TEST_LLM_BASE_URL` / `TEST_LLM_API_KEY` 应由测试凭据管理器预先提供，不要将真实 Key 写入仓库或命令记录：

```powershell
$env:MINI_AGENT_EVAL_JAR = (Resolve-Path './mini-agent-app/target/mini-agent-app-0.0.1-SNAPSHOT.jar').Path
$env:MINI_AGENT_EVAL_MODEL_BASE_URL = $env:TEST_LLM_BASE_URL
$env:MINI_AGENT_EVAL_MODEL_API_KEY = $env:TEST_LLM_API_KEY
$env:MINI_AGENT_EVAL_MODEL_MODE = 'live'

./scripts/agent-eval/run-prod-eval.ps1 -AllowPaid `
  -CasesFile ./scripts/agent-eval/examples/ui-artifact.manifest.json `
  -Adapter ./scripts/agent-eval/miniagent-adapter.mjs `
  -ModelIdentity ./scripts/agent-eval/examples/model-identity.example.json
```

| 环境变量 | 要求 |
| --- | --- |
| `MINI_AGENT_EVAL_JAR` | 必填，实际待测 fat JAR 路径；报告绑定该文件的 SHA256。 |
| `MINI_AGENT_EVAL_MODEL_BASE_URL` | 必填，测试模型的 HTTP(S) 基础端点；不接受内嵌用户名/密码、查询参数或 fragment。 |
| `MINI_AGENT_EVAL_MODEL_API_KEY` | 必填，专用测试凭据；不从桌面端配置、个人数据库或其他 Key 变量回退读取。 |
| `MINI_AGENT_EVAL_MODEL_MODE` | `live`（默认）或 `fixture`。使用本地合成模型服务时必须设为 `fixture`；即使通过也不代表真实模型能力。 |
| `MINI_AGENT_EVAL_CONTEXT_TOKENS` | 可选，默认 32768，范围 1024～10000000；应与实际模型能力相符。 |
| `MINI_AGENT_EVAL_JAVA` | 可选，Java 可执行文件的绝对路径；默认优先仓库桌面端内置 JRE，再使用 PATH 中的 `java`。 |
| `PLAYWRIGHT_BROWSERS_PATH` | 可选，已安装的匹配版本浏览器目录；默认 `mini-agent-desktop/browsers`。 |

内置适配器只接受每案例一个 `ui-artifact` 检查，并在启动模型前拒绝 `news`、`form-receipt`、混合检查或不完整交互契约。示例要求 Agent 在独立案例目录生成 `index.html`：桌面和手机视口都能看到必需控件，点击按钮后由采集器读取精确状态。页面应包含内联 CSS/JavaScript，不依赖 CDN、远程字体、网络 API、下载或弹窗；可以引用本案例目录内的资源。文件存在和模型自报成功均不足以通过。

`requiredActions` 必须包含 1～16 个动作。动作依次在首个视口执行，再调整到后续视口检查布局；不会在每个视口重复提交。支持 `click`、`fill`、`select`、`check`，其中 `fill`/`select` 的 `value` 为字符串，`check` 为布尔值。`observe` 支持 `textContent`、`value`、`checked`、`visible`，`expectedState` 为精确字符串（布尔观察值用 `"true"` / `"false"`）。`requiredSelectors` 在每个视口都必须存在且可见。视口数为 2～4 个，宽 240～2560、高 240～2160，每张图不超过 400 万像素。

隔离后端使用本次新建的 `agent-home`、数据库、测试账户、JWT、模型加密 Key、端口和浏览器 profile，验证实际生效的端点与模型。桌面控制、命令执行、MCP、向量检索和云端认证被关闭；这条验收路径不能据此证明电脑控制、外部表单提交、新闻真实性或其他业务已经通过。后端当前硬限制为 180 秒、40 次工具调用、30 次迭代，增加 manifest 超时不会自动提高这些限制。

页面证据由另一个 Java 进程通过 `PropertiesLauncher` 启动 `ArtifactUiCollector` 采集；该入口不启动 Spring、不调用模型、不读取个人数据库。采集器校验实际 HTML 哈希、渲染两种视口、保存真实 PNG，并实际操作控件及回读结果。它只允许从案例产物目录加载页面资源，阻止外部网络、WebSocket、弹窗和下载；这些请求会使检查失败。这是功能和布局验收，尚无参考图审美评分、真实供应商用量采集或可信 CI 签名，因此示例未配置费用预算，也不构成发布认证。

## Manifest 与可插拔业务校验器

格式为 `{ "schemaVersion": 1, "cases": [...] }`。每个案例必须有唯一 `id`、非空 `prompt`、正整数 `sloMs`、不小于 SLO 的 `timeoutMs`，以及至少一个已注册业务校验器。例：

```json
{
  "schemaVersion": 1,
  "cases": [{
    "id": "ai-news-today",
    "category": "news",
    "tier": "smoke",
    "prompt": "查询2026-11-13的AI技术新闻，给出来源、发布时间和摘要。",
    "sloMs": 60000,
    "timeoutMs": 90000,
    "checks": [{
      "type": "news",
      "date": "2026-11-13",
      "timeZone": "Asia/Shanghai",
      "minItems": 3,
      "minSources": 2
    }]
  }]
}
```

上面的新闻 manifest 说明可插拔校验契约，不能交给当前内置 UI 适配器执行。`fixtures.mjs` 包含三类完整合成输入，是适配器字段契约的可运行参考；不把它当真实线上事实。

| 校验器 | 业务结果与独立证据 |
| --- | --- |
| `news` | `delivery.news[]` 的 sourceId、标题、URL、发布时间逐条对应采集器 `newsSources[]`；来源须有HTTP 200、页面内容哈希、可信出版元数据证据。按 IANA 时区从来源时间戳计算日历日，拒绝把搜索时间、抓取时间或答案自己写的“今天”作为发布时间。支持来源域名白名单、必须引用的来源ID及最少独立域名数。未知发布时间不能自动视为当天。 |
| `ui-artifact` | 对本案例真实HTML文件计算SHA256，独立浏览器加载所得哈希必须一致；要求桌面/手机两种视口、对应尺寸PNG截图、无脚本/请求错误、无水平溢出、必需控件可见；可要求操作后观测状态。只有文件存在、HTML中有按钮文字或模型说“已完成”都不通过。 |
| `form-receipt` | 本地测试表单服务器独立读取的回执与本次nonce绑定；HTTP方法/路由、服务端收到的所有字段、控件回读值、上传文件名/字节数/内容哈希、回复回执号均须匹配。模型返回的success、页面上“提交成功”文字或旧回执均不充当服务端证据。 |

添加新业务检查时，在 `quality-gate.mjs` 注册纯校验函数并补正反向 fixture。Manifest 不能指定要执行的代码/命令；不支持的校验器直接失败。不要把模型生成的 `passed=true`、自行生成的日期或自己的文件当独立证据。

## 隔离适配器边界

适配器是由维护者审查、在独立监督子进程中执行的可信测试代码，不是 Agent 输出，也不是第三方不可信插件。它必须导出：

```javascript
export async function createHarness({ runId, agentHome, artifactRoot, harnessDir, modelIdentity, allowPaid, cases, signal }) {
  // 启动独立的测试进程；MINI_AGENT_HOME/DB/上传目录/浏览器profile全部使用agentHome。
  // 验证实际生效模型与modelIdentity一致，不允许连接操作者正在使用的客户端。
  return {
    mode: 'live', // 合成适配器必须标为'fixture'，其报告永远不能作发布验收
    agentHome,
    modelIdentitySha256: /* modelIdentityFingerprint(实际生效的非秘密标识) */ '',
    targetArtifact: '/absolute/path/to/the-tested-miniagent.jar', // live模式由runner计算真实包哈希
    async execute({ id, prompt, artifactDir, fixtureNonce, signal }) {
      // fixtureNonce配置到独立表单server；模型仅获得任务和专属空输出目录。
      // 返回status、terminal、delivery；DONE来自真实任务终态，不是正文中的“成功”。
    },
    async observe({ caseDefinition, result, artifactDir, nonce, signal }) {
      // 由采集器读取来源原文、真实浏览器状态或fixture server回执。
      // 返回{caseId, nonce, newsSources|ui|formReceipt|fieldReadback}，不能抄模型的“证据”。
    },
    async close({ signal }) { /* 取消/等待未完成任务、停止子进程、关闭浏览器和fixture server */ }
  };
}
```

执行器检查适配器声明的home必须是新分配的home，但这不是OS安全沙箱或可验证远程证明：隔离后端/采集器的真实性属于适配器审查与集成测试责任。监督进程固定使用本次 `MINI_AGENT_HOME`，屏蔽适配器标准输出，防止上游错误泄露凭据。启动、每案例与清理分别有硬超时；即使适配器同步死循环或不响应取消，监督进程也会终止对应进程树并停止后续案例。适配器仍需在正常close中等待任务停止/资源落盘，子进程不得自行脱离监督树。进程树终止不是副作用回滚，也不能防御恶意适配器逃逸；不要用本入口对用户真实表单、支付页面或桌面应用执行破坏性验收。

案例 `sloMs` 由父进程单调时钟测量，覆盖任务执行与独立证据采集，不采信模型/适配器自己上报的耗时。它不包含独立后端的冷启动与最终清理；这些应另设部署/冷启动验收。可选 `maxTokens`、`maxCostMicrousd`（百万分之一美元）会启用用量门禁；费用门禁还必须指定 `pricingRevision`。此时采集器必须提供 `usage: {source: 'provider-meter', totalTokens, costMicrousd, pricingRevision}`，缺失、超预算或计价版本不符都失败。不配置预算时不能据此宣称成本受控；模型估算费用不充当真实计量。

每次运行创建独占 `.verify/_agent-eval/<runId>/`，同名目录已存在即失败，绝不清空复用。每个案例仅从自己的新 `artifacts/<id>/` 读取文件；拒绝绝对路径、`..`、符号链接和目录联接，不再递归扫描用户/仓库共享workspace里的旧同名文件。

报告绑定Git commit、dirty状态及工作树哈希、manifest哈希、适配器哈希、live目标包哈希、各案例证据哈希、Node版本和模型配置标识哈希。`model-identity.json` 只需要：

```json
{ "providerId": "test-provider", "modelId": "provider/model", "configurationRevision": "config-v1" }
```

`configurationRevision` 在实际端点/模型参数变化后必须改变，适配器应核对实际服务配置；它不是自动探测能力。报告不保存Key、完整端点URL、上游异常正文、用户字段、模型回答或SSE日志；只有白名单检查代码与计数。运行时产物和截图可能含测试数据，留在忽略目录，不自动上传。运行过程中工作树变化也会使门禁失败。**执行器生成的所有报告均为 `releaseEligible:false`：手写mode=live或干净Git工作树不能证明进行了可信真实验收。**

## 只读验收报告

```powershell
./scripts/agent-eval/verify-run.ps1 -RunId <runId> -RequireLive `
  -Commit <expected-git-sha> -ManifestSha256 <expected-manifest-sha256> `
  -AdapterSha256 <reviewed-adapter-sha256> -TargetSha256 <tested-package-sha256> `
  -TrustedPublicKey C:/controlled-ci/acceptance-public-key.pem
```

普通验证重查每项检查/SLO/案例数量，空样本、漏跑、重复、失败返回非零；不隐式重试，也不联网。普通本地PASS只表示报告内部一致，不表示可信发布。

`-RequireLive` 则还必须提供外部固定的commit、suite/已审查adapter/实际目标包哈希以及受控CI的可信公钥，并验证报告的 `attestation: {algorithm: 'ed25519', signature: '<base64>'}`。签名负载由 `attestationPayload(report)` 导出（移除attestation后递归排序JSON键）；签名必须由另一个可信CI环节在核验真实实例、采集器、完整运行记录之后产生。评测器不加载签名私钥、不自动签名。缺签名、错误公钥、fixture、dirty、更换任何固定输入或篡改测量都会失败；不采信手填 `releaseEligible`。此仓库已有上述 UI 隔离适配器，但尚未部署可信签名服务，也没有因此宣称完整真实业务或安装包已通过发布验收。

旧 `rescore-run.ps1` 已停用：仅凭截断回答和共享旧文件不能把失败改成成功。需要重测必须显式发起新独立运行。
