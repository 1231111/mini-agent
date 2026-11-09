/* =========================================================================
   INSPECTOR — 右侧「任务执行详情」栏

   四块内容全部取自真实接口，没有一处是写死的占位数字：

     执行步骤   GET /api/conversations/{sid}/executions   → 取最新一次 execution
                GET /api/executions/{executionId}/steps   → 该次执行的完整步骤
     执行工具   同一份 steps 按 toolName 聚合（次数 / 耗时 / 失败数）
     记忆与知识 GET /api/shell/memory-insights?sessionId=  → episode / fact / procedure
     系统状态   GET /api/shell/status                      → 模型 / 工具数 / 堆状态

   刷新时机有三处，都不做定时轮询（security.js 给全站 fetch 打了 request/response
   日志，轮询会把这些日志刷成噪音）：
     1. 打开检查器时刷一次；
     2. 切换 / 新建会话时刷一次（由 09-session.js 的两处钩子触发）；
     3. 订阅 /api/conversations/{sid}/traces 的 SSE 推送，收到 trace 事件后防抖刷新。

   展开状态存在 localStorage['mini_agent_inspector']：宽屏（≥1180px）默认展开，
   窄屏默认收起，其中 >1180px 时右侧栏是常驻列、≤1180px 时是浮层（见 shell.css）。
   ========================================================================= */

const INSP_LS_KEY = 'mini_agent_inspector';
const INSP_NARROW_PX = 1180;
const INSP_PUSH_DEBOUNCE_MS = 250;
const INSP_MAX_STEPS = 12;     // 步骤太多时只留最近 N 条，完整列表给 /trace
const INSP_MAX_TOOLS = 8;

/** stepType → 中文标签，来源是 /api/trace-nodes（AgentStepNode.catalog()）。 */
let inspLabels = {};
/** 哪些 stepType 属于「非核心」，视图里默认不占位置。 */
let inspCore = {};
let inspCatalogLoaded = false;

let inspStreamAbort = null;
let inspPushTimer = null;
let inspRefreshTimer = null;
let inspBusy = false;
let inspPending = false;
/** 上一次渲染的步骤快照，用于判断是否真有新内容（避免每帧重绘闪烁）。 */
let inspLastSignature = '';

const INSP_FAIL_STATUS = new Set([
    'FAILURE', 'FAILED', 'ERROR', 'TIMEOUT', 'INTERRUPTED', 'ABORTED', 'REJECTED'
]);
const INSP_RUN_STATUS = new Set(['RUNNING', 'PENDING', 'WAITING']);

/* ------------------------------------------------------------------ 开关 */

function inspectorIsOpen() {
    const el = document.getElementById('inspector');
    return !!el && !el.classList.contains('hidden');
}

/**
 * 打开 / 收起右侧栏。
 * @param {boolean} [force] 传值则按该值设置，不传为切换。
 */
function toggleInspector(force) {
    const el = document.getElementById('inspector');
    if (!el) return;
    const next = typeof force === 'boolean' ? force : el.classList.contains('hidden');
    el.classList.toggle('hidden', !next);
    try { localStorage.setItem(INSP_LS_KEY, next ? '1' : '0'); } catch (_) {}
    const btn = document.getElementById('inspectorToggle');
    if (btn) {
        btn.setAttribute('aria-expanded', next ? 'true' : 'false');
        btn.title = next ? '收起执行详情' : '展开执行详情';
    }
    if (next) {
        refreshInspector(true);
        syncInspectorStream();
    } else {
        closeInspectorStream();
    }
}

/**
 * 首屏按「用户上次选择 / 当前宽度」定初始状态。
 * 这里刻意不走 toggleInspector()：没有历史选择时只算默认值，不写 localStorage，
 * 否则「默认跟随窗口宽度」在第一次打开就被固化成一次性的显式选择。
 */
function applyInspectorInitialState() {
    const el = document.getElementById('inspector');
    if (!el) return;
    let saved = null;
    try { saved = localStorage.getItem(INSP_LS_KEY); } catch (_) {}
    const narrow = window.innerWidth <= INSP_NARROW_PX;
    const open = saved === '1' ? true : saved === '0' ? false : !narrow;
    el.classList.toggle('hidden', !open);
    const btn = document.getElementById('inspectorToggle');
    if (btn) {
        btn.setAttribute('aria-expanded', open ? 'true' : 'false');
        btn.title = open ? '收起执行详情' : '展开执行详情';
    }
    if (open) {
        refreshInspector(true);
        syncInspectorStream();
    }
}

/* --------------------------------------------------------- 数据加载与渲染 */

/** 打开着就刷；没打开时除 force 外直接返回，省掉无谓请求。 */
function refreshInspector(force) {
    if (!force && !inspectorIsOpen()) return;
    if (inspBusy) { inspPending = true; return; }
    inspBusy = true;
    Promise.resolve()
        .then(() => Promise.all([
            renderInspectorStatus(),
            renderInspectorMemory(currentId),
            renderInspectorSteps(currentId)
        ]))
        .catch(e => console.warn('[Inspector] refresh failed:', e))
        .finally(() => {
            inspBusy = false;
            if (inspPending) { inspPending = false; refreshInspector(true); }
        });
}

/** 会话切换 / 新建后由 09-session.js 调；异步收口，不阻塞切会话。 */
function scheduleInspectorRefresh() {
    if (inspRefreshTimer) clearTimeout(inspRefreshTimer);
    inspRefreshTimer = setTimeout(() => {
        inspRefreshTimer = null;
        syncInspectorStream();
        refreshInspector(true);
    }, 0);
    // 切会话时先清掉上一会话的步骤，避免串味
    inspLastSignature = '';
}

async function loadInspectorCatalog() {
    if (inspCatalogLoaded) return;
    try {
        const resp = await fetch('/api/trace-nodes');
        if (!resp.ok) return;
        const list = await apiJson(resp) || [];
        const labels = {}, core = {};
        list.forEach(n => {
            if (!n || !n.code) return;
            labels[n.code] = n.label || n.code;
            core[n.code] = n.core !== false;
        });
        inspLabels = labels;
        inspCore = core;
        inspCatalogLoaded = true;
    } catch (e) {
        console.warn('[Inspector] trace-node catalog unavailable:', e);
    }
}

/* -------- 执行步骤 -------- */

async function renderInspectorSteps(sid) {
    const $steps = document.getElementById('inspSteps');
    const $tools = document.getElementById('inspTools');
    if (!$steps || !$tools) return;

    if (!sid) {
        inspLastSignature = '';
        $steps.innerHTML = '<div class="insp-empty">当前会话还没有执行记录。发一条消息后这里会实时显示每一步。</div>';
        $tools.innerHTML = '<div class="insp-empty">本次任务尚未调用工具。</div>';
        setInspectorProgress(0, 0);
        setInspectorState('空闲', 'done');
        return;
    }

    await loadInspectorCatalog();

    let execs = [];
    try {
        const resp = await fetch('/api/conversations/' + encodeURIComponent(sid) + '/executions');
        if (resp.ok) execs = await apiJson(resp) || [];
    } catch (e) {
        console.warn('[Inspector] executions failed:', e);
    }
    if (!Array.isArray(execs) || !execs.length) {
        inspLastSignature = '';
        $steps.innerHTML = '<div class="insp-empty">这个会话还没有执行记录。</div>';
        $tools.innerHTML = '<div class="insp-empty">本次任务尚未调用工具。</div>';
        setInspectorProgress(0, 0);
        setInspectorState('空闲', 'done');
        return;
    }

    const latest = pickLatestExecution(execs);
    let steps = [];
    try {
        const resp = await fetch('/api/executions/' + encodeURIComponent(latest.executionId) + '/steps');
        if (resp.ok) steps = await apiJson(resp) || [];
    } catch (e) {
        console.warn('[Inspector] steps failed:', e);
    }
    if (!Array.isArray(steps)) steps = [];

    // 同一份 steps 只画一次：SSE 每来一帧都会调这里，签名没变就跳过重绘
    const signature = latest.executionId + '#' + steps.length + '#' +
        (steps.length ? steps[steps.length - 1].id : '') + '#' + (latest.status || '');
    if (signature !== inspLastSignature) {
        inspLastSignature = signature;
        $steps.innerHTML = buildInspectorStepRows(steps);
        $tools.innerHTML = buildInspectorToolRows(steps);
    }

    setInspectorProgress(latest.subGoalDone, latest.subGoalTotal, steps);
    setInspectorStateFromExecution(latest, steps);
}

function pickLatestExecution(execs) {
    // 后端按 session 的 (turnIndex, id) 顺序分组，正常最后一条就是最新；
    // 再用 startTime 兜一次，避免顺序被改动时取错。
    let latest = execs[execs.length - 1];
    const ts = e => {
        const t = e && e.startTime ? Date.parse(e.startTime) : NaN;
        return Number.isNaN(t) ? 0 : t;
    };
    execs.forEach(e => { if (ts(e) >= ts(latest)) latest = e; });
    return latest;
}

function buildInspectorStepRows(steps) {
    // 只保留核心步骤：AgentStepNode.core=false 的是状态/只读/调试节点，
    // 全画出来会把真正发生的动作挤没。
    const visible = steps.filter(s => {
        const code = s && s.stepType;
        return !code || inspCore[code] !== false;
    });
    if (!visible.length) {
        return '<div class="insp-empty">这次执行没有产生可见步骤，完整数据见轨迹页。</div>';
    }
    const tail = visible.slice(-INSP_MAX_STEPS);
    return tail.map(s => {
        const state = stepState(s.status);
        const label = inspLabels[s.stepType] || s.stepType || '步骤';
        const meta = [];
        if (s.toolName) meta.push(esc(s.toolName));
        if (s.durationMs > 0) meta.push(formatDuration(s.durationMs));
        if (s.status && INSP_FAIL_STATUS.has(s.status)) meta.push(esc(s.status));
        const detail = shortText(s.content || s.subGoalText, 60);
        return `<div class="insp-step ${state}">
            <span class="insp-step-icon"></span>
            <div class="insp-step-body">
                <div class="insp-step-title">${esc(label)}</div>
                ${meta.length ? `<div class="insp-step-meta">${meta.join(' · ')}</div>` : ''}
                ${detail ? `<div class="insp-step-meta">${esc(detail)}</div>` : ''}
            </div>
        </div>`;
    }).join('');
}

function buildInspectorToolRows(steps) {
    const agg = new Map();
    steps.forEach(s => {
        const name = s && s.toolName;
        if (!name) return;
        const cur = agg.get(name) || { name, count: 0, durationMs: 0, failed: 0 };
        // TOOL_CALL 与 TOOL_RESULT 会成对出现，只统计调用发起点，避免次数翻倍
        if (s.stepType === 'TOOL_CALL') cur.count += 1;
        cur.durationMs += Number(s.durationMs) || 0;
        if (INSP_FAIL_STATUS.has(s.status)) cur.failed += 1;
        agg.set(name, cur);
    });
    const rows = [...agg.values()].sort((a, b) => b.count - a.count).slice(0, INSP_MAX_TOOLS);
    if (!rows.length) return '<div class="insp-empty">本次任务尚未调用工具。</div>';
    return rows.map(t => {
        const state = t.failed > 0 ? 'failed' : 'done';
        return `<div class="insp-tool ${state}">
            <span class="insp-tool-icon"></span>
            <span class="insp-tool-name" title="${esc(t.name)}">${esc(t.name)}</span>
            <span class="insp-tool-state">${t.count ? t.count + ' 次' : '—'}${
                t.durationMs > 0 ? ' · ' + formatDuration(t.durationMs) : ''}${
                t.failed > 0 ? ' · 失败 ' + t.failed : ''}</span>
        </div>`;
    }).join('');
}

function stepState(status) {
    if (!status) return 'done';
    if (INSP_FAIL_STATUS.has(status)) return 'failed';
    if (INSP_RUN_STATUS.has(status)) return 'running';
    return 'done';
}

/** 进度条读 subGoalDone/subGoalTotal；两个都是 0 就整条隐藏，不画假进度。 */
function setInspectorProgress(done, total, steps) {
    const wrap = document.getElementById('inspProgress');
    const fill = document.getElementById('inspProgressFill');
    if (!wrap || !fill) return;
    let d = Number(done) || 0;
    let t = Number(total) || 0;
    if (!t && Array.isArray(steps) && steps.length) {
        // 步骤本身不带子目标计数时，退化成「已完成步骤 / 总步骤」
        t = steps.length;
        d = steps.filter(s => !INSP_RUN_STATUS.has(s.status || '')).length;
    }
    if (t <= 0) { wrap.hidden = true; fill.style.width = '0%'; return; }
    const pct = Math.max(0, Math.min(100, Math.round((d / t) * 100)));
    wrap.hidden = false;
    fill.style.width = pct + '%';
    wrap.title = d + ' / ' + t;
}

function setInspectorState(text, kind) {
    const dot = document.getElementById('inspStateDot');
    const label = document.getElementById('inspStateText');
    if (dot) {
        dot.classList.remove('running', 'done', 'failed');
        if (kind) dot.classList.add(kind);
    }
    if (label) label.textContent = text;
}

function setInspectorStateFromExecution(exec, steps) {
    const last = steps.length ? steps[steps.length - 1] : null;
    const running = (exec && exec.status && INSP_RUN_STATUS.has(exec.status))
        || (last && INSP_RUN_STATUS.has(last.status || ''));
    const failed = (exec && exec.status && INSP_FAIL_STATUS.has(exec.status))
        || (last && INSP_FAIL_STATUS.has(last.status || ''));
    if (running) setInspectorState('运行中', 'running');
    else if (failed) setInspectorState('异常结束', 'failed');
    else setInspectorState('已完成', 'done');
}

/* -------- 记忆与知识 -------- */

async function renderInspectorMemory(sid) {
    const $el = document.getElementById('inspMemory');
    if (!$el) return;
    if (!sid) {
        $el.innerHTML = '<div class="insp-empty">选择或新建一个会话后显示记忆命中情况。</div>';
        return;
    }
    try {
        const resp = await fetch('/api/shell/memory-insights?sessionId='
            + encodeURIComponent(sid));
        if (!resp.ok) {
            $el.innerHTML = '<div class="insp-empty">记忆接口不可用（HTTP ' + resp.status + '）。</div>';
            return;
        }
        const data = await apiJson(resp) || {};
        const stats = data.stats || {};
        const rows = [
            ['记忆条目', stats.memoryCount],
            ['语义事实', stats.factCount],
            ['流程', stats.procedureCount],
            ['本会话情节', stats.episodeCount],
            ['本次关联', stats.relatedCount]
        ];
        let html = rows.map(([k, v]) =>
            `<div class="insp-kv-row"><span class="insp-kv-key">${k}</span>
             <span class="insp-kv-val">${v == null ? '—' : esc(String(v))}</span></div>`).join('');
        const tags = Array.isArray(data.tags) ? data.tags.filter(Boolean).slice(0, 6) : [];
        if (tags.length) {
            html += `<div class="insp-kv-row"><span class="insp-kv-key">标签</span>
                <span class="insp-kv-val" title="${esc(tags.join('、'))}">${esc(tags.join('、'))}</span></div>`;
        }
        if (!data.saved) {
            html += '<div class="insp-empty">这个会话还没有沉淀情节记录。</div>';
        }
        $el.innerHTML = html;
    } catch (e) {
        console.warn('[Inspector] memory insights failed:', e);
        $el.innerHTML = '<div class="insp-empty">记忆数据加载失败。</div>';
    }
}

/* -------- 系统状态 -------- */

async function renderInspectorStatus() {
    const $el = document.getElementById('inspStatus');
    if (!$el) return;
    try {
        const resp = await fetch('/api/shell/status');
        if (!resp.ok) {
            $el.innerHTML = '<div class="insp-empty">状态接口不可用（HTTP ' + resp.status + '）。</div>';
            return;
        }
        const data = await apiJson(resp) || {};
        const rows = [
            ['模型', data.modelName],
            ['可用工具', data.toolsAvailable],
            ['内存', data.memoryState],
            ['状态', data.status]
        ];
        $el.innerHTML = rows.map(([k, v]) =>
            `<div class="insp-kv-row"><span class="insp-kv-key">${k}</span>
             <span class="insp-kv-val" title="${esc(v == null ? '—' : String(v))}">${
                 esc(v == null || v === '' ? '—' : String(v))}</span></div>`).join('');
    } catch (e) {
        console.warn('[Inspector] status failed:', e);
        $el.innerHTML = '<div class="insp-empty">状态加载失败。</div>';
    }
}

/* ------------------------------------------------------- 实时推送（SSE） */

function closeInspectorStream() {
    if (inspStreamAbort) {
        try { inspStreamAbort.abort(); } catch (_) {}
        inspStreamAbort = null;
    }
    if (inspPushTimer) { clearTimeout(inspPushTimer); inspPushTimer = null; }
}

/** 换会话 / 开关栏时重建订阅。没登录或没会话就不开流。 */
function syncInspectorStream() {
    closeInspectorStream();
    if (!inspectorIsOpen() || !currentId) return;
    const tk = window.MiniAuth && window.MiniAuth.token ? window.MiniAuth.token() : '';
    if (!tk) return;
    const ctrl = new AbortController();
    inspStreamAbort = ctrl;
    openInspectorStream(currentId, ctrl);
}

/**
 * 用 fetch 读 SSE 而不是 EventSource：EventSource 带不了 Authorization 头，
 * 只能把 token 塞进查询串，那会进访问日志、留在浏览器历史，与全站 Bearer 两套鉴权。
 */
async function openInspectorStream(sid, ctrl) {
    try {
        const resp = await fetch('/api/conversations/' + encodeURIComponent(sid) + '/traces', {
            headers: { 'Accept': 'text/event-stream' },
            signal: ctrl.signal
        });
        if (!resp.ok || !resp.body) return;
        const reader = resp.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buf = '';
        for (;;) {
            const chunk = await reader.read();
            if (chunk.done) break;
            buf += decoder.decode(chunk.value, { stream: true });
            let m;
            while ((m = /\r?\n\r?\n/.exec(buf)) !== null) {
                const frame = buf.slice(0, m.index);
                buf = buf.slice(m.index + m[0].length);
                handleInspectorFrame(frame, sid);
            }
        }
    } catch (err) {
        if (err && err.name === 'AbortError') return;   // 主动关流，不是异常
        console.warn('[Inspector] trace stream closed:', err);
    }
}

/** 只处理 event: trace；心跳等其它帧忽略。 */
function handleInspectorFrame(frame, sid) {
    let eventName = 'message';
    const dataLines = [];
    frame.split('\n').forEach(line => {
        if (line.startsWith('event:')) eventName = line.slice(6).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
    });
    if (eventName !== 'trace') return;
    let data = null;
    try { data = JSON.parse(dataLines.join('\n')); } catch (_) { return; }
    if (!data || data.sessionId !== sid) return;
    if (inspPushTimer) clearTimeout(inspPushTimer);
    inspPushTimer = setTimeout(() => {
        inspPushTimer = null;
        refreshInspector(true);
    }, INSP_PUSH_DEBOUNCE_MS);
}

/* ------------------------------------------------------------------ 工具 */

function formatDuration(ms) {
    const n = Number(ms) || 0;
    if (n < 1000) return n + 'ms';
    if (n < 60000) return (n / 1000).toFixed(n < 10000 ? 1 : 0) + 's';
    const min = Math.floor(n / 60000);
    const sec = Math.round((n % 60000) / 1000);
    return min + 'm' + (sec ? sec + 's' : '');
}

/** 步骤正文压成一行：去掉换行与多余空白，超长截断。 */
function shortText(s, max) {
    if (!s) return '';
    const flat = String(s).replace(/\s+/g, ' ').trim();
    return flat.length > max ? flat.slice(0, max) + '…' : flat;
}

/* ------------------------------------------------------------------ 启动 */

(function initInspector() {
    const boot = () => {
        applyInspectorInitialState();
        loadInspectorCatalog();
    };
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot, { once: true });
    } else {
        boot();
    }
})();
