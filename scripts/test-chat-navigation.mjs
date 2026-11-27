import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../mini-agent-app/src/main/resources/static/js/chat/15-sync.js', import.meta.url), 'utf8');
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
function harness() {
    const intervals = new Map(); let timer = 0;
    const effects = { unlock: 0, hint: 0, rendered: 0, reload: 0, streamed: 0, cancel: 0,
        progressBodies: [], confirmBodies: [], streamedBodies: [] };
    const bodies = [];
    const context = vm.createContext({
        console: { warn() {} }, currentId: 'A', window: { _msgLoadEpoch: 1 },
        isStreaming: true, taskIsActive: true, taskRunningSessionId: 'A',
        taskPauseRequested: false, taskPausedAtCheckpoint: false, streamEpoch: 1,
        document: { querySelectorAll: () => bodies },
        renderTaskProgress(body, items, options) {
            effects.progressBodies.push({ body, items, options });
            body.dataset.taskProgressVersion = String(options.version);
            body._taskProgressSnapshot = { ...options, items };
        },
        syncPendingConfirm(items, options) { effects.confirmBodies.push({ items, options }); },
        apiJson: r => r.json(), setInterval: fn => { intervals.set(++timer, fn); return timer; },
        clearInterval: id => intervals.delete(id), syncTaskControlButton() {},
        unlockComposer() { effects.unlock++; context.isStreaming = false; },
        setStreaming(on) { context.isStreaming = on; },
        renderBotMsg() { effects.rendered++; return {}; },
        consumeStream: async (reader, body) => { effects.streamed++; effects.streamedBodies.push(body); },
        switchConv: async () => { effects.reload++; },
        fetch: async () => ({ ok: true, json: async () => ({ running: false }) })
    });
    vm.runInContext(source, context);
    context.showTaskRunningHint = () => effects.hint++;
    function navigate(sid) { context.currentId = sid; context.window._msgLoadEpoch++; context.taskRunningSessionId = sid; context.taskIsActive = true; context.isStreaming = true; }
    return { context, effects, intervals, navigate, bodies };
}

for (const backToA of [false, true]) {
    const h = harness(), request = deferred(); h.context.fetch = () => request.promise;
    const pending = h.context.reconnectStream('A');
    h.navigate('B'); if (backToA) h.navigate('A');
    request.reject(new Error('late connection failure')); await pending;
    assert.equal(h.effects.unlock, 0); assert.equal(h.effects.hint, 0); assert.equal(h.intervals.size, 0);
    assert.equal(h.context.isStreaming, true);
    console.log('PASS: stale reconnect rejection cannot unlock ' + (backToA ? 'A after A/B/A navigation' : 'B'));
}
{
    const h = harness(), request = deferred(); h.context.fetch = () => request.promise;
    const pending = h.context.checkAndPollTask('A'); h.navigate('B'); h.navigate('A');
    request.resolve({ ok: true, json: async () => ({ running: true, pauseRequested: true }) }); await pending;
    assert.equal(h.context.taskPauseRequested, false); assert.equal(h.effects.rendered, 0);
    console.log('PASS: stale task-status success is ignored after A/B/A navigation');
}
{
    const h = harness(), request = deferred(); h.context.fetch = () => request.promise;
    const pending = h.context.reconnectStream('A'); h.navigate('B'); h.navigate('A');
    request.resolve({ ok: true, body: { cancel: async () => { h.effects.cancel++; } } }); await pending;
    assert.equal(h.effects.cancel, 1); assert.equal(h.effects.rendered, 0); assert.equal(h.effects.unlock, 0);
    console.log('PASS: stale successful SSE attachment closes only its reader');
}
{
    const h = harness(), request = deferred(); h.context.fetch = () => request.promise;
    h.context.startPolling('A'); const oldCallback = [...h.intervals.values()][0]; const pending = oldCallback();
    h.navigate('B'); h.navigate('A'); h.context.startPolling('A');
    request.resolve({ ok: true, json: async () => ({ running: false }) }); await pending;
    assert.equal(h.effects.unlock, 0); assert.equal(h.effects.reload, 0); assert.equal(h.intervals.size, 1);
    console.log('PASS: stale terminal poll cannot unlock or stop the replacement poll');
}
{
    const h = harness(); h.context.startPolling('A'); await [...h.intervals.values()][0]();
    assert.equal(h.effects.unlock, 1); assert.equal(h.effects.reload, 1); assert.equal(h.intervals.size, 0);
    console.log('PASS: current terminal poll still unlocks and reloads its conversation');
}

{
    const h = harness();
    const oldBody = { dataset: { executionId: 'exec-old', taskProgressVersion: '90' }, classList: { add() {} } };
    const currentBody = { dataset: { executionId: 'exec-current', taskProgressVersion: '7' }, classList: { add() {} } };
    h.bodies.push(oldBody, currentBody);
    h.context.fetch = async () => ({ ok: true, json: async () => ({ running: false, progress: {
        executionId: 'exec-current', version: 8, status: 'WAITING', summary: '原任务目标',
        items: [{ id: 1, content: '等待确认', status: 'awaiting_confirm' }]
    } }) });
    await h.context.checkAndPollTask('A');
    assert.equal(h.effects.rendered, 0);
    assert.equal(h.effects.progressBodies.length, 1);
    assert.equal(h.effects.progressBodies[0].body, currentBody);
    assert.equal(h.effects.progressBodies[0].options.allowConfirm, true);
    assert.equal(h.effects.confirmBodies[0].options.botBody, currentBody);
    assert.equal(oldBody.dataset.taskProgressVersion, '90');
    console.log('PASS: refresh activates confirmation only on the server-owned current execution card');
}

{
    const h = harness();
    const originalBody = { dataset: { executionId: 'exec-active', taskProgressVersion: '5' }, classList: { add() {} } };
    h.bodies.push(originalBody);
    const status = { running: true, progress: { executionId: 'exec-active', version: 6, items: [] } };
    h.context.fetch = async () => ({ ok: true, headers: { get: () => 'text/event-stream' },
        body: { getReader: () => ({}) } });
    await h.context.reconnectStream('A', 1, status);
    assert.equal(h.effects.rendered, 0);
    assert.equal(h.effects.streamed, 1);
    assert.equal(h.effects.streamedBodies[0], originalBody);
    assert.equal(originalBody._resumeExistingTask, undefined);
    console.log('PASS: refresh attach resumes the matching execution without creating another assistant card');
}

{
    const h = harness();
    const unrelatedBody = { dataset: { executionId: 'exec-other' }, classList: { add() {} } };
    h.bodies.push(unrelatedBody);
    h.context.fetch = async () => ({ ok: true, headers: { get: () => 'text/event-stream' },
        body: { getReader: () => ({}) } });
    await h.context.reconnectStream('A', 1, { running: true, progress: { executionId: 'exec-new' } });
    assert.equal(h.effects.rendered, 1);
    assert.notEqual(h.effects.streamedBodies[0], unrelatedBody);
    console.log('PASS: attach never reuses an unrelated historical task card');
}

{
    const h = harness();
    const originalBody = { dataset: { executionId: 'exec-active' }, classList: { add() {} } };
    h.bodies.push(originalBody);
    h.context.fetch = async () => ({ ok: true, headers: { get: () => 'application/json' },
        body: { getReader: () => { throw new Error('JSON must not be consumed as SSE'); } } });
    await h.context.reconnectStream('A', 1, { running: true, progress: { executionId: 'exec-active' } });
    assert.equal(h.effects.rendered, 0); assert.equal(h.effects.streamed, 0);
    assert.equal(h.bodies[0], originalBody);
    assert.equal(h.intervals.size, 1);
    console.log('PASS: a non-SSE attach response retains the task card and falls back to status polling');
}
