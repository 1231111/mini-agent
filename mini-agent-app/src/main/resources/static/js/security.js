(function () {
    'use strict';

    const originalFetch = window.fetch.bind(window);
    const safeMethods = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);

    function cookie(name) {
        const prefix = name + '=';
        const part = document.cookie.split(';').map(v => v.trim())
            .find(v => v.startsWith(prefix));
        return part ? decodeURIComponent(part.substring(prefix.length)) : '';
    }

    function requestId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') {
            return window.crypto.randomUUID();
        }
        return 'web-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
    }

    function requestPath(url) {
        return url.origin === window.location.origin ? url.pathname : url.origin + url.pathname;
    }

    async function issueCsrfToken() {
        await originalFetch('/api/auth-status', {
            method: 'GET',
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { 'X-Request-ID': requestId() }
        });
        return cookie('XSRF-TOKEN');
    }

    function clearCsrfToken() {
        document.cookie = 'XSRF-TOKEN=; Path=/; Max-Age=0';
    }

    window.fetch = async function (input, init) {
        const options = Object.assign({}, init || {});
        const inputRequest = typeof input !== 'string' ? input : null;
        const method = String(options.method || (inputRequest && inputRequest.method) || 'GET').toUpperCase();
        const url = typeof input === 'string' ? new URL(input, window.location.href)
            : new URL(input.url, window.location.href);
        const sameOrigin = url.origin === window.location.origin;
        const unsafeRequest = sameOrigin && !safeMethods.has(method);
        const baseHeaders = new Headers(options.headers || (inputRequest && inputRequest.headers));
        const id = baseHeaders.get('X-Request-ID') || (sameOrigin ? requestId() : null);
        if (sameOrigin) {
            baseHeaders.set('X-Request-ID', id);
        }
        options.credentials = options.credentials
            || (inputRequest && inputRequest.credentials) || 'same-origin';
        const path = requestPath(url);
        const startedAt = performance.now();
        console.info('[HTTP] request', { method, path, requestId: id || null });

        let retryInput = input;
        if (inputRequest) {
            try {
                retryInput = input.clone();
            } catch (_) {
                retryInput = null;
            }
        }

        let csrfToken = unsafeRequest ? cookie('XSRF-TOKEN') : '';
        if (unsafeRequest && !csrfToken) {
            csrfToken = await issueCsrfToken();
        }

        const send = target => {
            const attemptOptions = Object.assign({}, options);
            const headers = new Headers(baseHeaders);
            if (unsafeRequest && csrfToken) {
                headers.set('X-XSRF-TOKEN', csrfToken);
            }
            attemptOptions.headers = headers;
            return originalFetch(target, attemptOptions);
        };

        try {
            let response = await send(input);
            if (unsafeRequest && retryInput !== null && response.status === 403
                    && response.headers.get('X-CSRF-Error') === 'true') {
                clearCsrfToken();
                csrfToken = await issueCsrfToken();
                if (csrfToken) {
                    console.warn('[HTTP] retrying with refreshed CSRF token', {
                        method,
                        path,
                        requestId: id
                    });
                    response = await send(retryInput);
                }
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
