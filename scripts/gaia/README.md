# GAIA 基准接入

[GAIA](https://huggingface.co/datasets/gaia-benchmark/GAIA)（General AI Assistants benchmark）
是目前最贴近"真实助理任务"的公开评测集：466 道题分三级，需要网页检索、文件解析
（PDF/xlsx/pptx/pdb/音频/视频）、多步推理与精确作答。对 mini-agent 这种"工具 + 循环 + 记忆"的
架构来说，它比文本相似度类评测有意义得多。

## 为什么需要一个脚本

1. **GAIA 是 gated 数据集**（`gated=auto`）：匿名请求一律 `401`。必须满足两件事 ——
   登录 HuggingFace 在数据集页面点过「Agree and access repository」，以及带一个 read token。
2. **题目分散在两类文件里**：题面/答案在 `2023/<split>/metadata.parquet`，而题目引用的附件
   （PDF/xlsx/png/mp3/MOV/pdb…）是仓库里的独立文件，靠 `file_name` 关联。
   只下 parquet 会得到一堆「附件缺失」的题 —— 那类题本地根本跑不动，
   混进结果里会把环境问题算成模型问题。
3. **评测运行器吃自己的格式**：本仓库的 `EvalRunner` / `scripts/agent-eval/run-prod-eval.ps1`
   用的是 `cases.json` 结构，需要转换。

## 用法

需要 `pandas + pyarrow + huggingface_hub`。本机已有一个具备这些包的解释器
（conda 环境 `bert_train`）：

```powershell
$PY = "C:\Users\abc\miniconda3\envs\bert_train\python.exe"

# 1) 先验凭据（不下载）
& $PY scripts/gaia/fetch-gaia.py --check

# 2) 设 token（read 权限即可；不要写进仓库文件）
$env:HF_TOKEN = "hf_xxxxxxxx"

# 3) 下载并转换（默认只下 validation：42 个文件、约 10MB，且**只有它有公开答案**）
& $PY scripts/gaia/fetch-gaia.py

# 可选：连 test 分片一起下（75 个附件、约 95MB；test 没有公开答案，只能跑链路不能判分）
& $PY scripts/gaia/fetch-gaia.py --splits validation,test

# 可选：每级只取 N 条，做快速冒烟
& $PY scripts/gaia/fetch-gaia.py --max-per-level 5

# 4) 把各分片合并成**一个 JSON**（466 道题 + 元信息，自校验后才算成功）
& $PY scripts/gaia/export-json.py
# 导出可脱离仓库使用的副本（附件路径写成绝对路径；基准是输出文件所在目录）
& $PY scripts/gaia/export-json.py --out C:\path\to\dest\gaia-all.json --absolute-paths
```

## 产物

| 文件 | 用途 |
|---|---|
| `scripts/agent-eval/gaia/data/2023/<split>/…` | 原始 parquet + 附件（**不进版本控制**，见 `.gitignore`） |
| `gaia-validation.jsonl` | 规范格式：`task_id / question / level / final_answer / file_name / file_path / has_attachment / runnable_locally`。可直接喂 GAIA 官方 scorer |
| `gaia-all.json` | **一个 JSON 看全 466 道题**：`counts` + `notes` + `questions[]`（含 answer / attachment / exists / runnable_locally）。`export-json.py` 生成，写完会自校验（id 唯一、test 无答案、声称存在的附件真实存在） |
| `gaia-summary.json` | 题量、各级分布、附件覆盖率、缺失附件数 |
| `cases-gaia-validation.json` | 转成本仓库评测格式，可直接被 `EvalRunner` / `run-prod-eval.ps1` 消费 |

## 两个必须说清的口径问题

1. **本地判分是"代理判定"，不是 GAIA 官方分数。** 仓库的 `EvalChecker` 只支持
   `response_contains` 等有限断言，而 GAIA 官方判分是**归一化后的准匹配**
   （去冠词/标点/大小写、数字与单位归一等）。所以 `cases-gaia-*.json` 里用的是
   "回复包含标准答案 + 无错误短语"——能跑通链路、能发现明显退化，但会**偏乐观**
   （答案作为子串偶然出现也算过）。要报官方分数，请用 `gaia-*.jsonl` 接官方 scorer。
2. **test 分片没有公开答案 —— 而且它的 parquet 里有个"陷阱"。**
   test 的 `Final answer` 列**不是空的**，301 行全是占位符 `"?"`。
   如果照着"非空即真答案"去转换，会得到 `response_contains("?")` 这种断言 ——
   **任何含问号的回复都算通过**，也就是一个"人人满分"的假评测，比没有数据更危险。
   本脚本显式识别占位符（`? / n/a / none / tbd` 等）并归为"无公开答案"，
   同时会删掉历史运行可能留下的、不含真答案的用例文件；
   `verify-gaia.py` 也会对"非空但唯一值只有一个"的答案列直接报错。

## 下载模式

| 命令 | 取什么 | 体积 | 能判分吗 |
|---|---|---|---|
| `fetch-gaia.py`（默认 `--splits validation`） | validation 题面 + 答案 + 全部附件 | 约 10 MB | ✅ 能 |
| `--splits validation,test` | 两个分片 + 两边附件 | 约 105 MB | 只有 validation 能 |
| `--splits test --metadata-only` | 只取题面（parquet） | 约 200 KB | ❌ 无答案 |

`--metadata-only` 用于"只想看 test 难度分布 / 做链路演示"的场景；
此时 `verify-gaia.py` 需要加 `--allow-missing-attachments`（附件压根没下是预期的）。


## 一键跑 + 自动判分

```powershell
# ① 先验脚本自身（几秒，不连服务、不需要模型 key）
.\scripts\gaia\run-gaia.ps1 -ScoreTest     # 判分逻辑自检（14 条等价/非等价样例）
.\scripts\gaia\run-gaia.ps1 -SelfTest      # 端到端自测：起本地桩服务，走完 登录→建会话→SSE→判分→报告

# ② 快速冒烟：每级 3 题（默认）
.\scripts\gaia\run-gaia.ps1 -BaseUrl http://127.0.0.1:8080 -User admin -Password '***'

# ③ 全量 validation + CI 门禁：通过率低于 30% 就返回非 0
.\scripts\gaia\run-gaia.ps1 -User admin -Password '***' -All -MinPassRate 0.3 --delay 2
```

`run-gaia.py`（`run-gaia.ps1` 只是找解释器 + 检查数据 + 转发）走的是和浏览器**完全相同**的三个接口：

```
POST /api/tokens                                → 拿 JWT
POST /api/conversations                         → 建会话（服务端签发 id）
POST /api/conversations/{sid}/messages/stream   → 发问，收 SSE（token/end/error）
GET  /api/conversations/{sid}/permission        → 跑前预检执行策略
```

### 为什么不用仓库内置的 EvalRunner

`EvalRunner` 是进程内直调：要先起完整 Spring 上下文（DB/Redis/模型 key 全就位），
而且它用的 `EVAL_USER_ID = 0L` 在库里没有对应用户 —— 每条用例都会以
`AUTH_SESSION_INVALID` 失败（审计报告 P1-18）。HTTP 方式只要服务在跑就行，
测的也正是真实链路。**EvalRunner 目前仍是坏的**，不要拿它的结果当数。

### 已验证 / 未验证（别混）

| 项 | 状态 |
|---|---|
| 判分逻辑（14 条自检样例，含假阳性防护） | ✅ 已验证，`-ScoreTest` 绿 |
| 登录 → 建会话 → SSE 解析 → 判分 → 报告全链路 | ✅ 已验证，`-SelfTest` 绿（桩服务只答对 L1，报告应显示 L1 全过、L2/L3 全不过；两者都对才算脚本可信） |
| 权限预检分支 | ✅ 已验证（桩服务返回 allow，走通该分支） |
| **连真实 agent + 真实模型的跑分** | ⚠️ **未验证** —— 写脚本时本机 8080/8081 无服务在跑。接口契约是按源码逐个核对的，但真实跑分需要你自己跑一次 |

### 跑真实 agent 前的三件事

1. **服务要起来**，且配好了模型 key（否则每题都会是"模型连接异常"）。
2. **执行策略要是 `allow`**：`agent.tools.exec-policy=ask` 时，需要跑命令的题会因
   "无人点审批"而失败，**看起来像模型答错**。脚本会跑前预检并警告；要硬性拦截就加
   `--require-exec-allow`。
3. **注意限流**：生产配置默认 30 请求/分钟，而**每题要 3 个请求** ——
   跑全量 165 题（约 495 请求）必须放慢：加 `--delay 2`（或调大服务端阈值）。
   撞到 429 会自动退避重试（`--retry-on-429`，默认 2 次），限流不会被记成答错。

### 判分口径

- 通过标准是**归一化后完全相等**（小写、去冠词、去标点、千分位、小数尾零、货币符号）。
- **子串命中单独统计、不算通过**：`"3"` 出现在 `"13 apples"` 里，短答案用子串判分必然虚高。
- 这是**近似**官方归一化。要对外报数（例如提交榜单）请用 `gaia-*.jsonl` 接 GAIA 官方 scorer。
- 报告在 `scripts/agent-eval/gaia/report.json`：含 summary（总体/分级通过率、仅子串命中数、错误数）、
  逐题结果（期望答案、模型答案、耗时、SSE 事件统计、会话 id，便于回查 trace）。

## 与现有评测的关系

`scripts/agent-eval/cases.json` 是仓库自建的用例（含工具调用断言等更细的检查）。
GAIA 补的是**外部可比性**：它不看你调了哪些工具，只看最终答案对不对 ——
两类一起跑，前者防回归，后者量能力。

CI 接法（审计报告 P1-18 建议的方向）：

```powershell
# 门禁用法：小样本 + 通过率门槛，脚本退出码即构建结果
$env:HF_TOKEN = "hf_xxx"                       # 仅在需要重新下载时用
& $PY scripts/gaia/fetch-gaia.py --max-per-level 5
.\scripts\gaia\run-gaia.ps1 -User $env:EVAL_USER -Password $env:EVAL_PASS `
    -Limit 5 -MinPassRate 0.2
```

## 安全提示

- token 是凭据：只放环境变量或 `--token` 参数，**不要**写进 `cases-*.json`、README 或提交记录。
- 下载的附件里可能包含个人信息（GAIA 部分题目来自真实网页快照）。它们已被 `.gitignore` 排除，
  不要在未确认的情况下把它们随产物一起分发。
- `run-gaia.ps1` 是 **UTF-8 with BOM**：Windows PowerShell 5.1 对无 BOM 的 `.ps1` 按 ANSI(GBK) 解码，
  脚本里的中文字符串会直接导致语法错误（仓库里其他 `.ps1` 侥幸没崩，是因为中文只在注释里）。
  改这个文件请保留 BOM。

