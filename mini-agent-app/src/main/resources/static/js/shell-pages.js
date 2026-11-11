/* =========================================================================
   shell-pages.js — /memory、/knowledge、/apps 三页共用的数据加载与渲染

   设计原则（与 chat.html 一致）：
     · 每个数字都来自接口，取不到就显式写「暂无 / 加载失败」，不编占位值；
     · 页面只做展示，不在此处硬编码工具名、模型名、记忆条数；
     · 数据接口统一返回 ApiResponse，{success, code, message, data}，
       失败时 message 直接展示给用户，避免「一片空白不知道为什么」。

   页面靠 <body data-page="memory|knowledge|apps"> 选择自己的渲染函数。
   ========================================================================= */

const PAGE_LOAD_ERROR = '加载失败';

/** 取 JSON 并解包 ApiResponse；HTTP 非 2xx 或 success=false 都抛错。 */
async function pageGet(path) {
    const resp = await fetch(path);
    let body = null;
    try { body = await resp.json(); } catch (_) { /* 非 JSON（网关错误页等） */ }
    if (!resp.ok) {
        throw new Error('HTTP ' + resp.status);
    }
    if (body && typeof body === 'object'
            && Object.prototype.hasOwnProperty.call(body, 'success')) {
        if (body.success === false) {
            throw new Error(body.message || body.code || '请求被拒绝');
        }
        return body.data;
    }
    return body;
}

function pe(s) {
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function num(v) {
    return v == null || v === '' ? '—' : String(v);
}

function setText(id, text) {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
}

function setHtml(id, html) {
    const el = document.getElementById(id);
    if (el) el.innerHTML = html;
}

/** 统计块：传 [{key, value}]，按顺序渲染。 */
function renderStats(hostId, items) {
    const host = document.getElementById(hostId);
    if (!host) return;
    host.innerHTML = items.map(it => `<div class="stat">
        <div class="stat-num">${pe(num(it.value))}</div>
        <div class="stat-key">${pe(it.key)}</div>
    </div>`).join('');
}

function emptyHtml(text) {
    return `<div class="empty">${pe(text)}</div>`;
}

/* =======================================================================
   记忆管理（/memory）
   -----------------------------------------------------------------------
   数据源：GET /api/shell/memory-insights
     · 不带 sessionId → 当前租户/用户的总量（记忆条目、事实、流程）
     · 带 sessionId  → 这个会话沉淀的 episode（情节）
   会话列表来自 GET /api/conversations，供下拉切换。
   ======================================================================= */
async function renderMemoryPage() {
    const picker = document.getElementById('memSession');
    const limit = 100;

    let total = null;
    try {
        total = await pageGet('/api/shell/memory-insights?limit=' + limit);
    } catch (e) {
        setHtml('memStats', emptyHtml(PAGE_LOAD_ERROR + '：' + e.message));
    }
    const stats = (total && total.stats) || {};
    renderStats('memStats', [
        { key: '记忆条目（ACTIVE）', value: stats.memoryCount },
        { key: '语义事实', value: stats.factCount },
        { key: '流程', value: stats.procedureCount }
    ]);

    // 标签云：来自真实 fact subject / procedure name / episode outcome
    const tags = (total && Array.isArray(total.tags)) ? total.tags : [];
    setHtml('memTags', tags.length
        ? tags.slice(0, 40).map(t => `<span class="tag-chip">${pe(t)}</span>`).join('')
        : emptyHtml('还没有可归纳的标签 —— 记忆沉淀出事实或流程后这里会自动出现。'));

    // 会话下拉 + 该会话的情节
    let convs = [];
    try {
        convs = await pageGet('/api/conversations') || [];
    } catch (_) { convs = []; }
    if (picker) {
        const opts = ['<option value="">— 不选（只看总量）—</option>'].concat(
            (Array.isArray(convs) ? convs : []).map(c =>
                `<option value="${pe(c.sessionId)}">${pe(c.title || '新对话')}</option>`));
        picker.innerHTML = opts.join('');
        picker.onchange = () => loadMemoryEpisodes(picker.value);
    }
    await loadMemoryEpisodes('');
}

async function loadMemoryEpisodes(sid) {
    const box = document.getElementById('memEpisodes');
    if (!box) return;
    if (!sid) {
        box.innerHTML = emptyHtml('选择一个会话，查看它在执行过程中沉淀下来的情节。');
        return;
    }
    try {
        const data = await pageGet('/api/shell/memory-insights?sessionId='
            + encodeURIComponent(sid) + '&limit=200');
        const related = (data && Array.isArray(data.related)) ? data.related : [];
        const eps = related.filter(r => r && r.type === 'episode');
        if (!eps.length) {
            box.innerHTML = emptyHtml('这个会话还没有沉淀情节记录。');
            return;
        }
        box.innerHTML = eps.map(e => `<div class="row">
            <span class="row-main">${pe(e.summary || '（无摘要）')}</span>
            <span class="row-meta">${pe(e.outcome || '')}</span>
        </div>`).join('');
    } catch (e) {
        box.innerHTML = emptyHtml(PAGE_LOAD_ERROR + '：' + e.message);
    }
}

/* =======================================================================
   知识库（/knowledge）
   -----------------------------------------------------------------------
   数据源同 /api/shell/memory-insights，但只取「长期知识」两类：
     · fact      subject / predicate / object
     · procedure name / version
   情节是会话内的一次性记录，不属于知识，这里不列。
   ======================================================================= */
async function renderKnowledgePage() {
    let data = null;
    try {
        data = await pageGet('/api/shell/memory-insights?limit=300');
    } catch (e) {
        setHtml('knStats', emptyHtml(PAGE_LOAD_ERROR + '：' + e.message));
        setHtml('knFacts', emptyHtml(PAGE_LOAD_ERROR));
        setHtml('knProcedures', emptyHtml(PAGE_LOAD_ERROR));
        return;
    }
    const stats = (data && data.stats) || {};
    renderStats('knStats', [
        { key: '语义事实', value: stats.factCount },
        { key: '流程', value: stats.procedureCount },
        { key: '本页已载入', value: stats.relatedCount }
    ]);

    const related = (data && Array.isArray(data.related)) ? data.related : [];
    const facts = related.filter(r => r && r.type === 'fact');
    const procs = related.filter(r => r && r.type === 'procedure');

    setHtml('knFacts', facts.length ? facts.map(f => {
        const subject = String(f.subject || '').trim();
        const object = String(f.object || '').trim();
        const predicate = String(f.predicate || '').trim();
        const duplicate = subject && object && subject.localeCompare(object, 'zh-CN', { sensitivity: 'base' }) === 0;
        const content = duplicate ? subject : subject
            + (predicate ? ' · ' + predicate : '')
            + (object ? ' → ' + object : '');
        const kind = /preference|偏好/i.test(predicate) ? '偏好' : /learned|learned_from|经验/i.test(predicate) ? '经验' : (predicate || '事实');
        return `<div class="row row--fact">
            <span class="knowledge-row-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 4a3 3 0 0 0-3 3v1a3 3 0 0 0 0 6v1a3 3 0 0 0 6 0v-1a3 3 0 0 0 0-6V7a3 3 0 0 0-3-3z"/><path d="M12 4v16"/></svg></span>
            <span class="row-main"><span class="row-tag">${pe(kind)}</span><span class="knowledge-row-content">${pe(content || predicate)}</span></span>
        </div>`;
    }).join('') : emptyHtml('还没有沉淀语义事实。'));

    setHtml('knProcedures', procs.length ? procs.map(p => `<div class="row">
        <span class="row-main"><span class="row-tag">流程</span>${pe(p.name)}</span>
        <span class="row-meta">v${pe(num(p.version))}</span>
    </div>`).join('') : emptyHtml('还没有沉淀可复用的流程。'));

    setText('knFactCount', '共 ' + num(stats.factCount) + ' 条，本页载入 ' + facts.length);
    setText('knProcCount', '共 ' + num(stats.procedureCount) + ' 条，本页载入 ' + procs.length);
}

/* =======================================================================
   应用市场（/apps）
   -----------------------------------------------------------------------
   数据源：
     GET /api/shell/tools  → 内置工具（含描述）+ MCP 已注册工具
     GET /api/mcp          → MCP 开关状态与服务数
   工具名与描述都由后端注册表给出，前端不维护第二份名单。
   ======================================================================= */
async function renderAppsPage() {
    let tools = null;
    try {
        tools = await pageGet('/api/shell/tools');
    } catch (e) {
        setHtml('appTools', emptyHtml(PAGE_LOAD_ERROR + '：' + e.message));
    }
    let mcp = null;
    try {
        mcp = await pageGet('/api/mcp');
    } catch (_) { mcp = null; }

    const builtin = (tools && Array.isArray(tools.builtin)) ? tools.builtin : [];
    const mcpTools = (tools && Array.isArray(tools.mcp)) ? tools.mcp : [];

    renderStats('appStats', [
        { key: '内置工具', value: tools ? tools.builtinCount : null },
        { key: 'MCP 工具', value: tools ? tools.mcpCount : null },
        { key: 'MCP 服务', value: mcp ? mcp.serverCount : null },
        { key: 'MCP 状态', value: mcp ? (mcp.enabled ? '已启用' : '未启用') : null }
    ]);

    const card = (name, desc, kind) => `<div class="tool-card">
        <div class="tool-card-name">${pe(name)}</div>
        ${desc ? `<div class="tool-card-desc">${pe(desc)}</div>` : ''}
        <div class="tool-card-kind">${pe(kind)}</div>
    </div>`;

    setHtml('appTools', builtin.length
        ? `<div class="tool-grid">${builtin.map(t =>
            card(t.name, t.description, '内置工具')).join('')}</div>`
        : emptyHtml('没有取到内置工具清单。'));

    setHtml('appMcp', mcpTools.length
        ? `<div class="tool-grid">${mcpTools.map(t =>
            card(t.name, t.description, 'MCP 工具')).join('')}</div>`
        : emptyHtml(mcp && mcp.enabled === false
            ? 'MCP 未启用：在配置里打开 agent.mcp.enabled 后，这里会列出已注册的 MCP 工具。'
            : '还没有注册任何 MCP 工具。'));

    const refreshBtn = document.getElementById('mcpRefresh');
    if (refreshBtn) {
        refreshBtn.onclick = async () => {
            refreshBtn.disabled = true;
            const old = refreshBtn.textContent;
            refreshBtn.textContent = '刷新中…';
            try {
                await fetch('/api/mcp/servers', { method: 'POST' });
                await renderAppsPage();
            } catch (e) {
                setHtml('appMcp', emptyHtml('刷新失败：' + e.message));
            } finally {
                refreshBtn.disabled = false;
                refreshBtn.textContent = old;
            }
        };
    }

    // 关键词过滤：只过滤已经渲染出来的卡片，不发请求
    const search = document.getElementById('appSearch');
    if (search) {
        search.oninput = () => {
            const q = search.value.trim().toLowerCase();
            document.querySelectorAll('.tool-card').forEach(el => {
                const text = el.textContent.toLowerCase();
                el.style.display = !q || text.includes(q) ? '' : 'none';
            });
        };
    }
}

/* ------------------------------------------------------------------ 启动 */

(function initShellPage() {
    const run = () => {
        const page = document.body.getAttribute('data-page');
        const job = page === 'memory' ? renderMemoryPage
            : page === 'knowledge' ? renderKnowledgePage
                : page === 'apps' ? renderAppsPage : null;
        if (!job) return;
        Promise.resolve().then(job).catch(e => {
            console.warn('[shell-page] failed:', e);
        });
    };
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', run, { once: true });
    } else {
        run();
    }
})();
