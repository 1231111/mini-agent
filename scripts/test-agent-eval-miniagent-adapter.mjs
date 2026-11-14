import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { readAdapterConfiguration, parseTaskEvents, pickUiArtifact, validateUiCases, createHarness }
    from './agent-eval/miniagent-adapter.mjs';

// These are offline contracts for the built-in adapter. They never start a real
// model, import the operator's settings, or connect to an existing MiniAgent.
const modelIdentity = Object.freeze({ providerId: 'fixture-provider', modelId: 'fixture-model',
    configurationRevision: 'offline-contract-v1' });
const fakeKey = 'fixture-key-never-send-to-a-provider';

function fixture(t) {
    const tempRoot = fs.realpathSync(os.tmpdir());
    const root = fs.mkdtempSync(path.join(tempRoot, 'miniagent-adapter-contract-'));
    const artifactRoot = path.join(root, 'artifacts');
    fs.mkdirSync(artifactRoot);
    const jar = path.join(root, 'miniagent-fixture.jar');
    fs.writeFileSync(jar, 'offline fixture only; must never be launched');
    t.after(() => {
        const absolute = path.resolve(root);
        const relative = path.relative(tempRoot, absolute);
        if (!relative || relative.startsWith('..') || path.isAbsolute(relative)
                || !path.basename(absolute).startsWith('miniagent-adapter-contract-'))
            throw new Error('Unsafe temporary fixture cleanup');
        fs.rmSync(absolute, { recursive: true, force: true });
    });
    return { root, artifactRoot, jar, env: {
        MINI_AGENT_EVAL_JAR: jar,
        MINI_AGENT_EVAL_MODEL_BASE_URL: 'https://model-fixture.example/v1',
        MINI_AGENT_EVAL_MODEL_API_KEY: fakeKey
    } };
}

function assertSafeError(action) {
    assert.throws(action, error => {
        assert.match(String(error.message), /^[A-Z_]+$/, 'errors must use stable, non-sensitive codes');
        assert.ok(!String(error.message).includes(fakeKey), 'model credentials must not leak into errors');
        return true;
    });
}

const frame = (event, data, newline = '\n') => `event: ${event}${newline}`
    + String(typeof data === 'string' ? data : JSON.stringify(data)).split('\n')
        .map(line => `data: ${line}`).join(newline) + newline + newline;
const outcome = (status = 'SUCCESS', extras = {}) => frame('task_outcome', { status, reason: status, ...extras });
const isDone = parsed => parsed?.status === 'DONE' && parsed?.terminal === true;

function uiCase() {
    return { id: 'responsive-ui', category: 'ui', tier: 'smoke',
        prompt: '生成一个包含可用表单和按钮的响应式页面', sloMs: 60000, timeoutMs: 90000,
        checks: [{ type: 'ui-artifact',
            viewports: [{ width: 1280, height: 800 }, { width: 390, height: 844 }],
            requiredSelectors: ['nav', '#action'],
            requiredActions: [{ id: 'activate', action: 'click', selector: '#action',
                observe: { selector: '#status', property: 'textContent' }, expectedState: '完成' }] }] };
}

function assertInvalidUi(change) {
    const definition = uiCase();
    change(definition.checks[0], definition);
    assert.throws(() => validateUiCases([definition]), error => {
        assert.match(error.message, /^EVAL_/, 'invalid contracts must fail with a stable preflight code');
        return true;
    });
}

test('adapter configuration uses only explicit eval settings and the requested model identity', t => {
    const f = fixture(t);
    const config = readAdapterConfiguration(f.env, modelIdentity);
    assert.equal(config.jarPath, fs.realpathSync(f.jar));
    assert.equal(config.baseUrl, f.env.MINI_AGENT_EVAL_MODEL_BASE_URL);
    assert.equal(config.apiKey, fakeKey);
    assert.equal(config.modelId, modelIdentity.modelId);
    assert.equal(config.contextWindowTokens, 32768);
    assert.equal(config.fixtureModel, false, 'live is the explicit production identity, never inferred from successful mocks');
});

for (const missing of ['MINI_AGENT_EVAL_JAR', 'MINI_AGENT_EVAL_MODEL_BASE_URL', 'MINI_AGENT_EVAL_MODEL_API_KEY']) {
    test(`adapter refuses missing ${missing} instead of using the operator's defaults`, t => {
        const f = fixture(t);
        delete f.env[missing];
        // Familiar application variables are deliberately insufficient authorization/configuration.
        Object.assign(f.env, { MINI_AGENT_HOME: f.root, LLM_API_KEY: fakeKey,
            LLM_BASE_URL: 'https://operator-settings.example/v1', LLM_MODEL: modelIdentity.modelId });
        assertSafeError(() => readAdapterConfiguration(f.env, modelIdentity));
    });
}

test('adapter validates the model identity before launching any runtime', t => {
    const f = fixture(t);
    for (const identity of [null, {}, { ...modelIdentity, modelId: '' },
        { ...modelIdentity, modelId: ' \n ' }])
        assertSafeError(() => readAdapterConfiguration(f.env, identity));
});

test('provider configuration rejects credential URLs and executable protocols without exposing values', t => {
    const f = fixture(t);
    for (const base of ['file:///C:/private/model', 'javascript:alert(1)',
        'https://user:password@model-fixture.example/v1', 'https://model-fixture.example/v1\nmalformed'])
        assertSafeError(() => readAdapterConfiguration({ ...f.env, MINI_AGENT_EVAL_MODEL_BASE_URL: base }, modelIdentity));
});

test('adapter context limits are bounded integers rather than silently parsed prefixes', t => {
    const f = fixture(t);
    for (const value of ['0', '1023', '10000001', '-1', '1.5', '32768tokens', 'NaN', 'Infinity'])
        assertSafeError(() => readAdapterConfiguration({ ...f.env, MINI_AGENT_EVAL_CONTEXT_TOKENS: value }, modelIdentity));
    for (const value of ['1024', '65536', '10000000'])
        assert.equal(readAdapterConfiguration({ ...f.env, MINI_AGENT_EVAL_CONTEXT_TOKENS: value }, modelIdentity)
            .contextWindowTokens, Number(value));
});

test('a fixture model is explicitly labeled and cannot claim a live model evaluation', t => {
    const f = fixture(t);
    assert.equal(readAdapterConfiguration({ ...f.env, MINI_AGENT_EVAL_MODEL_MODE: 'fixture' }, modelIdentity)
        .fixtureModel, true);
    assert.equal(readAdapterConfiguration({ ...f.env, MINI_AGENT_EVAL_MODEL_MODE: 'live' }, modelIdentity)
        .fixtureModel, false);
    assertSafeError(() => readAdapterConfiguration({ ...f.env, MINI_AGENT_EVAL_MODEL_MODE: 'pretend-live' }, modelIdentity));
});

test('createHarness rejects missing paid-work authorization before using environment or touching a home', async t => {
    const f = fixture(t);
    const agentHome = path.join(f.root, 'not-created-home');
    const harnessDir = path.join(f.root, 'not-created-harness');
    await assert.rejects(createHarness({ agentHome, artifactRoot: f.artifactRoot, harnessDir,
        modelIdentity, allowPaid: false, signal: new AbortController().signal }), error => {
        assert.match(error.message, /^[A-Z_]+$/);
        assert.ok(!error.message.includes(fakeKey));
        return true;
    });
    assert.equal(fs.existsSync(agentHome), false);
    assert.equal(fs.existsSync(harnessDir), false);
});

test('unsupported business checks are rejected before configuration or runtime directories are accessed', async t => {
    const f = fixture(t);
    const agentHome = path.join(f.root, 'not-created-home');
    const harnessDir = path.join(f.root, 'not-created-harness');
    await assert.rejects(createHarness({ agentHome, artifactRoot: f.artifactRoot, harnessDir,
        modelIdentity, allowPaid: true, signal: new AbortController().signal,
        cases: [{ id: 'news', category: 'news', checks: [{ type: 'news-freshness' }] }] }),
    { message: 'EVAL_UNSUPPORTED_BUSINESS_CHECK' });
    assert.equal(fs.existsSync(agentHome), false);
    assert.equal(fs.existsSync(harnessDir), false);
});

test('UI preflight accepts responsive contracts with click, fill, select and check observations', () => {
    const definition = uiCase();
    definition.checks[0].requiredActions = [
        { id: 'click', action: 'click', selector: '#submit',
            observe: { selector: '#status', property: 'textContent' }, expectedState: '已提交' },
        { id: 'fill', action: 'fill', selector: '#name', value: '测试用户',
            observe: { selector: '#name', property: 'value' }, expectedState: '测试用户' },
        { id: 'select', action: 'select', selector: '#plan', value: 'pro',
            observe: { selector: '#details', property: 'visible' }, expectedState: 'true' },
        { id: 'check', action: 'check', selector: '#consent', value: true,
            observe: { selector: '#consent', property: 'checked' }, expectedState: 'true' }
    ];
    assert.doesNotThrow(() => validateUiCases([definition]));
    definition.checks[0].requiredActions[1].value = '';
    definition.checks[0].requiredActions[1].expectedState = '';
    definition.checks[0].requiredActions[3].value = false;
    definition.checks[0].requiredActions[3].expectedState = 'false';
    assert.doesNotThrow(() => validateUiCases([definition]), 'clearing text and unchecking remain valid operations');
});

test('UI preflight requires exactly one supported check for every requested case', () => {
    for (const cases of [undefined, null, [], [null], [{}], [{ checks: [] }],
        [{ checks: [{ type: 'news-freshness' }] }],
        [{ checks: [...uiCase().checks, ...uiCase().checks] }],
        [uiCase(), { checks: [{ type: 'news-freshness' }] }]])
        assert.throws(() => validateUiCases(cases), { message: 'EVAL_UNSUPPORTED_BUSINESS_CHECK' });
});

test('UI preflight rejects missing, duplicate, excessive and malformed viewports', () => {
    for (const viewports of [undefined, [], [{ width: 1280, height: 800 }],
        [{ width: 390, height: 844 }, { width: 390, height: 844 }],
        Array.from({ length: 5 }, (_, i) => ({ width: 300 + i, height: 600 })),
        [null, { width: 390, height: 844 }], [{}, { width: 390, height: 844 }]])
        assertInvalidUi(check => { check.viewports = viewports; });
});

test('UI preflight enforces integer viewport dimensions and the pixel area bound', () => {
    for (const viewport of [{ width: 239, height: 800 }, { width: 2561, height: 800 },
        { width: 1280, height: 239 }, { width: 1280, height: 2161 },
        { width: 2000, height: 2001 }, { width: 390.5, height: 844 },
        { width: 390, height: Infinity }, { width: '1280', height: 800 }])
        assertInvalidUi(check => { check.viewports[0] = viewport; });
    const definition = uiCase();
    definition.checks[0].viewports = [{ width: 240, height: 240 }, { width: 2560, height: 240 },
        { width: 240, height: 2160 }, { width: 2000, height: 2000 }];
    assert.doesNotThrow(() => validateUiCases([definition]));
});

test('UI preflight requires bounded, nonblank selectors without control characters', () => {
    for (const selectors of [undefined, [], Array(33).fill('#field'), [null], [''], ['   '],
        ['#field\nbutton'], ['x'.repeat(513)]])
        assertInvalidUi(check => { check.requiredSelectors = selectors; });
    const definition = uiCase();
    definition.checks[0].requiredSelectors = Array.from({ length: 32 }, (_, i) => `#field-${i}`);
    assert.doesNotThrow(() => validateUiCases([definition]));
});

test('production UI checks require at least one observable action and at most sixteen', () => {
    for (const actions of [undefined, [], [null], Array.from({ length: 17 }, (_, i) => ({
        ...uiCase().checks[0].requiredActions[0], id: `action-${i}` }))])
        assertInvalidUi(check => { check.requiredActions = actions; });
    const definition = uiCase();
    definition.checks[0].requiredActions = Array.from({ length: 16 }, (_, i) => ({
        ...definition.checks[0].requiredActions[0], id: `action-${i}` }));
    assert.doesNotThrow(() => validateUiCases([definition]));
});

test('UI preflight rejects duplicate or invalid action identities', () => {
    assertInvalidUi(check => { check.requiredActions.push({ ...check.requiredActions[0] }); });
    for (const id of [undefined, '', '   ', 'bad id', '_bad', 'x'.repeat(81)])
        assertInvalidUi(check => { check.requiredActions[0].id = id; });
    const definition = uiCase();
    definition.checks[0].requiredActions[0].id = 'a'.repeat(80);
    assert.doesNotThrow(() => validateUiCases([definition]));
});

test('UI actions require a supported operation and valid action and observation selectors', () => {
    for (const action of [undefined, '', 'execute', 'hover'])
        assertInvalidUi(check => { check.requiredActions[0].action = action; });
    for (const selector of [undefined, null, '', '   ', 'x'.repeat(513), '#field\u0000']) {
        assertInvalidUi(check => { check.requiredActions[0].selector = selector; });
        assertInvalidUi(check => { check.requiredActions[0].observe.selector = selector; });
    }
    for (const observe of [undefined, null, {}])
        assertInvalidUi(check => { check.requiredActions[0].observe = observe; });
});

test('UI action values follow the operation type and never silently coerce strings to checkbox state', () => {
    for (const action of ['fill', 'select'])
        for (const value of [undefined, null, true, 1, {}, 'x'.repeat(4097)])
            assertInvalidUi(check => { Object.assign(check.requiredActions[0], { action, value }); });
    for (const value of [undefined, null, 'true', 'false', 1, 0])
        assertInvalidUi(check => { Object.assign(check.requiredActions[0], { action: 'check', value }); });
});

test('UI actions require an expected state and a supported observation property', () => {
    for (const expectedState of [undefined, null, true, 1, {}, 'x'.repeat(4097)])
        assertInvalidUi(check => { check.requiredActions[0].expectedState = expectedState; });
    for (const property of [undefined, null, '', 'innerHTML', 'text', 'CHECKED'])
        assertInvalidUi(check => { check.requiredActions[0].observe.property = property; });
});

test('an explicit successful task outcome and an end frame produce a completed result', () => {
    const parsed = parseTaskEvents(frame('token', '可验证的结果')
        + outcome('SUCCESS', { executionId: 'execution-one' }) + frame('end', '可验证的结果'));
    assert.equal(isDone(parsed), true);
    assert.equal(parsed.answer, '可验证的结果');
    assert.equal(parsed.executionId, 'execution-one');
});

test('transport end, natural language success, and arbitrary JSON never prove task completion', () => {
    for (const sse of [frame('end', '任务已成功完成'), frame('token', 'SUCCESS：全部完成'),
        'data: {"status":"SUCCESS"}\n\n' + frame('end', '完成'),
        frame('thinking', '{"status":"SUCCESS"}') + frame('end', '完成')])
        assert.equal(isDone(parseTaskEvents(sse)), false);
});

test('a successful outcome without an end frame is incomplete', () => {
    assert.equal(isDone(parseTaskEvents(outcome() + frame('token', '尚未封口'))), false);
});

test('failure, waiting and cancellation remain unsuccessful even when an end frame follows', () => {
    for (const status of ['FAILURE', 'WAITING', 'CANCELED', 'CANCELLED', 'INCOMPLETE'])
        assert.equal(isDone(parseTaskEvents(outcome(status) + frame('end', '模型说完成'))), false);
    assert.equal(parseTaskEvents(outcome('WAITING', { reason: 'USER_QUESTION' }) + frame('end', '请补充'))
        .status, 'WAITING');
});

test('error frames prevent a previously reported success from becoming a passing evaluation', () => {
    const parsed = parseTaskEvents(outcome() + frame('error', 'provider body must not become a reason code')
        + frame('end', '部分回答'));
    assert.equal(isDone(parsed), false);
    assert.match(parsed.reasonCode, /^[A-Z_]+$/);
    assert.doesNotMatch(parsed.reasonCode, /provider body/);
});

test('conflicting runtime outcomes cannot be hidden behind a later success marker', () => {
    assert.equal(isDone(parseTaskEvents(outcome('FAILURE') + outcome('SUCCESS') + frame('end', '完成'))), false);
    assert.equal(isDone(parseTaskEvents(outcome('SUCCESS') + outcome('FAILURE') + frame('end', '完成'))), false);
});

test('a sealed draft is discarded while the incomplete next answer remains uncompleted', () => {
    const parsed = parseTaskEvents(frame('token', '这是会被工具调用撤销的草稿') + frame('seal', '')
        + frame('token', '实际最终结果尚在生成'));
    assert.equal(parsed.answer, '实际最终结果尚在生成');
    assert.equal(isDone(parsed), false);
    assert.equal(parsed.terminal, false);
});

test('oversized event streams fail with a bounded code rather than retaining arbitrary provider output', () => {
    assertSafeError(() => parseTaskEvents('x'.repeat(4 * 1024 * 1024 + 1)));
});

test('SSE parser handles CRLF, comments and multiline data without reading event-like answer text as controls', () => {
    const text = ': heartbeat\r\n\r\n' + frame('token', '第一行\n第二行', '\r\n')
        + frame('task_outcome', { status: 'SUCCESS', reason: 'SUCCESS' }, '\r\n')
        + frame('end', '第一行\n第二行', '\r\n');
    const parsed = parseTaskEvents(text);
    assert.equal(isDone(parsed), true);
    assert.equal(parsed.answer, '第一行\n第二行');
    assert.equal(isDone(parseTaskEvents(frame('token', 'event: task_outcome\ndata: {"status":"SUCCESS"}')
        + frame('end', '未提供真实终态'))), false);
});

test('malformed outcomes cannot turn an interrupted or failed stream into success', () => {
    for (const body of ['{', 'null', '[]', '"SUCCESS"', '{"status":true}', '{"status":"SUCCESS"} trailing']) {
        const sse = frame('task_outcome', body) + frame('end', '回答');
        try { assert.equal(isDone(parseTaskEvents(sse)), false); }
        catch (error) { assert.match(error.message, /^[A-Z_]+$/); }
    }
});

test('artifact selection hashes the exact declared HTML file rather than another same-named file', t => {
    const f = fixture(t);
    const nested = path.join(f.artifactRoot, 'pages'); fs.mkdirSync(nested);
    const html = '<!doctype html><html><body>独立产物</body></html>';
    fs.writeFileSync(path.join(nested, 'index.html'), html);
    fs.writeFileSync(path.join(f.artifactRoot, 'index.html'), 'an unrelated root file');
    const selected = pickUiArtifact(f.artifactRoot, 'pages/index.html');
    assert.equal(selected.file, fs.realpathSync(path.join(nested, 'index.html')));
    assert.equal(selected.path.replaceAll('\\', '/'), 'pages/index.html');
    assert.equal(selected.sha256, crypto.createHash('sha256').update(html).digest('hex'));
});

test('artifact selection cannot escape a case via traversal, absolute paths or missing files', t => {
    const f = fixture(t);
    const outside = path.join(f.root, 'outside.html'); fs.writeFileSync(outside, 'must not be inspected');
    for (const relative of ['../outside.html', '..\\outside.html', 'pages/../../outside.html', outside,
        'C:\\outside.html', '/outside.html', 'missing/index.html', '', null])
        assertSafeError(() => pickUiArtifact(f.artifactRoot, relative));
});

test('HTML artifacts must be regular bounded HTML files', t => {
    const f = fixture(t);
    fs.writeFileSync(path.join(f.artifactRoot, 'not-html.txt'), '<html>wrong extension</html>');
    fs.mkdirSync(path.join(f.artifactRoot, 'directory.html'));
    fs.writeFileSync(path.join(f.artifactRoot, 'oversized.html'), Buffer.alloc(5 * 1024 * 1024 + 1, 32));
    for (const relative of ['not-html.txt', 'directory.html', 'oversized.html'])
        assertSafeError(() => pickUiArtifact(f.artifactRoot, relative));
});

test('directory links cannot disguise an out-of-case HTML file or an aliased artifact root', t => {
    const f = fixture(t);
    const outside = path.join(f.root, 'other-case'); fs.mkdirSync(outside);
    fs.writeFileSync(path.join(outside, 'index.html'), '<html>belongs to another case</html>');
    const alias = path.join(f.artifactRoot, 'linked-case');
    fs.symlinkSync(outside, alias, process.platform === 'win32' ? 'junction' : 'dir');
    assertSafeError(() => pickUiArtifact(f.artifactRoot, 'linked-case/index.html'));
    const rootAlias = path.join(f.root, 'artifact-root-alias');
    fs.writeFileSync(path.join(f.artifactRoot, 'index.html'), '<html>valid file in the real root</html>');
    fs.symlinkSync(f.artifactRoot, rootAlias, process.platform === 'win32' ? 'junction' : 'dir');
    assertSafeError(() => pickUiArtifact(rootAlias, 'index.html'));
});

if (process.platform !== 'win32') test('a file symlink is not accepted even when its target is inside the same case', t => {
    const f = fixture(t);
    fs.writeFileSync(path.join(f.artifactRoot, 'actual.html'), '<html>actual</html>');
    fs.symlinkSync(path.join(f.artifactRoot, 'actual.html'), path.join(f.artifactRoot, 'alias.html'));
    assertSafeError(() => pickUiArtifact(f.artifactRoot, 'alias.html'));
});
