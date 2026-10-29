/* =========================================================================
   SIDEBAR RENDER
   ========================================================================= */

const sidebarRunningSessions = new Set();
let sidebarRefreshTimer = null;
let sidebarRenderEpoch = 0;

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
    sidebarRefreshTimer = setInterval(refreshSidebarRunningSessions, 5000);
}

async function renderSidebar() {
    const renderEpoch = ++sidebarRenderEpoch;
    const el = document.getElementById('sbHistory');
    el.innerHTML = '';
    let list = [];
    try {
        const resp = await fetch('/api/conversations');
        if (!resp.ok) { el.innerHTML = '<div style="padding:10px 8px;font-size:12px;color:var(--text3)">\u6682\u65e0\u5386\u53f2\u5bf9\u8bdd</div>'; return; }
        list = await apiJson(resp);
        if (!Array.isArray(list)) list = [];
    } catch (e) {
        console.warn('Failed to load conversations:', e);
        el.innerHTML = '<div style="padding:10px 8px;font-size:12px;color:var(--text3)">\u52a0\u8f7d\u5931\u8d25</div>';
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
            row.innerHTML = `<span class="sb-item-title">${esc(c.title || '新对话')}</span>
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
    <div class="welcome-logo">🤖</div>
    <h1>有什么可以帮你的？</h1>
    <div class="suggestion-grid">
        <div class="sug-card" onclick="fillInput('帮我搜索今日 AI 技术最新动态')">
            <div class="s-title">🔍 搜索新闻</div>
            <div class="s-sub">获取最新 AI 技术资讯</div>
        </div>
        <div class="sug-card" onclick="fillInput('帮我生成一张赛博朋克风格的城市夜景图片')">
            <div class="s-title">🎨 生成图片</div>
            <div class="s-sub">描述画面，AI 一键生成</div>
        </div>
        <div class="sug-card" onclick="fillInput('帮我设计一个高并发秒杀系统的架构方案')">
            <div class="s-title">📐 架构设计</div>
            <div class="s-sub">系统设计与技术方案</div>
        </div>
        <div class="sug-card" onclick="fillInput('用 Python 写一个快速排序算法并分析复杂度')">
            <div class="s-title">💻 代码生成</div>
            <div class="s-sub">写代码、调试、解释代码</div>
        </div>
    </div>
</div>`;

