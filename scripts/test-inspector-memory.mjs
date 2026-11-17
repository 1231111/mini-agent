import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

// Exercise actual CONTEXT_LOAD memory and the production terminal refresh path.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const chat = path.join(root, 'mini-agent-app/src/main/resources/static/js/chat');
const scripts = ['13-markdown.js', '14a-inspector.js'].map(file => [file, fs.readFileSync(path.join(chat, file), 'utf8')]);

function harness() {
    const memory = { innerHTML: '' };
    const inspector = { hidden: false, classList: { contains: () => inspector.hidden } };
    const timers = new Map();
    let timerId = 0;
    const context = vm.createContext({
        console: { warn() {} }, URL, Date, Intl, AbortController,
        currentId: 'session-a',
        document: {
            readyState: 'loading', addEventListener() {},
            getElementById: id => id === 'inspMemory' ? memory : id === 'inspector' ? inspector : null
        },
        window: {}, localStorage: { getItem: () => null },
        setTimeout: (fn, delay) => { timers.set(++timerId, { fn, delay }); return timerId; },
        clearTimeout: id => timers.delete(id),
        apiJson: async response => response.data,
        fetch: async () => ({ ok: true, data: {} })
    });
    for (const [filename, source] of scripts) vm.runInContext(source, context, { filename });
    return {
        context, memory, inspector, timers,
        flushTimers() {
            for (const [id, timer] of [...timers]) {
                if (!timers.delete(id)) continue;
                timer.fn();
            }
        }
    };
}

const contextStep = context => ({ stepType: 'CONTEXT_LOAD', content: JSON.stringify(context) });
const snapshot = (memoryContext, extra = {}) => ({
    execution: { executionId: 'execution-a', status: 'COMPLETED' },
    steps: [contextStep({ injectMemory: true, injectUser: true, memoryContext, ...extra })]
});
const structured = preference => [
    '<memory-data>', '## 记忆', '当前任务: 查询今日 AI 新闻', '当前步骤: search-news',
    '', '已知事实:', '- OpenAI 发布模型需要核对官网',
    '', '历史经验:', '- 搜索今日新闻 → 核对发布时间',
    '', '可用方法:', '- 先查官方来源再汇总',
    '', '用户偏好:', '- ' + preference, '</memory-data>'
].join('\n');
const blob = (heading, items) => [
    '═'.repeat(46), heading + '（按当前任务匹配 ' + items.length + ' 条）',
    '═'.repeat(46), items.join('\n§\n')
].join('\n');
const deferred = () => {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
};
const response = data => ({ ok: true, data });
const trace = (stepType, sessionId = 'session-a') =>
    'event: trace\ndata: ' + JSON.stringify({ sessionId, stepType });
const plain = value => JSON.parse(JSON.stringify(value));
const settle = () => new Promise(resolve => setImmediate(resolve));
const rendered = (h, value, stats = {}) =>
    h.context.buildInspectorMemory(value, stats, 'session-a');
let passed = 0;
async function test(name, run) { await run(); passed++; console.log('PASS: ' + name); }

await test('actual prompt newlines and headings produce four distinct memory groups', () => {
    const h = harness(), raw = structured('人物介绍使用真实照片和时间线');
    assert.ok(raw.includes('\n'));
    assert.ok(!raw.includes('\\n'), 'fixture must contain real line breaks');
    assert.deepEqual(plain(h.context.parseInspectorMemoryContext(raw)), [
        { type: 'facts', items: ['OpenAI 发布模型需要核对官网'] },
        { type: 'episodes', items: ['搜索今日新闻 → 核对发布时间'] },
        { type: 'procedures', items: ['先查官方来源再汇总'] },
        { type: 'preferences', items: ['人物介绍使用真实照片和时间线'] }
    ]);
    const html = rendered(h, snapshot(raw), { memoryCount: 28, factCount: 7, procedureCount: 2 });
    assert.equal((html.match(/class="insp-memory-item"/g) || []).length, 4);
    assert.match(html, /本轮注入 4 条/);
    assert.match(html, /<strong>28<\/strong>/);
    assert.doesNotMatch(html, /当前任务:|当前步骤:|search-news|已保存的用户偏好/);
});

await test('only the latest CONTEXT_LOAD supplies memory for the execution', () => {
    const h = harness(), current = snapshot('用户偏好:\n- 当前任务使用的偏好');
    current.steps.unshift(contextStep({ memoryContext: '用户偏好:\n- 之前注入的偏好' }));
    current.steps.push({ stepType: 'TOOL_RESULT', content: JSON.stringify({
        memoryContext: '用户偏好:\n- 工具输出冒充的偏好'
    }) });
    current.userPreferences = [{ content: '未用于本次任务的已保存偏好', target: 'user', persisted: true }];
    current.related = [{ type: 'episode', summary: '旧会话的经验' }];
    const html = rendered(h, current);
    assert.match(html, /当前任务使用的偏好/);
    assert.doesNotMatch(html, /之前注入的偏好|工具输出冒充的偏好|未用于本次任务|旧会话的经验/);
});

await test('actual query-matched blob blocks merge and deduplicate by type', () => {
    const h = harness();
    const raw = [
        '## 记忆', '用户偏好:', '- 使用真实照片',
        blob('相关用户偏好', ['使用真实照片', '标注图片来源', '标注图片来源']),
        blob('相关记忆', ['AI 新闻需要核实发布日期'])
    ].join('\n\n');
    assert.deepEqual(plain(h.context.parseInspectorMemoryContext(raw)), [
        { type: 'preferences', items: ['使用真实照片', '标注图片来源'] },
        { type: 'facts', items: ['AI 新闻需要核实发布日期'] }
    ]);
    const html = rendered(h, snapshot(raw));
    assert.equal((html.match(/class="insp-memory-item"/g) || []).length, 2);
    assert.match(html, /用户偏好（2 条）/);
    assert.match(html, /本轮注入 3 条/);
    assert.doesNotMatch(html, /按当前任务匹配|═|§/);
});

await test('long injected memory remains expandable, complete and escaped', () => {
    const h = harness();
    const full = '最初的模板。'.repeat(50) + '最近添加：<img src=x onerror="alert(1)"> & <script>invalid</script>';
    const html = rendered(h, snapshot('用户偏好:\n- ' + full));
    assert.match(html, /<details class="insp-memory-more">/);
    assert.match(html, /查看合并的原始记忆（1 条）/);
    assert.match(html, /最近添加：&lt;img src=x onerror="alert\(1\)"&gt; &amp; &lt;script&gt;/);
    assert.ok(html.includes('最初的模板。'.repeat(50)));
    assert.doesNotMatch(html, /<img src=x|<script>|<details[^>]*\bopen\b/);
});

await test('many task preferences share one expandable card without losing entries', () => {
    const h = harness();
    const contents = ['第一项偏好', '第二项偏好', '第三项偏好', '第四项偏好'.repeat(60), '第五项偏好'];
    const html = rendered(h, snapshot(blob('相关用户偏好', contents)));
    assert.equal((html.match(/class="insp-memory-item"/g) || []).length, 1);
    assert.match(html, /用户偏好（5 条）/);
    assert.match(html, /查看合并的原始记忆（5 条）/);
    const full = html.slice(html.indexOf('class="insp-memory-full"'));
    for (const content of contents) assert.ok(full.includes(content));
    assert.doesNotMatch(html, /<details[^>]*\bopen\b/);
});

await test('missing execution and missing trace details have distinct empty states', () => {
    const h = harness();
    assert.match(h.context.buildInspectorMemory(null, null, null), /选择或新建一个会话/);
    assert.match(rendered(h, null), /暂时不可读取/);
    assert.match(rendered(h, { steps: [] }), /开始任务后显示/);
    assert.match(rendered(h, { execution: {}, steps: [] }), /没有记忆注入明细/);
    assert.match(rendered(h, { execution: {}, steps: [contextStep({ injectMemory: true })] }),
        /未记录本次记忆明细/);
    assert.match(rendered(h, { execution: {}, steps: [
        null, { stepType: 'CONTEXT_LOAD', content: '{invalid' }
    ] }), /未记录本次记忆明细/);
    assert.doesNotThrow(() => rendered(h, { execution: {}, steps: {} }));
});

await test('disabled injection differs from empty retrieval and ignores saved records', () => {
    const h = harness(), empty = snapshot('');
    empty.userPreferences = [{ content: '不相关的已保存记忆', persisted: true, target: 'user' }];
    assert.match(rendered(h, empty), /检索没有返回可注入的记忆内容/);
    const disabled = snapshot('', { injectMemory: false, injectUser: false });
    assert.match(rendered(h, disabled), /关闭了记忆注入/);
    assert.doesNotMatch(rendered(h, empty), /不相关的已保存记忆|关闭了记忆注入/);
    assert.doesNotMatch(rendered(h, disabled), /检索没有返回/);
    assert.doesNotMatch(rendered(h, snapshot({ untrusted: 'invalid structured value' })),
        /invalid structured value|\[object Object\]/);
});

await test('truncation is labelled and account totals link to their real content', () => {
    const h = harness();
    const html = rendered(h, snapshot('用户偏好:\n- 简短回答', { memoryContextTruncated: true }),
        { memoryCount: 11, factCount: 0, procedureCount: 3 });
    assert.match(html, /内容已截短/);
    assert.match(html, /href="\/memory#memAllContent"/);
    assert.match(html, /href="\/knowledge#knFactsSection"/);
    assert.match(html, /href="\/knowledge#knProceduresSection"/);
    assert.deepEqual([...html.matchAll(/<strong>([^<]*)<\/strong>/g)].map(match => match[1]), ['11', '0', '3']);
    const missing = h.context.buildInspectorMemorySummary({ memoryCount: '<script>invalid</script>', factCount: '' });
    assert.doesNotMatch(missing, /<script>|invalid/);
    assert.equal((missing.match(/<strong>—<\/strong>/g) || []).length, 3);
});

for (const outcome of ['success', 'http-error', 'network-error']) {
    await test('session changes ignore stale ' + outcome + ' responses', async () => {
        const h = harness(), old = deferred();
        h.context.fetch = () => old.promise;
        const loading = h.context.renderInspectorMemory('session-a', snapshot('用户偏好:\n- 旧会话偏好'));
        h.context.currentId = 'session-b';
        h.context.fetch = async () => response({ userStats: { memoryCount: 12 } });
        await h.context.renderInspectorMemory('session-b', snapshot('用户偏好:\n- 新会话实际使用的偏好'));
        if (outcome === 'network-error') old.reject(new Error('old request failed'));
        else old.resolve(outcome === 'http-error' ? { ok: false, status: 503 }
            : response({ userStats: { memoryCount: 4 } }));
        await loading;
        assert.match(h.memory.innerHTML, /新会话实际使用的偏好/);
        assert.match(h.memory.innerHTML, /<strong>12<\/strong>/);
        assert.doesNotMatch(h.memory.innerHTML, /旧会话偏好|HTTP|加载失败/);
    });
}

await test('a newer task snapshot wins when older same-session JSON arrives last', async () => {
    const h = harness(), oldJson = deferred();
    h.context.fetch = async () => ({ ok: true, data: oldJson.promise });
    const oldLoad = h.context.renderInspectorMemory('session-a', snapshot('用户偏好:\n- 过时任务偏好'));
    await settle();
    h.context.fetch = async () => response({ stats: { memoryCount: 20 } });
    await h.context.renderInspectorMemory('session-a', snapshot('用户偏好:\n- 最新任务偏好'));
    oldJson.resolve({ stats: { memoryCount: 2 } });
    await oldLoad;
    assert.match(h.memory.innerHTML, /最新任务偏好/);
    assert.match(h.memory.innerHTML, /<strong>20<\/strong>/);
    assert.doesNotMatch(h.memory.innerHTML, /过时任务偏好/);
});

await test('clearing the session invalidates in-flight memory renders', async () => {
    const h = harness(), old = deferred();
    h.context.fetch = () => old.promise;
    const loading = h.context.renderInspectorMemory('session-a', snapshot('用户偏好:\n- 旧会话偏好'));
    h.context.currentId = null;
    h.context.fetch = async () => response({});
    await h.context.renderInspectorMemory(null);
    old.resolve(response({ stats: { memoryCount: 1 } }));
    await loading;
    assert.match(h.memory.innerHTML, /选择或新建一个会话/);
    assert.doesNotMatch(h.memory.innerHTML, /旧会话偏好/);
});

await test('statistics failure preserves actual task memory with unknown totals', async () => {
    const h = harness();
    h.context.fetch = async () => ({ ok: false, status: 503 });
    await h.context.renderInspectorMemory('session-a', snapshot('用户偏好:\n- 本次注入仍可显示'));
    assert.match(h.memory.innerHTML, /本次注入仍可显示/);
    assert.equal((h.memory.innerHTML.match(/<strong>—<\/strong>/g) || []).length, 3);
});

await test('terminal requests debounce and refresh the snapshot before rendering memory', async () => {
    const h = harness(), snapshots = [], statsReads = [];
    h.context.renderInspectorSteps = async sid => {
        snapshots.push(sid);
        return snapshot('用户偏好:\n- 终态快照中的实际记忆');
    };
    h.context.renderInspectorStatus = () => assert.fail('terminal refresh must use steps-only');
    h.context.fetch = async url => {
        statsReads.push(url);
        return response({ userStats: { memoryCount: 9 } });
    };
    h.context.scheduleInspectorMemoryRefresh('session-a');
    h.context.scheduleInspectorMemoryRefresh('session-a');
    h.context.scheduleInspectorMemoryRefresh('session-a');
    assert.equal(snapshots.length, 0);
    assert.equal(h.timers.size, 1);
    h.flushTimers();
    await settle();
    assert.deepEqual(snapshots, ['session-a']);
    assert.match(h.memory.innerHTML, /终态快照中的实际记忆/);
    assert.deepEqual(statsReads, ['/api/shell/memory-insights?limit=1']);
    h.flushTimers();
    await settle();
    assert.equal(snapshots.length, 1);
});

await test('tool and RUN_END traces refresh snapshots without repeatedly fetching totals', async () => {
    const h = harness(), snapshots = [], statsReads = [];
    h.context.renderInspectorSteps = async sid => {
        snapshots.push(sid);
        return snapshot('用户偏好:\n- 真实执行快照偏好');
    };
    h.context.renderInspectorStatus = () => assert.fail('trace refresh must use steps-only');
    h.context.fetch = async url => {
        statsReads.push(url);
        return response({ stats: { memoryCount: 10 } });
    };
    h.context.handleInspectorFrame(trace('TOOL_RESULT'), 'session-a');
    h.flushTimers(); h.flushTimers();
    await settle();
    assert.equal(snapshots.length, 1);
    const beforeTerminal = snapshots.length;
    h.context.handleInspectorFrame(trace('RUN_END'), 'session-a');
    h.context.scheduleInspectorMemoryRefresh('session-a');
    h.flushTimers();
    await settle();
    assert.equal(snapshots.length, beforeTerminal + 1);
    h.flushTimers();
    await settle();
    assert.match(h.memory.innerHTML, /真实执行快照偏好/);
    assert.deepEqual(statsReads, ['/api/shell/memory-insights?limit=1']);
    const beforeIdle = snapshots.length;
    h.flushTimers(); h.flushTimers();
    await settle();
    assert.equal(snapshots.length, beforeIdle);
});

await test('closed sidebars, stale sessions and unrelated frames never refresh', async () => {
    const h = harness(), reads = [];
    h.context.renderInspectorSteps = async sid => { reads.push(sid); return snapshot(''); };
    h.context.handleInspectorFrame('event: heartbeat\ndata: {}', 'session-a');
    h.context.handleInspectorFrame('event: trace\ndata: invalid', 'session-a');
    h.context.handleInspectorFrame(trace('RUN_END', 'other'), 'session-a');
    h.context.scheduleInspectorMemoryRefresh('other');
    h.inspector.hidden = true;
    h.context.handleInspectorFrame(trace('RUN_END'), 'session-a');
    h.context.scheduleInspectorMemoryRefresh('session-a');
    h.flushTimers(); h.flushTimers();
    await settle();
    assert.deepEqual(reads, []);
});

await test('closing or switching sessions cancels scheduled terminal and trace refreshes', async () => {
    for (const action of ['close', 'switch']) {
        const h = harness(), reads = [];
        h.context.renderInspectorSteps = async sid => { reads.push(sid); return snapshot(''); };
        h.context.handleInspectorFrame(trace('RUN_END'), 'session-a');
        h.context.scheduleInspectorMemoryRefresh('session-a');
        if (action === 'close') {
            h.inspector.hidden = true;
            h.context.closeInspectorStream();
            assert.equal(h.timers.size, 0);
        } else h.context.currentId = 'session-b';
        h.flushTimers(); h.flushTimers();
        await settle();
        assert.deepEqual(reads, []);
    }
});

console.log(passed + ' inspector memory tests passed');
