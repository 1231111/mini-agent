const { contextBridge, ipcRenderer } = require('electron');

// 安全地暴露 API 给渲染进程
contextBridge.exposeInMainWorld('electronAPI', {
  // 获取后端 API 地址
  getApiBase: () => ipcRenderer.invoke('get-api-base'),

  // 配置存储
  getConfig: (key) => ipcRenderer.invoke('get-config', key),
  setConfig: (key, value) => ipcRenderer.invoke('set-config', key, value),

  // 获取平台信息
  getPlatform: () => ipcRenderer.invoke('get-platform'),

  // 账号门户地址（注册 / 会员 / 充值）。空串 = 本机没配门户，界面应退回壳内注册。
  getPortalUrl: () => ipcRenderer.invoke('get-portal-url'),

  // 用系统浏览器打开账号门户。主进程会校验只允许 http/https。
  openExternal: (url) => ipcRenderer.invoke('open-external', url),

  // 后端就绪 / 启动失败。主进程在后端 /actuator/health 返回 UP 之后发出，
  // 比渲染层自己盲轮询端口准确。
  getBackendStatus: () => ipcRenderer.invoke('get-backend-status'),

  onBackendReady: (callback) => {
    const handler = (_e, payload) => callback(payload);
    ipcRenderer.on('backend-ready', handler);
    return () => ipcRenderer.removeListener('backend-ready', handler);
  },

  onBackendError: (callback) => {
    const handler = (_e, payload) => callback(payload);
    ipcRenderer.on('backend-error', handler);
    return () => ipcRenderer.removeListener('backend-error', handler);
  },

  // 监听主进程消息
  onRestartServer: (callback) => {
    ipcRenderer.on('restart-server', callback);
    return () => {
      ipcRenderer.removeListener('restart-server', callback);
    };
  },

  // 自绘标题栏的窗口控制（最小化 / 最大化·还原 / 关闭）与窗口状态订阅
  windowControls: {
    minimize: () => ipcRenderer.invoke('win:minimize'),
    toggleMaximize: () => ipcRenderer.invoke('win:toggle-maximize'),
    close: () => ipcRenderer.invoke('win:close'),
    getState: () => ipcRenderer.invoke('win:state'),
    onStateChanged: (callback) => {
      const handler = (_e, state) => callback(state);
      ipcRenderer.on('win:state-changed', handler);
      return () => ipcRenderer.removeListener('win:state-changed', handler);
    }
  }
});

// 暴露 fetch API 的增强版本，自动添加 API 基础地址
contextBridge.exposeInMainWorld('api', {
  fetch: async (url, options = {}) => {
    const apiBase = await ipcRenderer.invoke('get-api-base');
    const fullUrl = url.startsWith('http') ? url : `${apiBase}${url}`;
    return fetch(fullUrl, options);
  },

  // SSE 流式请求
  fetchStream: async (url, options = {}) => {
    const apiBase = await ipcRenderer.invoke('get-api-base');
    const fullUrl = url.startsWith('http') ? url : `${apiBase}${url}`;
    return fetch(fullUrl, options);
  }
});

/* ═══════════════════════════════════════════════════════════════════
   自绘标题栏（WorkBuddy 式窗口）
   ═══════════════════════════════════════════════════════════════════
   主进程已把系统标题栏/边框去掉（frame:false），这一段负责在页面里补回
   窗口该有的功能：拖拽移动、双击最大化/还原、右上角最小化/最大化/关闭。

   为什么放在 preload 而不是后端模板：主窗口加载的是后端的完整聊天 UI
   （chat / login / membership / trace 四个页面 + 本地诊断页），放 preload
   一份代码对所有页面生效，后端模板零改动，将来新增页面也自动带上。

   配色用 CSS 变量 + fallback：chat.html 的设计令牌（--bg / --border /
   --text2 / --surface2）会随 data-theme 明暗切换，标题栏自动跟随主题；
   没有这些变量的页面（login 固定深色、本地诊断页）落到 fallback 深色。
   ───────────────────────────────────────────────────────────────── */
(function installTitlebar() {
  const BAR_HEIGHT = 32;
  const IS_MAC = process.platform === 'darwin';

  // 直接走 ipcRenderer，不经过 window.electronAPI —— 后者是 exposeInMainWorld
  // 挂到页面主世界的，而这里（含事件监听器）跑在 preload 的隔离上下文里，
  // 读不到主世界的 window。
  const winCtl = {
    minimize: () => ipcRenderer.invoke('win:minimize'),
    toggleMaximize: () => ipcRenderer.invoke('win:toggle-maximize'),
    close: () => ipcRenderer.invoke('win:close'),
    getState: () => ipcRenderer.invoke('win:state'),
    onStateChanged: (callback) => {
      const handler = (_e, state) => callback(state);
      ipcRenderer.on('win:state-changed', handler);
      return () => ipcRenderer.removeListener('win:state-changed', handler);
    }
  };

  const SVG = {
    // 最小化：一条横线
    min: '<svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">' +
         '<rect x="0" y="4.5" width="10" height="1" fill="currentColor"/></svg>',
    // 最大化：空心方框
    max: '<svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">' +
         '<rect x="0.5" y="0.5" width="9" height="9" fill="none" stroke="currentColor"/></svg>',
    // 还原：双方框
    restore: '<svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">' +
             '<rect x="0.5" y="2.5" width="7" height="7" fill="none" stroke="currentColor"/>' +
             '<polyline points="2.5,2.5 2.5,0.5 8,0.5 8,6" fill="none" stroke="currentColor"/></svg>',
    // 关闭：叉
    close: '<svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">' +
           '<path d="M0.5,0.5 L9.5,9.5 M9.5,0.5 L0.5,9.5" stroke="currentColor" fill="none"/></svg>'
  };

  function install() {
    if (!document.body || document.getElementById('ma-titlebar')) return;

    const style = document.createElement('style');
    style.textContent = [
      '#ma-titlebar{',
      // border-box：height 含 1px 底边框。否则 content-box 下总高 33px，
      // 而 body 只让出 32px，边框会压住内容 1px（无头实测 height=33 抓到的）。
      '  box-sizing:border-box;',
      '  position:fixed;top:0;left:0;right:0;height:' + BAR_HEIGHT + 'px;',
      '  display:flex;align-items:center;',
      '  background:var(--bg,#0e0f12);',
      '  border-bottom:1px solid var(--border,rgba(255,255,255,.09));',
      '  color:var(--text2,#a8adba);',
      '  font:12px/1 "Segoe UI",system-ui,-apple-system,sans-serif;',
      '  user-select:none;-webkit-user-select:none;',
      '  -webkit-app-region:drag;',
      '  z-index:2147483647;',
      '  padding-left:' + (IS_MAC ? 78 : 12) + 'px;',  // mac 给红绿灯让位
      '}',
      '#ma-titlebar .ma-tb-dot{width:8px;height:8px;border-radius:50%;',
      '  background:var(--accent,#10a37f);margin-right:8px;flex:none;}',
      '#ma-titlebar .ma-tb-title{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',
      '#ma-titlebar .ma-tb-btns{margin-left:auto;display:flex;height:100%;',
      '  -webkit-app-region:no-drag;}',
      '#ma-titlebar .ma-tb-btn{width:46px;height:100%;border:0;padding:0;',
      '  display:flex;align-items:center;justify-content:center;',
      '  background:transparent;color:inherit;cursor:default;}',
      '#ma-titlebar .ma-tb-btn:hover{background:var(--surface2,#2a2e36);',
      '  color:var(--text,#f3f4f6);}',
      '#ma-titlebar .ma-tb-btn.ma-tb-close:hover{background:#e81123;color:#fff;}',
      'html.ma-titlebar-hidden #ma-titlebar{display:none;}'
    ].join('\n');
    document.head.appendChild(style);

    const bar = document.createElement('div');
    bar.id = 'ma-titlebar';
    bar.title = 'MiniAgent';
    bar.innerHTML =
      '<div class="ma-tb-dot"></div>' +
      '<div class="ma-tb-title">Mini Agent</div>' +
      // mac 用系统红绿灯，不画按钮
      (IS_MAC ? '' :
        '<div class="ma-tb-btns">' +
          '<button class="ma-tb-btn ma-tb-min"  aria-label="最小化">' + SVG.min + '</button>' +
          '<button class="ma-tb-btn ma-tb-max"  aria-label="最大化">' + SVG.max + '</button>' +
          '<button class="ma-tb-btn ma-tb-close" aria-label="关闭">' + SVG.close + '</button>' +
        '</div>');
    document.body.appendChild(bar);

    // 页面让出顶部空间。叠加在原 padding 之上（不覆盖页面自带的 padding）；
    // border-box 是防「子元素 height:100% 叠 padding 溢出一屏」的双滚动条。
    const cs = window.getComputedStyle(document.body);
    const origPad = parseInt(cs.paddingTop, 10) || 0;
    document.body.style.boxSizing = 'border-box';
    document.body.style.paddingTop = (origPad + BAR_HEIGHT) + 'px';

    // 双击标题栏空白 = 最大化/还原（Windows 原生习惯）
    bar.addEventListener('dblclick', (e) => {
      if (e.target.closest('.ma-tb-btn')) return;
      winCtl.toggleMaximize();
    });

    if (!IS_MAC) {
      const btnMin = bar.querySelector('.ma-tb-min');
      const btnMax = bar.querySelector('.ma-tb-max');
      const btnClose = bar.querySelector('.ma-tb-close');
      btnMin.addEventListener('click', () => winCtl.minimize());
      btnMax.addEventListener('click', () => winCtl.toggleMaximize());
      btnClose.addEventListener('click', () => winCtl.close());

      // 最大化 ↔ 还原图标；全屏时整个标题栏让位（与原生全屏语义一致）
      const applyState = (state) => {
        const maximized = !!(state && (state.maximized || state.fullscreen));
        btnMax.innerHTML = maximized ? SVG.restore : SVG.max;
        btnMax.setAttribute('aria-label', maximized ? '还原' : '最大化');
        const hidden = !!(state && state.fullscreen);
        document.documentElement.classList.toggle('ma-titlebar-hidden', hidden);
        document.body.style.paddingTop = hidden ? '0px' : (origPad + BAR_HEIGHT) + 'px';
      };
      winCtl.getState().then(applyState);
      winCtl.onStateChanged(applyState);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', install, { once: true });
  } else {
    install();
  }
})();
