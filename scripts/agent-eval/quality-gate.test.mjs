import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { generateKeyPairSync, sign } from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { assessRun, createRun, digest, modelIdentityFingerprint, selectCases, validateManifest } from './quality-gate.mjs';
import { parseArguments } from './run-eval.mjs';
import { verifyReport, attestationPayload } from './verify-report.mjs';
import { makeFixtures } from './fixtures.mjs';

const directory = path.dirname(fileURLToPath(import.meta.url));
function fixture(t) {
    const temp = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'miniagent-gate-')));
    t.after(() => fs.rmSync(temp, { recursive: true, force: true }));
    return { ...makeFixtures(path.join(temp, 'artifacts')), temp };
}
const score = f => assessRun(f.manifest, f.captures, f.context);
const failed = (report, code) => {
    assert.equal(report.pass, false);
    assert.ok(report.errors.includes(code) || report.results.some(r => r.checks.some(c => c.code === code)), code);
};

test('synthetic fixtures verify all business validators without claiming live acceptance', t => {
    const report = score(fixture(t));
    assert.equal(report.pass, true);
    assert.equal(report.releaseEligible, false);
    assert.equal(verifyReport(report), true);
    assert.equal(verifyReport(report, { requireLive: true }), false);
});

test('SLO is part of pass even when every business outcome is correct', t => {
    const f = fixture(t); f.captures[0].elapsedMs = f.manifest.cases[0].sloMs + 1;
    failed(score(f), 'SLO_EXCEEDED_OR_MISSING');
});

for (const mode of ['missing', 'duplicate', 'unexpected']) test(`${mode} cases cannot pass`, t => {
    const f = fixture(t);
    if (mode === 'missing') f.captures.pop();
    if (mode === 'duplicate') f.captures.push(f.captures[0]);
    if (mode === 'unexpected') f.captures.push({ caseId: 'not-selected' });
    failed(score(f), { missing: 'MISSING_CASE', duplicate: 'DUPLICATE_CASE', unexpected: 'UNEXPECTED_CASE' }[mode]);
});

test('empty, unknown, duplicate and missing prompt cases fail before any adapter work', t => {
    const f = fixture(t);
    assert.throws(() => validateManifest({ schemaVersion: 1, cases: [] }), /EMPTY_CASE_SET/);
    assert.throws(() => selectCases(f.manifest, { ids: ['not-present'] }), /EMPTY_CASE_SET/);
    assert.throws(() => selectCases(f.manifest, { ids: ['daily-news', 'not-present'] }), /REQUESTED_CASE_NOT_SELECTED/);
    assert.throws(() => selectCases(f.manifest, { category: 'not-present' }), /EMPTY_CASE_SET/);
    assert.throws(() => selectCases(f.manifest, { ids: ['daily-news', 'daily-news'] }), /DUPLICATE_REQUESTED_ID/);
    const copy = structuredClone(f.manifest); copy.cases[0].prompt = '';
    assert.throws(() => validateManifest(copy), /MISSING_PROMPT/);
    copy.cases[0] = structuredClone(f.manifest.cases[0]); copy.cases[0].checks = [{ type: 'response_contains', value: '成功' }];
    assert.throws(() => validateManifest(copy), /UNKNOWN_VALIDATOR/);
});

test('prototype property names cannot bypass the business validator registry', t => {
    const f = fixture(t);
    for (const type of ['toString', 'constructor', '__proto__', 'hasOwnProperty']) {
        const manifest = structuredClone(f.manifest); manifest.cases[0].checks = [{ type }];
        assert.throws(() => validateManifest(manifest), /UNKNOWN_VALIDATOR/);
        const report = score(f);
        report.results[0].checks = report.results[0].checks.map(check =>
            check.type === 'news' ? { type, pass: true } : check);
        assert.equal(verifyReport(report), false);
    }
});

test('an end marker or model success flag cannot replace a business result/collector', t => {
    const f = fixture(t);
    f.captures[0] = { caseId: 'daily-news', runId: f.context.runId, status: 'DONE', terminal: true,
        elapsedMs: 1, passed: true, success: true, answer: '今日新闻已全部查询成功，2026-11-13。' };
    failed(score(f), 'SOURCE_EVIDENCE_REQUIRED');
});

test('daily news uses source publication timestamps and the requested timezone', t => {
    const f = fixture(t);
    assert.equal(score(f).pass, true, '16:30 UTC on prior calendar day is today in Shanghai');
    f.captures[0].observation.newsSources[0].publishedAt = '2026-11-12T12:00:00Z';
    f.captures[0].delivery.news[0].publishedAt = '2026-11-12T12:00:00Z';
    failed(score(f), 'NEWS_OUTSIDE_REQUESTED_DAY');
});

test('relabeling an old article as today cannot hide source evidence', t => {
    const f = fixture(t);
    f.captures[0].observation.newsSources[0].publishedAt = '2026-11-10T10:00:00+08:00';
    failed(score(f), 'NEWS_CITATION_MISMATCH');
});

for (const mode of ['wrong-link', 'duplicate-source', 'missing-source']) test(`news ${mode} is rejected`, t => {
    const f = fixture(t);
    if (mode === 'wrong-link') f.captures[0].delivery.news[0].url = 'https://one.example/unrelated';
    if (mode === 'duplicate-source') f.captures[0].delivery.news[1] = f.captures[0].delivery.news[0];
    if (mode === 'missing-source') f.captures[0].observation.newsSources = [];
    failed(score(f), { 'wrong-link': 'NEWS_CITATION_MISMATCH', 'duplicate-source': 'NEWS_SOURCE_MISSING_OR_DUPLICATE',
        'missing-source': 'SOURCE_EVIDENCE_REQUIRED' }[mode]);
});

test('HTML file existence alone does not prove a delivered usable UI', t => {
    const f = fixture(t); delete f.captures[1].observation.ui;
    failed(score(f), 'UI_RENDER_EVIDENCE_REQUIRED');
});

for (const mode of ['overflow', 'hidden-control', 'broken-action', 'changed-artifact', 'fake-screenshot']) test(`UI ${mode} is rejected`, t => {
    const f = fixture(t), render = f.captures[1].observation.ui;
    if (mode === 'overflow') render.viewports[1].scrollWidth = 1000;
    if (mode === 'hidden-control') render.viewports[1].elements.nav.visible = false;
    if (mode === 'broken-action') render.actions = [];
    if (mode === 'changed-artifact') fs.appendFileSync(path.join(f.context.artifactRoot, 'responsive-ui/index.html'), 'changed');
    if (mode === 'fake-screenshot') {
        const text = Buffer.from('not an image'.repeat(4));
        fs.writeFileSync(path.join(f.context.artifactRoot, 'responsive-ui/390.png'), text);
        render.viewports[1].screenshot.sha256 = digest(text);
    }
    failed(score(f), { overflow: 'UI_HORIZONTAL_OVERFLOW', 'hidden-control': 'UI_REQUIRED_ELEMENT_MISSING',
        'broken-action': 'UI_ACTION_NOT_VERIFIED', 'changed-artifact': 'ARTIFACT_HASH_MISMATCH',
        'fake-screenshot': 'UI_SCREENSHOT_DIMENSIONS' }[mode]);
});

for (const mode of ['no-server-receipt', 'stale-receipt', 'wrong-checkbox', 'wrong-dropdown', 'missing-upload', 'wrong-file', 'wrong-reply']) test(`form ${mode} is rejected`, t => {
    const f = fixture(t), capture = f.captures[2], observation = capture.observation;
    if (mode === 'no-server-receipt') { delete observation.formReceipt; capture.success = true; }
    if (mode === 'stale-receipt') observation.formReceipt.nonce = 'old-nonce';
    if (mode === 'wrong-checkbox') observation.formReceipt.fields.agree = false;
    if (mode === 'wrong-dropdown') observation.fieldReadback.city = '北京';
    if (mode === 'missing-upload') observation.formReceipt.files = [];
    if (mode === 'wrong-file') observation.formReceipt.files[0].sha256 = digest('different');
    if (mode === 'wrong-reply') capture.delivery.receiptId = 'invented';
    failed(score(f), { 'no-server-receipt': 'SERVER_RECEIPT_REQUIRED', 'stale-receipt': 'SERVER_RECEIPT_REQUIRED',
        'wrong-checkbox': 'FORM_SERVER_FIELDS_MISMATCH', 'wrong-dropdown': 'FORM_READBACK_MISMATCH',
        'missing-upload': 'FORM_UPLOAD_MISSING', 'wrong-file': 'FORM_UPLOAD_MISMATCH', 'wrong-reply': 'FORM_REPLY_RECEIPT_MISMATCH' }[mode]);
});

test('new run directories never reuse old files and reject traversal', t => {
    const f = fixture(t);
    createRun(f.temp, 'unique');
    assert.throws(() => createRun(f.temp, 'unique'), /EEXIST/);
    assert.throws(() => createRun(f.temp, '../escape'), /INVALID_RUN_ID/);
    f.captures[1].delivery.artifact.path = '../responsive-ui/index.html';
    failed(score(f), 'ARTIFACT_PATH');
});

test('files from a shared workspace cannot satisfy a fresh case', t => {
    const f = fixture(t);
    fs.renameSync(path.join(f.context.artifactRoot, 'responsive-ui/index.html'), path.join(f.temp, 'old-index.html'));
    f.captures[1].delivery.artifact.path = path.join(f.temp, 'old-index.html');
    failed(score(f), 'ARTIFACT_PATH');
});

test('symlink or junction cannot escape a case artifact directory', t => {
    const f = fixture(t);
    const outside = path.join(f.temp, 'outside'); fs.mkdirSync(outside);
    fs.writeFileSync(path.join(outside, 'old.html'), '<html>old</html>');
    fs.symlinkSync(outside, path.join(f.context.artifactRoot, 'responsive-ui/link'), 'junction');
    f.captures[1].delivery.artifact = { path: 'link/old.html', sha256: digest('<html>old</html>') };
    failed(score(f), 'ARTIFACT_SYMLINK');
});

test('replacing the fresh case root with a junction to old shared artifacts cannot pass', t => {
    const f = fixture(t), original = path.join(f.context.artifactRoot, 'responsive-ui');
    const shared = path.join(f.temp, 'old-shared-artifacts');
    fs.renameSync(original, shared); fs.symlinkSync(shared, original, 'junction');
    failed(score(f), 'ARTIFACT_ROOT_SYMLINK');
});

test('replacing the fresh case root with another real directory cannot pass its creation identity', t => {
    const f = fixture(t), original = path.join(f.context.artifactRoot, 'responsive-ui');
    const before = fs.statSync(original);
    f.context.artifactIdentities = { 'responsive-ui': { dev: String(before.dev), ino: String(before.ino) } };
    const shared = path.join(f.temp, 'old-real-directory'); fs.renameSync(original, shared);
    fs.cpSync(shared, original, { recursive: true });
    failed(score(f), 'ARTIFACT_DIRECTORY_REPLACED');
});

test('run/collector replay cannot pass another case or run', t => {
    const f = fixture(t); f.captures[0].runId = 'old-run';
    failed(score(f), 'INCOMPLETE_OR_WRONG_RUN');
    f.captures[0].runId = f.context.runId; f.captures[0].observation.nonce = 'old-nonce';
    failed(score(f), 'OBSERVATION_BINDING_MISMATCH');
});

test('identity excludes secrets and reports exclude model/provider bodies and form fields', t => {
    const first = { providerId: 'local', modelId: 'Qwen/Qwen-VL', configurationRevision: 'v1', apiKey: 'not-a-real-secret', baseUrl: 'https://private.example' };
    assert.equal(modelIdentityFingerprint(first), modelIdentityFingerprint({ ...first, apiKey: 'changed', baseUrl: 'https://other.example' }));
    assert.notEqual(modelIdentityFingerprint(first), modelIdentityFingerprint({ ...first, configurationRevision: 'v2' }));
    const f = fixture(t); f.captures[0].error = 'Authorization: private-token';
    const text = JSON.stringify(score(f));
    assert.ok(!text.includes('private-token') && !text.includes('测试用户') && !text.includes('https://one.example'));
});

test('verification rejects cached pass when SLO/checks/count/commit disagree', t => {
    const f = fixture(t), good = score(f);
    for (const mutate of [r => r.results[0].elapsedMs = 1e9, r => r.results[0].checks = [],
        r => r.completed = 0, r => r.results[0].checks[0].pass = false]) {
        const report = structuredClone(good); mutate(report); assert.equal(verifyReport(report), false);
    }
    assert.equal(verifyReport(good, { commit: 'different-commit' }), false);
    assert.equal(verifyReport(good, { manifestSha256: digest('different-cases') }), false);
});

test('live runner refuses to import an adapter without explicit paid opt-in', t => {
    const f = fixture(t), marker = path.join(f.temp, 'executed');
    const adapter = path.join(f.temp, 'adapter.mjs');
    fs.writeFileSync(adapter, `import fs from 'node:fs'; fs.writeFileSync(${JSON.stringify(marker)}, 'executed');`);
    const result = spawnSync(process.execPath, [path.join(directory, 'run-eval.mjs'), '--adapter', adapter], { encoding: 'utf8' });
    assert.equal(result.status, 2); assert.equal(fs.existsSync(marker), false);
    assert.throws(() => parseArguments(['--live', '--adapter', adapter]), /ALLOW_PAID/);
});

test('empty or unselected case sets exit nonzero before importing even an opted-in adapter', t => {
    const f = fixture(t), marker = path.join(f.temp, 'executed'), manifest = path.join(f.temp, 'cases.json');
    const adapter = path.join(f.temp, 'adapter.mjs');
    fs.writeFileSync(adapter, `import fs from 'node:fs'; fs.writeFileSync(${JSON.stringify(marker)}, 'executed');`);
    const invoke = args => spawnSync(process.execPath, [path.join(directory, 'run-eval.mjs'), '--live', '--allow-paid',
        '--manifest', manifest, '--adapter', adapter, '--model-identity', path.join(f.temp, 'identity.json'), ...args], { encoding: 'utf8' });
    fs.writeFileSync(manifest, JSON.stringify({ schemaVersion: 1, cases: [] }));
    assert.equal(invoke([]).status, 2);
    fs.writeFileSync(manifest, JSON.stringify(f.manifest));
    assert.equal(invoke(['--ids', 'daily-news,not-present']).status, 2);
    assert.equal(fs.existsSync(marker), false);
});

test('report CLI returns nonzero for empty/missing/failed reports and zero for valid offline fixture', t => {
    const f = fixture(t), file = path.join(f.temp, 'report.json');
    const invoke = args => spawnSync(process.execPath, [path.join(directory, 'verify-report.mjs'), ...args], { encoding: 'utf8' }).status;
    assert.equal(invoke([file]), 2);
    fs.writeFileSync(file, JSON.stringify({ expected: 0, results: [], pass: true })); assert.equal(invoke([file]), 1);
    fs.writeFileSync(file, JSON.stringify(score(f))); assert.equal(invoke([file]), 0);
    assert.equal(invoke([file, '--require-live']), 1);
    f.captures[0].elapsedMs = 999999;
    fs.writeFileSync(file, JSON.stringify(score(f))); assert.equal(invoke([file]), 1);
});

test('runner owns measured SLO and run identity; fixture adapter cannot forge a product pass', t => {
    const f = fixture(t);
    const manifestFile = path.join(f.temp, 'manifest.json');
    const identityFile = path.join(f.temp, 'identity.json');
    const adapterFile = path.join(f.temp, 'fixture-adapter.mjs');
    const output = path.join(f.temp, 'runs');
    const definition = structuredClone(f.manifest);
    definition.cases = [definition.cases[0]];
    definition.cases[0].sloMs = 10;
    fs.writeFileSync(manifestFile, JSON.stringify(definition));
    fs.writeFileSync(identityFile, '\uFEFF' + JSON.stringify({ providerId: 'fixture', modelId: 'fixture/model', configurationRevision: 'v1', apiKey: 'never-report-this' }));
    fs.writeFileSync(adapterFile, `
import path from 'node:path';
import { makeFixtures } from ${JSON.stringify(pathToFileURL(path.join(directory, 'fixtures.mjs')).href)};
import { modelIdentityFingerprint } from ${JSON.stringify(pathToFileURL(path.join(directory, 'quality-gate.mjs')).href)};
export async function createHarness({runId, agentHome, harnessDir, modelIdentity}) {
  const f = makeFixtures(path.join(harnessDir, 'oracle'), runId);
  return {
    mode: 'fixture', agentHome, modelIdentitySha256: modelIdentityFingerprint(modelIdentity),
    async execute() { await new Promise(r => setTimeout(r, 35)); return {...f.captures[0], elapsedMs: 0, runId: 'forged', success: true}; },
    async observe({nonce}) { return {...f.captures[0].observation, nonce}; },
    async close() {}
  };
}`);
    const result = spawnSync(process.execPath, [path.join(directory, 'run-eval.mjs'), '--live', '--allow-paid',
        '--manifest', manifestFile, '--adapter', adapterFile, '--model-identity', identityFile,
        '--output', output, '--run-id', 'fixture-cli'], { encoding: 'utf8', timeout: 20000 });
    assert.equal(result.status, 1, result.stderr);
    const report = JSON.parse(fs.readFileSync(path.join(output, 'fixture-cli/report.json'), 'utf8'));
    assert.equal(report.mode, 'fixture'); assert.equal(report.releaseEligible, false);
    assert.ok(report.results[0].checks.find(c => c.type === 'completion').pass, 'identity owned by runner');
    assert.ok(report.results[0].elapsedMs >= 30);
    failed(report, 'SLO_EXCEEDED_OR_MISSING');
    assert.ok(!JSON.stringify(report).includes('never-report-this'));
});

test('report inspection scripts cannot accidentally invoke live evaluation', () => {
    for (const name of ['verify-run.ps1', 'rescore-run.ps1']) {
        const source = fs.readFileSync(path.join(directory, name), 'utf8');
        assert.ok(!/\.&?\s*["'][^\r\n]*run-prod-eval/.test(source));
        assert.ok(!/Connect-Agent|Invoke-WebRequest|Invoke-RestMethod/.test(source));
    }
});

test('cost and token budgets require independent provider meters, not model estimates', t => {
    const f = fixture(t), definition = f.manifest.cases[0];
    definition.maxTokens = 2000; definition.maxCostMicrousd = 500; definition.pricingRevision = 'price-v1';
    failed(score(f), 'PROVIDER_USAGE_REQUIRED');
    f.captures[0].observation.usage = { source: 'provider-meter', totalTokens: 1500, costMicrousd: 400, pricingRevision: 'price-v1' };
    assert.equal(score(f).pass, true);
    f.captures[0].observation.usage.totalTokens = 2001;
    failed(score(f), 'TOKEN_BUDGET_EXCEEDED_OR_MISSING');
    f.captures[0].observation.usage.totalTokens = 1000;
    f.captures[0].observation.usage.pricingRevision = 'wrong-price';
    failed(score(f), 'COST_BUDGET_EXCEEDED_OR_MISSING');
});

test('handwritten live mode cannot become release evidence without trusted signature and pinned inputs', t => {
    const f = fixture(t), report = score(f), { privateKey, publicKey } = generateKeyPairSync('ed25519');
    report.mode = 'live'; report.releaseEligible = true; report.provenance.targetSha256 = digest('build');
    const pins = { requireLive: true, commit: report.provenance.git.commit, manifestSha256: report.manifestSha256,
        adapterSha256: report.provenance.adapterSha256, targetSha256: report.provenance.targetSha256, trustedPublicKey: publicKey };
    assert.equal(verifyReport(report, pins), false);
    report.attestation = { algorithm: 'ed25519', signature: sign(null, attestationPayload(report), privateKey).toString('base64') };
    assert.equal(verifyReport(report, pins), true);
    assert.equal(verifyReport(report, { ...pins, targetSha256: digest('different-build') }), false);
    assert.equal(verifyReport(report, { ...pins, adapterSha256: digest('different-adapter') }), false);
    assert.equal(verifyReport(report, { ...pins, trustedPublicKey: generateKeyPairSync('ed25519').publicKey }), false);
    assert.equal(verifyReport(report, { ...pins, trustedPublicKey: undefined }), false);
    report.results[0].elapsedMs = 0;
    assert.equal(verifyReport(report, pins), false, 'changing a signed measurement invalidates attestation');
});

test('an adapter ignoring cancellation or blocking synchronously is terminated at the case deadline', t => {
    const f = fixture(t), manifestFile = path.join(f.temp, 'manifest.json'), identityFile = path.join(f.temp, 'identity.json');
    const adapterFile = path.join(f.temp, 'blocking-adapter.mjs'), output = path.join(f.temp, 'runs');
    const manifest = structuredClone(f.manifest); manifest.cases = [manifest.cases[0]];
    manifest.cases[0].sloMs = 50; manifest.cases[0].timeoutMs = 150;
    fs.writeFileSync(manifestFile, JSON.stringify(manifest));
    fs.writeFileSync(identityFile, JSON.stringify({ providerId: 'fixture', modelId: 'fixture', configurationRevision: 'v1' }));
    fs.writeFileSync(adapterFile, `
import { modelIdentityFingerprint } from ${JSON.stringify(pathToFileURL(path.join(directory, 'quality-gate.mjs')).href)};
export async function createHarness({agentHome, modelIdentity}) {
  return {mode:'fixture', agentHome, modelIdentitySha256:modelIdentityFingerprint(modelIdentity),
    async execute(){ while(true){} }, async observe(){}, async close(){ await new Promise(()=>{}); }};
}`);
    const start = Date.now();
    const result = spawnSync(process.execPath, [path.join(directory, 'run-eval.mjs'), '--live', '--allow-paid',
        '--manifest', manifestFile, '--adapter', adapterFile, '--model-identity', identityFile,
        '--output', output, '--run-id', 'blocking-cli'], { encoding: 'utf8', timeout: 15000 });
    assert.equal(result.status, 1, result.stderr);
    assert.ok(Date.now() - start < 10000, 'uncooperative code cannot keep the gate running');
    const report = JSON.parse(fs.readFileSync(path.join(output, 'blocking-cli/report.json'), 'utf8'));
    failed(report, 'INCOMPLETE_OR_WRONG_RUN');
});
