# MiniAgent Desktop

MiniAgent 的桌面客户端，基于 Electron。后端是一个 Spring Boot 的 fat jar，
由 Electron 主进程拉起，两者之间只通过 `127.0.0.1` 上的 HTTP 通信。
后端就绪后窗口直接加载它提供的 `chat.html`；桌面端不再维护第二套聊天界面。

## 支持的平台

| 平台 | 架构 | 安装包格式 | 状态 |
|---|---|---|---|
| Windows | x64 | 便携 exe | 支持 |
| Linux | x64 | AppImage / deb | 支持 |
| Linux | arm64 | AppImage / deb | 支持 |
| macOS | Apple Silicon (arm64) | dmg | 支持 |
| macOS | Intel (x64) | — | **不支持** |

macOS Intel 不支持是硬约束，不是没做：内联 embedding 用的
`ai.djl.huggingface:tokenizers` 只带 `osx-aarch64` 的原生库，没有 `osx-x86_64`。
在 Intel Mac 上它会静默降级 —— 应用能启动，但语义检索类功能不工作。

## 环境要求

**最终用户：无。** 安装包里已经含 JRE（jlink 裁剪）、后端 jar、浏览器
（Chromium headless shell）和 ffmpeg，客户机不需要装 Java、Maven、Node，
也不需要联网下载浏览器。

**开发者 / 构建者：**

| 工具 | 版本 | 用途 |
|---|---|---|
| JDK | 21（需含 `jlink`，JRE 不行） | 编译后端 + 裁 JRE + 跑 Playwright CLI |
| Node.js | 18+ | Electron 运行时与打包 |
| npm | 9+ | 依赖安装 |

## 快速开始（开发态）

```bash
cd mini-agent-desktop
npm install
```

**Windows：**
```
start.bat          # 普通启动
start-dev.bat      # 开发模式
```

**macOS / Linux：**
```bash
./start.sh
./start-dev.sh
```

> **不要自己去起后端。** `main.js` 会 spawn `java -jar` 拉起它。
> 手工再起一份的话，第二个实例会因为 H2 文件锁
> 直接启动失败（`The file is locked`），弹出一个用户看不懂的报错框。
>
> 另外 `mvn spring-boot:run` 这条路对出厂形态是无效的 —— 它要求客户机装 Maven
> 并且能联网解析依赖。开发态可以这么调试，但别把它当成启动方式。

## 目录结构

```
mini-agent-desktop/
├── main.js              # Electron 主进程：起后端、探健康、管窗口与托盘
├── preload.js           # 安全桥接（contextIsolation）
├── after-pack.cjs       # electron-builder 钩子：替换各平台的 ffmpeg
├── package.json         # 依赖与 electron-builder 配置
├── start.bat / start.sh           # 启动（Windows / macOS+Linux）
├── start-dev.bat / start-dev.sh   # 开发模式启动
├── src/
│   └── index.html       # 后端启动失败时的本地诊断页
├── assets/
│   └── icon.png         # 1024×1024 图标源，electron-builder 自行转 ico / icns
├── jre/                 # 【构建产物】jlink 裁出的 JRE，勿提交
├── browsers/            # 【构建产物】随包浏览器（headless shell + ffmpeg），勿提交
├── ffmpeg/              # 【构建产物】Electron 专有编解码器版 ffmpeg，勿提交
├── models/              # 【构建产物】内联 embedding 的 ONNX 变体 + tokenizer，勿提交
└── dist/                # 【构建产物】安装包输出
```

`jre/`、`browsers/`、`ffmpeg/` 都是**平台专有**的。Windows 版 JRE 里的
`api-ms-win-core-*.dll` 拿到 macOS 上就是一堆废文件 —— 不要跨机器复用。

`models/` 不一样：ONNX 图与 tokenizer 是**平台无关**的，四个平台可以共用同一份。
但它有 315MB，而它的源头（`.verify/onnx/` 与 HF 模型目录）也都不入版本控制，
所以同样不提交 —— 每次构建从本地源重新复制。

以上全部已在 `.gitignore` 里。

## 构建安装包

唯一的构建入口是 `scripts/build-desktop.mjs`（跨平台，三平台通用）：

```bash
node scripts/build-desktop.mjs                     # 本平台完整构建
node scripts/build-desktop.mjs --skip-jar          # 复用已打好的 fat jar
node scripts/build-desktop.mjs --skip-jre          # 复用已裁好的 JRE
node scripts/build-desktop.mjs --skip-ffmpeg       # 复用已下载的 ffmpeg
node scripts/build-desktop.mjs --skip-browsers     # 复用已下载的浏览器
node scripts/build-desktop.mjs --skip-models       # 复用已复制好的模型
node scripts/build-desktop.mjs --skip-package      # 只准备物料，不出包
node scripts/build-desktop.mjs --with-headed-chromium   # 额外带上完整 Chromium（供有头调试）
node scripts/build-desktop.mjs --model-variant=fp32     # 换模型变体（默认 int8）
node scripts/build-desktop.mjs --help
```

它做六件事：

1. `mvnw package` —— 打 fat jar
2. `jlink` —— 裁一个最小 JRE 到 `jre/`（约 52MB）
3. 下载 Electron 官方含 H.264/AAC 的 ffmpeg 到 `ffmpeg/`（约 1.8MB）
4. 用 Playwright CLI 把浏览器下到 `browsers/`（默认只下 headless shell，见下）
5. 把 ONNX 变体与 `tokenizer.json` 复制到 `models/`（见下）
6. `electron-builder` —— 出本平台的安装包

### 第 4 步默认只下 headless shell

Playwright 1.49 起把 Chromium 拆成两个独立下载物，用哪个完全由 `headless` 选项决定 ——
`playwright-core` 的 `lib/server/chromium/chromium.js` 里就一行：

```js
getExecutableName(options) {
  if (options.channel) return options.channel;
  return options.headless ? 'chromium-headless-shell' : 'chromium';
}
```

`BrowserService` 只设了 `headless`、没设 `channel`，而本工程 `agent.browser.headless`
恒为 `true`（基础档、桌面档、prod 档都写死 true，且 `ProductionReadinessValidator`
在 prod 下会拒绝 `false`），所以解析到的一直是 **chromium-headless-shell**。
完整的 `chromium` 包（314MB）从头到尾不会被加载，构建脚本因此加了 `--only-shell` 跳过它。

实测结论（同一份 `browsers/` 目录，把 `chromium-<rev>` 改名藏起来）：

| 配置 | 结果 |
|---|---|
| `headless: true` | 正常启动并渲染出页面 |
| `headless: false` | `Executable doesn't exist at ...chromium-1148/chrome-win/chrome.exe` |

需要留出有头调试能力时，用 `--with-headed-chromium` 重新构建（体积 +314MB）。

### 第 5 步：随包的内联 embedding 模型

桌面档用 `local-onnx` 做 embedding（不需要 `embedding-server` 那个 Python sidecar，
也不需要外部 API），所以模型与 tokenizer 必须在构建期就放进包里 ——
理由和浏览器那条一样：客户机可能完全离线。

默认随包 **int8** 变体。三档实测：

| 变体 | 字节数 | 建会话（Java，出厂路径） | 推理·短文本 | 推理·长文本 | 改写检索 top-1 / top-3 / MRR |
|---|---|---|---|---|---|
| `fp32` | 1,307,989,980 | 5290 ms | 56.00 ms | 291.64 ms | 9/10 / 10/10 / 0.950 |
| `fp16` | 655,377,306 | 4883 ms | 70.59 ms | 357.80 ms | 9/10 / 10/10 / 0.950 |
| `int8` | **329,744,186** | **4746 ms** | **31.92 ms** | **132.81 ms** | 9/10 / 10/10 / 0.950 |

**两个口径不能混着看：**

- **建会话**那一列是 **Java 侧**测的（出厂实际跑的就是这条路）。三档只差 11% ——
  Java ORT 有一份约 4.5 秒的固定初始化开销在主导，跟模型大小基本无关：同一份 int8
  文件在 Python 侧建会话只要 0.53 秒，差 9 倍。**所以「int8 建会话更快」在出厂路径上
  几乎不成立**（只快 11%，不是快 4 倍）。
- **推理耗时**与**检索排序**目前只有 **Python 侧**的数字（40 次迭代、`threads=8`），
  Java 侧未测。建会话那件事恰好说明两边不能互相推算，所以不把 Python 的数字当成
  出厂保证。体积与排序两项不受影响 —— 一个是文件事实，一个是模型行为。

预检启动时会异步加载模型（所以那 4.7 秒不会拖慢窗口出现），并在日志里留下
`内联 embedding 预检通过: dim=1792` 或一条 ERROR。

**为什么不是 fp16**：ONNX Runtime 的 CPU EP 没有原生 fp16 kernel，要多插 Cast 节点，
实测它比 fp32 还慢，却只省一半体积 —— 两头不占。

**int8 的退化面**：逐条 cosine 最小值 0.9811（剔除空串与单空格后）；20 条样本互查的
全序一致率 86.1%。差异集中在无实义文本（纯标点、空串、罕见字）——
它们在检索里本来就不是有效 query。上表最右列是 10 条人话 query 查 20 条文档，
int8 与 fp32 逐项相同。明细见 `.verify/onnx/rank-fidelity.json`。

**构建期按字节数卡一道**：模型被截断时 ORT 建会话会失败，而
`LocalOnnxEmbeddingModel` 只会降级成「没有向量的检索」—— 应用照常启动，
症状是「检索结果不准」。这种故障在客户机上极难定位，所以要在构建期就拦住。
期望值记在 `scripts/lib/model-manifest.mjs` 的 `MODEL_VARIANTS` 里，来源是
`.verify/onnx/manifest.json`。校验规则也和打包脚本（`scripts/pack-models.mjs`）共用
这一份 —— 两份定义等于改一处漏一处，会造出「打包放行、构建拦住」这种自相矛盾的状态。

模型物料不在版本控制里，本机第一次构建前要先备齐两样：

1. **HF 原始模型**（提供 `tokenizer.json`），目标路径：
   `embedding-server/models/models/IEITYuan--Yuan-embedding-2.0-zh/snapshots/master/`。
   仓库里的 `embedding-server/fetch_modelscope.sh` 是**容器内**脚本，它的
   `cache_dir=/models` 对应宿主机就是这个目录 —— 在容器里跑它即可落到位。
   （注意 `entrypoint.sh` 走的是 HF 缓存 `embedding-server/models/hf`，不是这个目录。）
2. **导出 ONNX 变体**。`--variants` 是开关、不带值，会同时导 fp16 与 int8：

   ```bash
   python .verify/onnx/export_onnx.py --variants
   ```

   产出落在 `.verify/onnx/`，也就是 `--model-src` 的默认值。

用 `--model-src=<dir>` 可以指向别处的制品目录。tokenizer 的取值顺序是
「先看这个目录里有没有 `tokenizer.json`，**没有**才退回上面那个 HF 目录」——
CI 上解开物料归档后模型与 tokenizer 就在同一个目录里（见下节），走的是第一条。
构建日志会明确打出 `tokenizer 来源：物料目录自带` 还是 `HF 模型目录（兜底）`：
这一行不能省，否则「物料归档漏了 tokenizer」在**本机**会被 HF 目录悄悄兜住，
而 CI 只解归档、没有 HF 目录，到那边才失败。

#### CI 上模型从哪来：打一次归档，四个平台共用

模型是**平台无关**的产物，跟 `jre` / `dmg` / `AppImage` 不是一类东西 ——
后者不能交叉产出，模型则可以、而且应该只产一次。在四个 runner 上各跑一遍
`export_onnx.py`，等于装四遍 torch、下四遍 1.3GB 原始模型、跑四遍同样的图优化，
只换来四个可能不同的结果。

所以路径是「本机导出一次 → 打成归档 → 传到本仓库的一个 release → 四个 runner 下载」：

```bash
# 1. 打包（产出 dist-models/miniagent-models-int8.tar.gz 与它的 .sha256）
node scripts/pack-models.mjs --variant=int8

# 2. 发布（一次性，之后四个平台都从这儿取）
gh release create models-v1 \
   dist-models/miniagent-models-int8.tar.gz \
   dist-models/miniagent-models-int8.tar.gz.sha256 \
   --title "models-v1（内联 embedding 物料）" --notes "int8 330MB + tokenizer.json"
```

实测：源 314.9 MB → 归档 **195.9 MB**（gzip 省 38%），打包耗时约 35 秒。
归档内容是**平铺**的（没有目录层级，解开即可直接当 `--model-src`）：
`yuan-embedding-2.0-zh.int8.onnx`、`tokenizer.json`，外加一份 `MANIFEST.txt`
（变体、打包时间、各成员字节数与 sha256）。

归档里同时装模型与 tokenizer 是有意为之：两者必须成对，装在一起就不可能
出现「模型换了、tokenizer 还是旧的」这种版本错配 —— 错配时向量照样算得出来，
只是已经是错的，没有任何运行期异常会提示你。

workflow 里的 `Download model artifacts` 步骤做三件事：

1. 用 `gh release download` 一次取到归档**与它的 `.sha256`**（用 `gh` 而不是 `curl`：
   `gh` 会带 `GITHUB_TOKEN` 走 API，私有仓库也能用，而匿名 `curl` 取私有仓库的
   release asset 会 404）；
2. `scripts/verify-models-archive.mjs` 校验摘要 —— 下载被中断或被代理改写过时，
   拿到的可能是一个 HTML 错误页，它有文件名、有字节数，会被一路放行到建会话才失败；
3. 解到 `.verify/onnx/`，也就是构建脚本 `--model-src` 的默认值。

**物料缺失时这一步会故意硬失败**，不产出「能装但没有语义检索」的包。
这一步红是正确信号，补料才是解法，不要用跳过的方式绕过去。

换模型时**新开 tag**（`models-v2`）并同步改 workflow 里的 `MODELS_RELEASE_TAG`，
不要覆盖旧 release 的 asset —— 已发布的安装包要能追溯它当时用的是哪份物料。

### 出厂体积（Windows x64 实测）

| 指标 | 含完整 Chromium | 默认（仅 headless shell） |
|---|---|---|
| `browsers/` | 510.2 MB | **195.6 MB** |
| 解压后 `win-unpacked/` 总占用 | 1.3 GB | **1.0 GB** |
| 便携 exe | 700.1 MB | **624.3 MB** |

安装包体积只降 76MB 是因为 portable 是 7z 压缩的，而解压后是实打实少 324MB。
对 portable 形态来说后者更要紧：它每次启动都要把整包解压到 `%TEMP%`。

其余大头（解压后）：fat jar 533.5 MB、Electron 本体约 210 MB、JRE 52 MB。
fat jar 里塞的是全平台二进制（`driver-bundle` 189MB、`onnxruntime` 92MB、
`tokenizers` 18MB），要裁得走分平台出包，见 `application-desktop.yml` 顶部的清单。

再算上随包的模型与 tokenizer（int8 实测 314.9 MB），解压后总占用约 **1.32 GB**
（1007 MB + 315 MB）。portable exe 会相应增加，但那要重打一次才有准数，这里不给估算。

模型这一项也解释了为什么不选 fp16 / fp32 —— 换 fp16 会让包再涨 310MB、换 fp32 涨 978MB，
而两者的检索排序与 int8 完全一样（见上一节）。

### 为什么必须在目标平台上构建

**`jlink` 产出的 JRE 是平台专有的**，只能在对应平台上生成；`electron-builder`
的 dmg 只能由 macOS 产出，AppImage / deb 只能由 Linux 产出。这意味着四平台各自
需要一台对应架构的机器，Windows 上一条命令出不了四个包。

构建脚本不会让你踩这个坑：`--target` 只接受与当前机器一致的平台，写别的会直接失败
（理由写在同一处：跨平台出包塞进去的是一份跑不起来的 JRE）。

### 跑错机器会静默出错包，所以有两道断言

`jlink` 与 `electron-builder` 都只按**当前机器**出包，不会去核对你「以为」自己在打什么。
而 CI 里 artifact 的名字是手写的，与 runner 的实际架构没有任何绑定关系 ——
runner 标签本身也不绑架构（GitHub 的 macOS 标签里 `macos-latest` / `macos-14` /
`macos-15` 是 arm64，`macos-15-intel` / `macos-14-large` 才是 Intel）。
写错标签时整条链路会照常成功：JRE、安装包、产物名里的架构全都按当前机器算，
唯独上传时用的还是那个手写的名字。

所以脚本有前后两道断言：

| 时机 | 参数 / 行为 |
|---|---|
| 构建前 | `--target=win\|mac\|linux` 必须等于当前平台；`--expect-arch=x64\|arm64` 必须等于当前架构（都支持 `aarch64` / `x86_64` 别名） |
| 构建后 | 回读 `dist/` 里的产物名，若其中带的架构标记与当前机器不符则失败；若产物名里**完全没有**架构标记则告警（说明 `artifactName` 丢了 `${arch}`） |

CI 四行矩阵各自把期望值传进来，所以 runner 被换错会立刻失败，而不是产出一份名实不符的包。
本机不带这两个参数也能构建，只是少了这层保护。

CI 配置本身有一套静态校验（本机 GitHub 直连不通，跑不了真实的 Actions）：

```bash
NODE_PATH=mini-agent-desktop/node_modules node .verify/check-workflow.mjs
```

它检查 YAML 可解析、矩阵四行字段齐全、runner 标签与架构自洽、artifact 名与
`artifactName` 口径一致、Build 步骤传了这两个参数、上传通配符覆盖各目标平台的扩展名，
以及**物料链路**：归档名与变体名在 workflow / `pack-models.mjs` / `build-desktop.mjs`
三处一致、下载在 `checkout` 之后且在 Build 之前、带了 `GH_TOKEN`、校验摘要后才解包、
解开到 `--model-src` 的默认目录。

其中两条针对 Windows 上两个「报错看不出方向」的坑（详见 workflow 里的注释）：
`tar` 的 `-f` 一旦是带盘符的绝对路径，GNU tar 会把 `D:` 当主机名去连；
`tar` 在 stdin 是匿名管道时会以 `EBUSY` 启动失败。这两条断言最初写成
`dlRun.includes('< /dev/null')`，结果**注释里就有这几个字**，把命令行的重定向删掉
照样 PASS（假绿）。现在改成先按行抓出 `tar` 命令行再对它断言。

CI 已经配好了四平台矩阵（`.github/workflows/desktop-release.yml`），
打 `v*` 标签或手动触发即可。

## 各平台的注意事项

### Windows

- 产物是 **便携版 exe**（portable）。它每次启动会把内嵌的约 1.32GB 内容解压到
  `%TEMP%`，首次启动明显偏慢。要改成一装即用的形态，把 `package.json` 里
  `win.target` 从 `portable` 换成 `nsis`。
- 便携版不做任何签名，SmartScreen 可能拦一次，选"仍要运行"即可。

### macOS

- **未签名的 dmg 会被 Gatekeeper 拦**，提示"无法验证开发者"。当前
  `package.json` 里 `mac.identity: null`，即不做签名，客户机需要
  `xattr -cr /Applications/MiniAgent.app` 才能打开。正式出厂应配开发者证书
  并把 `identity` 改回证书名，再走公证（notarization）。
- 替换 Framework 里的 ffmpeg 会让签名失效，`after-pack.cjs` 会自动重做一次
  ad-hoc 签名 —— Apple Silicon 强制要求所有可执行代码有有效签名，缺了直接拒绝启动。

### Linux

- **AppImage 依赖 FUSE2**。Ubuntu 24.04 起默认不带 `libfuse2`，用户双击会报错。
  所以 `package.json` 里同时输出了 `deb` —— 优先让用户装 deb。坚持用 AppImage 的话，
  可以 `./MiniAgent-*.AppImage --appimage-extract-and-run`。
- 跑 Electron 需要常见的桌面库（`libnss3`、`libgtk-3`、`libasound2` 等）。
  常规桌面发行版都有，纯净容器里没有。

## 配置说明

应用配置存储在用户目录：

- **Windows**: `%APPDATA%/mini-agent-desktop/config.json`
- **macOS**: `~/Library/Application Support/mini-agent-desktop/config.json`
- **Linux**: `~/.config/mini-agent-desktop/config.json`

```json
{
  "windowBounds": { "width": 1200, "height": 800 },
  "theme": "dark"
}
```

后端每次由 Java 在 `127.0.0.1` 上分配随机空闲端口，不把端口写入配置。
Electron 为本次子进程生成随机 nonce；只有子进程输出的
`MINIAGENT_READY {"port":...,"nonce":...,"buildId":...}` 中 nonce 匹配时，
壳才接受该端口，随后再检查 `/actuator/health`。

后端的数据目录由 `MINI_AGENT_HOME` 决定，默认 `~/.miniagent`
（拆日志、库文件、workspace 都在这里）。卸载客户端不会删掉它。

桌面壳每次启动都通过 `MINI_AGENT_DESKTOP_NONCE` 传入本次握手 nonce。
出厂形态还会传两个物料路径变量：

| 变量 | 指向 | 不设会怎样 |
|---|---|---|
| `PLAYWRIGHT_BROWSERS_PATH` | `resources/browsers` | 浏览器工具去平台默认缓存目录找（空的），触发一次上百 MB 的联网下载 |
| `MINI_AGENT_MODELS_DIR` | `resources/models` | 内联 embedding 回退到 `~/.miniagent/models`，那里没有就降级成"没有向量的检索" |

两个都只在 `app.isPackaged` 且对应目录存在时才设；开发态不设，走各自的回退路径。

## 云端账号（注册/登录走云端）

账号体系可以放到一台**云端** MiniAgent 上：注册与密码校验都由它完成，本机只留一条
"影子用户"记录。配置在 `application-desktop.yml`：

```yaml
agent:
  auth:
    cloud:
      base-url: http://<云端主机>:8081   # CLOUD_AUTH_BASE_URL，留空则走本地账号
      connect-timeout-ms: 3000
      read-timeout-ms: 10000
      adopt-role: false
```

云端用 `docker compose -f docker-compose.account.yml up -d --build` 启动。
桌面壳启动后端时，若环境变量 `CLOUD_AUTH_BASE_URL` 为空，则设为
`http://120.53.87.241:8081`（`main.js` 的 `CLOUD_ACCOUNT_BASE`）。
门户地址同样默认这个主机：环境变量 `MINIAGENT_PORTAL_URL`，否则 `config.json` 的 `portalUrl`，
都没有才用上面的默认值。登录页没有本机注册，登录框下面的链接打开
`{门户}/login?desktop=1`。线上账号服务目前只有 `/login`，没有 `/register`，
所以不能把浏览器打到 `/register`（会得到 `SYSTEM.01.01`）。
注册成功后账号服务发一张一次性凭证，浏览器跳
`miniagent://login?ticket=...`，壳用它向本机 `POST /api/auth/desktop-login` 换本地会话并进入主页。
`/account` 仍是套餐和订单。直接双击 jar、不经壳启动时，`CLOUD_AUTH_BASE_URL` 仍为空，登录走本机账号。

**为什么要影子用户**：`SignedSessionFilter` 每个请求都要 `userRepository.findById(userId)`，
本地没有对应行就一律 401，token 本身再合法也没用；而且
`chat_conversations.user_id` 等外键也需要落点。所以云端用户在本地必须有一行，
`users.external_id` 就是"这一行对应云端哪个人"的映射键（`V8__cloud_account_shadow_user.sql`）。

```
前端 → 本地 POST /api/tokens → 云端 POST {base}/api/tokens 校验
     → upsert 影子用户（external_id = 云端 userId，租户用本地 system）
     → 用本地 jwtSessionService 签发本地会话 → 返回前端
```

前端与现有鉴权链因此**一行都不用改**；`SignedSessionFilter` / `DbSessionStore` / 外键全部保持原样。

四条设计决定，都写在对应类的 javadoc 里：

| 决定 | 理由 |
|---|---|
| 影子用户角色一律 `USER` | 云端的权限域和本机不是一回事；自动等同等于把云端提权放大到设备上。确实要同步就开 `adopt-role: true` |
| 绝不"接管"同名的本地账号 | 那等于把前者的聊天记录和记忆交给后者。影子用户改用 `用户名#外部id` 避让，并打 WARN |
| 影子用户 `password_hash` 存随机值 | `password_hash` 是 NOT NULL，而影子用户没有本地密码；随机值让"本地登录"这条路径恒为失败。注意**只能用一个 UUID**——两个拼起来 73 字节，正好越过 BCrypt 的 72 字节上限，实测报 `CONFIG.02.01` |
| 云端错误码原样透传 | `AUTH.01.01` 是"密码错"、`AUTH.03.01` 是"云连不上"，混成一句用户就会一直改密码 |

**这是硬语义，不是可降级的软约束**：`base-url` 配了之后本机必须能访问它，否则谁都登不进去
（返回 `AUTH.03.01`）。界面会在用户输账号**之前**通过 `GET /api/auth/cloud-status` 提示
"云端账号服务不可达"，所以断网时看到的是明确说明，不是一条"登录失败"。

本地联调不用真部署云端：在本机起第二份实例即可（**必须**给独立的 `MINI_AGENT_HOME`，
否则两份实例抢同一个 H2 文件锁，第二份起不来）：

```bash
# "云端"
MINI_AGENT_HOME=D:/tmp/_cloud JWT_SECRET=<64位随机串> \
  java -jar mini-agent-app/target/mini-agent-app-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=desktop --server.port=8099

# "客户端"
MINI_AGENT_HOME=D:/tmp/_client JWT_SECRET=<另一个64位随机串> \
  java -jar mini-agent-app/target/mini-agent-app-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=desktop --server.port=8098 \
  --agent.auth.cloud.base-url=http://127.0.0.1:8099
```

## 常见问题

**Q: 启动时报"随包分发的浏览器不可用"**
A: 安装包不完整，或 `resources/browsers` 被清理过。日志里会点名缺的是哪个目录：

- 缺 `chromium_headless_shell-<rev>`：headless 模式所需的包没了，浏览器类工具不可用，
  其余功能正常。重新安装即可。
- 缺 `chromium-<rev>`：说明你把 `agent.browser.headless` 改成了 `false`，而有头模式
  需要完整 Chromium —— 出厂包默认不含它。把 `headless` 设回 `true` 即可恢复浏览器工具；
  确实需要窗口模式就用 `--with-headed-chromium` 重新构建。

两种情况下日志都不会再去联网下载 —— 那是刻意的，离线客户机上自动下载必然失败，
还会把"包坏了"伪装成"首次启动慢"。

**Q: 语义检索没生效 / 检索结果不准？**
A: 先查启动日志里有没有 `内联 embedding 预检失败`。桌面档用进程内的 ONNX 模型，
出厂包缺了 `resources/models` 里的文件时**不会报错**，只会降级成"没有向量的检索"，
所以启动时会打印一条明确结论（成功时是 `内联 embedding 预检通过: dim=1792`）。

预检是**异步**的 —— 它不参与就绪判断（桌面壳先校验 READY nonce，
再检查 `/actuator/health`），
所以这几行可能比"后端已就绪"晚几秒出现，属正常。之所以异步，是因为建会话在
Java 侧实测要 4.7 秒，同步做会白白拖慢窗口出现。

开发态不起壳直接跑 jar 时 `MINI_AGENT_MODELS_DIR` 没传，后端会去 `~/.miniagent/models/`
找 —— 那里没有就会报同样的错。

**Q: macOS 上提示应用已损坏 / 无法验证开发者**
A: 未签名包触发了 Gatekeeper。`xattr -cr /Applications/MiniAgent.app` 后重试。

**Q: Linux 上双击 AppImage 没反应**
A: 装了 `libfuse2` 才能直接跑。`sudo apt install libfuse2`，或改用 deb 包。

**Q: 后端起不来，怎么排查？**
A: 看 `~/.miniagent/logs/backend.log`。启动失败时弹窗里也会带上最后 12 行。

**Q: 端口被占用怎么办？**
A: 不用管。后端让操作系统分配随机空闲端口，只监听 `127.0.0.1`；
壳通过本次子进程的 READY nonce 获取端口，不会复用其他本机服务。

**Q: 双击后界面一直停在"正在连接本机服务…"，后端日志却是正常的？**
A: 看 `~/.miniagent/logs/renderer.log`（新加的文件）。渲染层的报错默认只进 F12
控制台，客户机上没人按 F12，所以壳现在会把 `console-message` /
`preload-error` / `did-fail-load` / `render-process-gone` 统一落盘。
正常启动会直接进入后端的 `/` 页面；看到这句说明窗口进入了本地故障诊断页。

本地诊断页曾在 2026-09-28 踩过下面这个问题：

- `preload.js` 用 `contextBridge.exposeInMainWorld('api', {...})` 往主世界挂了一个
  **不可配置**（`configurable: false`）的全局属性；
- `src/index.html` 的 `<script>` 顶层又写了 `async function api(path, opt) {...}`；
- 按 ES 规范，顶层声明遇到不可配置的同名全局属性会直接抛
  `SyntaxError: Identifier 'api' has already been declared`；
- **这是解析期失败，整段脚本一行都不执行** —— 所以既不会 resolve 也不会 reject，
  `waitReady().then(...).catch(...)` 的 catch 永远不跑，界面连"后端没起来"都等不到。

两个反直觉的点：

1. **`node --check` 查不出来。** 把内联脚本抽出来做静态语法检查会返回 OK，
   因为冲突来自"和已存在的全局属性撞名"，不是脚本自身的语法问题。
   **静态语法检查在这里是无效判据。**
2. **`preload` 挂的每一个全局名都是地雷。** 不只是 `api`；`electronAPI` 同样不可配置。

所以页面脚本**整体包在 IIFE 里**（见 `src/index.html`），让页面所有标识符退到函数作用域，
从结构上不可能再和 `contextBridge` 的全局名冲突。改名只是把地雷挪到下一个名字上。

配套两层兜底，都已落地：

- 页面里有一段**独立**的看门狗 `<script>`（不与主脚本共用作用域、也不放在主脚本之后，
  所以主脚本整体解析失败时它照样执行）：`error` / `unhandledrejection` 监听 +
  20 秒定时器。它保证**绝不会无声地停在启动页**，而是把错误原文写进界面。
- 壳侧把渲染层报错写进 `logs/renderer.log`。

**Q: 为什么双击 exe 没有安装过程？**
A: 因为构建出来的是**免安装的目录版**。`package.json` 里 `win.target = ["portable"]`，
双击即运行，设计上就没有安装向导；`dist/MiniAgent-1.0.0-win-x64.exe` 是单文件便携版
（运行时会自解压到临时目录），也不是安装器。

想要安装向导就把 `win.target` 改成 `["nsis"]` —— `build.nsis` 那段配置
（`oneClick: false` / `allowToChangeInstallationDirectory` / 建快捷方式）已经写好了，
只是没被 `target` 引用。注意 NSIS 装出来的应用和便携版共用同一个 `userData` 目录，
测试时别把两者的配置搞混。

**Q: 怎么确认打包后的窗口真的进入了完整客户端，而不是靠人工双击目测？**
A: 用 `scripts/desktop-cdp-inspect.mjs` 接 CDP 进去读。它把「改完立刻确认」的回路
缩到最短。正常结果应是本机 HTTP 地址，并且页面是登录页或带 `#messages` 的聊天页。

```bash
cd mini-agent-desktop/dist/win-unpacked
env -u ELECTRON_RUN_AS_NODE -u NODE_OPTIONS ./MiniAgent.exe \
  --remote-debugging-port=9333 --user-data-dir=/tmp/_edbg \
  --disable-gpu --disable-gpu-compositing --disable-software-rasterizer --no-sandbox

node scripts/desktop-cdp-inspect.mjs "JSON.stringify({
  url: location.href,
  hasElectronAPI: !!window.electronAPI,
  page: document.getElementById('messages') ? 'chat' :
    document.getElementById('loginForm') ? 'login' :
    document.getElementById('boot') ? 'backend-error' : 'unknown'
})"
```

三个前提缺一不可，否则会得到完全错误的结论（脚本头部有详细说明）：

- **必须摘掉 `ELECTRON_RUN_AS_NODE`。** 带了这个变量时 exe 会退化成纯 Node 进程 ——
  没有窗口、没有输出，看起来像"应用卡住了"，实际根本没进 Electron 主进程。
  带参数启动会报 `<exe>: bad option: --xxx`，这句文案来自 **Node** 的参数解析器，
  不是 Electron 的。
- **必须加 `--disable-gpu`。** 无可用 GPU 的环境里 GPU 进程会崩
  （`exit_code=-1073741819` = `0xC0000005`），而 Electron 在 GPU 不可用时的默认行为是
  **整个进程退出**，不是降级到软件渲染。
- **必须用 `--user-data-dir` 指到临时目录**，换掉单实例锁的作用域 —— 否则已有实例在跑时，
  新进程 `requestSingleInstanceLock()` 失败会直接 `app.quit()`，你什么都看不到。

另外一个技巧：CDP 的 `Runtime.enable` 会**重放已经发生过的** exception / console 消息，
所以「页面里到底有没有抛过异常」不需要复现故障，连上去那一刻就能拿到 ——
上面那条 `SyntaxError` 就是这么一次定性的。

## 相关链接

- [Electron 官方文档](https://www.electronjs.org/)
- [electron-builder 文档](https://www.electron.build/)
