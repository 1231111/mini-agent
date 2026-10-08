# 内联 ONNX embedding —— 验证报告

日期：2026-09-28
范围：Task #13 第 0 步（导出 + 一致性闸门 + 延迟基准 + Java 侧可行性探针）
结论：**全部 PASS，可以进入 Java 侧正式接入**

---

## 一、结论摘要

| 问题 | 答案 |
|---|---|
| 能不能把 `embedding-server` 这个 Python sidecar 去掉，改进程内推理？ | **能**，Java 侧余弦相似度 1.0000000000（fp32） |
| Java 侧 tokenizer 会不会和 Python 分叉？ | **不会**，20 条边界样本 ids 逐 token 一致 |
| Java 侧要自己写 padding/truncation 吗？ | **不要**，DJL 自动应用 `tokenizer.json` 内建配置 |
| LangChain4j 自带的 `OnnxEmbeddingModel` 能直接吃这个模型吗？ | **不能**，见第五节 |
| 内联比 HTTP 快多少？ | 只快 1.2~1.7x。**收益不在延迟，在"去掉 sidecar"**，见第五节 |
| 出厂该带哪个变体？ | **fp16（655 MB）**，fp32（1308 MB）精度无增益 |

---

## 二、模型与管道的来源（逐段核实，不是推测）

管道定义来自 `<模型目录>/modules.json`，不是按"标准 sentence-transformers 应该是这样"假设的：

```
Transformer(BERT)  →  Pooling(mean, include_prompt=true)  →  Dense(1024→1792, bias, Identity)
最后 encode(normalize_embeddings=True) 做 L2 归一化
```

| 环节 | 实现细节 | 出处 |
|---|---|---|
| mean pooling | `sum(token_emb * mask, 1) / clamp(mask.sum(1), min=1e-9)` | `Pooling.forward` 的 `pooling_mode_mean_tokens` 分支 |
| `clamp` 下限 | **必须保留 `1e-9`** —— 空串时 mask 求和为 0，不 clamp 会除零得 NaN | 同上 |
| Dense 层 | 权重 1024→1792，`activation_function` 是 `Identity` | `2_Dense/config.json` |
| L2 归一化 | `F.normalize(p=2, dim=1)` | `util.normalize_embeddings` |

**踩过的坑（`Dense` 不能当 `nn.Linear` 用）**：`Dense.forward(features: dict)` 内部做 `features.update(...)`，直接喂 Tensor 会报
`AttributeError: 'Tensor' object has no attribute 'update'`。只能取它内部的 `nn.Linear`。

---

## 三、导出结果

| 变体 | 主文件 | 伴生 `.data` | 合计（十进制） | 合计（MiB） | 占 fp32 |
|---|---|---|---|---|---|
| fp32 | 2.74 MB | 1305.2 MB | **1308.0 MB** | 1247.4 MiB | 1.000 |
| fp16 | 2.75 MB | 652.6 MB | **655.4 MB** | 625.0 MiB | 0.501 |
| int8 | 329.7 MB（无伴生） | — | **329.7 MB** | 314.5 MiB | 0.252 |

> **体积必须算上伴生 `.data` 文件**。只看主文件会得到"2.7MB"这种假数字 —— 1.3GB 权重在 external data 里。

导出签名（三个变体一致）：

```
inputs : input_ids      tensor(int64) [batch, sequence]
         attention_mask tensor(int64) [batch, sequence]
outputs: sentence_embedding   tensor(float) [batch, 1792]   # 未归一化
         normalized_embedding tensor(float) [batch, 1792]   # 已 L2 归一化 ← Java 侧用这个
```

实测 opset = **18**，导出器 `dynamo=True`，导出耗时 23.3s。
（请求 17 会触发一次注定失败的自动降级转换 —— dynamo 导出器最低只实现到 18。）

**fp16 变体用 `keep_io_types=True`**，所以对外输入输出仍是 `int64`/`float32`，Java 侧不需要处理半精度类型。

---

## 四、数值一致性闸门

门槛 `cosine >= 0.9999`。参考实现是 `sentence-transformers`。

### Python 侧（ONNX vs sentence-transformers）

| 变体 | 归一化 cosine min | 未归一化 max\|diff\| | 批量填充不变性 | 判定 |
|---|---|---|---|---|
| fp32 | 0.9999998808 | 5.051e-06 | PASS | **PASS** |
| fp16 | 0.9999998808 | 6.410e-04 | PASS | **PASS** |
| int8 | 0.9317430854 | 7.013e-01 | PASS | FAIL（仅作数据点） |

**int8 为什么不能出厂**：最差样本是 `s17`（空串）。动态量化对权重做 int8 近似，空串只有 `[CLS][SEP]` 两个有效 token，池化后向量本身接近"零点"，量化误差在这个位置上相对占比最大，cosine 掉到 0.93。这不是"再调调就能救"的量级差 —— 量化后已经无法保证召回排序稳定。

**未归一化输出必须单独比**：归一化是除法，会把绝对差压小。只看归一化 cosine 会把 6e-04 的偏差看成"几乎无差"。

### tokenizer 等价性

20 条样本覆盖：超长（>512 走 `LongestFirst` 截断）、空串、单空格、纯标点、`[UNK]` 罕见字、emoji、全角、大小写、换行、URL/路径。

结果：**单条 0 处不一致，批次完全一致**。`tokenizer.json` 内建的 `padding(BatchLongest/Right)` 与 `truncation(LongestFirst/max_length=512)` 由库自动应用，Java 侧不需要任何自定义 padding/truncation 代码。

> 核实过程中的一个自我更正：`encode_batch(20条)` 实测**全部 pad 到 512**，第一眼像是"固定 pad 到 512"。回查 `tokenizer.json` 才发现 padding 策略是 `BatchLongest` —— 全部 512 只是因为批次里含 `s15`（被截断到 512 的超长样本），把批次最长长度拉到了 512。如果照第一眼印象去写 Java 侧 padding，实现就会错。

---

## 五、延迟基准（p50，含 tokenize，线程数固定 8）

| 场景 | L1 ONNX fp32 | L1 ONNX fp16 | L1 ONNX int8 | L2 PyTorch 同进程 | L3 HTTP 进程外 |
|---|---|---|---|---|---|
| short(~30字) | **56.11 ms** | 67.98 ms | 24.87 ms | 80.53 ms | 92.96 ms |
| long(~400字) | **281.60 ms** | 350.09 ms | 132.87 ms | 426.71 ms | 425.99 ms |
| batch8(短) | **378.96 ms** | 422.55 ms | 158.06 ms | 427.37 ms | 465.64 ms |

加速比（p50）：

| 对比 | short | long | batch8 |
|---|---|---|---|
| L1(fp32) vs L2(PyTorch) | 1.4x | 1.5x | 1.1x |
| L1(fp32) vs L3(HTTP) | **1.7x** | **1.5x** | **1.2x** |

### 这组数字推翻了"内联=大幅提速"的直觉，必须照实说

- **HTTP 那一跳只值 13 ms**（short: 92.96 → 80.53，这就是网络 + JSON 序列化 1792 个 float 的全部成本）。
- 瓶颈是 **CPU 前向本身**（BERT-base 规模，12 层 + 1024 维 hidden）。
- 所以内联的真实收益是：**去掉 2.5 GB 的 Python sidecar、去掉一个进程/端口/启动顺序依赖、去掉外部 API 依赖**。延迟只是附属好处。
- **fp16 在 CPU 上比 fp32 更慢**（+21%）：CPU 没有原生 fp16 矩阵乘，反而要多插 Cast 节点。fp16 的价值**只在体积**（省一半），不在速度。

---

## 六、LangChain4j 的 `OnnxEmbeddingModel` 为什么不能直接用

先查清楚再决定，避免"自己重写一遍其实库里就有"。

- 坐标：`dev.langchain4j:langchain4j-embeddings:1.15.0-beta25`
  （注意版本号：core 是 GA 的 `1.15.0`，embeddings 系列一直是 `-betaN` 后缀，BOM 里用 `${langchain4j.beta.version}` 管理。写 `1.15.0` 会 `Could not find artifact`。）
- 它的依赖正好是我需要的两件：`com.microsoft.onnxruntime:onnxruntime:1.20.0` + `ai.djl.huggingface:tokenizers:0.36.0`

### 不能直接用的硬证据（`javap -c` 字节码）

```java
private float[] toEmbedding(OrtSession.Result result) {
    float[][][] out = (float[][][]) result.get(0).getValue();  // checkcast "[[[F"
    float[][] firstBatch = out[0];
    return pool(firstBatch);                                    // 池化 [seq][hidden]
}
```

三个不可调和的假设：

1. **`checkcast [[[F`** —— 硬性要求 3 维张量 `[batch, seq, hidden]`。我的模型输出是 2 维 `[batch, 1792]`，直接 `ClassCastException`。
2. **`result.get(0)`** —— 硬编码取第 0 个输出，不认输出名。
3. **池化在 Java 侧做，且不做 Dense 层**。我的模型里 mean pooling + Dense(1024→1792) + L2 归一化都已经在图里做完了。

**所以不能"改造模型去适配它"**：一旦改成输出 token 级 hidden state，Dense 层就丢了，维度会从 1792 变 1024，且数值全错。

**正确做法：只用它的两个依赖，自己写 model。** 好处是池化/Dense/归一化全都留在 ONNX 图里 —— 单一事实来源，与已验证的 Python 参考实现逐位对齐。**Java 侧只负责 tokenize 和喂图。**

---

## 七、Java 侧可行性探针（`Probe.java`，不改项目代码）

用 `onnxruntime 1.20.0`（Java 绑定）+ `ai.djl.huggingface:tokenizers 0.36.0`，跑 20 条样本与 Python 金标准逐项比对：

| 检查项 | fp32 | fp16 |
|---|---|---|
| token ids 逐 token 一致 | **是**（20/20） | **是** |
| attention_mask 一致 | **是** | **是** |
| cosine(normalized) min | **1.0000000000** | 0.9999999042 |
| 未归一化 max\|diff\| | 4.004e-06 | 7.105e-04 |
| Java 侧 L2 范数范围 | 0.999999218 ~ 1.000000734 | 0.999599852 ~ 1.000297543 |
| 判定 | **PASS** | **PASS** |

### 三个可直接用于接入的实测事实

1. **ONNX 加载 1.3 GB 模型只要 1.0 秒**（fp16 0.5 秒）—— ORT 对 external data 是 mmap，不往堆里复制。所以进程启动成本可接受。
2. **DJL tokenizer 不传 options 时自动应用 `tokenizer.json` 内建的 padding/truncation** —— 实测 `newInstance(path, new HashMap<>())` 与显式 `optPadding(true).optTruncation(true).optMaxLength(512)` 结果**完全相同**。Java 侧只需要 `newInstance(path, new HashMap<>())` 一行。
3. **DJL tokenizers 的 jar 自带全平台 native**（`ai.djl.huggingface:tokenizers:0.36.0`，18 MB）：
   - `native/lib/win-x86_64/cpu/tokenizers.dll` (11.7 MB) + `libgcc_s_seh-1.dll` + `libstdc++-6.dll` + `libwinpthread-1.dll`
   - `libtokenizers.so`（linux x86_64 / aarch64）、`libtokenizers.dylib`（**只有 osx-aarch64，没有 Intel Mac**）
   - **不能**靠 pom 里的传递依赖拿到 native —— 这个 pom 只依赖 `ai.djl:api`，native 全在 jar 内部，出厂时可按目标平台裁剪。

---

## 八、出厂选型建议

| 维度 | fp32 | fp16 | int8 |
|---|---|---|---|
| 体积 | 1308 MB | **655 MB** | 330 MB |
| 短文本延迟 | **56 ms** | 68 ms | 25 ms |
| 数值可信 | 满分 | cosine 0.9999999 | **不可用（0.93）** |
| 建议 | 精度基准 | **出厂默认** | 不用 |

**推荐 fp16 出厂**：省一半体积，cosine 0.9999999 远高于门槛，代价是慢 21%。
**int8 的速度（2.3x）很诱人但不能要** —— 0.93 的 cosine 会破坏向量召回排序，属于"看着能跑、实际会错"的典型。

---

## 八之二、第 1 步：Java 侧接入（已落地并实测）

### 改了什么

| 文件 | 改动 |
|---|---|
| `pom.xml` | 新增 `onnxruntime.version=1.20.0` / `djl.version=0.36.0` 与三个 dependencyManagement 条目 |
| `mini-agent-common/pom.xml` | 引入 `com.microsoft.onnxruntime:onnxruntime` 与 `ai.djl.huggingface:tokenizers`（**不引** `langchain4j-embeddings`，理由见第六节） |
| `LocalOnnxEmbeddingModel.java`（新增） | 进程内推理：tokenize → 喂图 → 取 `normalized_embedding` |
| `SharedEmbeddingModel.java` | 新增 `agent.codebase.embedding-provider`（`remote` / `local-onnx`）分派，对外 API 不变 |
| `VectorMemoryStore.java` | **收口**：删掉自己 build 的 `OpenAiEmbeddingModel`，改用 `SharedEmbeddingModel` |
| `CodebaseSearchTool.java` | **收口**：同上 |
| `application.yml` | 补 `embedding-provider: remote` 与 local 三项默认值 |
| `application-desktop.yml` | `embedding-enabled: false` → **`true` + `provider: local-onnx` + fp16 模型** |

### 为什么必须收口那两处

`VectorMemoryStore` 与 `CodebaseSearchTool` 各自复制了一份 embedding client 构造逻辑，判定条件是
`apiKey 非空`。桌面档切到 `local-onnx` 后，这两处仍指向 HTTP，而出厂配置里 apiKey 为空
→ `isEnabled()=false` → **记忆向量检索退化为全量注入、codebase 语义检索不可用，且日志里没有任何异常**。
不收口的话，内联 embedding 只落地一半，而且是静默的那一半。

### 性能实测（fp16，20 条边界样本，线程数 8）

| 场景 | 耗时 |
|---|---|
| 首次初始化（DJL native 解压 + 建 ORT 会话 + 载 tokenizer） | 5031 ms |
| `embedAll(20 条)` —— 初版（整批交给 batchEncode） | **15627 ms** |
| `embedAll(20 条)` —— 改为按 token 数排序分桶后 | **2867 ms** |
| 逐条 `embed()` × 20 | 5002 ms |

**5.4 倍差距全部来自 padding 空算**：`tokenizer.json` 的 `BatchLongest` 会把整批补到批内最长，
20 条里混了那条被截断到 512 token 的超长样本，于是其余 19 条都被补到 512 白算。

改法：**先逐条 `encode` 拿真实 token 数（单条不 padding），按长度排序后自己切批并手工补齐**，
批内最长/最短比例控制在 2 倍以内。这样既拿到批量的并行收益，又不吃 padding 的亏。
优化后 `embedAll` 反而比逐条更快（2867 vs 5002 ms）。

> 注意：只按"字符数"排序是不够的 —— 中英混排时字符数与 token 数不成比例，
> 而 padding 浪费直接取决于 token 数。所以是先 encode 再排序，只 tokenize 一次。

### 生产类数值验证（判的是接进项目的那份实现，不是探针）

| 项 | cosine min | 结论 |
|---|---|---|
| `embedAll(20条)` vs Python 金标准 | 0.9999999098 | PASS |
| 逐条 `embed()` vs 金标准 | 0.9999999098 | PASS |
| batch 与 single 互相一致（填充不变性） | 0.9999999909 | PASS |

零向量 0 条，L2 范数范围 0.999613 ~ 1.000290。

### 固化成测试

`mini-agent-common/src/test/java/.../LocalOnnxEmbeddingModelTest.java`，**5 项全过（17.1s）**：

| 用例 | 覆盖什么 |
|---|---|
| `missingModelDegradesInsteadOfThrowing` | 模型缺失时"不可用"而非崩（配置缺失应是功能降级） |
| `embedAllAlwaysReturnsOneEntryPerInput` | 返回条数 == 入参条数 |
| `blankInputIsConsistentAcrossBothEntryPoints` | `embed("")` 与 `embedAll([""])` 必须一致（防这次的 bug 回归） |
| `matchesPythonReference` | 主闸门：逐条 / 批量 / 两者互比，三条 cosine ≥ 0.9999 |
| `mixedLengthBatchKeepsAlignment` | 30 条长短混排（第 17 条是超长文本），验证分桶后下标回填不错位 |

模型与金标准都在仓库 `.verify/` 下（655MB 不适合进 `src/test/resources`），
**文件缺失时自动跳过**，不会让没导过模型的环境构建失败。

### 运行期验证：desktop 档真实启动（已验证的部分）

按 desktop 档起真实进程（独立 `MINI_AGENT_HOME`，命令行覆盖模型路径）：

```
已选择内联 embedding 后端: provider=local-onnx, model=.../yuan-embedding-2.0-zh.fp16.onnx
内联 embedding 就绪: yuan-embedding-2.0-zh.fp16.onnx (dim=1792, threads=4, 加载耗时 4775 ms)
Started MiniAgentSpringbootApplication in 13.724 seconds
```

- 收口改动没有破坏依赖注入（新增的两个 `@Autowired SharedEmbeddingModel` 注入点正常解析）
- provider 分派、`@Value` 新键绑定、`@PreDestroy` 均正常
- **启动期**就完成模型加载（不是首次调用时），首次查询不会额外等 5 秒
- 异常数 0
- `JWT_SECRET` 未设时启动直接失败并给出明确报错（`agent.auth.jwt-secret must be set`）—— 既有行为，与本改动无关

全量测试：**383 项 0 失败**（common 8 / tools 109 / loop 131 / planner 95 / app 40）。

### 未验证项（如实记录，不声称通过）

**业务路径上的收口改动只在编译与单测层面过了，没有跑通真实调用链** ——
即 `VectorMemoryStore.reindex` / `recall` 与 `CodebaseSearchTool.handle` 里
改成 `embeddingService.embed(...)` 的那几行。

- 触发路径存在（`POST /v1/memory/memories` 写入会触发 reindex，`.../memories/search` 触发召回），
  但两个端点都要求 `Authorization: Bearer` 认证；
- 端到端脚本已写好：`.verify/verify_inline_embedding_e2e.py`，
  设计成用**与目标条目零关键词重叠**的语义查询来证明向量真的在算
  （查询「服务突然整体卡顿，怀疑是后端资源不够了」应命中
  「数据库连接池被占满之后，新的请求会一直在队列里等待，表现为整体响应变慢」）——
  词法匹配不可能命中，只有向量召回能做到；
- 但**执行时被安全审批拦截，未跑成**。

**要补的话**：起 desktop 档后手工注册账号再跑该脚本，或补一个 `@SpringBootTest`
直接注入 `VectorMemoryStore` 调用（绕开 HTTP 认证）。

### 验证抓到的一个真实不一致

初版 `LocalOnnxEmbeddingModel.embed("")` 返回空数组，而 `embedAll(List.of(""))` 返回 1792 维向量 ——
同一层两个入口对同一输入给出不同答案。

根因是 `embed()` 里带了业务语义（blank 就不算）。**这一层只该管推理**，"空文本要不要算"是上层策略，
而且 `SharedEmbeddingModel.embed` 本来就已经有 `isBlank` 判断，重复判断制造了分歧。已删掉。
（附带修掉一个 `List.of(text)` 遇 `null` 抛 NPE 的问题，改用 `Collections.singletonList`。）

---

## 九、待办

- [x] 第 0 步：导出三变体 + Python 一致性闸门 + tokenizer 等价性
- [x] 第 0 步补尾：三方延迟基准（L1/L2/L3）
- [x] 接入前探针：Java 侧 ids/向量/加载成本全部实测通过
- [x] **第 1 步**：`LocalOnnxEmbeddingModel` 落地 + `SharedEmbeddingModel` 分派 + 两处 client 收口 + 配置
- [x] 第 1 步验证：生产类对金标准 cosine 0.9999999，全量测试无回归
- [ ] **第 2 步**：把模型文件分发流程做出来 —— 655MB 的 ONNX + 652MB 伴生 `.data` + `tokenizer.json`
      怎么进安装包（随包放 `models/` 还是首启下载/解压），以及 DJL native 与 onnxruntime 的
      native 按目标平台裁剪（这两个 jar 各含全平台 native，分别是 92MB 和 18MB）
- [ ] 桌面壳或 logback 侧指定日志文件编码为 UTF-8（现在 Windows 控制台是 GBK，中文日志乱码）
- [ ] 未做（本轮范围外）：`int8 + 二阶段重排` 方案是否值得（速度 2.3x 但精度需补）

---

## 附：产生这些结论的文件

| 文件 | 作用 |
|---|---|
| `.verify/onnx/export_onnx.py` | 从 `modules.json` 读管道并导出 fp32/fp16/int8 |
| `.verify/onnx/check_consistency.py` | Python 侧闸门（A/B/C/D/E 五组） |
| `.verify/onnx/bench.py` | L1/L2/L3 三方延迟基准 |
| `.verify/onnx/make_probe_golden.py` | 生成 Java 探针的金标准 |
| `.verify/onnx/check_java_probe.py` | 探针（DJL + ORT 可行性）的裁判 |
| `.verify/onnx/check_local_model.py` | **生产类**的裁判（batch / single / 填充不变性） |
| `.verify/java-probe/Probe.java` | Java 侧可行性探针 |
| `.verify/java-probe/VerifyLocalModel.java` | 驱动 `LocalOnnxEmbeddingModel` 跑同一套样本 |
| `.verify/onnx/manifest.json` / `consistency.json` / `bench.json` / `probe-java*.json` / `local-model-out.json` | 原始数据 |
