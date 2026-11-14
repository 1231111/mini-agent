import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { artifactPath, digest, modelIdentityFingerprint } from './quality-gate.mjs';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const demand = (condition, code) => { if (!condition) throw new Error(code); };
const MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

/** No user config, running desktop, database, credentials file or implicit provider fallback. */
export function readAdapterConfiguration(env, identity) {
    modelIdentityFingerprint(identity);
    demand(typeof env.MINI_AGENT_EVAL_JAR === 'string' && env.MINI_AGENT_EVAL_JAR.trim(), 'EVAL_JAR_REQUIRED');
    let jarPath;
    try { jarPath = fs.realpathSync(env.MINI_AGENT_EVAL_JAR); demand(fs.statSync(jarPath).isFile(), 'EVAL_TARGET_NOT_FILE'); }
    catch { throw new Error('EVAL_TARGET_NOT_FILE'); }
    let endpoint;
    try { endpoint = new URL(env.MINI_AGENT_EVAL_MODEL_BASE_URL); } catch { throw new Error('EVAL_INVALID_MODEL_BASE_URL'); }
    demand(['http:', 'https:'].includes(endpoint.protocol) && !endpoint.username && !endpoint.password
        && !endpoint.search && !endpoint.hash && !/[\x00-\x20\\]/.test(env.MINI_AGENT_EVAL_MODEL_BASE_URL), 'EVAL_INVALID_MODEL_BASE_URL');
    demand(typeof env.MINI_AGENT_EVAL_MODEL_API_KEY === 'string' && env.MINI_AGENT_EVAL_MODEL_API_KEY.trim(), 'EVAL_MODEL_KEY_REQUIRED');
    const contextWindowTokens = Number(env.MINI_AGENT_EVAL_CONTEXT_TOKENS ?? 32768);
    demand(Number.isSafeInteger(contextWindowTokens) && contextWindowTokens >= 1024 && contextWindowTokens <= 10000000,
        'EVAL_CONTEXT_WINDOW_INVALID');
    demand(env.MINI_AGENT_EVAL_MODEL_MODE === undefined || ['live', 'fixture'].includes(env.MINI_AGENT_EVAL_MODEL_MODE), 'EVAL_MODEL_MODE_INVALID');
    const bundledJava = path.join(repository, 'mini-agent-desktop/jre/bin', process.platform === 'win32' ? 'java.exe' : 'java');
    const javaPath = env.MINI_AGENT_EVAL_JAVA || (fs.existsSync(bundledJava) ? bundledJava : 'java');
    if (env.MINI_AGENT_EVAL_JAVA) demand(path.isAbsolute(javaPath) && fs.existsSync(javaPath) && fs.statSync(javaPath).isFile(), 'EVAL_JAVA_NOT_FOUND');
    return { jarPath, javaPath, baseUrl: endpoint.href.replace(/\/$/, ''), apiKey: env.MINI_AGENT_EVAL_MODEL_API_KEY,
        modelId: identity.modelId, contextWindowTokens,
        browserPath: env.PLAYWRIGHT_BROWSERS_PATH || path.join(repository, 'mini-agent-desktop/browsers'),
        fixtureModel: env.MINI_AGENT_EVAL_MODEL_MODE === 'fixture' };
}

/** Completion comes from the runtime outcome AND terminal frame, never answer text or token exhaustion. */
export function parseTaskEvents(text) {
    demand(typeof text === 'string' && Buffer.byteLength(text) <= MAX_RESPONSE_BYTES, 'EVAL_STREAM_TOO_LARGE');
    let answer = '', outcome, ended = false, failed = false;
    for (const block of text.replace(/\r\n?/g, '\n').split('\n\n')) {
        let event = 'message'; const data = [];
        for (const line of block.split('\n')) {
            if (line.startsWith('event:')) event = line.slice(6).trim();
            if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
        }
        const value = data.join('\n');
        if (event === 'seal') answer = '';
        if (event === 'token') answer += value;
        if (event === 'task_outcome') {
            try {
                const next = JSON.parse(value);
                if (!next || typeof next.status !== 'string' || (outcome && JSON.stringify(outcome) !== JSON.stringify(next))) failed = true;
                outcome = next;
            } catch { failed = true; }
        }
        if (event === 'end') { ended = true; answer = value; }
        if (event === 'error') failed = true;
    }
    const success = !failed && ended && outcome?.status === 'SUCCESS';
    return { status: success ? 'DONE' : !failed && outcome?.status === 'WAITING' ? 'WAITING' : 'ERROR',
        terminal: ended || failed, answer,
        executionId: typeof outcome?.executionId === 'string' ? outcome.executionId : '',
        reasonCode: success ? 'SUCCESS' : failed ? 'STREAM_FAILED' : ended ? 'NON_SUCCESS_OUTCOME' : 'INCOMPLETE_STREAM' };
}

export function pickUiArtifact(root, relative) {
    try {
        const file = artifactPath(root, relative);
        demand(/\.html?$/i.test(relative) && fs.statSync(file).size <= 5 * 1024 * 1024, 'EVAL_UI_ARTIFACT_REQUIRED');
        return { file, path: relative, sha256: digest(fs.readFileSync(file)) };
    } catch { throw new Error('EVAL_UI_ARTIFACT_REQUIRED'); }
}

/** Fail before starting a provider for business checks this adapter cannot observe. */
export function validateUiCases(cases) {
    demand(Array.isArray(cases) && cases.length > 0, 'EVAL_UNSUPPORTED_BUSINESS_CHECK');
    const text = (value, limit) => typeof value === 'string' && value.trim().length > 0
        && value.length <= limit && !/[\x00-\x1f]/.test(value);
    for (const definition of cases) {
        demand(Array.isArray(definition?.checks) && definition.checks.length === 1
            && definition.checks[0]?.type === 'ui-artifact', 'EVAL_UNSUPPORTED_BUSINESS_CHECK');
        const check = definition.checks[0];
        demand(Array.isArray(check.viewports) && check.viewports.length >= 2 && check.viewports.length <= 4,
            'EVAL_INVALID_UI_CONTRACT');
        const sizes = new Set();
        for (const viewport of check.viewports) {
            demand(viewport && typeof viewport === 'object', 'EVAL_INVALID_UI_CONTRACT');
            const { width, height } = viewport;
            demand(Number.isSafeInteger(width) && width >= 240 && width <= 2560
                && Number.isSafeInteger(height) && height >= 240 && height <= 2160
                && width * height <= 4000000 && !sizes.has(`${width}x${height}`), 'EVAL_INVALID_UI_CONTRACT');
            sizes.add(`${width}x${height}`);
        }
        demand(Array.isArray(check.requiredSelectors) && check.requiredSelectors.length > 0
            && check.requiredSelectors.length <= 32 && check.requiredSelectors.every(value => text(value, 512)), 'EVAL_INVALID_UI_CONTRACT');
        demand(Array.isArray(check.requiredActions) && check.requiredActions.length > 0
            && check.requiredActions.length <= 16, 'EVAL_INVALID_UI_CONTRACT');
        const ids = new Set();
        for (const action of check.requiredActions) {
            demand(text(action?.id, 80) && /^[A-Za-z0-9][A-Za-z0-9_.-]*$/.test(action.id) && !ids.has(action.id)
                && ['click', 'fill', 'select', 'check'].includes(action.action) && text(action.selector, 512)
                && text(action.observe?.selector, 512) && ['textContent', 'value', 'checked', 'visible'].includes(action.observe.property)
                && typeof action.expectedState === 'string' && action.expectedState.length <= 4096
                && (action.action === 'click' || (action.action === 'check' ? typeof action.value === 'boolean'
                    : typeof action.value === 'string' && action.value.length <= 4096)), 'EVAL_INVALID_UI_CONTRACT');
            ids.add(action.id);
        }
    }
}

function isolatedEnvironment(extra) {
    const allowed = new Set(['path', 'systemroot', 'windir', 'temp', 'tmp', 'java_home', 'lang', 'lc_all']);
    return { ...Object.fromEntries(Object.entries(process.env).filter(([key]) => allowed.has(key.toLowerCase()))), ...extra };
}

async function unusedPort() {
    const server = net.createServer();
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
    const port = server.address().port;
    await new Promise(resolve => server.close(resolve));
    return port;
}

async function responseText(response, signal) {
    demand(response.ok, 'EVAL_HTTP_FAILED');
    const reader = response.body.getReader(); const chunks = []; let bytes = 0;
    try {
        while (true) {
            signal?.throwIfAborted();
            const next = await reader.read(); if (next.done) break;
            bytes += next.value.byteLength;
            demand(bytes <= MAX_RESPONSE_BYTES, 'EVAL_RESPONSE_TOO_LARGE');
            chunks.push(Buffer.from(next.value));
        }
        return Buffer.concat(chunks).toString('utf8');
    } finally { try { await reader.cancel(); } catch { /* Request already stopped. */ } }
}

async function stopProcess(child) {
    if (!child?.pid || child.exitCode !== null || child.signalCode !== null) return;
    const ended = new Promise(resolve => child.once('exit', resolve));
    if (process.platform === 'win32') {
        const killer = spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'],
            { windowsHide: true, shell: false, stdio: 'ignore' });
        await new Promise(resolve => {
            const timer = setTimeout(() => { killer.kill(); resolve(); }, 5000);
            const finish = () => { clearTimeout(timer); resolve(); };
            killer.once('exit', finish); killer.once('error', finish);
        });
    } else child.kill('SIGTERM');
    await Promise.race([ended, delay(3000)]);
    if (child.exitCode === null && child.signalCode === null) {
        child.kill('SIGKILL');
        await Promise.race([ended, delay(1000)]);
    }
    demand(child.exitCode !== null || child.signalCode !== null, 'EVAL_PROCESS_CLEANUP_FAILED');
}

async function collectUi(configuration, requestFile, signal) {
    const child = spawn(configuration.javaPath, ['-Dloader.main=com.miniagent.evaluation.ArtifactUiCollector',
        '-cp', configuration.jarPath, 'org.springframework.boot.loader.launch.PropertiesLauncher', requestFile],
    { env: isolatedEnvironment({ PLAYWRIGHT_BROWSERS_PATH: configuration.browserPath, PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD: '1' }),
        stdio: ['ignore', 'pipe', 'ignore'], windowsHide: true, shell: false });
    const chunks = []; let bytes = 0, tooLarge = false, timedOut = false;
    const completion = new Promise((resolve, reject) => { child.once('exit', resolve); child.once('error', reject); });
    const abort = () => { void stopProcess(child).catch(() => {}); };
    const timer = setTimeout(() => { timedOut = true; abort(); }, 60000);
    child.stdout.on('data', chunk => { bytes += chunk.length;
        if (bytes > MAX_RESPONSE_BYTES) { tooLarge = true; abort(); } else chunks.push(chunk); });
    signal?.addEventListener('abort', abort, { once: true });
    try {
        signal?.throwIfAborted();
        const code = await completion;
        demand(code === 0 && !tooLarge && !timedOut, 'EVAL_UI_COLLECTOR_FAILED');
        try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new Error('EVAL_UI_COLLECTOR_INVALID'); }
    } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); await stopProcess(child); }
}

/** A real, newly launched MiniAgent backend. No existing instance or user's credentials are consulted. */
export async function createHarness({ agentHome, artifactRoot, harnessDir, modelIdentity, allowPaid, cases, signal }) {
    demand(allowPaid === true, 'LIVE_EVAL_REQUIRES_EXPLICIT_ALLOW_PAID');
    validateUiCases(cases);
    const configuration = readAdapterConfiguration(process.env, modelIdentity);
    const home = fs.realpathSync(agentHome), artifacts = fs.realpathSync(artifactRoot), evidence = fs.realpathSync(harnessDir);
    demand(fs.readdirSync(home).length === 0 && !fs.lstatSync(agentHome).isSymbolicLink()
        && !fs.lstatSync(artifactRoot).isSymbolicLink() && !fs.lstatSync(harnessDir).isSymbolicLink(), 'EVAL_FRESH_HOME_REQUIRED');
    const userHome = path.join(home, 'runtime-home'); fs.mkdirSync(userHome);
    const port = await unusedPort();
    const base = `http://127.0.0.1:${port}`;
    const env = isolatedEnvironment({ MINI_AGENT_HOME: home, HOME: userHome, USERPROFILE: userHome,
        JWT_SECRET: crypto.randomBytes(48).toString('base64'), MODEL_CONFIG_ENCRYPTION_KEY: crypto.randomBytes(32).toString('base64'),
        LLM_API_KEY: configuration.apiKey, LLM_BASE_URL: configuration.baseUrl, LLM_MODEL: configuration.modelId,
        PLAYWRIGHT_BROWSERS_PATH: configuration.browserPath, PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD: '1',
        CLOUD_AUTH_BASE_URL: '', MEMORY_SERVICE_BASE_URL: '', MINI_AGENT_MODELS_DIR: path.join(home, 'models') });
    const child = spawn(configuration.javaPath, ['-Xmx768m', `-Duser.home=${userHome}`, '-jar', configuration.jarPath,
        '--spring.profiles.active=desktop', '--spring.config.import=', '--server.address=127.0.0.1', `--server.port=${port}`,
        '--agent.auth.cloud.base-url=', '--agent.auth.registration-enabled=true', '--agent.desktop.enabled=false',
        '--agent.tools.exec-policy=block', '--agent.mcp.enabled=false', '--agent.memory.vector.enabled=false',
        '--agent.codebase.embedding-enabled=false', `--agent.context.max-tokens=${configuration.contextWindowTokens}`,
        `--agent.models.presets[0].context-window-tokens=${configuration.contextWindowTokens}`,
        '--agent.execution.deadline-ms=180000',
        '--agent.execution.max-tool-calls=40', '--agent.execution.max-iterations=30'],
    { cwd: home, env, stdio: 'ignore', windowsHide: true, shell: false });
    let spawnFailed = false; child.on('error', () => { spawnFailed = true; });
    let token, closed = false; const sessions = new Map();
    const api = async (route, { method = 'GET', body, signal: requestSignal } = {}) => {
        const response = await fetch(base + route, { method, redirect: 'error', signal: requestSignal ?? AbortSignal.timeout(10000),
            headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
            ...(body ? { body: JSON.stringify(body) } : {}) });
        let value; try { value = JSON.parse(await responseText(response, requestSignal)); } catch { throw new Error('EVAL_API_RESPONSE_INVALID'); }
        demand(value?.success === true, 'EVAL_API_REJECTED');
        return value;
    };
    const close = async () => {
        if (closed) return; closed = true;
        try {
            if (token) for (const sessionId of sessions.values()) {
                try { await api(`/api/conversations/${sessionId}/task`, { method: 'DELETE' }); } catch { /* The child is stopped below. */ }
            }
        } finally { await stopProcess(child); token = undefined; }
    };
    try {
        const deadline = performance.now() + 90000;
        let healthy = false;
        while (performance.now() < deadline) {
            signal?.throwIfAborted();
            demand(!spawnFailed && child.exitCode === null && child.signalCode === null, 'EVAL_BACKEND_START_FAILED');
            try {
                const response = await fetch(base + '/actuator/health', { redirect: 'error', signal: AbortSignal.timeout(1000) });
                healthy = response.ok && (await response.json()).status === 'UP';
            } catch { /* A new backend needs time to initialize its own empty DB. */ }
            if (healthy) break;
            await delay(200, undefined, { signal });
        }
        demand(healthy, 'EVAL_BACKEND_NOT_READY');
        const account = await api('/api/users', { method: 'POST', body: { username: 'eval-' + crypto.randomBytes(8).toString('hex'),
            password: crypto.randomBytes(32).toString('base64url'), displayName: 'Isolated Agent Evaluation' } });
        token = account.data?.token; demand(typeof token === 'string' && token.length > 0, 'EVAL_ACCOUNT_NOT_CREATED');
        const model = await api('/api/model');
        demand(model.current?.configurationValid === true && model.current.modelName === configuration.modelId
            && model.current.hasApiKey === true && String(model.current.baseUrl).replace(/\/$/, '') === configuration.baseUrl,
        'EVAL_EFFECTIVE_MODEL_MISMATCH');
        fs.writeFileSync(path.join(evidence, 'instance.json'), JSON.stringify({ isolated: true,
            targetSha256: digest(fs.readFileSync(configuration.jarPath)), modelIdentitySha256: modelIdentityFingerprint(modelIdentity),
            execBlocked: true, desktopEnabled: false, fixtureModel: configuration.fixtureModel }), { mode: 0o600, flag: 'wx' });
    } catch (error) { await close(); throw error; }
    return { mode: configuration.fixtureModel ? 'fixture' : 'live', agentHome: home,
        targetArtifact: configuration.jarPath, modelIdentitySha256: modelIdentityFingerprint(modelIdentity),
        async execute({ id, prompt, artifactDir, signal: caseSignal }) {
            demand(!closed && !sessions.has(id) && fs.realpathSync(artifactDir).startsWith(artifacts + path.sep), 'EVAL_CASE_ISOLATION');
            const created = await api('/api/conversations', { method: 'POST', signal: caseSignal });
            const session = created.data?.sessionId;
            demand(typeof session === 'string' && /^[\w-]+$/.test(session), 'EVAL_CONVERSATION_REQUIRED');
            sessions.set(id, session);
            await api(`/api/conversations/${session}/workspace`, { method: 'PUT', body: { path: artifactDir }, signal: caseSignal });
            const response = await fetch(base + `/api/conversations/${session}/messages/stream`, { method: 'POST',
                redirect: 'error', signal: caseSignal, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
                body: JSON.stringify({ message: prompt + '\n\n将实际可运行网页保存到本会话工作目录的 index.html；最终回复该文件链接。',
                    permissionMode: 'accept_edits', confirmPolicy: 'auto' }) });
            const result = parseTaskEvents(await responseText(response, caseSignal));
            const settledBy = performance.now() + 5000;
            let settled = false;
            do {
                const state = await api(`/api/conversations/${session}/task`, { signal: caseSignal });
                if (state.data?.running === false) { settled = true; break; }
                await delay(100, undefined, { signal: caseSignal });
            } while (performance.now() < settledBy);
            demand(settled, 'EVAL_TASK_STILL_RUNNING');
            if (result.status !== 'DONE') return { status: result.status, terminal: result.terminal };
            const artifact = pickUiArtifact(artifactDir, 'index.html');
            return { status: 'DONE', terminal: true, delivery: { artifact: { path: artifact.path, sha256: artifact.sha256 } } };
        },
        async observe({ caseDefinition, result, artifactDir, nonce, signal: caseSignal }) {
            demand(caseDefinition.checks.length === 1 && caseDefinition.checks[0].type === 'ui-artifact', 'EVAL_UNSUPPORTED_BUSINESS_CHECK');
            demand(sessions.has(caseDefinition.id) && result?.status === 'DONE' && result.terminal === true, 'EVAL_NO_COMPLETED_TASK');
            const artifact = pickUiArtifact(artifactDir, result.delivery?.artifact?.path);
            demand(artifact.sha256 === result.delivery.artifact.sha256, 'EVAL_ARTIFACT_CHANGED');
            const requestFile = path.join(evidence, `${caseDefinition.id}-collector.json`);
            fs.writeFileSync(requestFile, JSON.stringify({ artifactDir, artifact: result.delivery.artifact,
                check: caseDefinition.checks[0] }), { mode: 0o600, flag: 'wx' });
            const ui = await collectUi(configuration, requestFile, caseSignal);
            return { caseId: caseDefinition.id, nonce, ui };
        }, close };
}
