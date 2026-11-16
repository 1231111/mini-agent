# MiniAgent 第二轮性能优化验证报告

日期：2026-11-16。范围：todo 重复持久化、浏览器状态重复写入、快照等待预算、DAG 执行归属检查。本轮保留上一轮记忆缓存、文章抽取、语义验收凭据与工具排队优化，以及 DAG 证据提交修复。

本轮代码与本地回归验证完成；四条局部路径的收益有可复查证据。验证环境为 Windows x64、Temurin 21.0.11+10、Node 24.16.0。模型链路验证使用本地确定性服务和隔离 H2 数据库，没有使用个人模型配置或数据库。

## 结果

| 验证场景 | 优化前 | 优化后 | 口径 |
| --- | ---: | ---: | --- |
| 200 次未变化的子目标读取：保存 / UI 推送次数 | 200 / 200 | 0 / 0 | 真实 store 与工具，合成持久化端口；权威读取仍为 200 次 |
| 100 次 DAG submit：持久化读取次数 | 500 | 300 | hard focus，12 节点；每次实际提交仍保存和推送 |
| 100 次旧 completed 兼容请求：持久化读取次数 | 600 | 400 | 保留证据提交兼容协议和调度器验收权 |
| 含相同 64 KiB 合成字段的浏览器状态保存 20 次：密文重写次数 | 20 | 0 | 每次仍回读、解密并验证现有文件 |
| 含相同 64 KiB 合成字段的状态：单次保存均耗时 | 21.366 ms | 9.964 ms | 本机文件系统探针，不含 Playwright `storageState()` 捕获 |
| 含相同 1 MiB 合成字段的状态：单次保存均耗时 | 43.178 ms | 22.142 ms | 同上，密文重写次数也从 20 降到 0 |
| 默认 full 快照 SDK 最大等待配置 | 120,000 ms | 不超过 15,000 ms | 配置上限变化，不是任务实测耗时；工具内使用剩余预算并预留 100 ms |
| 200 条合成审计事件：DAG 归属检查中位耗时 | 1.6165 ms | 0.6397 ms | 本地 H2、真实 Spring Data 与事务代理 |
| 2,000 条合成审计事件：DAG 归属检查中位耗时 | 11.8285 ms | 1.3631 ms | 压力场景，超过默认事件保留数量，不代表普通任务 |
| 无审计事件：DAG 归属检查中位耗时 | 0.7838 ms | 0.7902 ms | 没有证明有性能收益，不把波动算成优化 |

这些是局部代码路径的测量，不能相加或换算为整个智能体的提速比例。未调用真实模型服务，尚无真实模型任务的首响应、完成耗时或 P95 改善数据。

## 代码变化与行为保证

### 1. Todo 只保存实际变化，回执使用本次快照

`TaskTodoStore.currentSubGoalDetail` 每次仍读取权威持久化状态，只有依赖晋级、确认等待等导致条目变化时才保存、推送。没有变化的查询不再重写会话全部任务分区。显式历史重放继续推送清单。

`TodoTool` 的 DAG submit 及旧 completed 兼容回执，使用本次已验证、已保存的不可变结果生成 `todo / stats / items`，避免为格式化回执再次读取数据库。执行日志也使用传入的同一操作快照。普通 set/update 回执仍有进一步优化空间。保留 ID 对齐、依赖校验、用户授权、终态保护和旧 completed 兼容路径；没有增加跨请求授权缓存。

### 2. 相同浏览器登录态不重写密文

`BrowserSessionStateStore.save` 每次仍校验路径、所有者权限、密钥与 GCM 密文完整性，解密后的状态与本次完全相同时返回。真实变化继续重新加密并执行强制落盘、原子替换。没有缓存明文登录态或密钥；损坏、密钥丢失、非普通文件及其他身份密文均不能被静默覆盖。

本轮仍在每次浏览器操作后调用 `context.storageState()`；仅减少相同内容的后续加密和写盘，未宣称消除了浏览器状态捕获成本。

### 3. 快照使用实际剩余预算

新增单调时钟 `ToolExecutionDeadline`，从工具执行预算传到 Playwright 专属 actor，排队不重置预算，嵌套操作只能收紧预算。full 快照直接调用使用工具声明的默认 15 秒；经 pipeline 的普通或 full 快照继承本次实际预算，包括配置覆盖。

在启动新页面前、获取无障碍树、绑定元素引用及处理观察结果时检查剩余时间；显式设置 ariaSnapshot 和 elementHandle 的剩余等待上限。预算不足时不传 SDK 中代表无限等待的 0 值；未完成观察不发布可操作引用，并释放已取得的临时元素句柄。

保留 Playwright 单线程和已派发动作的 UNKNOWN 保护。导航已经成功、后续观察超时，返回未知结果并要求重新观察，不自动重复导航。被重定向安全策略明确拦截的导航仍返回失败。没有通过中断共享 actor 来取消可能已提交的输入。

此改动限制已识别的快照与元素等待路径，不表示浏览器冷启动、所有同步 SDK 调用及文件系统持久化都能被硬中断。

### 4. DAG 归属检查不再加载审计历史

数据库路径使用权威标量查询，仅取 `execution_id / state_json`，不选择或解析 `events_json`。独立 READ_COMMITTED 读事务避免复用外层 JPA 实体或旧事务快照。仍完整反序列化任务图，保留构造约束，以及执行身份、计划版本、所有请求节点的 RUNNING 状态检查。

数据库异常不使用 Redis 旧快照授权；执行替换、计划修订、节点结束和删除均使原执行失效。工具派发和最终 CAS 保持原有权威校验。200 ms 检查频率未改变。

H2 探针固定 32 个节点和 32 KiB 合成输出，每条路径预热 30 次，再交替顺序采样 150 次。2,000 事件压力场景中，调用线程平均分配从 29,920,874 降到 370,148 字节；选取 JSON 大小从 4,438,503 降到 53,352 字节。后者是选中列的 UTF-8 大小估计，未测量网络流量。无事件场景的分配略增，完整任务图解析仍是剩余成本。

## 回归与构建

- Java 定向回归：79 个测试类、690 项通过，无失败、错误或跳过；已核对选择清单与全部 XML 报告一致。包含此前 DAG、执行隔离、授权、取消、未知副作用及性能优化回归。这不是整个仓库的全量测试。
- Node 离线检查：128 项通过，无失败或跳过。
- 真实 Chromium：13 个测试类、78 项通过，无失败、错误或跳过。覆盖文章抽取、表单、文件上传、回读、跨框架字段、取消、重定向拦截、快照预算及客户端产物展示。
- 当前源码 fat JAR：Maven `package -DskipTests` 成功；该命令仍编译测试，运行测试由前述独立回归完成。
- 新 JAR 实际启动：adapter 与 supervised runner 两套隔离验证均通过，涵盖策略路由、同步和流式模型协议、执行 `write_file`、HTML 文件交付，以及 1280×800 / 390×844 下打开页面、点击并回读交互结果。两次实例记录的 JAR SHA-256 均与本次构建一致。
- supervised runner：1 / 1 场景通过，独立执行 `verify-report.mjs` 返回 `VERIFY PASS`。

两套 JAR 验证均为 `mode=fixture`、`releaseEligible=false`，验证的是服务和执行链路。它们使用 direct 路由，完整 DAG 状态和调度保障由 Java 回归覆盖；本次没有真实模型 DAG 任务的端到端性能结论，也不构成生产发布认证。上述测试组有不同覆盖范围，不将其简单相加为独立场景总数。

构建产物：`D:/AI/miniagent/mini-agent-app/target/mini-agent-app-0.0.1-SNAPSHOT.jar`，552,320,017 字节。SHA-256：

```text
e1614220a28aa2c5d04294bbdba629b02b1734fd9a11a8e18e745f6478c200cf
```

首次 Chromium 回归发现一处状态兼容回归：安全策略明确拦截重定向时被误归为 UNKNOWN。已收窄判断，增加“明确拦截仍为失败”回归；修正后定向 9 类、57 项通过，并纳入上述最终 Java 回归。首次失败证据保留，完整 Chromium 结果以最终目录为准。

## 证据与复现

统一证据目录：`D:/AI/miniagent/.verify/performance-round2-2026-11-16`。

- `todo/baseline.json`、`todo/optimized.json`：持久化端口计数；`todo/measure.ps1` 可对保存的基线和当前代码复测。
- `browser-state/before.json`、`browser-state/after.json`、`browser-state/comparison.json`：合成登录态与密文重写探针。
- `snapshot-budget/before.log`、`snapshot-budget/after.log`：真实 BrowserService 搭配受控 SDK，记录等待上限；不是生产延迟测量。
- `fence/RESULT.md`、`fence/probe.log`、`fence/FenceProbe.java`：H2 方法、样本和原始结果。
- `selected-java-classes.txt`、`final-integration-reports`、`final-java-summary.json`、`final-integration-maven.log`、`offline-checks.log`：最终统一回归记录；此前的首轮记录保留。
- `browser-contracts-final/manifest.json`、`browser-contracts-final/summary.json`、`browser-contracts-final.log`：最终 Chromium 清单、XML 汇总与日志。
- `package.log`、`jar.json`：构建结果和 JAR 身份。
- `adapter-smoke-1794800275357-9211d775/summary.json`：独立 adapter 验证；`harness/instance.json` 记录本次启动 JAR 的哈希。
- `runner-smoke-1794800310476-2c1b68a2/summary.json`、其 `supervised/report.json` 与 `supervised/harness/instance.json`：受监督评测、截图和构建身份；`verify-report.log` 为独立校验结果。
- `validation-summary.json`：本轮最终验证汇总。

统一 Java 回归使用选择清单执行 Maven，报告写入独立目录；浏览器合约通过 `node scripts/run-browser-contracts.mjs --evidence-dir=<新的证据目录>` 执行，不能与其他 Maven 构建并行。

在仓库根目录可顺序复现构建与隔离链路验证：

```powershell
.\mvnw.cmd -B -ntp -q -pl mini-agent-app -am package "-DskipTests"
node .verify/performance-round2-2026-11-16/adapter-smoke.mjs --jar D:/AI/miniagent/mini-agent-app/target/mini-agent-app-0.0.1-SNAPSHOT.jar
node .verify/performance-round2-2026-11-16/runner-smoke.mjs --jar D:/AI/miniagent/mini-agent-app/target/mini-agent-app-0.0.1-SNAPSHOT.jar
```

`.verify` 中的探针、脚本和原始结果是本地验证证据，不是业务运行依赖。它们与本报告分别保存，复现时需要保留该目录。

## 仍未覆盖的性能工作

路由、DAG 编译和执行的模型往返仍串行；部分 direct 任务仍携带较多工具 schema；模型重试存在多层预算；上下文摘要可能占用主调用链；DAG 节点仍逐个调度，浏览器仍共享 actor；大记忆库冷查询仍需持久向量索引。上述工作需要独立实施和真实服务分段计时，不能根据本轮局部探针认定已经优化完成。

本轮未替换已安装的 Windows 桌面程序。新后端需要重新打包并更新客户端后生效。
