import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../mini-agent-app/src/main/resources/static/js/chat/15-sync.js', import.meta.url), 'utf8');
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
function harness() {
    const intervals = new Map(); let timer = 0;
    const effects = { unlock: 0, hint: 0, rendered: 0, reload: 0, streamed: 0, cancel: 0 };
    const context = vm.createContext({
        console: { warn() {} }, currentId: 'A', window: { _msgLoadEpoch: 1 },
        isStreaming: true, taskIsActive: true, taskRunningSessionId: 'A',
        taskPauseRequested: false, taskPausedAtCheckpoint: false, streamEpoch: 1,
        apiJson: r => r.json(), setInterval: fn => { intervals.set(++timer, fn); return timer; },
        clearInterval: id => intervals.delete(id), syncTaskControlButton() {},
        unlockComposer() { effects.unlock++; context.isStreaming = false; },
        setStreaming(on) { context.isStreaming = on; },
        renderBotMsg() { effects.rendered++; return {}; },
        consumeStream: async () => { effects.streamed++; },
        switchConv: async () => { effects.reload++; },
        fetch: async () => ({ ok: true, json: async () => ({ running: false }) })
    });
    vm.runInContext(source, context);
    context.showTaskRunningHint = () => effects.hint++;
    function navigate(sid) { context.currentId = sid; context.window._msgLoadEpoch++; context.taskRunningSessionId = sid; context.taskIsActive = true; context.isStreaming = true; }
    return { context, effects, intervals, navigate };
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
