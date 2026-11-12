import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

// Run the real chat SSE handlers with a small DOM substitute. No browser or npm
// dependencies are needed: node scripts/test-chat-terminal.mjs
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const chat = path.join(root, 'mini-agent-app/src/main/resources/static/js/chat');

class Element {
    constructor(tag = 'div') {
        this.tagName = tag.toUpperCase();
        this.children = [];
        this.parentNode = null;
        this.className = '';
        this.style = {};
        this.dataset = {};
        this._html = '';
        this._text = '';
        this.classList = {
            contains: name => this.className.split(/\s+/).includes(name),
            add: (...names) => { this.className = [...new Set([...this.className.split(/\s+/).filter(Boolean), ...names])].join(' '); },
            remove: (...names) => { this.className = this.className.split(/\s+/).filter(name => !names.includes(name)).join(' '); },
            toggle: (name, force) => {
                const on = force === undefined ? !this.classList.contains(name) : !!force;
                this.classList[on ? 'add' : 'remove'](name);
                return on;
            }
        };
    }
    set innerHTML(value) { this.children.forEach(child => { child.parentNode = null; }); this.children = []; this._text = ''; this._html = String(value); }
    get innerHTML() { return this._html + this._text + this.children.map(child => child.innerHTML).join(''); }
    set textContent(value) { this.innerHTML = ''; this._text = String(value); }
    get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
    get parentElement() { return this.parentNode; }
    get isConnected() { return true; }
    setAttribute(name, value) { this[name] = String(value); }
    getAttribute(name) { return this[name] ?? null; }
    appendChild(child) { child.remove(); this.children.push(child); child.parentNode = this; return child; }
    append(...children) { children.forEach(child => this.appendChild(child)); }
    insertBefore(child, ref) { child.remove(); const index = this.children.indexOf(ref); assert.ok(index >= 0); this.children.splice(index, 0, child); child.parentNode = this; }
    remove() { if (this.parentNode) { const siblings = this.parentNode.children; siblings.splice(siblings.indexOf(this), 1); this.parentNode = null; } }
    removeAttribute(name) { if (name.startsWith('data-')) delete this.dataset[name.slice(5)]; else delete this[name]; }
    matches(selector) { return selector.startsWith('.') && selector.slice(1).split('.').every(name => this.classList.contains(name)); }
    querySelectorAll(selector) { return this.children.flatMap(child => [...(selector.split(',').some(s => child.matches(s.trim())) ? [child] : []), ...child.querySelectorAll(selector)]); }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    closest(selector) { for (let el = this; el; el = el.parentNode) if (el.matches(selector)) return el; return null; }
    scrollTo() {}
}

function createHarness() {
    const ids = new Map();
    const timers = new Map();
    let timerId = 0;
    const context = vm.createContext({
        console, TextDecoder, URL, Date, Intl,
        document: {
            readyState: 'loading',
            getElementById: id => { if (!ids.has(id)) ids.set(id, new Element()); return ids.get(id); },
            createElement: tag => new Element(tag),
            createTextNode: value => { const el = new Element(); el.textContent = value; return el; },
            querySelectorAll: () => [],
            addEventListener() {}
        },
        localStorage: { getItem: () => null, setItem() {} },
        window: {},
        setTimeout: (fn, delay = 0) => { timers.set(++timerId, { fn, delay }); return timerId; },
        clearTimeout: id => timers.delete(id),
        setInterval: () => ++timerId,
        clearInterval() {},
        fetch: async () => ({ ok: true, json: async () => ({ success: true }) }),
        onInputChange() {}, bindLightbox() {}, stopPolling() {}, removeTaskRunningHint() {},
        renderMD: text => String(text), renderMDStreaming: text => String(text),
        splitStreamingMarkdown: text => ({ frozen: '', tail: text }), dropLeadBlanks: text => text,
        esc: text => String(text), escQ: text => String(text)
    });
    for (const filename of ['01-core.js', '07-permission.js', '10-stream.js', '11-send.js', '14a-inspector.js']) {
        vm.runInContext(fs.readFileSync(path.join(chat, filename), 'utf8'), context, { filename });
    }
    vm.runInContext("currentId = 'regression-session'; isStreaming = true; taskIsActive = true; taskRunningSessionId = currentId; turnStartedAt = Date.now() - 1000; turnFinishedAt = 0;", context);
    const bot = new Element();
    bot.className = 'bot-body streaming';
    const row = new Element();
    row.className = 'bot-row';
    row.appendChild(bot);
    return {
        context, bot,
        flushPaint() {
            for (const [id, timer] of [...timers]) {
                if (timer.delay > 200) continue;
                timers.delete(id);
                timer.fn();
            }
        }
    };
}

function event(name, data) {
    return `event: ${name}\n${String(data).split('\n').map(line => `data: ${line}`).join('\n')}\n\n`;
}

async function consume(harness, chunks, reconnectAttempt = 0) {
    let index = 0;
    const reader = {
        async read() {
            while (index < chunks.length) {
                const chunk = chunks[index++];
                if (typeof chunk === 'function') { await chunk(); continue; }
                return { value: Buffer.from(chunk), done: false };
            }
            return { done: true };
        }
    };
    await harness.context.consumeStream(reader, harness.bot, 'regression-session', reconnectAttempt);
    return harness.bot.querySelector('.answer-stream');
}

for (const answerFirst of [true, false]) {
    const h = createHarness();
    const nodes = event('todo', JSON.stringify({ items: [{ id: 1, content: '核验来源', status: 'completed' }] }));
    const token = event('token', '今日动态已核验');
    await consume(h, answerFirst ? [token, () => h.flushPaint(), nodes, event('end', '今日动态已核验')]
        : [nodes, token, event('end', '今日动态已核验')]);
    assert.ok(h.bot.children.indexOf(h.bot.querySelector('.todo-panel'))
        < h.bot.children.indexOf(h.bot.querySelector('.answer-stream')));
    assert.equal(h.bot.querySelectorAll('.todo-panel').length, 1);
    console.log('PASS: task card precedes its answer when ' + (answerFirst ? 'tokens arrive first' : 'nodes arrive first'));
}

{
    const h = createHarness();
    const makeHistoricalBody = () => {
        const row = new Element(); row.className = 'bot-row';
        const body = new Element(); body.className = 'bot-body';
        const answer = new Element(); answer.className = 'answer-stream'; answer.textContent = '相同问题的回复';
        body.appendChild(answer); row.appendChild(body); return body;
    };
    const first = makeHistoricalBody(), second = makeHistoricalBody();
    // JSON round-trip models the server response after a process restart.
    const turns = JSON.parse(JSON.stringify([
        { id: 41, progress: { executionId: 'exec-first', status: 'SUCCESS', items: [
            { id: 1, content: '首轮查询来源', status: 'completed' }
        ] } },
        { id: 42, progress: { executionId: 'exec-second', status: 'FAILURE', items: [
            { id: 1, content: '第二轮生成页面', status: 'completed' },
            { id: 2, content: '第二轮验证页面', status: 'blocked' }
        ] } }
    ]));
    h.context.restoreTaskProgress(first, turns[0]); h.context.restoreTaskProgress(second, turns[1]);
    h.context.restoreTaskProgress(first, turns[0]); // idempotent rerender must not duplicate cards
    assert.equal(first.closest('.bot-row').dataset.chatTaskId, '41');
    assert.equal(second.closest('.bot-row').dataset.chatTaskId, '42');
    assert.equal(first.dataset.executionId, 'exec-first');
    assert.equal(second.dataset.executionId, 'exec-second');
    assert.equal(first.querySelectorAll('.todo-panel').length, 1);
    assert.equal(first.querySelector('.todo-progress-meta').textContent, '1/1 已完成');
    assert.equal(second.querySelector('.todo-progress-meta').textContent, '1/2 已完成');
    assert.equal(second.querySelector('.todo-progress-title').textContent, '任务执行失败');
    assert.ok(first.children.indexOf(first.querySelector('.todo-panel')) < first.children.indexOf(first.querySelector('.answer-stream')));
    assert.ok(second.children.indexOf(second.querySelector('.todo-panel')) < second.children.indexOf(second.querySelector('.answer-stream')));
    assert.doesNotMatch(first.textContent, /第二轮/); assert.doesNotMatch(second.textContent, /首轮/);
    console.log('PASS: reload preserves two separate execution snapshots, order, statuses and node counts');
}

{
    const h = createHarness();
    h.context.restoreTaskProgress(h.bot, { id: 43, progress: { status: 'WAITING', items: [
        { id: 1, content: '曾经等待确认的历史节点', status: 'awaiting_confirm' }
    ] } });
    assert.equal(h.bot.querySelector('.todo-confirm-btn'), null);
    assert.match(h.bot.querySelector('.todo-progress-title').textContent, /等待你的操作/);
    console.log('PASS: historical approval nodes are read only and cannot approve a different current task');
}

const ask = () => event('permission_ask', JSON.stringify({
    tool: 'exec_command', display: 'node verify-ui.js', grantToken: 'test-token',
    expiresAt: Date.now() + 60_000
}));
for (const backToA of [false, true]) {
    const h = createHarness();
    await consume(h, [
        () => vm.runInContext("currentId = 'other-session'; window._msgLoadEpoch = 1; isStreaming = true; taskIsActive = true; taskRunningSessionId = currentId;", h.context),
        ...(backToA ? [() => vm.runInContext("currentId = 'regression-session'; window._msgLoadEpoch = 2; taskRunningSessionId = currentId;", h.context)] : []),
        event('task_outcome', JSON.stringify({ status: 'SUCCESS' })), event('end', '旧会话答复')
    ]);
    assert.equal(vm.runInContext('isStreaming', h.context), true);
    assert.equal(vm.runInContext('taskIsActive', h.context), true);
    assert.equal(h.bot.querySelector('.answer-stream'), null);
    console.log('PASS: late SSE terminal event cannot finish ' + (backToA ? 'a newer navigation to the same session' : 'another conversation'));
}
{
    const h = createHarness();
    let rejectPost;
    h.context.fetch = () => new Promise((resolve, reject) => { rejectPost = reject; });
    const pending = h.context.sendChatPost('原会话任务', 'regression-session', [], [], h.bot, null);
    vm.runInContext("currentId = 'other-session'; window._msgLoadEpoch = 1; isStreaming = true; taskIsActive = true; taskRunningSessionId = currentId;", h.context);
    rejectPost(new Error('original POST failed after navigation')); await pending;
    assert.equal(vm.runInContext('isStreaming', h.context), true);
    assert.equal(h.bot.textContent, '');
    console.log('PASS: late POST rejection cannot unlock the newly selected conversation');
}
const finalAnswer = '已生成虚拟仿真交互页面。\n\n[查看 HTML 文件](awsim_virtual_sim_ui.html)';

{
    const h = createHarness();
    const answer = await consume(h, [
        ask(),
        async () => {
            assert.equal(vm.runInContext('pendingAskTool', h.context), 'exec_command');
            await h.context.grantAskTool();
            assert.equal(vm.runInContext('pendingAskTool', h.context), null);
        },
        event('token', '授权后的回复正文'),
        () => {
            h.flushPaint();
            assert.equal(h.bot.querySelector('.answer-stream')?.innerHTML, '授权后的回复正文');
        },
        event('end', finalAnswer)
    ]);
    assert.equal(answer?.innerHTML, finalAnswer);
    assert.ok(answer.classList.contains('has-content'));
    assert.equal(h.bot.querySelector('.task-status-card'), null);
    assert.equal(h.bot.querySelector('.permission-request-status')?.textContent, '已批准，Agent 正在继续执行。');
    console.log('PASS: approval clears the pending tool, resumes tokens, and preserves the final SSE answer');
}

{
    const h = createHarness();
    const answer = await consume(h, [ask(), event('end', finalAnswer)]);
    assert.equal(answer?.innerHTML, finalAnswer);
    assert.equal(vm.runInContext('pendingAskTool', h.context), null);
    assert.ok(h.bot.querySelector('.permission-request-card').classList.contains('closed'));
    console.log('PASS: final SSE text overrides an outstanding local approval flag');
}

{
    const h = createHarness();
    const answer = await consume(h, [event('token', '完整流式回复'), event('end', '')]);
    assert.equal(answer?.innerHTML, '完整流式回复');
    console.log('PASS: an empty terminal frame retains a nonempty streamed answer');
}

{
    const h = createHarness();
    const answer = await consume(h, [event('end', '')]);
    assert.match(answer?.innerHTML || '', /没有生成最终回复/);
    assert.ok(answer.classList.contains('has-content'));
    assert.equal(h.bot.querySelector('.task-status-card'), null);
    console.log('PASS: an empty terminal answer receives an explicit fallback');
}

{
    const h = createHarness();
    const answer = await consume(h, [ask(), event('end', '')]);
    assert.match(answer?.innerHTML || '', /没有生成最终回复/);
    assert.ok(answer.classList.contains('has-content'));
    assert.equal(h.bot.querySelector('.task-status-card'), null);
    assert.equal(vm.runInContext('pendingAskTool', h.context), null);
    assert.equal(vm.runInContext('pendingAskGrantToken', h.context), null);
    assert.ok(h.bot.querySelector('.permission-request-card').classList.contains('closed'));
    assert.match(h.bot.querySelector('.permission-request-status')?.textContent || '', /任务已结束/);
    console.log('PASS: an empty terminal answer with pending approval has a fallback and closes the approval card');
}

{
    const h = createHarness();
    const answer = await consume(h, [event('error', '生成页面失败：服务连接断开')]);
    assert.match(answer?.innerHTML || '', /生成页面失败：服务连接断开/);
    assert.ok(answer.classList.contains('has-content'), 'a terminal error must remain visible without a status card');
    assert.equal(h.bot.querySelector('.task-status-card'), null);
    console.log('PASS: terminal errors without streamed tokens remain visible in the reply');
}

{
    const h = createHarness();
    const steps = [];
    for (let index = 0; index < 12; index++) {
        steps.push({ stepType: 'TOOL_CALL', toolName: 'exec_command', toolArgs: '{"command":"node verify-ui.js"}' });
        steps.push({ stepType: index % 3 === 2 ? 'TOOL_ERROR' : 'TOOL_RESULT', toolName: 'exec_command', status: index % 3 === 2 ? 'FAILURE' : 'SUCCESS', content: index % 3 === 2 ? 'Exit code: 1' : 'OK' });
    }
    const html = h.context.buildInspectorToolRows(steps);
    assert.match(html, /失败 4 次 · 共调用 12 次/);
    assert.equal((html.match(/次调用 · 失败/g) || []).length, 4);
    assert.equal((html.match(/次调用 · 成功/g) || []).length, 8);
    console.log('PASS: tool summaries distinguish 4 failures from 12 total calls');
}

{
    const h = createHarness();
    const steps = [];
    for (let index = 0; index < 3; index++) {
        steps.push({ stepType: 'TOOL_CALL', toolName: 'write_file', status: 'RUNNING', toolArgs: JSON.stringify({ path: 'ui.html', append: index > 0 }) });
        steps.push({ stepType: 'TOOL_RESULT', toolName: 'write_file', status: 'SUCCESS', content: 'OK' });
    }
    const html = h.context.buildInspectorStepRows(steps, { status: 'SUCCESS' });
    assert.match(html, /共调用 3 次，最近一次完成/);
    assert.doesNotMatch(html, /重试/);
    console.log('PASS: repeated file writes are counted as calls rather than mislabeled as retries');
}

{
    const h = createHarness();
    await consume(h, [
        event('subgoal', JSON.stringify({ text: '模型正在分析任务', done: 1, total: 3 })),
        () => assert.equal(h.bot.querySelector('.task-status-card'), null),
        event('end', '普通答复，无拆分步骤。')
    ]);
    assert.equal(h.bot.querySelector('.todo-panel'), null);
    assert.equal(h.bot.querySelector('.task-status-card'), null);
    console.log('PASS: loop subgoals and direct replies never create a generic status card');
}

{
    const h = createHarness();
    const nodes = [
        { id: 1, content: '分析任务需求', status: 'completed' },
        { id: 2, content: '整理与分类照片', status: 'in_progress' },
        { id: 3, content: '生成相册', status: 'pending' }
    ];
    const todo = items => event('todo', JSON.stringify({ items }));
    await consume(h, [
        todo(nodes),
        () => {
            assert.equal(h.bot.querySelector('.todo-progress-fill').style.width, '33%');
            assert.equal(h.bot.querySelector('.todo-progress-meta').textContent, '1/3 已完成');
            assert.match(h.bot.querySelector('.todo-progress-title').textContent, /整理与分类照片/);
            assert.equal(h.bot.querySelectorAll('.todo-item.completed').length, 1);
            assert.equal(h.bot.querySelectorAll('.todo-item.in_progress').length, 1);
            assert.equal(h.bot.querySelectorAll('.todo-item.pending').length, 1);
        },
        todo(nodes.map((item, index) => ({ ...item, status: index < 2 ? 'completed' : 'in_progress' }))),
        () => {
            assert.equal(h.bot.querySelector('.todo-progress-fill').style.width, '67%');
            assert.equal(h.bot.querySelectorAll('.todo-item.completed').length, 2);
        },
        todo(nodes.map(item => ({ ...item, status: 'completed' }))),
        event('end', '相册已生成。')
    ]);
    assert.equal(h.bot.querySelector('.todo-progress-fill').style.width, '100%');
    assert.equal(h.bot.querySelector('.todo-progress-meta').textContent, '3/3 已完成');
    assert.equal(h.bot.querySelectorAll('.todo-item.completed').length, 3);
    assert.equal(h.bot.querySelectorAll('.todo-panel').length, 1);
    assert.equal(h.bot.querySelector('.task-status-card'), null);
    console.log('PASS: real task nodes drive 33/67/100 percent progress and completed nodes remain visible');
}

{
    const h = createHarness();
    await consume(h, [
        event('todo', JSON.stringify({ items: [
            { id: 1, content: '分析需求', status: 'completed' },
            { id: 2, content: '生成页面', status: 'in_progress' },
            { id: 3, content: '旧方案', status: 'cancelled' }
        ] })),
        event('error', '用户停止执行')
    ]);
    assert.equal(h.bot.querySelector('.todo-progress-fill').style.width, '50%');
    assert.equal(h.bot.querySelector('.todo-progress-meta').textContent, '1/2 已完成');
    assert.equal(h.bot.querySelector('.todo-progress-title').textContent, '任务已停止');
    assert.equal(h.bot.querySelector('.todo-item.in_progress').querySelector('.todo-node-state').textContent, '待继续');
    assert.equal(h.bot.querySelector('.todo-item.cancelled').querySelector('.todo-node-state').textContent, '已取消');
    console.log('PASS: stopped and cancelled nodes do not inflate completed progress');
}

{
    const h = createHarness();
    const answer = await consume(h, [
        event('thinking', '检查所需权限'), ask(),
        event('todo', JSON.stringify({ items: [
            { id: 1, content: '执行已准备的命令', status: 'in_progress' }
        ] })),
        event('task_outcome', JSON.stringify({ status: 'WAITING', reason: 'PERM_ASK' })),
        event('end', '')
    ]);
    assert.match(answer.innerHTML, /等待你的操作/);
    assert.equal(h.bot.dataset.taskStatus, 'WAITING');
    assert.equal(h.bot.dataset.taskReason, 'PERM_ASK');
    assert.equal(vm.runInContext('pendingAskTool', h.context), 'exec_command');
    assert.equal(vm.runInContext('pendingAskGrantToken', h.context), 'test-token');
    assert.ok(!h.bot.querySelector('.permission-request-card').classList.contains('closed'));
    assert.notEqual(h.bot.querySelector('.permission-request-actions').hidden, true);
    assert.match(h.bot.querySelector('.think-label').textContent, /等待你的操作/);
    assert.match(h.bot.querySelector('.todo-progress-title').textContent, /等待你的操作/);
    assert.doesNotMatch(h.bot.querySelector('.think-label').textContent, /已完成/);
    console.log('PASS: WAITING preserves the valid approval card and never displays completion');
}

{
    const h = createHarness();
    const answer = await consume(h, [
        event('thinking', '需要补充信息'),
        event('user_question', JSON.stringify({ question: '选择出行日期', options: ['明天', '后天'] })),
        event('task_outcome', JSON.stringify({ status: 'WAITING', reason: 'USER_QUESTION' })),
        event('end', '等待你选择日期。')
    ]);
    assert.equal(answer.innerHTML, '等待你选择日期。');
    assert.ok(h.bot.querySelector('.user-question-card'));
    assert.equal(h.bot.dataset.taskStatus, 'WAITING');
    assert.match(h.bot.querySelector('.think-label').textContent, /等待你的操作/);
    console.log('PASS: WAITING retains the in-chat user question');
}

for (const [status, reason, expected, transport] of [
    ['CANCELED', 'CANCELLED', '已取消', 'end'],
    ['FAILURE', 'MAX_ITERATIONS', '失败', 'end'],
    ['FAILURE', 'OUTCOME_UNKNOWN', '失败', 'error'],
    ['SUCCESS', 'SUCCESS', '已完成', 'end']
]) {
    const h = createHarness();
    await consume(h, [
        event('thinking', '处理中'), ask(),
        event('task_outcome', JSON.stringify({ status, reason })),
        event(transport, status === 'SUCCESS' ? '产物已保存。' : '本轮已结束。')
    ]);
    assert.equal(h.bot.dataset.taskStatus, status);
    assert.match(h.bot.querySelector('.think-label').textContent, new RegExp(expected));
    assert.equal(vm.runInContext('pendingAskTool', h.context), null);
    assert.ok(h.bot.querySelector('.permission-request-card').classList.contains('closed'));
    if (status !== 'SUCCESS') assert.doesNotMatch(h.bot.querySelector('.think-label').textContent, /已完成/);
    console.log(`PASS: ${status}/${reason} displays ${expected} on ${transport} and settles stale approvals`);
}

for (const partialAnswer of ['部分结果，尚未完成', '']) {
    const h = createHarness();
    const answer = await consume(h, [
        event('thinking', '任务尚在处理'),
        ...(partialAnswer ? [event('token', partialAnswer)] : [])
    ], 3);
    assert.equal(h.bot.dataset.taskStatus, 'INCOMPLETE');
    assert.match(h.bot.querySelector('.think-label').textContent, /未完成/);
    assert.doesNotMatch(h.bot.querySelector('.think-label').textContent, /已完成|已取消/);
    if (partialAnswer) assert.equal(answer.innerHTML, partialAnswer);
    else assert.match(answer.innerHTML, /连接多次中断/);
    console.log(`PASS: exhausted reconnect without a terminal frame is incomplete ${partialAnswer ? 'with' : 'without'} a partial answer`);
}
