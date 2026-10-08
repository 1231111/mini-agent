const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, dialog, shell } = require('electron');
const { spawn } = require('child_process');
const crypto = require('crypto');
const http = require('http');
const path = require('path');
const fs = require('fs');

/* ─────────────────────────── 配置 ─────────────────────────── */

const configPath = path.join(app.getPath('userData'), 'config.json');

function loadConfig() {
  try {
    if (fs.existsSync(configPath)) {
      return JSON.parse(fs.readFileSync(configPath, 'utf8'));
    }
  } catch (e) {}
  return {
    windowBounds: { width: 1200, height: 800 },
    theme: 'dark'
  };
}

function saveConfig(c) {
  try {
    fs.writeFileSync(configPath, JSON.stringify(c, null, 2), 'utf8');
  } catch (e) {
    console.error('Failed to save config:', e);
  }
}

let config = loadConfig();
let mainWindow;
let tray;
let backend = null;
let backendOwned = false;
let backendLogPath = null;
let backendLogStream = null;
let backendFailure = null;
let backendReady = false;
let runtimePort = 0;

const READY_PREFIX = 'MINIAGENT_READY ';
const BACKEND_START_TIMEOUT_MS = 120000;

const getApiBase = () => `http://127.0.0.1:${runtimePort}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * 账号门户地址（注册 / 会员等级 / 充值都在这个站点上）。
 *
 * 这是「跳出去」的落点，不是后端 API 地址 —— 两者不要混用：API 地址由
 * agent.auth.cloud.base-url 配在后端进程里，门户地址只有壳自己需要。
 *
 * 刻意不从后端 /api/auth/cloud-status 取这个值：那条路径在 SecurityConfig.PUBLIC_PATHS
 * 里，未认证就能访问，而云端地址属于部署细节，不该从一个公开接口漏出去。
 *
 * 来源优先级：环境变量 MINIAGENT_PORTAL_URL > config.json 的 portalUrl。
 * 都没有就返回空串。云端地址由部署时填写，不写死本机。
 */
function portalUrl() {
  const raw = process.env.MINIAGENT_PORTAL_URL !== undefined
    ? process.env.MINIAGENT_PORTAL_URL
    : (config.portalUrl !== undefined && config.portalUrl !== null
      ? String(config.portalUrl)
      : '');
  return raw.trim().replace(/\/+$/, '');
}

/**
 * 数据目录。必须与后端 application.yml 里的
 *   agent.data-dir = ${MINI_AGENT_HOME:${user.home}/.miniagent}
 * 保持一致。不一致的话，壳去读日志的位置和后端写日志的位置是两个目录。
 */
function resolveDataDir() {
  return process.env.MINI_AGENT_HOME || path.join(app.getPath('home'), '.miniagent');
}

/* ────────────────────── 运行时路径解析 ────────────────────── */

/**
 * 解析 java 可执行文件与 fat jar。
 *
 * 打包后走随包分发的 JRE（electron-builder 的 extraResources 里那个 jre/），
 * 不再依赖客户机装 JDK，也不再调用 mvnw —— 后者要求客户机有 Maven
 * 并且能联网解析依赖，出厂形态下必然失败。
 */
function resolveRuntime() {
  const javaExe = process.platform === 'win32' ? 'java.exe' : 'java';

  if (app.isPackaged) {
    return {
      mode: 'packaged',
      java: path.join(process.resourcesPath, 'jre', 'bin', javaExe),
      jar: path.join(process.resourcesPath, 'mini-agent-app.jar')
    };
  }

  // 开发态：PATH 上的 java + mini-agent-app/target 下最新的 fat jar。
  // 过滤掉 .original —— spring-boot repackage 会把 repackage 之前的原始 jar
  // 留成 xxx.jar.original，它没有 Boot 启动器，选错会报 no main manifest。
  const targetDir = path.resolve(__dirname, '..', 'mini-agent-app', 'target');
  let jar = null;
  try {
    const newest = fs.readdirSync(targetDir)
      .filter((f) => f.endsWith('.jar') && !f.endsWith('.original'))
      .map((f) => ({ f, t: fs.statSync(path.join(targetDir, f)).mtimeMs }))
      .sort((a, b) => b.t - a.t)[0];
    if (newest) jar = path.join(targetDir, newest.f);
  } catch (e) {}
  return { mode: 'dev', java: 'java', jar };
}

/* ───────────────────── 后端健康检查 ───────────────────── */

/**
 * 探后端就绪端点。
 *
 * 端口只接受当前子进程通过 READY+nonce 上报的随机端口；这里再确认应用已完全就绪。
 *
 * 前提：desktop 档已把 management.health.redis.enabled 置 false，
 * 否则不提供 Redis 的客户机上这个端点会永远 DOWN。
 */
function probeHealth(port, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const req = http.get(
      { host: '127.0.0.1', port, path: '/actuator/health', timeout: timeoutMs },
      (res) => {
        let body = '';
        res.on('data', (c) => { body += c; });
        res.on('end', () => {
          try {
            resolve(res.statusCode === 200 && JSON.parse(body).status === 'UP');
          } catch (e) {
            resolve(false);
          }
        });
      }
    );
    req.on('error', () => resolve(false));
    req.on('timeout', () => { req.destroy(); resolve(false); });
  });
}

/* ───────────────────────── 后端进程 ───────────────────────── */

function startBackend(rt, nonce) {
  const logDir = path.join(resolveDataDir(), 'logs');
  fs.mkdirSync(logDir, { recursive: true });
  backendLogPath = path.join(logDir, 'backend.log');

  backendLogStream = fs.createWriteStream(backendLogPath, { flags: 'a' });
  backendLogStream.write(
    `\n===== backend start ${new Date().toISOString()} mode=${rt.mode} port=auto =====\n`
  );

  // 保留最后若干行：后端启动失败时把真实报错带进弹窗，
  // 而不是只给一句「起不来」让用户自己去翻日志。
  const tail = [];
  const record = (chunk) => {
    if (backendLogStream) backendLogStream.write(chunk);
    String(chunk).split(/\r?\n/).forEach((line) => {
      if (line.trim()) {
        tail.push(line);
        if (tail.length > 40) tail.shift();
      }
    });
  };

  // 随包浏览器的位置必须让后端知道，否则它会去平台默认缓存目录找 —— 那里是空的，
  // 于是每次启动都触发一次上百 MB 的下载（客户机离线时直接失败）。
  //
  // 用环境变量而不是 -D 系统属性：Playwright 自己认的是 PLAYWRIGHT_BROWSERS_PATH，
  // 系统属性只能影响 BrowserService 那一侧的检查逻辑，两边会指向不同目录。
  const env = { ...process.env };
  env.MINI_AGENT_DESKTOP_NONCE = nonce;
  const bundledBrowsers = path.join(process.resourcesPath, 'browsers');
  if (app.isPackaged && fs.existsSync(bundledBrowsers)) {
    env.PLAYWRIGHT_BROWSERS_PATH = bundledBrowsers;
  }

  // 内联 embedding 的模型同理，但后果更隐蔽：不告诉后端它在哪，后端会去
  // agent.data-dir/models 找 —— 出厂客户机上那里是空的，于是
  // LocalOnnxEmbeddingModel 降级成"没有向量的检索"。
  // 症状是"检索结果不准"，不是报错，用户和客服都很难联想到"安装包"。
  //
  // 用环境变量而不是 -D：application-desktop.yml 里写的就是
  //   ${MINI_AGENT_MODELS_DIR:${agent.data-dir}/models}
  // 传系统属性的话，这个占位符解析不到，仍然会回退到 data-dir。
  const bundledModels = path.join(process.resourcesPath, 'models');
  if (app.isPackaged && fs.existsSync(bundledModels)) {
    env.MINI_AGENT_MODELS_DIR = bundledModels;
  }

  backend = spawn(
    rt.java,
    ['-jar', rt.jar, '--spring.profiles.active=desktop', '--server.port=0'],
    {
      cwd: resolveDataDir(),
      env,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
      // Unix 上必须以独立进程组启动，否则 stopBackend() 只能杀掉 java 自己，
      // 后端拉起的 Chromium / 渲染子进程会变成孤儿留在系统里。
      // Windows 不需要这个标志 —— 那边靠 taskkill /t 级联。
      detached: process.platform !== 'win32'
    }
  );
  backendOwned = true;

  return new Promise((resolve, reject) => {
    let settled = false;
    let stdoutBuffer = '';
    const timer = setTimeout(() => {
      fail(`后端在 ${Math.round(BACKEND_START_TIMEOUT_MS / 1000)} 秒内未上报 READY`);
    }, BACKEND_START_TIMEOUT_MS);

    const succeed = (port) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      resolve(port);
    };
    const fail = (message) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      backendFailure = message;
      stopBackend();
      reject(new Error(message));
    };

    backend.stdout.on('data', (chunk) => {
      record(chunk);
      stdoutBuffer += String(chunk);
      const lines = stdoutBuffer.split(/\r?\n/);
      stdoutBuffer = lines.pop() || '';
      for (const line of lines) {
        if (!line.startsWith(READY_PREFIX)) {
          continue;
        }
        try {
          const ready = JSON.parse(line.slice(READY_PREFIX.length));
          if (ready.nonce !== nonce) {
            fail('后端 READY nonce 校验失败');
            return;
          }
          const port = Number(ready.port);
          if (!Number.isInteger(port) || port < 1 || port > 65535) {
            fail(`后端 READY 端口非法: ${ready.port}`);
            return;
          }
          succeed(port);
        } catch (e) {
          fail(`后端 READY 格式非法: ${e.message}`);
        }
      }
    });
    backend.stderr.on('data', record);

    backend.on('error', (err) => {
      fail(`无法执行 ${rt.java}\n${err.message}`);
    });

    backend.on('exit', (code, signal) => {
      if (backendLogStream) {
        backendLogStream.write(`===== backend exit code=${code} signal=${signal} =====\n`);
        backendLogStream.end();
        backendLogStream = null;
      }
      if (!app.isQuitting && code !== 0 && !backendFailure) {
        backendFailure = `后端进程退出（code=${code}）\n\n${tail.slice(-12).join('\n')}`;
      }
      const exitFailure = backendFailure || `后端在 READY 前退出（code=${code}）`;
      backend = null;
      if (!settled) {
        fail(exitFailure);
      }
    });
  });
}

function stopBackend() {
  if (!backendOwned || !backend || backend.killed) return;
  if (process.platform === 'win32') {
    // /t：一并结束后端可能拉起的子进程
    spawn('taskkill', ['/pid', String(backend.pid), '/f', '/t'], { windowsHide: true });
  } else {
    // 负 PID 表示"整个进程组" —— 这是 spawn 时 detached:true 换来的能力。
    // 只对 java 自己发 SIGTERM 的话，它拉起的 Chromium / ffmpeg 会变成孤儿进程，
    // 用户退出应用之后这些进程还在后台跑着，占内存也占端口。
    try {
      process.kill(-backend.pid, 'SIGTERM');
    } catch (e) {
      // 进程组已经不在了（后端自己先退的）。退回杀单个 PID，再失败就说明进程已消失。
      try { backend.kill('SIGTERM'); } catch (_) { /* 已退出，无需处理 */ }
    }
  }
  backendOwned = false;
}

async function waitForBackend(port, timeoutMs = 120000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (backendFailure) throw new Error(backendFailure);
    if (await probeHealth(port)) return;
    await sleep(1000);
  }
  throw new Error(`后端在 ${Math.round(timeoutMs / 1000)} 秒内没有就绪`);
}

/* ─────────────────────────── 窗口 ─────────────────────────── */

/**
 * 应用菜单：菜单栏不显示，但快捷键必须保留。
 *
 * 关键在于「菜单栏隐藏」和「没有菜单」是两件事：
 *   setApplicationMenu(null)  —— 菜单没了，挂在菜单项上的 accelerators 也一并失效，
 *                               Ctrl+R 刷新、Ctrl+Shift+I 开工具、Ctrl+/-/0 缩放、
 *                               F11 全屏、Ctrl+W 关窗全部死掉；
 *   setMenuBarVisibility(false) —— 只是不画菜单栏那一行，菜单树还在，
 *                               accelerators 照常工作。
 * 所以这里保留一份全 role 的标准菜单（role 项的快捷键是 Electron 内置的，
 * 不用自己维护键位表），再对 Windows / Linux 隐藏菜单栏本身。
 *
 * macOS 不隐藏：菜单栏是平台规范，红绿灯与菜单一体，所有 mac 应用都有。
 */
function applyApplicationMenu() {
  const menu = Menu.buildFromTemplate([
    { role: 'fileMenu' },
    { role: 'editMenu' },
    { role: 'viewMenu' },
    { role: 'windowMenu' },
    ...(process.platform === 'darwin' ? [{ role: 'helpMenu' }] : [])
  ]);
  Menu.setApplicationMenu(menu);
  if (process.platform !== 'darwin' && mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.setMenuBarVisibility(false);
  }
}

function createWindow() {
  const bounds = config.windowBounds || { width: 1200, height: 800 };

  mainWindow = new BrowserWindow({
    width: bounds.width,
    height: bounds.height,
    minWidth: 800,
    minHeight: 600,
    title: 'MiniAgent',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      webSecurity: true
    },
    // WorkBuddy 式窗口：去掉系统标题栏与边框，标题栏由 preload 在页面里自绘
    // （拖拽区 + 双击最大化 + 右上角最小化/最大化/关闭），视觉上与页面融为一体。
    // macOS 不去框：红绿灯是平台惯例，hiddenInset 只收掉标题栏文字、保留红绿灯，
    // 页面同样让出顶部空间（preload 按平台处理）。
    ...(process.platform === 'darwin'
      ? { frame: true, titleBarStyle: 'hiddenInset' }
      : { frame: false }),
    backgroundColor: '#0e0f12',
    show: false
  });

  /* 渲染层的报错默认只出现在 F12 控制台里 —— 客户机上没人会按 F12。
     落一份到 <数据目录>/logs/renderer.log，让「界面卡住」这类问题第一次就能定位。
     真实案例：preload 用 contextBridge 挂了名为 api 的不可配置全局属性，
     页面顶层又声明了 function api，整段脚本解析期即失败，界面永远停在 boot 页，
     而此前任何地方都不会记录这件事。 */
  const rendererLogPath = path.join(resolveDataDir(), 'logs', 'renderer.log');
  const appendRendererLog = (line) => {
    try {
      fs.mkdirSync(path.dirname(rendererLogPath), { recursive: true });
      fs.appendFileSync(rendererLogPath, line + '\n');
    } catch (e) { /* 日志写不进去不能影响主流程 */ }
  };
  appendRendererLog(`\n===== renderer ${new Date().toISOString()} =====`);

  // Electron 28 的签名是 (event, level, message, line, sourceId)；
  // Electron 30+ 改成了 (event, details)。两种都接住，免得升级后静默失效。
  mainWindow.webContents.on('console-message', (...a) => {
    const LEVELS = ['verbose', 'info', 'warning', 'error'];
    if (a[1] && typeof a[1] === 'object') {
      const d = a[1];
      appendRendererLog(`[${LEVELS[d.level] || d.level}] ${d.message}` +
        (d.sourceId ? `  (${d.sourceId}:${d.lineNumber})` : ''));
    } else {
      appendRendererLog(`[${LEVELS[a[1]] || a[1]}] ${a[2]}` + (a[4] ? `  (${a[4]}:${a[3]})` : ''));
    }
  });
  mainWindow.webContents.on('preload-error', (_e, preloadPath, error) => {
    appendRendererLog(`[preload-error] ${preloadPath}: ${error && error.message}`);
  });
  mainWindow.webContents.on('did-fail-load', (_e, code, desc, url) => {
    appendRendererLog(`[did-fail-load] code=${code} ${desc} ${url}`);
  });
  mainWindow.webContents.on('render-process-gone', (_e, details) => {
    appendRendererLog(`[render-process-gone] reason=${details.reason} exitCode=${details.exitCode}`);
  });

  // 成功启动后直接承载后端的完整聊天客户端。不要在 Electron 里再维护一套
  // 简化聊天页，否则会持续漏掉会话历史、附件、Markdown、权限模式等能力。
  // 本地页面只保留给后端启动失败时展示诊断信息。
  if (backendReady) {
    mainWindow.loadURL(`${getApiBase()}/`);
  } else {
    mainWindow.loadFile('src/index.html');
  }

  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
  });

  // 菜单栏隐藏但快捷键保留（否则 Ctrl+R / F12 / 缩放等 accelerators 全部失效）。
  applyApplicationMenu();

  // 自绘标题栏要跟着窗口状态换图标（最大化 ↔ 还原），全屏时整体隐藏标题栏。
  const pushWindowState = () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('win:state-changed', {
        maximized: mainWindow.isMaximized(),
        fullscreen: mainWindow.isFullScreen()
      });
    }
  };
  mainWindow.on('maximize', pushWindowState);
  mainWindow.on('unmaximize', pushWindowState);
  mainWindow.on('enter-full-screen', pushWindowState);
  mainWindow.on('leave-full-screen', pushWindowState);

  mainWindow.webContents.on('before-input-event', (event, input) => {
    if (input.key === 'F12' && input.type === 'keyDown') {
      mainWindow.webContents.toggleDevTools();
    }
  });

  mainWindow.on('resize', saveWindowBounds);
  mainWindow.on('move', saveWindowBounds);

  mainWindow.on('close', (e) => {
    if (!app.isQuitting) {
      e.preventDefault();
      mainWindow.hide();
    }
  });
}

function saveWindowBounds() {
  if (mainWindow && !mainWindow.isDestroyed()) {
    config.windowBounds = mainWindow.getBounds();
    saveConfig(config);
  }
}

function createTray() {
  let trayIcon;
  try {
    const iconPath = path.join(__dirname, 'assets/icon.png');
    if (fs.existsSync(iconPath)) {
      trayIcon = nativeImage.createFromPath(iconPath);
    }
  } catch (e) {}

  if (!trayIcon || trayIcon.isEmpty()) {
    const size = 16;
    const buffer = Buffer.alloc(size * size * 4);
    for (let i = 0; i < size * size; i++) {
      buffer[i * 4] = 102;
      buffer[i * 4 + 1] = 126;
      buffer[i * 4 + 2] = 234;
      buffer[i * 4 + 3] = 255;
    }
    trayIcon = nativeImage.createFromBuffer(buffer, { width: size, height: size });
  }

  tray = new Tray(trayIcon);
  tray.setToolTip('MiniAgent');

  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '显示窗口', click: () => { if (mainWindow) { mainWindow.show(); mainWindow.focus(); } } },
    { type: 'separator' },
    { label: '退出', click: () => { app.isQuitting = true; app.quit(); } }
  ]));

  tray.on('double-click', () => {
    if (mainWindow) { mainWindow.show(); mainWindow.focus(); }
  });
}

/* ────────────────────────── IPC ────────────────────────── */

ipcMain.handle('get-api-base', () => getApiBase());

ipcMain.handle('get-config', (event, key) => config[key]);

ipcMain.handle('set-config', (event, key, value) => {
  config[key] = value;
  saveConfig(config);
});

ipcMain.handle('get-platform', () => process.platform);

/**
 * 就绪状态的主动查询入口。
 * 渲染层主要靠事件拿状态，但事件可能在页面脚本注册监听之前就发出去了，
 * 所以同时提供这个 handler 让页面可以自己补一次。
 */
ipcMain.handle('get-backend-status', () => ({
  ready: backendReady,
  port: runtimePort,
  apiBase: getApiBase(),
  error: backendFailure,
  logPath: backendLogPath
}));

/** 账号门户地址；空串表示本机没配门户，界面应退回壳内注册。 */
ipcMain.handle('get-portal-url', () => portalUrl());

/**
 * 用系统浏览器打开账号门户。
 *
 * 只放行 http/https，且用白名单判定。渲染层一旦被注入，这个 handler 就等于
 * "以当前用户身份打开任意 URL"的能力，而 Windows 上 file:// 与各种自定义协议
 * 可以直接跳到本地可执行文件 —— 那是从"页面出错"升级成"本地代码执行"。
 * 不用黑名单：黑名单漏一个就是漏一个。
 */
ipcMain.handle('open-external', async (event, rawUrl) => {
  let parsed;
  try {
    parsed = new URL(String(rawUrl));
  } catch (e) {
    return { ok: false, error: '不是合法的 URL' };
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    return { ok: false, error: `只允许 http/https，收到 ${parsed.protocol}` };
  }
  try {
    await shell.openExternal(parsed.toString());
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e.message || String(e) };
  }
});

/* ────────── 自绘标题栏的窗口控制（preload 里那三个按钮走这里） ────────── */

ipcMain.handle('win:minimize', () => {
  if (mainWindow && !mainWindow.isDestroyed()) mainWindow.minimize();
});

ipcMain.handle('win:toggle-maximize', () => {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  if (mainWindow.isMaximized()) {
    mainWindow.unmaximize();
  } else {
    mainWindow.maximize();
  }
});

/**
 * 关闭按钮。走 mainWindow.close() 而不是直接 hide —— close 事件上挂着
 * 「隐藏到托盘」的既有逻辑（见 createWindow 里的 close 处理），窗口控制按钮
 * 必须与标题栏行为一致，不能另立一套退出语义。
 */
ipcMain.handle('win:close', () => {
  if (mainWindow && !mainWindow.isDestroyed()) mainWindow.close();
});

ipcMain.handle('win:state', () => ({
  maximized: mainWindow && !mainWindow.isDestroyed() ? mainWindow.isMaximized() : false,
  fullscreen: mainWindow && !mainWindow.isDestroyed() ? mainWindow.isFullScreen() : false
}));

/* ────────────────────── 应用生命周期 ────────────────────── */

/**
 * 单实例锁。
 *
 * H2 文件模式是单进程独占的：第二个进程去开同一个 .mv.db 会直接失败
 * （实测报 "Database may be already in use" / "The file is locked"）。
 *
 * 不拦的话双击第二次的后果是这样的：
 *   如果同时拉起两个后端，第二个后端一碰数据库就会撞锁、启动失败，
 *   弹出一个用户看不懂的报错框。窗口能开，功能是坏的。
 *
 * 拿不到锁的进程直接退出，并把已有窗口拉到前台 —— 这也正是用户
 * 双击图标时的预期行为。
 */
const hasSingleInstanceLock = app.requestSingleInstanceLock();

if (!hasSingleInstanceLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.show();
      mainWindow.focus();
    }
  });

  app.whenReady().then(startUp);
}

async function startUp() {
  const rt = resolveRuntime();
  let failure = null;

  try {
    if (!rt.jar) {
      throw new Error(
        '没有找到后端 jar。\n' +
        '开发态请先在仓库根目录执行：\n' +
        '  mvnw.cmd package -pl mini-agent-app -am -DskipTests'
      );
    }

    const nonce = crypto.randomBytes(32).toString('hex');
    runtimePort = await startBackend(rt, nonce);
    await waitForBackend(runtimePort, BACKEND_START_TIMEOUT_MS);
    backendReady = true;
  } catch (e) {
    failure = e.message;
    backendFailure = failure;
  }

  // 无论成败都开窗口：失败时窗口里给出可读原因，比静默退出有用。
  createWindow();
  createTray();

  mainWindow.webContents.once('did-finish-load', () => {
    if (backendReady) {
      mainWindow.webContents.send('backend-ready', { port: runtimePort, apiBase: getApiBase() });
    } else if (failure) {
      mainWindow.webContents.send('backend-error', {
        message: failure,
        logPath: backendLogPath
      });
    }
  });

  if (failure) {
    dialog.showErrorBox('后端启动失败', `${failure}\n\n日志：${backendLogPath || '(未产生)'}`);
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    } else if (mainWindow) {
      mainWindow.show();
    }
  });
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('before-quit', () => {
  app.isQuitting = true;
  stopBackend();
});
