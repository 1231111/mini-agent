# MiniAgent Desktop 快速开始

## 两种形态，差别只有一件事：JRE 从哪来

- **出厂形态** —— 双击安装包（Windows 便携 exe / Linux AppImage·deb / macOS dmg）。
  包里自带 JRE、后端 jar、浏览器（Chromium headless shell）和 ffmpeg，客户机不需要装
  Java / Maven / Node，也不需要联网下载浏览器。
- **开发形态** —— `npm start`，或双击 `start.bat`（Windows）/ `./start.sh`（macOS·Linux）。
  后端由 `main.js` 用**本机的** `java` 拉起 `mini-agent-app/target` 下的 fat jar，
  所以只要先打好那个 jar。

两种形态下后端都由 `main.js` 负责启动和停止。不要另外手动起一份 ——
第二个实例会撞上 H2 的文件锁直接启动失败。

## 第一步：打后端 jar

```bash
./mvnw package -pl mini-agent-app -am -DskipTests      # macOS / Linux
mvnw.cmd package -pl mini-agent-app -am -DskipTests    # Windows
```

`main.js` 会挑 `mini-agent-app/target` 下**最新**的 jar，并自动排除
`.jar.original`（`spring-boot:repackage` 留下的、没有启动器的原始包，选错会报
no main manifest）。

## 开发形态

```bash
cd mini-agent-desktop
npm install      # 首次
npm start
```

或 `start.bat` / `./start.sh`（普通）、`start-dev.bat` / `./start-dev.sh`（带 DevTools）。

## 出厂打包

唯一的构建入口是跨平台的 Node 脚本：

```bash
node scripts/build-desktop.mjs
```

六步：打 fat jar → jlink 裁一个自带 JRE → 下 Electron 专有编解码器版 ffmpeg →
下浏览器（随包分发，默认只下 headless shell）→ 复制内联 embedding 模型 →
electron-builder 出本平台的安装包。
产物在 `mini-agent-desktop/dist/`。

只重做其中一环：

```bash
node scripts/build-desktop.mjs --skip-jar              # 复用已打好的 jar
node scripts/build-desktop.mjs --skip-jre              # 复用已裁好的 JRE
node scripts/build-desktop.mjs --skip-ffmpeg           # 复用已下的 ffmpeg
node scripts/build-desktop.mjs --skip-browsers         # 复用已下的浏览器
node scripts/build-desktop.mjs --skip-models           # 复用已复制好的模型
node scripts/build-desktop.mjs --skip-package          # 只准备物料，不出包
node scripts/build-desktop.mjs --with-headed-chromium  # 额外带上完整 Chromium（+314MB）
node scripts/build-desktop.mjs --model-variant=fp32    # 换模型变体（默认 int8）
node scripts/build-desktop.mjs --help
```

> **默认不含完整 Chromium。** 只有 `agent.browser.headless=false`（有头模式）才需要它，
> 而本工程 headless 恒为 true。想看着浏览器跑就需要 `--with-headed-chromium`，
> 详见 `README.md` 的「第 4 步默认只下 headless shell」。

> **模型物料要先自备（本机构建）。** 第 5 步从 `.verify/onnx/`（ONNX 变体）与
> `embedding-server/models/models/IEITYuan--Yuan-embedding-2.0-zh/snapshots/master/`
> （`tokenizer.json`）复制，这两处都不入版本控制。备齐方式：
> `python .verify/onnx/export_onnx.py --variants`（开关，不带值）导出变体；
> HF 原始模型按 `README.md`「第 5 步」里的目标路径放好。
> 构建期会按字节数校验，截断的模型直接失败而不是打进包里。

> **CI 上不用现导出，用发布好的归档。** 模型是平台无关的产物，导出一次、
> 打成归档、传 release，四个平台共用同一份 —— 在四个 runner 上各导一遍只换来
> 四个可能不同的结果。发布（本机做一次）：
>
> ```bash
> node scripts/pack-models.mjs --variant=int8   # 产出 195.9MB 归档 + 同名 .sha256
> gh release create models-v1 \
>    dist-models/miniagent-models-int8.tar.gz \
>    dist-models/miniagent-models-int8.tar.gz.sha256 \
>    --title "models-v1（内联 embedding 物料）" --notes "int8"
> ```
>
> workflow 里的下载步骤会自动取归档、校验 sha256、解到 `.verify/onnx/`
> （就是 `--model-src` 的默认值）。**没发布过物料时这一步会红** —— 那是正确信号，
> 补料才是解法。换模型时新开 tag（`models-v2`）而不是覆盖旧 release 的 asset。
> 详见 `README.md` 的「CI 上模型从哪来」。

> **jlink 与 Electron 都无法交叉产出。** 四个平台各自需要一台对应架构的机器：
> Windows x64、Linux x64、Linux arm64、macOS Apple Silicon。
> CI 里已经配好这个矩阵（`.github/workflows/desktop-release.yml`），
> 每行还会带上 `--target` 与 `--expect-arch` 两个断言 —— 跑到错的机器上会立刻失败，
> 而不是打出一份名实不符的包。详见 `README.md` 的「跑错机器会静默出错包」。

## 配置放在哪

| 位置 | 放什么 |
|---|---|
| `~/.miniagent/config.yml`（Windows 为 `%USERPROFILE%\.miniagent\config.yml`） | **密钥**。不在版本库、不进安装包。模板见仓库根 `config.example.yml` |
| `%APPDATA%\mini-agent-desktop\config.json`（macOS: `~/Library/Application Support/...`，Linux: `~/.config/...`） | 壳自己的配置：窗口位置、主题 |
| `application-desktop.yml` | 出厂形态的配置档（内联 ONNX embedding、关掉 Redis 健康检查等） |

整个 `~/.miniagent/` 是后端的数据根（H2 库文件、日志、workspace），
由 `MINI_AGENT_HOME` 环境变量决定。卸载客户端不会删掉它。

## 常见问题

**Q: 后端起不来，窗口提示启动失败？**
看 `~/.miniagent/logs/backend.log`。壳弹出的错误框里也会带上最后 12 行。

**Q: 语义检索没生效 / 检索结果不准？**
先查启动日志里有没有「内联 embedding 预检失败」。桌面档用进程内的 ONNX 模型，
出厂包缺了 `models/` 里的文件时**不会报错**，只会降级成「没有向量的检索」，
所以启动时会打印一条明确结论（成功时是「内联 embedding 预检通过: dim=1792」）。
开发态不起壳直接跑 jar 时变量没传，后端会去 `~/.miniagent/models/` 找 ——
那里没有就会报同样的错。

**Q: 后端端口被占用？**
后端每次在 `127.0.0.1` 上申请随机空闲端口。壳只接受本次子进程携带匹配 nonce
的 READY 信号，再检查 `/actuator/health`，不会复用其他本机服务。

**Q: 为什么开发态提示找不到 jar？**
`main.js` 不用 `mvn spring-boot:run` 启动后端（那要求本机装 Maven，
正是出厂形态要摆脱的东西）。先跑第一步那条 `mvnw package`。

**Q: macOS 上提示"应用已损坏"/ Linux 上双击 AppImage 没反应？**
都是平台特有的限制，见 `README.md` 的「各平台的注意事项」。

**Q: 图标怎么改？**
`assets/icon.png` 是唯一的图标源（1024×1024），electron-builder 会自行生成
Windows 的 `.ico` 和 macOS 的 `.icns`。要换设计就改这个文件；
重新生成默认图标可以跑 `java scripts/MakeIcon.java mini-agent-desktop/assets/icon.png`。
