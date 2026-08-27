# MiniAgent Desktop 快速开始

## 一键启动 (Windows)

```
双击 start.bat
```

这会自动：
1. 启动 Spring Boot 后端
2. 等待后端就绪
3. 启动 Electron 桌面端

## 手动启动

### 步骤 1: 安装桌面端依赖

```bash
cd mini-agent-desktop
npm install
```

### 步骤 2: 启动后端

```bash
cd ..
mvn spring-boot:run -pl mini-agent-app
```

### 步骤 3: 启动桌面端

```bash
cd mini-agent-desktop
npm start
```

## 开发模式

```bash
# 带 DevTools 的开发模式
start-dev.bat
```

## 打包发布

```bash
# Windows 安装包
npm run build:win

# macOS 安装包
npm run build:mac

# Linux AppImage
npm run build:linux
```

## 常见问题

**Q: 后端连不上？**
A: 确保后端启动在 8080 端口，检查 `http://localhost:8080/actuator/health`

**Q: 如何修改端口？**
A: 编辑 `%APPDATA%/mini-agent-desktop/config.json`，修改 `serverPort`

**Q: 图标在哪里？**
A: 将你的应用图标放到 `assets/icon.png` (推荐 256x256 PNG)
