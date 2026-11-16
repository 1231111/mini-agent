import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

// Exercise persisted-memory rendering and the real terminal trace refresh path.
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

const saved = content => ({ preferencesAvailable: true, userPreferences: [{ content, target: 'user', persisted: true }] });
const deferred = () => {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
};
const response = data => ({ ok: true, data });
const trace = (stepType, sessionId = 'session-a') => `event: trace\ndata: ${JSON.stringify({ sessionId, stepType })}`;
let passed = 0;
async function test(name, run) { await run(); passed++; console.log('PASS: ' + name); }

await test('saved preferences precede old episode summaries and use distinct labels', () => {
    const h = harness();
    const html = h.context.buildInspectorMemory({ ...saved('人物介绍使用真实照片和时间线'),
        saved: true, related: [{ type: 'episode', summary: '搜索今日新闻及近一个月电影' }], stats: { memoryCount: 2 } });
    assert.match(html, /已保存的用户偏好/);
    assert.match(html, /人物介绍使用真实照片和时间线/);
    assert.match(html, /会话经验摘要/);
    assert.ok(html.indexOf('人物介绍使用真实照片和时间线') < html.indexOf('搜索今日新闻'));
    assert.doesNotMatch(html, /本会话已保存到记忆/);
});

await test('an episode or saved flag cannot stand in for a persisted user preference', () => {
    const h = harness();
    const html = h.context.buildInspectorMemory({ saved: true, related: [{ type: 'episode', summary: '助手说已记住' }] });
    assert.match(html, /用户偏好状态未知/);
    assert.match(html, /会话经验摘要/);
    assert.doesNotMatch(html, /已保存的用户偏好/);
    assert.match(h.context.buildInspectorMemory({ preferencesAvailable: true, userPreferences: [] }), /尚未保存用户偏好/);
});

await test('only actual user records with explicit persistence receipts are displayed', () => {
    const h = harness();
    const html = h.context.buildInspectorMemory({ preferencesAvailable: true, userPreferences: [
        { content: 'agent notes must be ignored', target: 'memory', persisted: true },
        { content: 'unconfirmed content must be ignored', target: 'user', persisted: false },
        { content: 'string boolean must be ignored', target: 'user', persisted: 'true' },
        { content: {}, target: 'user', persisted: true },
        { content: '   ', target: 'user', persisted: true },
        null, ...saved('已保存模板').userPreferences
    ] });
    assert.match(html, /已保存模板/);
    assert.doesNotMatch(html, /must be ignored|\[object Object\]|尚未保存/);
});

await test('a failed preference read is unavailable rather than empty or successful', () => {
    const h = harness();
    const html = h.context.buildInspectorMemory({ ...saved('stale untrusted cache'), preferencesAvailable: false });
    assert.match(html, /用户偏好暂时无法读取/);
    assert.doesNotMatch(html, /尚未保存用户偏好|已保存的用户偏好|stale untrusted cache/);
    assert.doesNotThrow(() => h.context.buildInspectorMemory(null));
    assert.doesNotThrow(() => h.context.buildInspectorMemory({ preferencesAvailable: true, userPreferences: {}, related: {} }));
});

await test('long preference content remains expandable and escaped', () => {
    const h = harness();
    const html = h.context.buildInspectorMemory(saved('最初的模板。'.repeat(50) + '\n最近添加的偏好：<img src=x onerror=alert(1)>'));
    assert.match(html, /<details class="insp-memory-more">/);
    assert.match(html, /查看已保存内容/);
    assert.match(html, /最近添加的偏好：&lt;img/);
    assert.doesNotMatch(html, /<img src=x|<details[^>]*\bopen\b/);
});

await test('only the newest three preferences are visible and older entries remain expandable', () => {
    const h = harness();
    const contents = ['最新偏好一', '最新偏好二', '最新偏好三', '旧偏好四'.repeat(60), '旧偏好五'];
    const html = h.context.buildInspectorMemory({ preferencesAvailable: true,
        userPreferences: contents.map(content => ({ content, target: 'user', persisted: true })) });
    const summary = '<summary>查看其余 2 条已保存偏好</summary>';
    assert.match(html, /<details class="insp-memory-more"><summary>查看其余 2 条已保存偏好<\/summary>/);
    const visible = html.slice(0, html.indexOf(summary));
    assert.equal((visible.match(/class="insp-memory-item"/g) || []).length, 3);
    for (const content of contents.slice(0, 3)) assert.ok(visible.includes(content));
    assert.doesNotMatch(visible, /旧偏好四|旧偏好五/);
    assert.ok(html.includes(contents[3]));
    assert.match(html, /旧偏好五/);
    assert.match(html.slice(html.indexOf(summary)), /查看已保存内容/);
    assert.doesNotMatch(html, /<details[^>]*\bopen\b/);
});

for (const outcome of ['success', 'http-error', 'network-error']) {
    await test(`session changes ignore stale ${outcome} responses`, async () => {
        const h = harness(), old = deferred();
        h.context.fetch = () => old.promise;
        const loading = h.context.renderInspectorMemory('session-a');
        h.context.currentId = 'session-b';
        h.context.fetch = async () => response(saved('新会话的用户偏好'));
        await h.context.renderInspectorMemory('session-b');
        if (outcome === 'network-error') old.reject(new Error('old request failed'));
        else old.resolve(outcome === 'http-error' ? { ok: false, status: 503 } : response(saved('旧会话偏好')));
        await loading;
        assert.match(h.memory.innerHTML, /新会话的用户偏好/);
        assert.doesNotMatch(h.memory.innerHTML, /旧会话偏好|HTTP|加载失败/);
    });
}

await test('a newer request wins even when the older same-session JSON arrives last', async () => {
    const h = harness(), oldJson = deferred();
    h.context.fetch = async () => ({ ok: true, data: oldJson.promise });
    const oldLoad = h.context.renderInspectorMemory('session-a');
    await Promise.resolve();
    h.context.fetch = async () => response(saved('后来保存的新偏好'));
    await h.context.renderInspectorMemory('session-a');
    oldJson.resolve(saved('过时偏好'));
    await oldLoad;
    assert.match(h.memory.innerHTML, /后来保存的新偏好/);
    assert.doesNotMatch(h.memory.innerHTML, /过时偏好/);
});

await test('clearing the session invalidates in-flight preference reads', async () => {
    const h = harness(), old = deferred();
    h.context.fetch = () => old.promise;
    const loading = h.context.renderInspectorMemory('session-a');
    h.context.currentId = null;
    await h.context.renderInspectorMemory(null);
    old.resolve(response(saved('旧会话偏好')));
    await loading;
    assert.match(h.memory.innerHTML, /选择或新建一个会话/);
    assert.doesNotMatch(h.memory.innerHTML, /旧会话偏好/);
});

await test('terminal trace events debounce memory reads without polling on tool events', () => {
    const h = harness(), reads = [];
    h.context.renderInspectorMemory = sid => reads.push(sid);
    h.context.refreshInspector = () => {};
    h.context.handleInspectorFrame(trace('TOOL_RESULT'), 'session-a');
    h.flushTimers(); h.flushTimers();
    assert.equal(reads.length, 0);
    h.context.handleInspectorFrame(trace('RUN_END'), 'session-a');
    h.context.scheduleInspectorMemoryRefresh('session-a'); // main chat terminal arrives too
    h.flushTimers();
    assert.deepEqual(reads, ['session-a']);
});

await test('closed sidebars, stale sessions and unrelated frames never refresh memory', () => {
    const h = harness(), reads = [];
    h.context.renderInspectorMemory = sid => reads.push(sid);
    h.context.refreshInspector = () => {};
    h.context.handleInspectorFrame('event: heartbeat\ndata: {}', 'session-a');
    h.context.handleInspectorFrame('event: trace\ndata: invalid', 'session-a');
    h.context.handleInspectorFrame(trace('RUN_END', 'other'), 'session-a');
    h.context.scheduleInspectorMemoryRefresh('other');
    h.inspector.hidden = true;
    h.context.scheduleInspectorMemoryRefresh('session-a');
    h.flushTimers();
    assert.equal(reads.length, 0);
});

await test('closing or switching sessions cancels scheduled preference refreshes', () => {
    for (const action of ['close', 'switch']) {
        const h = harness(), reads = [];
        h.context.renderInspectorMemory = sid => reads.push(sid);
        h.context.scheduleInspectorMemoryRefresh('session-a');
        if (action === 'close') h.context.closeInspectorStream();
        else h.context.currentId = 'session-b';
        h.flushTimers();
        assert.equal(reads.length, 0);
    }
});

console.log(`${passed} inspector memory tests passed`);
