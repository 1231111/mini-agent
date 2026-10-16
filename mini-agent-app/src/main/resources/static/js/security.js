(function () {
    'use strict';

    const originalFetch = window.fetch.bind(window);
    const LOGIN_PATH = '/login';
    // 这几个接口本身就负责「报告未登录/登录失败」，它们的 401 不应触发跳转，
    // 否则登录页会因一次失败的登录尝试被反复重载。
    const AUTH_ENDPOINTS = ['/api/tokens', '/api/users'];
    let redirecting = false;

    function requestId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') {
            return window.crypto.randomUUID();
        }
        return 'web-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
    }

    function requestPath(url) {
        return url.origin === window.location.origin ? url.pathname : url.origin + url.pathname;
    }

    // 凭证来源按优先级取：
    //   1. 壳提供的持久存储（Electron 客户端）—— 跨重启保留，磁盘副本在页面之外
    //   2. 页面 web 存储（直接用浏览器打开本机后端时的开发态）
    // 服务端只认 Authorization: Bearer —— cookie 与 access_token 查询参数两条通路已随
    // 无状态改造移除，所以这里不再注入 X-XSRF-TOKEN，也不再读任何 cookie。
    function shellBridge() {
        try {
            var b = window.desktopAuth;
            return (b && typeof b.getToken === 'function') ? b : null;
        } catch (_) {
            return null;
        }
    }

    function authToken() {
        const b = shellBridge();
        if (b) {
            try {
                return b.getToken() || '';
            } catch (_) { /* 桥异常则退到 web 存储 */ }
        }
        try {
            return sessionStorage.getItem('ma_token') || '';
        } catch (_) {
            return '';
        }
    }

    // 壳可用时只写壳，不再往 sessionStorage 塞一份 ——
    // 同一份凭证少一处能被页面脚本读到的落点。
    function setAuthToken(tk) {
        const v = tk || '';
        const b = shellBridge();
        if (b && typeof b.setToken === 'function') {
            try {
                b.setToken(v);
                return;
            } catch (_) { /* 落壳失败则退到 web 存储，至少本次会话可用 */ }
        }
        try { sessionStorage.setItem('ma_token', v); } catch (_) {}
    }

    // 清除两边都做：旧版本往 sessionStorage 写过，升级上来的用户那份残留不清掉，
    // 下次打开又会被当成已登录。
    function clearAuthToken() {
        const b = shellBridge();
        if (b && typeof b.clear === 'function') {
            try { b.clear(); } catch (_) {}
        }
        try { sessionStorage.removeItem('ma_token'); } catch (_) {}
    }

    /**
     * 401 统一处理：清掉本地 token 并跳登录页。
     * redirecting 防抖——并发请求会同时收到 401，不能触发多次跳转。
     */
    function handleUnauthorized(path) {
        clearAuthToken();
        if (redirecting) {
            return;
        }
        if (window.location.pathname === LOGIN_PATH) {
            return;
        }
        if (AUTH_ENDPOINTS.some(p => path.startsWith(p))) {
            return;
        }
        redirecting = true;
        console.warn('[HTTP] 401 -> redirect to login', { path });
        window.location.replace(LOGIN_PATH);
    }

    // 页面脚本（chat.html 等）需要的 token 读写入口。放在这里是为了让
    // sessionStorage 的键名只有一处定义，避免各页面自己拼字符串。
    window.MiniAuth = {
        loginPath: LOGIN_PATH,
        token: authToken,
        setToken: setAuthToken,
        clear: clearAuthToken,
        goLogin: function () {
            redirecting = true;
            window.location.replace(LOGIN_PATH);
        }
    };

    window.fetch = async function (input, init) {        const options = Object.assign({}, init || {});
        const inputRequest = typeof input !== 'string' ? input : null;
        const method = String(options.method || (inputRequest && inputRequest.method) || 'GET').toUpperCase();
        const url = typeof input === 'string' ? new URL(input, window.location.href)
            : new URL(input.url, window.location.href);
        const sameOrigin = url.origin === window.location.origin;
        // Some browsers reject `new Headers(null)`. Start empty, then copy
        // headers from the fetch options or the Request object explicitly.
        const baseHeaders = new Headers(options.headers || undefined);
        if (!options.headers && inputRequest) {
            inputRequest.headers.forEach((value, name) => baseHeaders.set(name, value));
        }
        const id = baseHeaders.get('X-Request-ID') || (sameOrigin ? requestId() : null);
        if (sameOrigin) {
            baseHeaders.set('X-Request-ID', id);
        }
        options.credentials = options.credentials
            || (inputRequest && inputRequest.credentials) || 'same-origin';
        const path = requestPath(url);
        const startedAt = performance.now();
        console.info('[HTTP] request', { method, path, requestId: id || null });

        const send = target => {
            const attemptOptions = Object.assign({}, options);
            const headers = new Headers(baseHeaders);
            const tk = authToken();
            if (sameOrigin && tk) {
                headers.set('Authorization', 'Bearer ' + tk);
            }
            attemptOptions.headers = headers;
            return originalFetch(target, attemptOptions);
        };

        try {
            const response = await send(input);
            if (response.status === 401) {
                handleUnauthorized(path);
            }
            const elapsedMs = Math.round(performance.now() - startedAt);
            const responseId = response.headers.get('X-Request-ID') || id || null;
            console.info('[HTTP] response', {
                method,
                path,
                status: response.status,
                durationMs: elapsedMs,
                requestId: responseId
            });
            return response;
        } catch (error) {
            const elapsedMs = Math.round(performance.now() - startedAt);
            console.error('[HTTP] failed', {
                method,
                path,
                durationMs: elapsedMs,
                requestId: id || null,
                error: error && error.message ? error.message : String(error)
            });
            throw error;
        }
    };
})();
