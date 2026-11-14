/* =========================================================================
   MESSAGE DOM BUILDERS
   ========================================================================= */
let botMessageSequence = 0;

function nextBotBodyId() {
    return 'bb_' + Date.now().toString(36) + '_' + (++botMessageSequence).toString(36);
}

function attachmentExtension(name) {
    return fileExt(String(name || '').split('?')[0].split('#')[0]).toLowerCase();
}

function attachmentNameFromSource(src) {
    try {
        const url = new URL(String(src || ''), window.location.href);
        const provided = url.searchParams.get('name');
        if (provided) return provided;
        return decodeURIComponentSafe(url.pathname.split('/').pop() || '附件');
    } catch (_) {
        return decodeURIComponentSafe(String(src || '').split('/').pop().split('?')[0] || '附件');
    }
}

function createAttachmentPreview(attachment) {
    const item = typeof attachment === 'string'
        ? { src: attachment, name: attachmentNameFromSource(attachment) }
        : (attachment || {});
    const src = String(item.src || item.url || '');
    const name = String(item.name || '附件');
    const ext = attachmentExtension(name || src).toUpperCase() || 'FILE';
    const type = String(item.type || '').toLowerCase();
    const mediaExt = ext.toLowerCase();
    const isImage = type.startsWith('image/') || ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'avif'].includes(mediaExt);
    const isVideo = type.startsWith('video/') || VIDEO_EXTS.has(mediaExt);
    const isAudio = type.startsWith('audio/') || AUDIO_EXTS.has(mediaExt);
    const card = document.createElement('div');
    card.className = 'attachment-preview' + (isImage ? ' image' : isVideo ? ' video' : isAudio ? ' audio' : ' file');
    if (src.startsWith('blob:')) card.dataset.objectUrl = src;

    if (isImage || isVideo || isAudio) {
        const media = document.createElement(isImage ? 'img' : isVideo ? 'video' : 'audio');
        media.className = 'attachment-media';
        if (isVideo) {
            media.controls = true;
            media.playsInline = true;
            media.preload = 'metadata';
        } else if (isAudio) {
            media.controls = true;
            media.preload = 'metadata';
        } else {
            media.alt = name;
            media.loading = 'lazy';
            media.referrerPolicy = 'no-referrer';
        }
        if (/^\/api\//i.test(src)) media.dataset.authSrc = src;
        else if (src) media.src = src;
        card.appendChild(media);
        if (isVideo || isAudio) {
            const label = document.createElement('div');
            label.className = 'attachment-caption';
            label.textContent = name;
            card.appendChild(label);
        }
    } else {
        const badge = document.createElement('span');
        badge.className = 'attachment-file-ext';
        badge.textContent = ext.slice(0, 5);
        const meta = document.createElement('span');
        meta.className = 'attachment-file-meta';
        const title = document.createElement('strong');
        title.textContent = name;
        meta.appendChild(title);
        const hint = document.createElement('small');
        hint.textContent = item.size ? formatBytes(item.size) : (src.startsWith('/api/conversation-media/') ? '点击下载' : '已附加');
        meta.appendChild(hint);
        card.append(badge, meta);
        if (src.startsWith('/api/conversation-media/')) {
            const download = document.createElement('a');
            download.href = '#';
            download.className = 'conversation-download-link';
            download.dataset.authDownload = src.replace('/api/conversation-media/', '/api/conversation-download/');
            download.dataset.downloadName = name;
            download.setAttribute('aria-label', '下载 ' + name);
            card.appendChild(download);
        } else if (src.startsWith('/api/user-upload-download/')) {
            const download = document.createElement('a');
            download.href = '#';
            download.className = 'conversation-download-link';
            download.dataset.authDownload = src;
            download.dataset.downloadName = name;
            download.setAttribute('aria-label', '下载 ' + name);
            card.appendChild(download);
        } else if (src.startsWith('blob:')) {
            const download = document.createElement('a');
            download.href = src;
            download.download = name;
            download.className = 'attachment-local-download';
            download.setAttribute('aria-label', '下载 ' + name);
            card.appendChild(download);
        }
    }
    if (item.size) card.dataset.size = String(item.size);
    return card;
}

function decodeURIComponentSafe(value) {
    try { return decodeURIComponent(value); } catch (_) { return value; }
}

function formatBytes(value) {
    const bytes = Number(value);
    if (!Number.isFinite(bytes) || bytes <= 0) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

function releaseAttachmentObjectUrls(root) {
    if (!root || !root.querySelectorAll) return;
    root.querySelectorAll('[data-object-url]').forEach(card => {
        URL.revokeObjectURL(card.dataset.objectUrl);
        card.removeAttribute('data-object-url');
    });
}

function renderUserMsg(text, imgSrc, imgCount = imgSrc ? 1 : 0, fileNames = [], isInject = false, attachments = null) {
    // 系统控制语（前端自己拼、当用户消息发回后端的那几条）不装成用户气泡：
    // 它不是用户打的字。装成气泡有两个后果 —— 用户以为「系统批注成了我的输入」，
    // 以及 row.dataset.task 会让顶部任务条把它读成任务标题。
    // 放在这里收口，是因为「发送时」与「历史重载时」两条路径都过这个函数。
    if (isSystemAuthoredText(text)) {
        renderSystemNote(text);
        return null;
    }
    const row = document.createElement('div');
    row.className = 'msg-row user-row';
    row.dataset.task = text || '';   // 顶部任务条读这里，避免从 DOM 反推被图片/附件污染
    const injectBadge = isInject ? `<span style="font-size:10px;background:var(--accent);color:#fff;padding:1px 6px;border-radius:8px;margin-right:6px;vertical-align:middle">补充</span>` : '';
    row.innerHTML = `
        <div class="msg-inner">
            <div class="user-bubble">
                ${injectBadge}${text ? esc(text).replace(/\n/g, '<br>') : ''}
            </div>
            <div class="user-avatar" aria-label="用户">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 12a4.2 4.2 0 1 0 0-8.4A4.2 4.2 0 0 0 12 12Zm0 2.1c-4.3 0-7.8 2.2-7.8 4.9v1h15.6v-1c0-2.7-3.5-4.9-7.8-4.9Z"/></svg>
            </div>
        </div>`;
    const bubble = row.querySelector('.user-bubble');
    const mediaItems = Array.isArray(attachments) ? attachments.filter(Boolean) : [];
    if (!mediaItems.length && imgSrc) mediaItems.push({ src: imgSrc, name: '上传图片' });
    if (mediaItems.length || fileNames.length) {
        const grid = document.createElement('div');
        grid.className = 'attachment-preview-grid';
        mediaItems.forEach(item => grid.appendChild(createAttachmentPreview(item)));
        fileNames.forEach(name => grid.appendChild(createAttachmentPreview({ name })));
        bubble.insertBefore(grid, bubble.firstChild);
    }
    if (!Array.isArray(attachments) && imgSrc && imgCount > 1) {
        const count = document.createElement('div');
        count.className = 'attachment-count';
        count.textContent = '另附 ' + (imgCount - 1) + ' 张图片';
        bubble.insertBefore(count, bubble.firstChild);
    }
    $msg.appendChild(row);
    bindLightbox(row);
    hydrateProtectedMedia(row);
    return row;
}

/**
 * 系统控制语提示条：居中灰字，不占用户气泡、不设 dataset.task。
 *
 * 形态刻意弱于正文：它不是对话内容，只是「上一步被放行了」这件事的告知。
 */
function renderSystemNote(text) {
    const row = document.createElement('div');
    row.className = 'msg-row sys-row';
    row.innerHTML = `<div class="sys-note">${esc(text || '')}</div>`;
    $msg.appendChild(row);
}

function renderBotMsg(initHtml, streaming = true) {
    const row = document.createElement('div');
    row.className = 'msg-row bot-row';
    const bid = nextBotBodyId();
    const initialContent = streaming ? initHtml
        : `<div class="answer-stream md-body has-content">${initHtml}</div>`;
    row.innerHTML = `
        <div class="msg-inner">
            <div class="bot-avatar" aria-label="MiniAgent"><svg viewBox="0 0 48 48" aria-hidden="true"><use href="#ma-ai-core"/></svg></div>
            <div class="bot-content">
                <div class="bot-identity"><span>MiniAgent</span><span class="bot-identity-label">AI 助手</span></div>
                <div class="bot-body ${streaming ? 'streaming' : ''}" id="${bid}">${initialContent}</div>
                <div class="bot-actions">
                    <button class="ba-btn" onclick="copyBotMsg(this)" title="复制">
                        <svg fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                            <rect x="9" y="9" width="13" height="13" rx="2"/>
                            <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
                        </svg>
                        复制
                    </button>
                </div>
            </div>
        </div>`;
    $msg.appendChild(row);
    scrollToBottom();
    return document.getElementById(bid);
}

function bindLightbox(row) {
    row?.querySelectorAll('img').forEach(img => {
        img.style.cursor = 'zoom-in';
        img.onclick = () => openLightbox(img.src);
    });
}

/** Open answer links through the desktop bridge, without popup windows or leaving the chat. */
function handleChatExternalLink(event) {
    const link = event.target?.closest?.('a[href]');
    if (!link || !link.closest?.('.bot-body') || link.hasAttribute('data-auth-download')
            || link.hasAttribute('download') || event.defaultPrevented) return false;
    const href = link.getAttribute('href') || '';
    let parsed;
    try { parsed = new URL(href, window.location.href); } catch (_) { return false; }
    if (!['http:', 'https:'].includes(parsed.protocol) || parsed.origin === window.location.origin) return false;
    if (parsed.username || parsed.password) {
        event.preventDefault(); showChatLinkError(link, '链接包含账号密码，请使用不含凭据的网页地址。'); return true;
    }
    link.target = '_blank'; link.rel = 'noopener noreferrer';
    const bridge = window.electronAPI;
    if (!bridge || typeof bridge.openExternal !== 'function') return false;
    event.preventDefault();
    if (link.getAttribute('data-external-opening') === 'true') return true;
    link.setAttribute('data-external-opening', 'true');
    Promise.resolve().then(() => bridge.openExternal(parsed.href)).then(result => {
        if (!result?.ok) throw new Error(result?.error || '无法打开系统浏览器，请稍后重试。');
        const notice = link.nextElementSibling;
        if (notice?.classList?.contains('chat-link-error')) notice.remove();
    }).catch(error => showChatLinkError(link, error?.message || '无法打开系统浏览器，请稍后重试。'))
        .finally(() => link.removeAttribute('data-external-opening'));
    return true;
}

function showChatLinkError(link, message) {
    let notice = link.nextElementSibling;
    if (!notice?.classList?.contains('chat-link-error')) {
        notice = document.createElement('span'); notice.className = 'chat-link-error';
        notice.setAttribute('role', 'alert'); link.after(notice);
    }
    notice.textContent = message;
}

document.addEventListener('click', async event => {
    if (handleChatExternalLink(event)) return;
    const link = event.target.closest('a[data-auth-download], a.workspace-download-link[aria-disabled="true"]');
    if (!link) return;
    event.preventDefault();
    const url = link.getAttribute('data-auth-download');
    if (!url || link.getAttribute('aria-busy') === 'true') return;
    const name = link.getAttribute('data-download-name') || 'download';
    const token = window.MiniAuth?.token();
    if (!token) {
        window.location.replace('/login');
        return;
    }
    link.setAttribute('aria-busy', 'true');
    try {
        const response = await fetch(url, {
            headers: { Authorization: 'Bearer ' + token },
            credentials: 'same-origin'
        });
        if (!response.ok) {
            const error = new Error(fileDownloadErrorMessage(response.status));
            error.status = response.status;
            throw error;
        }
        const objectUrl = URL.createObjectURL(await response.blob());
        const anchor = document.createElement('a');
        anchor.href = objectUrl;
        anchor.download = name;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        setTimeout(() => URL.revokeObjectURL(objectUrl), 60000);
    } catch (error) {
        console.warn('工作区文件下载失败:', url, error);
        const message = error.status ? error.message : '下载连接失败，请检查网络后重试。';
        const hint = link.querySelector('small');
        if (hint) {
            hint.textContent = message;
            hint.setAttribute('role', 'alert');
        } else {
            const notice = document.createElement('span');
            notice.className = 'file-download-error';
            notice.textContent = message;
            notice.setAttribute('role', 'alert');
            link.after(notice);
        }
        link.title = message;
        if ([400, 403, 404].includes(error.status)) {
            link.removeAttribute('data-auth-download');
            link.removeAttribute('href');
            link.setAttribute('aria-disabled', 'true');
        }
    } finally {
        link.removeAttribute('aria-busy');
    }
});

function fileDownloadErrorMessage(status) {
    if (status === 401) return '登录已失效，请重新登录后下载。';
    if (status === 403) return '当前账号无权下载这个文件。';
    if (status === 404) return '当前会话工作区中没有这个文件；回复中的文件引用未提供下载。';
    if (status === 400) return '文件引用无效，请使用具体文件路径，不能包含通配符。';
    if (status === 429) return '下载请求过于频繁，请稍后重试。';
    return '服务暂时无法读取文件，请稍后重试（HTTP ' + status + '）。';
}

function copyBotMsg(btn) {
    const bd = btn.closest('.bot-content').querySelector('.bot-body');
    navigator.clipboard.writeText(bd.innerText).then(() => {
        btn.innerHTML = `<svg fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24" width="12" height="12"><path d="M20 6L9 17l-5-5"/></svg> 已复制`;
        setTimeout(() => {
            btn.innerHTML = `<svg fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24" width="12" height="12"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg> 复制`;
        }, 2000);
    });
}

function renderStreaming(t) { return esc(t).replace(/\n/g, '<br>'); }

/** 流式阶段也走 MD：未闭合 ``` 时临时补闭合，避免只显示纯文本 */
function renderMDStreaming(t) {
    if (t == null || t === '') return '';
    let s = String(t);
    const fences = s.match(/```/g);
    if (fences && fences.length % 2 === 1) s += '\n```';
    return renderMD(s);
}

/**
 * 去掉片段开头的空行。
 *
 * <p>切点把空行留在了 tail / 增量块的开头，而 `renderMD` 会把 `\n\n` 变成 `</p><p>`。
 * 单独渲染这一块时，开头那个 `</p>` 前面没有任何 `<p>` 可闭合 —— 按 HTML 的解析规则，
 * 游离的 `</p>` 会**凭空造出一个空 `<p></p>`**，段落之间就多出 10px 的空隙。
 * 块级片段本来就不需要行首空行，渲染前丢掉。
 */
function dropLeadBlanks(s) {
    return String(s == null ? '' : s).replace(/^(?:[ \t]*\r?\n)+/, '');
}

/**
 * 把流式正文切成「已定稿块」与「末尾还在长的块」。
 *
 * <p><b>为什么必须切</b>：`renderMD` 是整段重算的。流式期间每 50ms 拿全文重算一次，
 * 就等于每 50ms 把整棵子树推倒重建。回答里一旦有 `<img>`，旧节点被销毁、新节点在
 * 图片解码完成前高度是 0，解码后又撑开 —— 正文随之上下来回跳。
 * 切成两段之后，只有末尾那一段会重画，前面已经画好的图再也不动。
 *
 * <p><b>切点规则</b>：
 * <ul>
 *   <li>切在**最后一个空行**上，且空行归入 tail（tail 以 `\n\n` 开头，渲染前由
 *       {@link dropLeadBlanks} 丢掉，各段落各自成块，段落间距与整段渲染时一致）。</li>
 *   <li>切点不能落在代码围栏内部，否则半个围栏被当正文、另一半被当代码。</li>
 *   <li>末尾还有未闭合的 ``` 时，围栏之后的内容一律留在 tail（围栏没闭合时它的渲染结果
 *       本身就不稳定，不能定稿）。</li>
 * </ul>
 *
 * <p><b>不变量</b>：文本只会往后长，所以新的 `frozen` 一定是旧 `frozen` 的**前缀延长**。
 * `paintAnswerNow` 的「只追加增量」依赖这一条；一旦不成立就只能整段重画。
 * 切点是「最后一个空行的位置」，文本变长它只会往后移或不动，因此前缀性天然成立。
 */
function splitStreamingMarkdown(text) {
    const s = text == null ? '' : String(text);
    if (!s) return { frozen: '', tail: '' };

    // 1) 标出所有代码围栏区间；末尾未闭合的那个一路延伸到文末
    const fences = [];
    let at = 0;
    for (;;) {
        const open = s.indexOf('```', at);
        if (open === -1) break;
        const close = s.indexOf('```', open + 3);
        if (close === -1) { fences.push([open, s.length]); break; }
        fences.push([open, close + 3]);
        at = close + 3;
    }
    const insideFence = pos => fences.some(f => pos > f[0] && pos < f[1]);

    // 2) 从后往前找第一个不落在围栏里的空行
    const hits = [];
    const blank = /\n[ \t]*\n/g;
    let m;
    while ((m = blank.exec(s)) !== null) hits.push(m.index);
    let cut = -1;
    for (let i = hits.length - 1; i >= 0; i--) {
        if (!insideFence(hits[i])) { cut = hits[i]; break; }
    }
    if (cut <= 0) return { frozen: '', tail: s };
    return { frozen: s.slice(0, cut), tail: s.slice(cut) };
}

