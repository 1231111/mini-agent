#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import { assessRun, createRun, digest, gitProvenance, modelIdentityFingerprint, selectCases } from './quality-gate.mjs';
import { supervisedAdapter } from './supervised-adapter.mjs';
const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));

export function parseArguments(args) {
    const options = {};
    for (let i = 0; i < args.length; i++) {
        const key = args[i];
        if (['--live', '--allow-paid'].includes(key)) { options[key.slice(2)] = true; continue; }
        if (!['--manifest', '--adapter', '--model-identity', '--output', '--run-id', '--ids', '--tier', '--category'].includes(key)
            || !args[i + 1] || args[i + 1].startsWith('--')) throw new Error('INVALID_ARGUMENTS');
        if (options[key.slice(2)] !== undefined) throw new Error('DUPLICATE_ARGUMENT');
        options[key.slice(2)] = args[++i];
    }
    // Check before reading/importing an adapter: offline invocations must never start paid work.
    if (!options.live || !options['allow-paid']) throw new Error('LIVE_EVAL_REQUIRES_EXPLICIT_ALLOW_PAID');
    if (!options.manifest || !options.adapter || !options['model-identity']) throw new Error('MANIFEST_ADAPTER_AND_MODEL_IDENTITY_REQUIRED');
    return options;
}

async function bounded(operation, timeoutMs) {
    const control = new AbortController();
    let timer;
    try {
        return await Promise.race([Promise.resolve().then(() => operation(control.signal)),
            new Promise((_, reject) => { timer = setTimeout(() => {
                control.abort(); reject(new Error('HARNESS_TIMEOUT'));
            }, timeoutMs); })]);
    } finally { clearTimeout(timer); }
}

export async function runEvaluation(options) {
    if (!options.live || !options['allow-paid']) throw new Error('LIVE_EVAL_REQUIRES_EXPLICIT_ALLOW_PAID');
    const manifest = selectCases(readJson(options.manifest), {
        ids: options.ids ? options.ids.split(',').map(s => s.trim()).filter(Boolean) : [],
        tier: options.tier, category: options.category
    });
    const identity = readJson(options['model-identity']);
    const modelIdentitySha256 = modelIdentityFingerprint(identity);
    const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
    const adapterPath = fs.realpathSync(options.adapter);
    const provenance = { git: gitProvenance(repository), modelIdentitySha256,
        adapterSha256: digest(fs.readFileSync(adapterPath)), nodeVersion: process.version };
    const run = createRun(options.output ?? path.join(repository, '.verify', '_agent-eval'), options['run-id']);
    const nonces = {}, captures = [], artifactIdentities = {};
    let harness, worker, mode = 'unverified';
    try {
        worker = supervisedAdapter(run);
        harness = await bounded(signal => worker.call('initialize', { adapterPath, run,
            allowPaid: true,
            cases: manifest.cases,
            modelIdentity: { providerId: identity.providerId, modelId: identity.modelId,
                configurationRevision: identity.configurationRevision } }, signal), 120_000);
        if (!harness || !['live', 'fixture'].includes(harness.mode)
            || harness.modelIdentitySha256 !== modelIdentitySha256
            || fs.realpathSync(harness.agentHome) !== fs.realpathSync(run.agentHome)) throw new Error('ISOLATED_HARNESS_REQUIRED');
        mode = harness.mode;
        if (mode === 'live') {
            if (!harness.targetArtifact || !fs.statSync(harness.targetArtifact).isFile()) throw new Error('TARGET_BUILD_REQUIRED');
            provenance.targetSha256 = digest(fs.readFileSync(harness.targetArtifact));
        }
        for (const definition of manifest.cases) {
            const nonce = crypto.randomBytes(24).toString('hex');
            nonces[definition.id] = nonce;
            const artifactDir = path.join(run.artifactRoot, definition.id);
            fs.mkdirSync(artifactDir, { mode: 0o700 });
            const created = fs.statSync(artifactDir);
            artifactIdentities[definition.id] = { dev: String(created.dev), ino: String(created.ino) };
            const started = performance.now();
            let capture;
            try {
                const output = await bounded(async signal => {
                    // The model receives prompt/output directory, never collector evidence or oracles.
                    const result = await worker.call('execute', { id: definition.id, prompt: definition.prompt,
                        artifactDir, fixtureNonce: nonce }, signal);
                    // Collector reads actual browser/server/source state outside the agent response.
                    const observation = await worker.call('observe', { caseDefinition: definition, result,
                        artifactDir, nonce }, signal);
                    return { result, observation };
                }, definition.timeoutMs);
                capture = { ...output.result, observation: output.observation };
            } catch { capture = { status: 'ERROR', terminal: false, error: 'HARNESS_FAILED' }; }
            // The runner owns identity/duration; adapter or model cannot shorten measured time.
            captures.push({ ...capture, runId: run.runId, caseId: definition.id,
                elapsedMs: performance.now() - started });
            // A timed-out adapter must stop pending work before another case can run.
            if (capture.error) break;
        }
    } catch {
        // Missing cases fail. Raw exceptions can embed auth/provider bodies and must not be printed.
    } finally {
        if (harness && !worker.isStopped()) {
            try { await bounded(signal => worker.call('close', {}, signal), 15_000); }
            catch { captures.push({ caseId: '__harness_cleanup_failed__' }); }
        }
        worker?.stop();
    }
    const report = assessRun(manifest, captures, { ...run, mode, nonces, artifactIdentities, provenance });
    if (gitProvenance(repository).workingTreeSha256 !== provenance.git.workingTreeSha256) {
        report.errors.push('WORKTREE_CHANGED_DURING_RUN'); report.pass = false; report.releaseEligible = false;
    }
    // Whitelisted summaries only: no prompts, source bodies, form fields, SSE logs or credentials.
    fs.writeFileSync(path.join(run.directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
    fs.writeFileSync(path.join(run.directory, 'manifest.sha256'), digest(manifest) + '\n', { mode: 0o600 });
    return { report, reportPath: path.join(run.directory, 'report.json') };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        const { report, reportPath } = await runEvaluation(parseArguments(process.argv.slice(2)));
        console.log(`EVAL ${report.pass ? 'PASS' : 'FAIL'} completed=${report.completed}/${report.expected}`);
        console.log(`REPORT=${reportPath}`);
        process.exitCode = report.pass ? 0 : 1;
    } catch (error) {
        console.error(/^[A-Z_]+$/.test(error?.message) ? error.message : 'EVAL_PREFLIGHT_FAILED');
        process.exitCode = 2;
    }
}
