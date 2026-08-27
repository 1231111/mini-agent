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

  // 监听主进程消息
  onRestartServer: (callback) => {
    ipcRenderer.on('restart-server', callback);
    return () => {
      ipcRenderer.removeListener('restart-server', callback);
    };
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
