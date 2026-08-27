# MiniAgent Desktop

MiniAgent 的桌面客户端，基于 Electron 构建。

## 功能特性

- 原生桌面应用体验
- 系统托盘支持
- 自动检测后端服务
- 暗色/亮色主题
- 窗口状态记忆

## 环境要求

- **Java**: JDK 21+
- **Node.js**: 18+
- **npm**: 9+

## 快速开始

### 1. 安装依赖

```bash
cd mini-agent-desktop
npm install
```

### 2. 启动应用

**Windows:**
```bash
# 双击 start.bat
# 或命令行
start.bat
```

**开发模式:**
```bash
start-dev.bat
```

**手动启动:**
```bash
# 终端 1: 启动后端
cd ..
mvn spring-boot:run -pl mini-agent-app

# 终端 2: 启动桌面端
cd mini-agent-desktop
npm start
```

## 项目结构

```
mini-agent-desktop/
├── main.js              # Electron 主进程
├── preload.js           # 预加载脚本 (安全桥接)
├── package.json         # 项目配置
├── start.bat            # Windows 启动脚本
├── start-dev.bat        # 开发模式启动脚本
├── src/
│   └── index.html       # 桌面端入口页面
├── assets/
│   └── icon.png         # 应用图标
└── dist/                # 打包输出目录
```

## 打包发布

### Windows
```bash
npm run build:win
```
生成 `dist/MiniAgent Setup x.x.x.exe`

### macOS
```bash
npm run build:mac
```
生成 `dist/MiniAgent-x.x.x.dmg`

### Linux
```bash
npm run build:linux
```
生成 `dist/MiniAgent-x.x.x.AppImage`

## 配置说明

应用配置存储在用户目录：
- **Windows**: `%APPDATA%/mini-agent-desktop/config.json`
- **macOS**: `~/Library/Application Support/mini-agent-desktop/config.json`
- **Linux**: `~/.config/mini-agent-desktop/config.json`

### 配置项

```json
{
  "windowBounds": { "width": 1200, "height": 800 },
  "serverPort": 8080,
  "theme": "dark"
}
```

## 开发说明

### 调试后端
```bash
# 启动后端 (带调试端口)
mvn spring-boot:run -pl mini-agent-app -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
```

### 调试前端
开发模式下 DevTools 自动打开，或按 `Ctrl+Shift+I`

### 热重载
后端代码修改后自动重启（Spring DevTools）
前端代码修改后需重启 Electron

## 常见问题

### Q: 后端连接失败
A: 确保 Spring Boot 后端已启动且运行在 8080 端口

### Q: 如何修改端口
A: 修改 `config.json` 中的 `serverPort`，或启动时设置环境变量

### Q: 如何禁止最小化到托盘
A: 在 `main.js` 中注释掉 `mainWindow.on('close', ...)` 相关代码

## 相关链接

- [Electron 官方文档](https://www.electronjs.org/)
- [electron-builder 文档](https://www.electron.build/)
