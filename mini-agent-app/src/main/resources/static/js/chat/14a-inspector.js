/* =========================================================================
   INSPECTOR — 右侧「任务执行详情」栏

   四块内容全部取自真实接口，没有一处是写死的占位数字：

     执行步骤   GET /api/conversations/{sid}/todos 优先展示任务拆分；没有 todo 时回退到执行轨迹
                GET /api/conversations/{sid}/executions + /api/executions/{id}/steps
     执行工具   同一份 steps 聚合调用状态，并补充注册表描述和参数摘要
     记忆与知识 GET /api/shell/memory-insights?sessionId=  → episode / fact / procedure
     系统状态   GET /api/shell/status                      → 模型 / 工具数 / 堆状态

   刷新时机有三处，都不做定时轮询（security.js 给全站 fetch 打了 request/response
   日志，轮询会把这些日志刷成噪音）：
     1. 打开检查器时刷一次；
     2. 切换 / 新建会话时刷一次（09-session.js 切换/清空钩子，04-media.js 首次建会话钩子）；
     3. 订阅 /api/conversations/{sid}/traces 的 SSE 推送，收到 trace 事件后防抖刷新。

   展开状态存在 localStorage['mini_agent_inspector']：宽屏（≥1180px）默认展开，
   窄屏默认收起；≤1180px 展开时使用抽屉，避免挤压对话和文件卡片。
   ========================================================================= */

const INSP_LS_KEY = 'mini_agent_inspector';
const INSP_NARROW_PX = 1180;
const INSP_PUSH_DEBOUNCE_MS = 500;
const INSP_MIN_STEP_REFRESH_MS = 5000;
const INSP_RECONNECT_BASE_MS = 1000;
const INSP_RECONNECT_MAX_MS = 30000;
const INSP_MAX_STEPS = 6;      // 侧栏只保留少量 DAG 节点；完整轨迹在 /trace
const INSP_MAX_TOOLS = 3;

/** stepType → 中文标签，来源是 /api/trace-nodes（AgentStepNode.catalog()）。 */
let inspLabels = {};
/** 哪些 stepType 属于「非核心」，视图里默认不占位置。 */
let inspCore = {};
let inspCatalogLoaded = false;

let inspStreamAbort = null;
let inspPushTimer = null;
let inspRefreshTimer = null;
let inspStepRefreshTimer = null;
let inspReconnectTimer = null;
let inspDataRetryTimer = null;
let inspReconnectAttempt = 0;
let inspDataRetryAttempt = 0;
let inspLastStepRefreshAt = 0;
let inspBusy = false;
let inspPending = false;
let inspStepsPending = false;
/** 上一次渲染的步骤快照，用于判断是否真有新内容（避免每帧重绘闪烁）。 */
let inspLastSignature = '';

const INSP_FAIL_STATUS = new Set([
    'FAILURE', 'FAILED', 'ERROR', 'TIMEOUT', 'INTERRUPTED', 'ABORTED', 'REJECTED'
]);
const INSP_RUN_STATUS = new Set(['RUNNING', 'PENDING']);
const INSP_WAIT_STATUS = new Set(['WAITING', 'WAITING_FOR_HUMAN', 'AWAITING_CONFIRM', 'AWAITING_USER']);
const INSP_SUCCESS_STATUS = new Set(['SUCCESS', 'COMPLETED', 'DONE']);

let inspToolDescriptions = {};
let inspToolCatalogLoaded = false;
let inspToolLogSessionId = '';
let inspOpenToolLogKeys = new Set();

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
        btn.hidden = next;
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
        btn.hidden = open;
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
function refreshInspector(force, stepsOnly) {
    if (!force && !inspectorIsOpen()) return;
    if (inspBusy) {
        if (stepsOnly) inspStepsPending = true;
        else inspPending = true;
        return;
    }
    inspBusy = true;
    Promise.resolve()
        .then(() => stepsOnly
            ? renderInspectorSteps(currentId)
            : Promise.all([
                renderInspectorStatus(),
                renderInspectorMemory(currentId),
                renderInspectorSteps(currentId)
            ]))
        .catch(e => console.warn('[Inspector] refresh failed:', e))
        .finally(() => {
            inspBusy = false;
            if (inspPending) {
                inspPending = false;
                inspStepsPending = false;
                refreshInspector(true);
            } else if (inspStepsPending) {
                inspStepsPending = false;
                refreshInspector(true, true);
            }
        });
}

/** 会话切换 / 清空 / 首次创建后由 09-session.js、04-media.js 调；异步收口，不阻塞发送。 */
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
        inspToolLogSessionId = '';
        inspOpenToolLogKeys.clear();
        $steps.innerHTML = '<div class="insp-empty">选择或新建会话后显示当前任务步骤。</div>';
        $tools.innerHTML = '<div class="insp-empty">本次任务尚未调用工具。</div>';
        const $details = document.getElementById('inspTaskDetails');
        if ($details) $details.innerHTML = '<div class="insp-empty">发起任务后显示实际执行信息。</div>';
        setInspectorProgress(0, 0);
        setInspectorState('空闲', 'done');
        return;
    }

    await Promise.all([loadInspectorCatalog(), loadInspectorToolCatalog()]);
    let todos = [];
    let taskRunning = false;
    let steps = [];
    let latestExecution = null;
    try {
        const resp = await fetch('/api/conversations/' + encodeURIComponent(sid) + '/inspector-snapshot');
        if (!resp.ok) {
            const transient = resp.status === 408 || resp.status === 429 || resp.status >= 500;
            if (transient) {
                setInspectorState('同步重试中', 'waiting');
                scheduleInspectorDataRetry(sid);
            }
            // Keep the last good snapshot. A temporary 429 must not replace a
            // real execution with an empty list and an "idle" status.
            return;
        }
        const snapshot = await apiJson(resp) || {};
        todos = Array.isArray(snapshot.todos) ? snapshot.todos : [];
        taskRunning = snapshot.running === true;
        latestExecution = snapshot.execution || null;
        steps = Array.isArray(snapshot.steps) ? snapshot.steps : [];
    } catch (e) {
        console.warn('[Inspector] task details failed:', e);
        scheduleInspectorDataRetry(sid);
    }
    if (sid !== currentId) return;
    if (!Array.isArray(todos)) todos = [];
    if (!Array.isArray(steps)) steps = [];
    if (inspToolLogSessionId !== sid) {
        inspToolLogSessionId = sid;
        inspOpenToolLogKeys.clear();
    }
    inspDataRetryAttempt = 0;
    if (inspDataRetryTimer) {
        clearTimeout(inspDataRetryTimer);
        inspDataRetryTimer = null;
    }

    const signature = JSON.stringify({
        executionId: latestExecution && latestExecution.executionId,
        userQuestion: latestExecution && latestExecution.userQuestion,
        todos: todos.map(t => [t.id, t.content, t.status]),
        steps: steps.map(s => [s.id, s.stepType, s.toolName, s.status, s.durationMs,
            s.createdAt, s.content]),
        taskRunning,
        executionStatus: latestExecution && latestExecution.status
    });
    if (signature !== inspLastSignature) {
        inspLastSignature = signature;
        $steps.innerHTML = buildInspectorCurrentTask(latestExecution) + (todos.length
            ? buildInspectorTodoRows(todos, steps)
            : buildInspectorStepRows(steps, latestExecution));
        $tools.innerHTML = buildInspectorToolRows(steps);
        $tools.querySelectorAll('.insp-tool-card').forEach(card => {
            card.open = inspOpenToolLogKeys.has(card.dataset.toolKey || '');
        });
        const $details = document.getElementById('inspTaskDetails');
        if ($details) $details.innerHTML = buildInspectorTaskDetails(latestExecution, todos, steps, taskRunning);
    }

    if (todos.length) setInspectorProgressFromTodos(todos);
    else setInspectorProgress(0, 0);
    const failed = todos.some(t => t && (t.status === 'blocked' || t.status === 'failed'))
        || (latestExecution && INSP_FAIL_STATUS.has(String(latestExecution.status || '').toUpperCase()));
    const waiting = todos.some(t => t && t.status === 'awaiting_confirm')
        || (latestExecution && INSP_WAIT_STATUS.has(String(latestExecution.status || '').toUpperCase()));
    if (taskRunning) setInspectorState('进行中', 'running');
    else if (failed) setInspectorState('受阻', 'failed');
    else if (waiting) setInspectorState('待确认', 'waiting');
    else if (todos.length && todos.every(t => t.status === 'cancelled')) setInspectorState('已取消', 'done');
    else if (todos.length && todos.every(t => ['completed', 'cancelled'].includes(t.status)))
        setInspectorState('已完成', 'done');
    else if (todos.some(t => t.status === 'pending' || t.status === 'in_progress'))
        setInspectorState('待执行', 'done');
    else if (latestExecution && INSP_SUCCESS_STATUS.has(String(latestExecution.status || '').toUpperCase()))
        setInspectorState('已完成', 'done');
    else setInspectorState('空闲', 'done');
}

function buildInspectorCurrentTask(execution) {
    const question = shortText(execution && execution.userQuestion, 180);
    if (!question) return '';
    return `<div class="insp-current-task">
        <div class="insp-current-task-label">当前任务</div>
        <div class="insp-current-task-title" title="${escQ(esc(execution.userQuestion))}">${esc(question)}</div>
    </div>`;
}

function buildInspectorTaskDetails(execution, todos, steps, taskRunning) {
    const status = String(execution && execution.status || '').toUpperCase();
    const inProgress = taskRunning || INSP_RUN_STATUS.has(status) || (todos || []).some(todo =>
        todo && todo.status === 'in_progress');
    const failed = INSP_FAIL_STATUS.has(status) || (todos || []).some(todo =>
        todo && ['blocked', 'failed'].includes(String(todo.status || '')));
    const waiting = INSP_WAIT_STATUS.has(status) || (todos || []).some(todo =>
        todo && todo.status === 'awaiting_confirm');
    const done = (todos || []).filter(todo => todo && todo.status === 'completed').length;
    const allTodosSettled = todos.length > 0 && todos.every(todo =>
        todo && ['completed', 'cancelled'].includes(String(todo.status || '')));
    const allTodosCancelled = todos.length > 0 && todos.every(todo =>
        todo && todo.status === 'cancelled');
    const consumedToolResults = new Set();
    const toolPresentations = [];
    (steps || []).forEach((step, index) => {
        if (!step || step.stepType !== 'TOOL_CALL' || !step.toolName) return;
        const resultIndex = steps.findIndex((candidate, candidateIndex) => candidateIndex > index
            && !consumedToolResults.has(candidateIndex) && candidate && candidate.toolName === step.toolName
            && ['TOOL_RESULT', 'TOOL_ERROR'].includes(candidate.stepType));
        if (resultIndex >= 0) consumedToolResults.add(resultIndex);
        toolPresentations.push(toolPresentation(step.toolName, step.toolArgs,
            resultIndex >= 0 ? steps[resultIndex].content : null));
    });
    const toolNames = [...new Set(toolPresentations.map(tool => tool.title))];
    const categories = [...new Set(toolPresentations.map(tool => tool.kind))];
    const categoryLabels = { file: '文件处理', web: '联网搜索', code: '代码与命令',
        image: '图像处理', memory: '记忆操作', other: '通用工具' };
    const type = categories.length
        ? categories.map(kind => categoryLabels[kind] || '通用任务').join('、')
        : '对话任务';
    const state = inProgress ? '进行中' : failed ? '受阻' : waiting ? '待确认'
        : allTodosCancelled ? '已取消' : INSP_SUCCESS_STATUS.has(status) || allTodosSettled ? '已完成'
            : todos.length ? '待执行' : execution ? '已记录' : '等待任务';
    const stateKind = inProgress ? 'running' : failed ? 'failed' : waiting ? 'waiting'
        : allTodosCancelled ? 'pending' : INSP_SUCCESS_STATUS.has(status) || allTodosSettled ? 'done' : 'pending';
    const rows = [
        ['任务类型', type],
        ['任务进度', todos.length ? done + ' / ' + todos.length + ' 项完成' : '暂无拆分步骤']
    ];
    if (execution && execution.startTime) rows.push(['开始时间', formatClock(execution.startTime)]);
    if (execution && execution.startTime) {
        const start = Date.parse(execution.startTime);
        const end = inProgress ? Date.now() : execution.endTime ? Date.parse(execution.endTime) : NaN;
        if (Number.isFinite(start) && Number.isFinite(end) && end >= start) {
            rows.push(['执行耗时', formatDuration(end - start)]);
        }
    }
    if (toolNames.length) rows.push(['涉及工具', shortText(toolNames.join('、'), 54)]);
    return '<div class="insp-detail-state state-' + stateKind + '">' +
        '<span class="insp-detail-state-dot"></span><span>' + esc(state) + '</span></div>' +
        '<div class="insp-detail-grid">' + rows.map(row =>
            '<div class="insp-detail-row"><span class="insp-detail-label">' + esc(row[0]) +
            '</span><span class="insp-detail-value" title="' + esc(row[1]) + '">' + esc(row[1]) + '</span></div>'
        ).join('') + '</div>';
}

function buildInspectorTodoRows(todos, steps) {
    if (!todos.length) return '<div class="insp-empty">当前任务还没有待办步骤。</div>';
    const timestamps = todoTraceTimestamps(steps);
    const currentTodo = todos.find(todo => todo &&
        ['in_progress', 'awaiting_confirm'].includes(String(todo.status || '')));
    return todos.slice(0, INSP_MAX_STEPS).map(todo => {
        const status = String(todo.status || 'pending');
        const state = status === 'completed' || status === 'cancelled' ? 'done'
            : status === 'awaiting_confirm' ? 'awaiting'
                : status === 'in_progress' ? 'running'
                : status === 'blocked' ? 'failed' : 'pending';
        const labels = {
            pending: '待执行', in_progress: '进行中', awaiting_confirm: '待确认',
            completed: '已完成', cancelled: '已取消', blocked: '受阻'
        };
        const title = shortText(todo.content || '', 100);
        const note = shortText(todo.note || '', 60);
        const time = timestamps.get(String(todo.id) + ':' + status);
        const meta = [labels[status] || status,
            currentTodo && currentTodo.id === todo.id ? '当前步骤' : '',
            formatClock(time)].filter(Boolean).join(' · ');
        return `<div class="insp-step ${state} todo-${esc(status)}">
            <span class="insp-step-icon"></span>
            <div class="insp-step-body">
                <div class="insp-step-title">${esc(title)}</div>
                <div class="insp-step-meta status-meta">${esc(meta)}</div>
                ${note ? `<div class="insp-step-meta">${esc(note)}</div>` : ''}
            </div>
        </div>`;
    }).join('') + (todos.length > INSP_MAX_STEPS
        ? `<div class="insp-empty">另有 ${todos.length - INSP_MAX_STEPS} 个步骤，完整轨迹见任务中心。</div>` : '');
}

function todoTraceTimestamps(steps) {
    const times = new Map();
    const todoEvents = new Set(['TASK_SET', 'TASK_UPDATE', 'TASK_REOPEN', 'TASK_RESTORE', 'TASK_RESUME']);
    (steps || []).forEach(step => {
        if (!step || !todoEvents.has(step.stepType) || !step.createdAt) return;
        const payload = parseJson(step.content);
        const items = payload && Array.isArray(payload.items) ? payload.items : [];
        items.forEach(item => {
            if (!item || item.id == null || !item.status) return;
            times.set(String(item.id) + ':' + String(item.status), step.createdAt);
        });
    });
    return times;
}

function setInspectorProgressFromTodos(todos) {
    const done = todos.filter(t => t && t.status === 'completed').length;
    setInspectorProgress(done, todos.length);
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

function buildInspectorStepRows(steps, execution) {
    const activityTypes = new Set([
        'WAITING_FOR_HUMAN', 'TOOL_CALL', 'TOOL_RESULT', 'TOOL_ERROR',
        'ERROR', 'CANCELED', 'ABORTED'
    ]);
    const rows = [];
    const toolGroups = new Map();
    const consumed = new Set();
    (steps || []).forEach((step, index) => {
        if (!step || consumed.has(index) || !activityTypes.has(step.stepType)) return;
        if (step.stepType === 'TOOL_RESULT' || step.stepType === 'TOOL_ERROR') return;
        if (step.stepType === 'TOOL_CALL') {
            const resultIndex = steps.findIndex((candidate, candidateIndex) => candidateIndex > index
                && !consumed.has(candidateIndex)
                && candidate && candidate.toolName === step.toolName
                && ['TOOL_RESULT', 'TOOL_ERROR'].includes(candidate.stepType));
            const result = resultIndex >= 0 ? steps[resultIndex] : null;
            if (result) consumed.add(resultIndex);
            const status = result
                ? (result.stepType === 'TOOL_ERROR' ? 'FAILURE' : result.status)
                : step.status;
            const state = INSP_WAIT_STATUS.has(String(status || '').toUpperCase())
                ? 'awaiting' : result && status === 'RUNNING' ? 'running' : stepState(status);
            const presentation = toolPresentation(step.toolName, step.toolArgs, result && result.content);
            const title = presentation.title;
            const groupKey = presentation.kind + ':' + title.toLocaleLowerCase('zh-CN');
            let group = toolGroups.get(groupKey);
            if (!group) {
                group = { title, attempts: [] };
                toolGroups.set(groupKey, group);
                rows.push(group);
            }
            group.attempts.push({
                state,
                detail: (result && result.stepType === 'TOOL_ERROR' ? toolFailureSummary(result) : '')
                    || toolArgsSummary(step.toolArgs, step.toolName)
                    || toolDescription(step.toolName),
                time: (result && result.createdAt) || step.createdAt,
                durationMs: result && result.durationMs
            });
            return;
        }

        const label = inspLabels[step.stepType] || step.stepType || '步骤';
        let detail = step.subGoalText || step.content || '';
        if (step.stepType === 'PLAN') detail = step.subGoalText || String(detail).replace(/^子目标[：:]\s*/, '');
        if (step.stepType === 'TASK_PLAN') detail = planSummary(detail);
        if (typeof detail === 'string' && /^\s*[{[]/.test(detail)) detail = '';
        const state = stepState(step.status);
        rows.push({
            state: INSP_WAIT_STATUS.has(String(step.status || '').toUpperCase()) ? 'awaiting' : state,
            title: label,
            detail: shortText(detail, 72),
            time: step.createdAt,
            status: traceStatusLabel(step.status),
            durationMs: step.durationMs
        });
    });

    const normalizedRows = rows.map(row => {
        if (!row.attempts) return row;
        const latest = row.attempts[row.attempts.length - 1];
        const callCount = row.attempts.length;
        return {
            state: latest.state,
            title: row.title,
            detail: latest.detail,
            time: latest.time,
            status: latest.state === 'failed' ? '执行失败'
                : latest.state === 'awaiting' ? '待确认'
                    : latest.state === 'running' ? '进行中'
                        : callCount > 1 ? '共调用 ' + callCount + ' 次，最近一次完成' : '已完成',
            durationMs: latest.durationMs
        };
    });
    const tail = normalizedRows.slice(-INSP_MAX_STEPS);
    if (!tail.length) {
        const terminal = execution && INSP_SUCCESS_STATUS.has(String(execution.status || '').toUpperCase());
        return `<div class="insp-empty">${terminal
            ? '本次任务已直接完成，没有需要展开的执行步骤。'
            : '当前任务还没有可展示的步骤。'}</div>`;
    }
    return tail.map(row => {
        const meta = [row.status, formatClock(row.time), row.durationMs > 0 ? formatDuration(row.durationMs) : '']
            .filter(Boolean).join(' · ');
        return `<div class="insp-step ${row.state}">
            <span class="insp-step-icon"></span>
            <div class="insp-step-body">
                <div class="insp-step-title">${esc(row.title)}</div>
                ${meta ? `<div class="insp-step-meta status-meta">${esc(meta)}</div>` : ''}
                ${row.detail ? `<div class="insp-step-meta" title="${esc(row.detail)}">${esc(shortText(row.detail, 48))}</div>` : ''}
            </div>
        </div>`;
    }).join('');
}

function buildInspectorToolRows(steps) {
    const calls = [];
    const consumedResults = new Set();
    (steps || []).forEach((step, index) => {
        if (!step || step.stepType !== 'TOOL_CALL' || !step.toolName) return;
        const resultIndex = steps.findIndex((candidate, candidateIndex) => candidateIndex > index
            && !consumedResults.has(candidateIndex)
            && candidate && candidate.toolName === step.toolName
            && ['TOOL_RESULT', 'TOOL_ERROR'].includes(candidate.stepType));
        const result = resultIndex >= 0 ? steps[resultIndex] : null;
        if (result) consumedResults.add(resultIndex);
        const resultStatus = result && String(result.status || '').toUpperCase();
        const failed = !!result && (result.stepType === 'TOOL_ERROR'
            || INSP_FAIL_STATUS.has(resultStatus));
        const state = failed ? 'failed' : INSP_WAIT_STATUS.has(resultStatus) ? 'awaiting'
            : !result || INSP_RUN_STATUS.has(resultStatus) ? 'running' : 'done';
        const presentation = toolPresentation(step.toolName, step.toolArgs, result && result.content);
        calls.push({
            name: step.toolName,
            title: presentation.title,
            kind: presentation.kind,
            htmlFile: presentation.htmlFile,
            state,
            detail: (result && result.stepType === 'TOOL_ERROR' ? toolFailureSummary(result) : '')
                || toolArgsSummary(step.toolArgs, step.toolName)
                || toolDescription(step.toolName),
            args: step.toolArgs || '',
            output: result ? result.content || '' : '',
            time: (result && result.createdAt) || step.createdAt || '',
            durationMs: result ? Number(result.durationMs) || 0 : 0
        });
    });
    const latestByName = new Map();
    calls.forEach(call => {
        const key = call.kind + ':' + call.title.toLocaleLowerCase('zh-CN');
        const previous = latestByName.get(key);
        latestByName.set(key, {
            ...call,
            count: previous ? previous.count + 1 : 1,
            failedCount: (previous ? previous.failedCount : 0) + (call.state === 'failed' ? 1 : 0),
            calls: previous ? [...previous.calls, call] : [call]
        });
    });
    const rows = [...latestByName.values()].slice(-INSP_MAX_TOOLS);
    if (!rows.length) return '<div class="insp-empty">本次任务尚未调用工具。</div>';
    return rows.map(t => {
        const state = t.state;
        const stateLabel = state === 'running' ? '进行中'
            : state === 'awaiting' ? '待确认'
                : state === 'failed' ? '失败'
                : t.durationMs > 0 ? formatDuration(t.durationMs) : '已完成';
        const countLabel = t.failedCount > 0
            ? (t.count > 1 ? `失败 ${t.failedCount} 次 · 共调用 ${t.count} 次` : '失败')
            : t.count > 1
                ? (state === 'awaiting' ? '待确认 · ' : '') + '调用 ' + t.count + ' 次'
                : stateLabel;
        const detail = shortText(t.detail || toolDescription(t.name), 62);
        const key = t.kind + ':' + t.title.toLocaleLowerCase('zh-CN');
        return `<details class="insp-tool-card ${state}" data-tool-key="${escQ(esc(key))}"
                    ontoggle="onInspectorToolLogToggle(this)">
            <summary class="insp-tool" title="点击查看${escQ(esc(t.title))}的执行日志" aria-label="查看${escQ(esc(t.title))}的执行日志">
                <span class="insp-tool-icon kind-${t.kind}">${toolIcon(t.name, t.kind)}</span>
                <span class="insp-tool-main">
                    <span class="insp-tool-name" title="${escQ(esc(t.name))}">${esc(t.title)}</span>
                    <span class="insp-tool-detail">${esc(detail)}</span>
                </span>
                <span class="insp-tool-state">${esc(countLabel)}</span>
                <span class="insp-tool-open-hint" aria-hidden="true">⌄</span>
            </summary>
            <div class="insp-tool-log" aria-label="${esc(t.title)}执行记录">
                ${t.calls.map((call, index) => buildInspectorToolLogEntry(call, index, t.calls.length)).join('')}
            </div>
        </details>`;
    }).join('');
}

function onInspectorToolLogToggle(card) {
    if (!card || !card.dataset || !card.dataset.toolKey) return;
    if (card.open) inspOpenToolLogKeys.add(card.dataset.toolKey);
    else inspOpenToolLogKeys.delete(card.dataset.toolKey);
}

function buildInspectorToolLogEntry(call, index, total) {
    const state = call.state;
    const stateLabel = state === 'failed' ? '失败'
        : state === 'awaiting' ? '等待授权'
            : state === 'running' ? '执行中' : '成功';
    const heading = total > 1 ? `第 ${index + 1} 次调用 · ${stateLabel}` : `执行记录 · ${stateLabel}`;
    const meta = [call.time ? formatClock(call.time) : '',
        call.durationMs > 0 ? formatDuration(call.durationMs) : ''].filter(Boolean).join(' · ');
    const args = call.args ? formatInspectorToolLog(call.args) : '';
    const argsValue = parseJson(call.args);
    const outputValue = parseJson(call.output);
    const htmlWrite = call.name === 'write_file'
        && toolPresentation(call.name, call.args, call.output).htmlFile
        && argsValue && typeof argsValue.content === 'string'
        && (argsValue.content.length > 320 || /[\r\n]/.test(argsValue.content));
    let argsMarkup = args ? `<div class="insp-tool-log-block"><div class="insp-tool-log-label">调用参数</div><pre>${esc(args)}</pre></div>` : '';
    if (htmlWrite) {
        const redactedArgs = redactInspectorLogValue(argsValue);
        const source = String(redactedArgs.content);
        const compactArgs = { ...redactedArgs, content: `[HTML 源码，${argsValue.content.length} 字符]` };
        argsMarkup = `<div class="insp-tool-log-block"><div class="insp-tool-log-label">调用参数摘要</div><pre>${esc(JSON.stringify(compactArgs, null, 2))}</pre></div>
            <details class="insp-tool-log-block insp-tool-source"><summary class="insp-tool-log-label">查看 HTML 源码 · ${argsValue.content.length} 字符</summary><pre>${esc(source)}</pre></details>
            <details class="insp-tool-log-block insp-tool-source"><summary class="insp-tool-log-label">查看完整调用参数</summary><pre>${esc(args)}</pre></details>`;
    }
    const output = call.output ? formatInspectorToolLog(call.output)
        : state === 'running' || state === 'awaiting' ? '等待工具返回…' : '没有记录到返回内容。';
    const outputLabel = state === 'failed' ? '错误日志' : '工具返回';
    return `<article class="insp-tool-log-entry ${state}">
        <div class="insp-tool-log-head"><strong>${esc(heading)}</strong>${meta ? `<span>${esc(meta)}</span>` : ''}</div>
        <div class="insp-tool-log-label">底层工具：${esc(call.name || '未知工具')}</div>
        ${call.state === 'done' && call.name === 'write_file' && call.htmlFile
                && outputValue && outputValue.success === true
            ? '<div class="insp-tool-log-label">HTML 页面文件已保存；页面运行效果需另行验证。</div>' : ''}
        ${argsMarkup}
        <div class="insp-tool-log-block"><div class="insp-tool-log-label">${outputLabel}</div><pre>${esc(output)}</pre></div>
    </article>`;
}

function formatInspectorToolLog(raw) {
    const text = String(raw == null ? '' : raw);
    try {
        const parsed = JSON.parse(text);
        return JSON.stringify(redactInspectorLogValue(parsed), null, 2);
    } catch (_) {
        return text
            .replace(/\bBearer\s+[^\s,;]+/gi, 'Bearer [已隐藏]')
            .replace(/((?:api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|password|passwd|secret|cookie|credential)["']?\s*[:=]\s*["']?)[^"'\s,;}]+/gi, '$1[已隐藏]');
    }
}

function redactInspectorLogValue(value, key) {
    if (key && /(?:api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|password|passwd|secret|cookie|credential)/i.test(key)) {
        return '[已隐藏]';
    }
    if (Array.isArray(value)) return value.map(item => redactInspectorLogValue(item, key));
    if (value && typeof value === 'object') {
        return Object.fromEntries(Object.entries(value)
            .map(([childKey, child]) => [childKey, redactInspectorLogValue(child, childKey)]));
    }
    return typeof value === 'string'
        ? value.replace(/\bBearer\s+[^\s,;]+/gi, 'Bearer [已隐藏]')
        : value;
}

async function loadInspectorToolCatalog() {
    if (inspToolCatalogLoaded) return;
    try {
        const resp = await fetch('/api/shell/tools');
        if (!resp.ok) return;
        const data = await apiJson(resp) || {};
        const descriptions = {};
        [...(data.builtin || []), ...(data.mcp || [])].forEach(tool => {
            if (tool && tool.name && tool.description) descriptions[tool.name] = tool.description;
        });
        inspToolDescriptions = descriptions;
        inspToolCatalogLoaded = true;
    } catch (e) {
        console.warn('[Inspector] tool catalog unavailable:', e);
    }
}

function toolDescription(name) {
    return inspToolDescriptions[name] || '';
}

function toolDisplayName(name) {
    const known = {
        read_file: '读取文件', file_read: '读取文件', read_package: '读取项目文件',
        search_web: '联网搜索', web_search: '联网搜索', browser_search: '联网搜索',
        web_extract: '提取网页', http_get: '读取网页', http_post: '提交网页请求',
        analyze_code: '代码分析', search_code: '代码检索', codebase_search: '代码检索',
        ast_search: '代码检索', write_file: '写入文件', edit_file: '编辑文件',
        list_files: '浏览文件', exec_command: '执行命令', image_generate: '生成图片',
        generate_image: '生成图片', browser_navigate: '浏览网页', browser_snapshot: '读取页面',
        browser_click: '操作网页', browser_type: '填写网页', browser_extract_text: '提取网页内容',
        browser_form_inspect: '识别表单', browser_form_verify: '核验字段', browser_select: '选择选项',
        browser_set_checked: '设置勾选', browser_upload: '上传附件',
        delegate_task: '子任务委派', todo: '更新任务清单', memory: '记忆操作',
        skill_list: '查看技能列表', skill_view: '读取技能', skill_manage: '管理技能'
    };
    if (known[name]) return known[name];
    const clean = String(name || '执行工具').replace(/^mcp__[^_]+__/, '').replace(/[_-]+/g, ' ').trim();
    return clean || '执行工具';
}

/** A tool's label describes its actual file target, not the overall task's completion. */
function toolPresentation(name, rawArgs, rawOutput) {
    const fallback = { title: toolDisplayName(name), kind: toolKind(name), htmlFile: false };
    const actions = { write_file: '保存', edit_file: '编辑', read_file: '读取', file_read: '读取' };
    if (!actions[name]) return fallback;
    const args = parseJson(rawArgs);
    const output = parseJson(rawOutput);
    const candidates = [args && args.path, args && args.file_path, args && args.filename,
        args && args.fileName, args && args.file, output && output.path];
    const path = candidates.find(value => typeof value === 'string' && value.trim());
    if (!path || !/\.html?$/i.test(path.trim())) return fallback;
    return { title: actions[name] + ' HTML 页面', kind: 'code', htmlFile: true };
}

function toolKind(name) {
    const key = String(name || '').toLowerCase();
    if (/file|read|write|document|pdf|directory|folder/.test(key)) return 'file';
    if (/search|browser|web|http|fetch/.test(key)) return 'web';
    if (/code|command|shell|exec|python|java|terminal/.test(key)) return 'code';
    if (/image|photo|picture|comfy/.test(key)) return 'image';
    if (/memory|knowledge|todo|task/.test(key)) return 'memory';
    return 'other';
}

function toolIcon(name, kind) {
    const paths = {
        file: '<path d="M4 7a2 2 0 0 1 2-2h4l2 2h6a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z"/><path d="M4 10h16"/>',
        web: '<circle cx="11" cy="11" r="7"/><path d="m16 16 4.5 4.5M8 11h6M11 8v6"/>',
        code: '<path d="m8 8-4 4 4 4M16 8l4 4-4 4M14 5l-4 14"/>',
        image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="8.5" cy="9" r="1.5"/><path d="m21 15-5-5L5 20"/>',
        memory: '<path d="M12 4a3 3 0 0 0-3 3v1a3 3 0 0 0 0 6v1a3 3 0 0 0 6 0v-1a3 3 0 0 0 0-6V7a3 3 0 0 0-3-3z"/><path d="M12 4v16"/>',
        other: '<path d="M12 3v18M3 12h18"/><circle cx="12" cy="12" r="8"/>'
    };
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
        + paths[kind || toolKind(name)] + '</svg>';
}

function toolArgsSummary(raw, toolName) {
    const args = parseJson(raw);
    if (!args || typeof args !== 'object') return '';
    if (toolName === 'skill_view' && typeof args.name === 'string' && args.name.trim()) {
        return '技能：' + shortText(args.name.trim(), 48);
    }
    if (toolName === 'skill_list') return '已安装技能';
    const candidates = ['query', 'search_query', 'filename', 'fileName', 'file_path', 'file', 'path', 'url', 'title', 'target'];
    for (const key of candidates) {
        const value = args[key];
        if (typeof value !== 'string' || !value.trim()) continue;
        let text = value.trim();
        if (['filename', 'fileName', 'file_path', 'file', 'path'].includes(key)) text = text.split(/[\\/]/).filter(Boolean).pop() || text;
        else if (key === 'url') {
            try { text = new URL(text).hostname; } catch (_) {}
        }
        return shortText(text, 62);
    }
    return '';
}

function toolFailureSummary(step) {
    const payload = parseJson(step && step.content);
    if (!payload || typeof payload !== 'object') {
        return shortText(String(step && step.content || '').trim(), 96);
    }
    const message = [payload.error, payload.detail, payload.hint, payload.message]
        .find(value => typeof value === 'string' && value.trim());
    if (!message) return '';
    const code = typeof payload.errorCode === 'string' ? payload.errorCode.trim() : '';
    return shortText((code ? code + ' · ' : '') + message.trim(), 96);
}

function parseJson(value) {
    if (value && typeof value === 'object') return value;
    if (typeof value !== 'string' || !value.trim()) return null;
    try { return JSON.parse(value); } catch (_) { return null; }
}

function planSummary(raw) {
    const parsed = parseJson(raw);
    if (parsed && typeof parsed === 'object') {
        return parsed.goal || parsed.objective || parsed.summary || parsed.description || '';
    }
    return raw;
}

function traceStatusLabel(status) {
    const value = String(status || '').toUpperCase();
    if (INSP_WAIT_STATUS.has(value)) return '待确认';
    if (INSP_RUN_STATUS.has(value)) return '进行中';
    if (INSP_FAIL_STATUS.has(value)) return '异常';
    if (INSP_SUCCESS_STATUS.has(value)) return '已完成';
    return status ? String(status) : '';
}

function formatClock(value) {
    if (!value) return '';
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) return '';
    return new Intl.DateTimeFormat('zh-CN', {
        hour: '2-digit', minute: '2-digit', hour12: false
    }).format(date);
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
        dot.classList.remove('running', 'done', 'failed', 'waiting');
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
        if (sid !== currentId) return;
        const stats = data.stats || {};
        const related = Array.isArray(data.related) ? data.related : [];
        const episodes = related.filter(item => item && item.type === 'episode');
        let html = '<div class="insp-memory-list">';
        if (episodes.length) {
            const summary = shortText(episodes[0].summary || '', 88);
            html += memoryRow('episode', '✦', '本会话已保存到记忆',
                summary || '已生成本次对话的经验记录');
        }
        html += '</div>';
        const counts = [
            ['长期记忆', stats.memoryCount], ['知识事实', stats.factCount], ['可复用流程', stats.procedureCount]
        ].filter(([, value]) => value != null);
        if (counts.length) {
            html += `<div class="insp-memory-stats">${counts.map(([label, value]) =>
                `<span class="insp-memory-chip">${esc(label)} ${esc(String(value))}</span>`).join('')}</div>`;
        }
        if (!episodes.length && !counts.length) {
            html = '<div class="insp-empty">当前还没有可展示的记忆或知识沉淀。</div>';
        }
        $el.innerHTML = html;
    } catch (e) {
        console.warn('[Inspector] memory insights failed:', e);
        $el.innerHTML = '<div class="insp-empty">记忆数据加载失败。</div>';
    }
}

function memoryRow(kind, icon, title, detail) {
    return `<div class="insp-memory-item">
        <span class="insp-memory-icon kind-${kind}">${icon}</span>
        <span class="insp-memory-main">
            <span class="insp-memory-title">${esc(title)}</span>
            <span class="insp-memory-detail">${esc(detail)}</span>
        </span>
    </div>`;
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
        const stateText = data.status || '运行正常';
        const systemDot = document.getElementById('inspSystemStateDot');
        const systemText = document.getElementById('inspSystemStateText');
        const isError = /异常|错误|不可用|失败/i.test(stateText);
        if (systemDot) systemDot.classList.toggle('failed', isError);
        const systemState = systemDot && systemDot.parentElement;
        if (systemState) systemState.classList.toggle('failed', isError);
        if (systemText) systemText.textContent = stateText;
        const rows = [
            ['模型', data.modelName],
            ['工具', data.toolsAvailable == null ? null : data.toolsAvailable + ' 个可用'],
            ['内存', data.memoryState]
        ];
        $el.innerHTML = rows.map(([k, v]) =>
            `<div class="insp-kv-row"><span class="insp-kv-key">${k}</span>
             <span class="insp-kv-val" title="${esc(v == null ? '—' : String(v))}">${
                 esc(v == null || v === '' ? '—' : String(v))}</span></div>`).join('');
    } catch (e) {
        console.warn('[Inspector] status failed:', e);
        const systemDot = document.getElementById('inspSystemStateDot');
        const systemText = document.getElementById('inspSystemStateText');
        if (systemDot) systemDot.classList.add('failed');
        const systemState = systemDot && systemDot.parentElement;
        if (systemState) systemState.classList.add('failed');
        if (systemText) systemText.textContent = '状态未知';
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
    if (inspStepRefreshTimer) { clearTimeout(inspStepRefreshTimer); inspStepRefreshTimer = null; }
    if (inspReconnectTimer) { clearTimeout(inspReconnectTimer); inspReconnectTimer = null; }
    if (inspDataRetryTimer) { clearTimeout(inspDataRetryTimer); inspDataRetryTimer = null; }
    inspReconnectAttempt = 0;
    inspDataRetryAttempt = 0;
}

/** 换会话 / 开关栏时重建订阅。没登录或没会话就不开流。 */
function syncInspectorStream() {
    closeInspectorStream();
    if (!inspectorIsOpen() || !currentId) return;
    const tk = window.MiniAuth && window.MiniAuth.token ? window.MiniAuth.token() : '';
    if (!tk) return;
    inspReconnectAttempt = 0;
    startInspectorStream(currentId);
}

function startInspectorStream(sid) {
    if (!inspectorIsOpen() || !currentId || sid !== currentId) return;
    const ctrl = new AbortController();
    inspStreamAbort = ctrl;
    openInspectorStream(sid, ctrl);
}

/**
 * 用 fetch 读 SSE 而不是 EventSource：EventSource 带不了 Authorization 头，
 * 只能把 token 塞进查询串，那会进访问日志、留在浏览器历史，与全站 Bearer 两套鉴权。
 */
async function openInspectorStream(sid, ctrl) {
    let retryable = true;
    const startedAt = Date.now();
    try {
        const resp = await fetch('/api/conversations/' + encodeURIComponent(sid) + '/traces', {
            headers: { 'Accept': 'text/event-stream' },
            signal: ctrl.signal
        });
        if (!resp.ok || !resp.body) {
            retryable = resp.status === 408 || resp.status === 429
                || resp.status >= 500 || !resp.body;
            if (!retryable) console.warn('[Inspector] trace stream rejected:', resp.status);
            return;
        }
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
    } finally {
        if (!ctrl.signal.aborted && retryable && sid === currentId && inspectorIsOpen()) {
            if (Date.now() - startedAt >= 60000) inspReconnectAttempt = 0;
            scheduleInspectorReconnect(sid);
        }
    }
}

function scheduleInspectorReconnect(sid) {
    if (inspReconnectTimer || sid !== currentId || !inspectorIsOpen()) return;
    const exponent = Math.min(inspReconnectAttempt, 5);
    const delay = Math.min(INSP_RECONNECT_MAX_MS, INSP_RECONNECT_BASE_MS * (2 ** exponent));
    inspReconnectAttempt++;
    inspReconnectTimer = setTimeout(() => {
        inspReconnectTimer = null;
        if (sid !== currentId || !inspectorIsOpen()) return;
        refreshInspector(true, true);
        startInspectorStream(sid);
    }, delay);
}

function scheduleInspectorDataRetry(sid) {
    if (inspDataRetryTimer || sid !== currentId || !inspectorIsOpen()) return;
    const exponent = Math.min(inspDataRetryAttempt, 5);
    const delay = Math.min(INSP_RECONNECT_MAX_MS, INSP_RECONNECT_BASE_MS * (2 ** exponent));
    inspDataRetryAttempt++;
    inspDataRetryTimer = setTimeout(() => {
        inspDataRetryTimer = null;
        if (sid === currentId && inspectorIsOpen()) refreshInspector(true, true);
    }, delay);
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
        if (inspStepRefreshTimer) return;
        const elapsed = Date.now() - inspLastStepRefreshAt;
        const wait = Math.max(0, INSP_MIN_STEP_REFRESH_MS - elapsed);
        inspStepRefreshTimer = setTimeout(() => {
            inspStepRefreshTimer = null;
            if (sid !== currentId || !inspectorIsOpen()) return;
            inspLastStepRefreshAt = Date.now();
            refreshInspector(true, true);
        }, wait);
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
