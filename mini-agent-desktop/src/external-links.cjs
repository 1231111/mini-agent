/** One web-only policy for renderer IPC, new windows and top-level navigation. */
function normalizeWebLink(rawUrl) {
  if (typeof rawUrl !== 'string' || rawUrl.length > 32768 || /[\u0000-\u001f\u007f]/.test(rawUrl)) return null;
  const value = rawUrl.trim();
  if (!/^https?:\/\//i.test(value)) return null;
  try {
    const parsed = new URL(value);
    if (!['https:', 'http:'].includes(parsed.protocol) || !parsed.hostname || parsed.username || parsed.password) return null;
    return parsed.toString();
  } catch (_) { return null; }
}

async function openWebLink(rawUrl, openExternal, reportError = () => {}) {
  const url = normalizeWebLink(rawUrl);
  if (!url) return { ok: false, error: '链接无效，仅支持不含账号密码的 HTTP/HTTPS 网页地址。' };
  try {
    await openExternal(url);
    return { ok: true };
  } catch (error) {
    reportError(error);
    return { ok: false, error: '无法打开系统浏览器，请检查默认浏览器设置后重试。' };
  }
}

function installExternalLinkHandling(webContents, openExternal, getAppOrigin, reportError = () => {}) {
  const open = rawUrl => openWebLink(rawUrl, openExternal, reportError);
  webContents.setWindowOpenHandler(({ url }) => {
    void open(url);
    return { action: 'deny' };
  });
  const navigate = (event, rawUrl) => {
    try {
      if (new URL(rawUrl).origin === getAppOrigin()) return;
    } catch (_) { /* Invalid and non-web navigation is blocked below. */ }
    event.preventDefault();
    void open(rawUrl);
  };
  webContents.on('will-navigate', navigate);
  webContents.on('will-redirect', navigate);
  return open;
}

module.exports = { normalizeWebLink, openWebLink, installExternalLinkHandling };
