/* =========================================================================
   SIDEBAR RENDER
   ========================================================================= */

const sidebarRunningSessions = new Set();
let sidebarRefreshTimer = null;
let sidebarRenderEpoch = 0;
/** 历史对话搜索框的当前关键词（小写）。renderSidebar() 重建 DOM 后要据此重新过滤。 */
let convSearchQuery = '';

/**
 * 搜索框 oninput 入口。只做前端过滤，不发请求 —— 会话列表本来就已经全量取回来了，
 * 再打一个后端搜索接口属于重复往返。
 */
function filterConversations(value) {
    convSearchQuery = String(value == null ? '' : value).trim().toLowerCase();
    applyConvFilter();
}

/** Keep the persistent conversation header tied to the selected session and live execution state. */
function syncConversationHeader() {
    const titleEl = document.getElementById('conversationTitle');
    const statusEl = document.getElementById('conversationStatus');
    if (!titleEl || !statusEl) return;
    const activeRow = currentId
        ? [...document.querySelectorAll('.sb-item[data-session-id]')]
            .find(row => row.dataset.sessionId === currentId)
        : null;
    const rowTitle = activeRow?.querySelector('.sb-item-title')?.textContent?.trim();
    titleEl.textContent = rowTitle || (currentId ? '当前对话' : '新对话');
    const running = !!(currentId && sidebarRunningSessions.has(currentId)) || !!isStreaming;
    statusEl.classList.toggle('is-running', running);
    statusEl.lastElementChild.textContent = running
        ? '正在执行'
        : currentId ? '会话已就绪' : '准备就绪';
}

function conversationIconMarkup(title) {
    const value = String(title || '').toLowerCase();
    let path = '<path d="M21 11.5a8.5 8.5 0 0 1-8.5 8.5H8l-5 3 1.2-4.2A8.5 8.5 0 1 1 21 11.5z"/><path d="M8 11h8M8 15h5"/>';
    if (/图片|照片|相册/.test(value)) path = '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="8.5" cy="9" r="1.5"/><path d="m21 15-5-5L5 20"/>';
    else if (/文件|桌面|工作区|整理/.test(value)) path = '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>';
    else if (/新闻|搜索|查询/.test(value)) path = '<circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 4.5 4.5"/>';
    else if (/代码|项目|架构|开发/.test(value)) path = '<path d="m8 8-4 4 4 4M16 8l4 4-4 4M14 5l-4 14"/>';
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + path + '</svg>';
}

/** 按 convSearchQuery 显隐 .sb-item，并把整组都无命中的日期分组标题一起藏掉。 */
function applyConvFilter() {
    const el = document.getElementById('sbHistory');
    if (!el) return;
    const rows = [...el.querySelectorAll('.sb-item')];
    let visible = 0;
    rows.forEach(row => {
        const title = (row.title || row.textContent || '').toLowerCase();
        const hit = !convSearchQuery || title.includes(convSearchQuery);
        row.hidden = !hit;
        if (hit) visible++;
    });
    [...el.querySelectorAll('.sb-date-label')].forEach(label => {
        let node = label.nextElementSibling;
        let count = 0;
        while (node && !node.classList.contains('sb-date-label')) {
            if (node.classList.contains('sb-item') && !node.hidden) count++;
            node = node.nextElementSibling;
        }
        label.hidden = count === 0;
    });
    let hint = document.getElementById('sbNoMatch');
    if (convSearchQuery && visible === 0 && rows.length) {
        if (!hint) {
            hint = document.createElement('div');
            hint.id = 'sbNoMatch';
            hint.className = 'sb-no-match';
            el.appendChild(hint);
        }
        hint.textContent = '没有匹配「' + convSearchQuery + '」的对话';
    } else if (hint) {
        hint.remove();
    }
}

async function refreshSidebarRunningSessions() {
    const rows = [...document.querySelectorAll('.sb-item[data-session-id]')];
    const states = await Promise.all(rows.map(async row => {
        const sid = row.dataset.sessionId;
        try {
            const resp = await fetch('/api/conversations/' + encodeURIComponent(sid) + '/task');
            if (!resp.ok) return null;
            const status = await apiJson(resp) || {};
            return [sid, status.running === true];
        } catch (_) { return null; }
    }));
    states.forEach(state => {
        if (!state) return;
        if (state[1]) sidebarRunningSessions.add(state[0]);
        else sidebarRunningSessions.delete(state[0]);
    });
    rows.forEach(row => updateSidebarRunningIndicator(row));
    syncConversationHeader();
}

function updateSidebarRunningIndicator(row) {
    const running = sidebarRunningSessions.has(row.dataset.sessionId);
    row.classList.toggle('is-running', running);
    let indicator = row.querySelector('.sb-running-indicator');
    if (running && !indicator) {
        indicator = document.createElement('span');
        indicator.className = 'sb-running-indicator';
        indicator.setAttribute('role', 'img');
        indicator.setAttribute('aria-label', '任务运行中');
        indicator.title = '任务运行中';
        indicator.innerHTML = '<svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="6"></circle></svg>';
        row.insertBefore(indicator, row.querySelector('.sb-item-actions'));
    } else if (!running && indicator) indicator.remove();
}

function startSidebarRunningRefresh() {
    if (sidebarRefreshTimer) return;
    // Keep the running indicator coarse so large histories do not create a burst
    // of status requests whenever the sidebar refreshes.
    sidebarRefreshTimer = setInterval(refreshSidebarRunningSessions, 15000);
}

async function renderSidebar() {
    const renderEpoch = ++sidebarRenderEpoch;
    const el = document.getElementById('sbHistory');
    el.innerHTML = '';
    let list = [];
    try {
        const resp = await fetch('/api/conversations');
        if (!resp.ok) { el.innerHTML = '<div style="padding:10px 8px;font-size:12px;color:var(--text3)">\u6682\u65e0\u5386\u53f2\u5bf9\u8bdd</div>'; syncConversationHeader(); return; }
        list = await apiJson(resp);
        if (!Array.isArray(list)) list = [];
    } catch (e) {
        console.warn('Failed to load conversations:', e);
        el.innerHTML = '<div style="padding:10px 8px;font-size:12px;color:var(--text3)">\u52a0\u8f7d\u5931\u8d25</div>';
        syncConversationHeader();
        return;
    }
    if (renderEpoch !== sidebarRenderEpoch) return;
    // Normalize updatedAt to numeric timestamps
    list.forEach(c => {
        if (typeof c.updatedAt === 'string') {
            const n = Number(c.updatedAt);
            c.updatedAt = isNaN(n) ? new Date(c.updatedAt).getTime() : n;
        } else if (!c.updatedAt) c.updatedAt = 0;
    });
    if (list.length === 0) {
        el.innerHTML = '<div style="padding:10px 8px;font-size:12px;color:var(--text3)">暂无历史对话</div>';
        syncConversationHeader();
        applyConvFilter();
        if (renderEpoch === sidebarRenderEpoch) startSidebarRunningRefresh();
        return;
    }
    const ts = Date.now();
    const D0 = new Date(new Date().toDateString()).getTime();
    const D1 = D0 - 86400000;
    const D7 = D0 - 7 * 86400000;
    const groups = [
        { k: '今天',     f: c => c.updatedAt >= D0 },
        { k: '昨天',     f: c => c.updatedAt >= D1 && c.updatedAt < D0 },
        { k: '最近 7 天', f: c => c.updatedAt >= D7 && c.updatedAt < D1 },
        { k: '更早',     f: c => c.updatedAt < D7 },
    ];
    groups.forEach(g => {
        const items = list.filter(g.f);
        if (!items.length) return;
        const lbl = document.createElement('div');
        lbl.className = 'sb-date-label';
        lbl.textContent = g.k;
        el.appendChild(lbl);
        items.forEach(c => {
            const row = document.createElement('div');
            row.className = 'sb-item' + (c.sessionId === currentId ? ' active' : '');
            row.dataset.sessionId = c.sessionId;
            row.title = c.title || '新对话';
            row.innerHTML = `<span class="sb-item-glyph" aria-hidden="true">${conversationIconMarkup(c.title)}</span>
                <span class="sb-item-title">${esc(c.title || '新对话')}</span>
                <div class="sb-item-actions">
                    <button class="sb-act-btn danger" title="删除"
                            onclick="event.stopPropagation();delConvAndRefresh('${c.sessionId}')">🗑</button>
                </div>`;
            row.onclick = () => switchConv(c.sessionId);
            row.oncontextmenu = e => { e.preventDefault(); showCtxMenu(e, c.sessionId); };
            el.appendChild(row);
            updateSidebarRunningIndicator(row);
        });
    });
    // 重建 DOM 会丢掉搜索过滤，这里按当前关键词重放一次
    applyConvFilter();
    syncConversationHeader();
    if (renderEpoch === sidebarRenderEpoch) {
        refreshSidebarRunningSessions();
        startSidebarRunningRefresh();
    }
}

/* =========================================================================
   CONTEXT MENU
   ========================================================================= */
function removeCtxMenu() { document.getElementById('ctxMenu')?.remove(); }

function showCtxMenu(e, id) {
    removeCtxMenu();
    const m = document.createElement('div');
    m.id = 'ctxMenu';
    m.className = 'ctx-menu';
    m.style.cssText = `top:${e.clientY}px;left:${e.clientX}px`;
    m.innerHTML = `<div class="ctx-item danger" onclick="delConvAndRefresh('${id}');removeCtxMenu()">🗑 删除对话</div>`;
    document.body.appendChild(m);
    setTimeout(() => document.addEventListener('click', removeCtxMenu, { once: true }), 0);
}

async function delConvAndRefresh(id) {
    try {
        await fetch('/api/conversations/' + encodeURIComponent(id), { method: 'DELETE' });
    } catch (e) {
        console.warn('Failed to delete conversation:', e);
    }
    if (id === currentId) startNewChat(false);
    else renderSidebar();
}

/* =========================================================================
   WELCOME SCREEN
   ========================================================================= */
const WELCOME = `
<div class="welcome" id="welcomeScreen">
    <div class="welcome-logo" aria-hidden="true">
        <svg viewBox="0 0 48 48" fill="none"><use href="#ma-ai-core"/></svg>
    </div>
    <h1>有什么可以帮你的？</h1>
    <div class="suggestion-grid">
        <div class="sug-card" onclick="fillInput('帮我搜索今日 AI 技术最新动态')">
            <div class="s-title"><span class="s-icon s-icon-search" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="10.8" cy="10.8" r="6.8"/><path d="m16 16 4.5 4.5"/></svg></span><span>搜索新闻</span></div>
            <div class="s-sub">获取最新 AI 技术资讯</div>
        </div>
        <div class="sug-card" onclick="fillInput('帮我生成一张赛博朋克风格的城市夜景图片')">
            <div class="s-title"><span class="s-icon s-icon-image" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3.5" width="18" height="17" rx="2.5"/><circle cx="8.5" cy="9" r="1.5"/><path d="m5 18 5-5 3.2 3.2 2.2-2.2L20 19"/></svg></span><span>生成图片</span></div>
            <div class="s-sub">描述画面，AI 一键生成</div>
        </div>
        <div class="sug-card" onclick="fillInput('帮我设计一个高并发秒杀系统的架构方案')">
            <div class="s-title"><span class="s-icon s-icon-architecture" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="3.5" width="6" height="6" rx="1.2"/><rect x="14.5" y="14.5" width="6" height="6" rx="1.2"/><path d="M9.5 6.5h4a2 2 0 0 1 2 2v6M6.5 9.5v4a2 2 0 0 0 2 2h6"/></svg></span><span>架构设计</span></div>
            <div class="s-sub">系统设计与技术方案</div>
        </div>
        <div class="sug-card" onclick="fillInput('用 Python 写一个快速排序算法并分析复杂度')">
            <div class="s-title"><span class="s-icon s-icon-code" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="m8.5 7-5 5 5 5M15.5 7l5 5-5 5M13.5 4l-3 16"/></svg></span><span>代码生成</span></div>
            <div class="s-sub">写代码、调试、解释代码</div>
        </div>
    </div>
</div>`;

