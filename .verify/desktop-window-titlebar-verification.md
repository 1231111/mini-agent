# 桌面档窗口改造 —— 自绘标题栏静态实测记录

日期：2026-09-29 ｜ 环境：无头 Edge 1280×800（本机 GPU 崩、Electron 窗口无法自证，改 DOM 级静态实测）
被测代码：`mini-agent-desktop/preload.js` 的 `installTitlebar` IIFE（**源码原封嵌入测试页，未重写逻辑**）
测试页：`.verify/titlebar-dom-test/harness.html`（主题令牌取自 `chat.html` 真实值）

## 结果总览

| 变体 | 断言数 | FAIL |
|---|---|---|
| win32 · dark · 普通态 | 22 | 0 |
| win32 · light · 普通态 | 22 | 0 |
| win32 · dark · 最大化态 | 22 | 0 |
| win32 · dark · 全屏态 | 19 | 0 |
| win32 · 无令牌页（login/诊断页 fallback） | 21 | 0 |
| darwin（mac 红绿灯分支） | 13 | 0 |
| **合计** | **119** | **0** |

明暗两张截图：`shot-dark.png` / `shot-light.png`（同目录）。

## 验了什么（行为边界，非实现细节）

- **结构**：标题栏注入成功、挂在 `body` 直属、**不在 `#scroller` 内**（防回归：历史消息 prepend 不会顶走它）
- **几何**：固定顶部横贯窗口、总高 32px（含 1px 底边框）、顶栏 16px 处命中测试点得到标题栏（z 序在页面之上）、内容未被遮挡
- **让位**：body `padding-top = 原 16 + 32 = 48px`（叠加不覆盖页面自带 padding）；全屏归零
- **交互**：点最小化→`win:minimize`、点最大化→`win:toggle-maximize`、点关闭→`win:close`；
  双击标题空白→最大化/还原；**双击按钮不误触发**（不算标题栏双击）
- **拖拽区**：标题栏 computed `-webkit-app-region=drag`，按钮区 `no-drag`
- **状态联动**：最大化/全屏态按钮换「还原」图标（aria-label 同步换）；全屏自动隐藏标题栏（display:none 零占位、不拦截点击）
- **配色**：dark 跟 `--bg #0e0f12`/`--text2 #a8adba`，light 跟 `#ffffff`/`#5d6068`（chat.html 真实令牌）；
  无令牌页面落到 fallback 深色 `#0e0f12`
- **mac 分支**：不画按钮（系统红绿灯）、左让位 78px

## 本轮实测抓到并修掉的缺陷

1. **真缺陷（改代码）**：`#ma-titlebar` 原是 `height:32px` + `border-bottom:1px` 且默认
   `content-box` → 总高 **33px**，而 body 只让出 32px，边框压内容 1px。
   修法：给标题栏补 `box-sizing:border-box`，总高恰 32px（`preload.js`）。
2. **断言缺陷（改测试）**：全屏态下两条几何断言（横贯宽度、命中测试）不该跑
   —— 标题栏已 `display:none`，量到 `width=0`、点到 BODY 是正确行为，补状态 gate。

## 交付物状态

- `mini-agent-desktop/preload.js`（8760 bytes，含 box-sizing 修复）、`main.js` 已是最新
- `dist/win-unpacked/resources/app.asar` 已重打包（**172185 bytes**），
  包内 preload.js 与工作区**逐字节一致**，`frame:false`/`applyApplicationMenu` 均在
- 待用户真桌面验收：`dist\win-unpacked\MiniAgent.exe`
  （拖拽 / 双击最大化 / 三键 / 明暗跟随 / F11 全屏隐藏标题栏）
- 验收通过后重打正式包：`electron-builder --win portable`（替换 dist 里 14:56 的旧 1.0.1 exe）

## 复现方式

```bash
python C:/Users/abc/AppData/Local/Temp/ma-tb-build/build_harness.py   # 重新装配测试页
python C:/Users/abc/AppData/Local/Temp/ma-tb-build/run_harness.py     # 8 次无头 Edge，末行 TOTAL_FAIL
```

断言逐条结果：同目录 `result-*.txt`。
