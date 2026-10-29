/* =========================================================================
   MESSAGE DOM BUILDERS
   ========================================================================= */
function renderUserMsg(text, imgSrc, imgCount = imgSrc ? 1 : 0, fileNames = [], isInject = false) {
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
    const moreCount = imgCount > 1 ? `<div style="font-size:11px;color:var(--text2);margin-top:4px">附 ${imgCount} 张图片</div>` : '';
    const fileList = fileNames.length > 0 ? `<div style="font-size:11px;color:var(--text2);margin-top:4px">附文件: ${fileNames.map(esc).join(', ')}</div>` : '';
    const injectBadge = isInject ? `<span style="font-size:10px;background:var(--accent);color:#fff;padding:1px 6px;border-radius:8px;margin-right:6px;vertical-align:middle">补充</span>` : '';
    row.innerHTML = `
        <div class="msg-inner">
            <div class="user-bubble">
                ${imgSrc ? `<img src="${escA(imgSrc)}" alt="上传图片">${moreCount}` : ''}
                ${fileList}
                ${injectBadge}${text ? esc(text).replace(/\n/g, '<br>') : ''}
            </div>
        </div>`;
    $msg.appendChild(row);
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
    const bid = 'bb_' + Date.now();
    row.innerHTML = `
        <div class="msg-inner">
            <div class="bot-avatar">🤖</div>
            <div class="bot-content">
                <div class="bot-body ${streaming ? 'streaming' : ''}" id="${bid}">${initHtml}</div>
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

document.addEventListener('click', async event => {
    const link = event.target.closest('a[data-auth-download]');
    if (!link) return;
    event.preventDefault();
    const url = link.getAttribute('data-auth-download');
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
        if (!response.ok) throw new Error('HTTP ' + response.status);
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
        window.alert('文件下载失败，请确认登录状态后重试。');
    } finally {
        link.removeAttribute('aria-busy');
    }
});

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

