import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { isDeepStrictEqual } from 'node:util';

export const digest = value => crypto.createHash('sha256').update(
    typeof value === 'string' || Buffer.isBuffer(value) ? value : JSON.stringify(value)).digest('hex');
const requireValue = (condition, code) => { if (!condition) throw new Error(code); };
const identifier = value => typeof value === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,95}$/.test(value);
const nonempty = value => typeof value === 'string' && value.trim().length > 0;
const isoTime = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(value)
    && Number.isFinite(Date.parse(value));
const equal = isDeepStrictEqual;
export const hasValidator = (validators, type) => typeof type === 'string' && Object.hasOwn(validators, type)
    && typeof validators[type] === 'function';

export function validateManifest(manifest, validators = businessValidators) {
    requireValue(manifest?.schemaVersion === 1, 'MANIFEST_VERSION');
    requireValue(Array.isArray(manifest.cases) && manifest.cases.length > 0, 'EMPTY_CASE_SET');
    const ids = new Set();
    for (const c of manifest.cases) {
        requireValue(identifier(c.id) && !ids.has(c.id), 'INVALID_OR_DUPLICATE_CASE_ID');
        ids.add(c.id);
        requireValue(nonempty(c.prompt), 'MISSING_PROMPT');
        requireValue(Number.isSafeInteger(c.sloMs) && c.sloMs > 0, 'INVALID_SLO');
        requireValue(Number.isSafeInteger(c.timeoutMs) && c.timeoutMs >= c.sloMs, 'INVALID_TIMEOUT');
        for (const key of ['maxTokens', 'maxCostMicrousd']) {
            requireValue(c[key] === undefined || (Number.isSafeInteger(c[key]) && c[key] > 0), 'INVALID_USAGE_BUDGET');
        }
        requireValue(c.maxCostMicrousd === undefined || identifier(c.pricingRevision), 'PRICING_REVISION_REQUIRED');
        requireValue(Array.isArray(c.checks) && c.checks.length > 0, 'NO_BUSINESS_CHECKS');
        for (const check of c.checks) requireValue(hasValidator(validators, check.type), 'UNKNOWN_VALIDATOR');
    }
    return manifest;
}

export function selectCases(manifest, { ids = [], category = 'all', tier = 'all' } = {}) {
    validateManifest(manifest);
    requireValue(new Set(ids).size === ids.length, 'DUPLICATE_REQUESTED_ID');
    let cases = manifest.cases.filter(c => (category === 'all' || c.category === category)
        && (tier === 'all' || c.tier === tier) && (!ids.length || ids.includes(c.id)));
    requireValue(cases.length > 0, 'EMPTY_CASE_SET');
    requireValue(ids.every(id => cases.some(c => c.id === id)), 'REQUESTED_CASE_NOT_SELECTED');
    return { ...manifest, cases };
}

/** Files must be inside this case's newly-created directory, including every symlink component. */
export function artifactPath(root, relative, expectedIdentity) {
    requireValue(nonempty(relative) && !path.isAbsolute(relative) && !relative.includes('\\')
        && !relative.split('/').some(part => part === '..' || part === '' || part === '.'), 'ARTIFACT_PATH');
    const base = path.resolve(root);
    // Do not trust realpath(root) as a new authority: an adapter could replace the
    // entire fresh case directory with a junction to a shared old workspace.
    requireValue(!fs.lstatSync(base).isSymbolicLink(), 'ARTIFACT_ROOT_SYMLINK');
    requireValue(fs.realpathSync(base) === base, 'ARTIFACT_ROOT_SYMLINK');
    const actual = fs.statSync(base);
    if (expectedIdentity) requireValue(String(actual.dev) === expectedIdentity.dev
        && String(actual.ino) === expectedIdentity.ino, 'ARTIFACT_DIRECTORY_REPLACED');
    const target = path.resolve(base, relative);
    requireValue(target.startsWith(base + path.sep), 'ARTIFACT_OUTSIDE_CASE');
    let cursor = base;
    for (const part of relative.split('/')) {
        cursor = path.join(cursor, part);
        requireValue(!fs.lstatSync(cursor).isSymbolicLink(), 'ARTIFACT_SYMLINK');
    }
    requireValue(fs.statSync(target).isFile(), 'ARTIFACT_NOT_FILE');
    requireValue(fs.realpathSync(target).startsWith(base + path.sep), 'ARTIFACT_OUTSIDE_CASE');
    return target;
}

export function inspectArtifact(context, artifact) {
    requireValue(artifact && /^[a-f0-9]{64}$/.test(artifact.sha256), 'ARTIFACT_HASH_REQUIRED');
    const file = artifactPath(context.artifactDir, artifact.path, context.artifactIdentity);
    const sha256 = digest(fs.readFileSync(file));
    requireValue(sha256 === artifact.sha256, 'ARTIFACT_HASH_MISMATCH');
    return { file, sha256 };
}

function dateInZone(timestamp, timeZone) {
    requireValue(isoTime(timestamp), 'SOURCE_TIMESTAMP_REQUIRED');
    const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
        timeZone, year: 'numeric', month: '2-digit', day: '2-digit'
    }).formatToParts(new Date(timestamp)).map(part => [part.type, part.value]));
    return `${parts.year}-${parts.month}-${parts.day}`;
}

function news(check, result, observation) {
    requireValue(/^\d{4}-\d{2}-\d{2}$/.test(check.date) && nonempty(check.timeZone), 'NEWS_DATE_CONTRACT');
    requireValue(Number.isSafeInteger(check.minItems) && check.minItems > 0
        && Number.isSafeInteger(check.minSources) && check.minSources > 0, 'NEWS_MINIMUM_CONTRACT');
    // Publication facts come from a collector's source records, never from the answer's own date claims.
    requireValue(Array.isArray(observation.newsSources) && observation.newsSources.length > 0, 'SOURCE_EVIDENCE_REQUIRED');
    const sources = new Map();
    for (const source of observation.newsSources) {
        requireValue(identifier(source.id) && !sources.has(source.id) && source.httpStatus === 200
            && nonempty(source.title) && nonempty(source.publisher)
            && /^[a-f0-9]{64}$/.test(source.bodySha256)
            && ['publisher-metadata', 'fixture-oracle'].includes(source.dateEvidence), 'SOURCE_EVIDENCE_INVALID');
        const url = new URL(source.url);
        requireValue(['https:', 'http:'].includes(url.protocol) && !url.username && !url.password, 'SOURCE_URL_INVALID');
        sources.set(source.id, { ...source, url: url.href, host: url.hostname });
    }
    const items = result.delivery?.news;
    requireValue(Array.isArray(items) && items.length >= check.minItems, 'NEWS_ITEMS_MISSING');
    const used = new Set(), publishers = new Set();
    for (const item of items) {
        const source = sources.get(item.sourceId);
        requireValue(source && !used.has(item.sourceId), 'NEWS_SOURCE_MISSING_OR_DUPLICATE');
        requireValue(nonempty(item.summary) && item.title === source.title && item.url === source.url
            && item.publishedAt === source.publishedAt, 'NEWS_CITATION_MISMATCH');
        requireValue(dateInZone(source.publishedAt, check.timeZone) === check.date, 'NEWS_OUTSIDE_REQUESTED_DAY');
        requireValue(!check.allowedHosts || check.allowedHosts.includes(source.host), 'NEWS_SOURCE_HOST');
        used.add(item.sourceId); publishers.add(source.host);
    }
    requireValue(publishers.size >= check.minSources, 'NEWS_INSUFFICIENT_SOURCES');
    requireValue(!check.requiredSourceIds || check.requiredSourceIds.every(id => used.has(id)), 'NEWS_REQUIRED_SOURCE_MISSING');
}

function uiArtifact(check, result, observation, context) {
    const artifact = inspectArtifact(context, result.delivery?.artifact);
    requireValue(['.html', '.htm'].includes(path.extname(artifact.file).toLowerCase()), 'UI_HTML_REQUIRED');
    const render = observation.ui;
    requireValue(render && render.artifactSha256 === artifact.sha256 && render.loaded === true, 'UI_RENDER_EVIDENCE_REQUIRED');
    requireValue(Array.isArray(render.consoleErrors) && !render.consoleErrors.length
        && Array.isArray(render.failedRequests) && !render.failedRequests.length, 'UI_RUNTIME_ERRORS');
    requireValue(Array.isArray(check.viewports) && check.viewports.length >= 2
        && Array.isArray(check.requiredSelectors) && check.requiredSelectors.length > 0, 'UI_CONTRACT_INCOMPLETE');
    for (const viewport of check.viewports) {
        const captures = render.viewports?.filter(v => v.width === viewport.width && v.height === viewport.height) ?? [];
        requireValue(captures.length === 1, 'UI_VIEWPORT_EVIDENCE_MISSING');
        const capture = captures[0];
        const screenshot = inspectArtifact(context, capture.screenshot);
        const png = fs.readFileSync(screenshot.file);
        requireValue(png.length >= 33 && png.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex'))
            && png.subarray(12, 16).toString() === 'IHDR'
            && png.readUInt32BE(16) === viewport.width && png.readUInt32BE(20) === viewport.height,
        'UI_SCREENSHOT_DIMENSIONS');
        requireValue(Number.isFinite(capture.scrollWidth) && capture.scrollWidth > 0
            && capture.scrollWidth <= viewport.width + 1, 'UI_HORIZONTAL_OVERFLOW');
        for (const selector of check.requiredSelectors) {
            const item = capture.elements?.[selector];
            requireValue(item?.visible === true && Number.isSafeInteger(item.count) && item.count > 0, 'UI_REQUIRED_ELEMENT_MISSING');
        }
    }
    for (const action of check.requiredActions ?? []) {
        const events = render.actions?.filter(event => event.id === action.id) ?? [];
        requireValue(events.length === 1 && events[0].observedState === action.expectedState, 'UI_ACTION_NOT_VERIFIED');
    }
    // These are functional/layout checks. Pixel-level similarity needs a separately calibrated visual oracle.
}

function formReceipt(check, result, observation) {
    const receipt = observation.formReceipt;
    requireValue(receipt && receipt.origin === 'fixture-server' && receipt.nonce === observation.nonce
        && receipt.accepted === true && nonempty(receipt.receiptId), 'SERVER_RECEIPT_REQUIRED');
    requireValue(receipt.method === (check.method ?? 'POST') && receipt.route === check.route, 'FORM_DESTINATION_MISMATCH');
    requireValue(check.expectedFields && Object.keys(check.expectedFields).length > 0, 'FORM_FIELDS_CONTRACT');
    requireValue(equal(receipt.fields, check.expectedFields), 'FORM_SERVER_FIELDS_MISMATCH');
    requireValue(equal(observation.fieldReadback, check.expectedFields), 'FORM_READBACK_MISMATCH');
    requireValue(result.delivery?.receiptId === receipt.receiptId, 'FORM_REPLY_RECEIPT_MISMATCH');
    requireValue(Array.isArray(receipt.files) && receipt.files.length === (check.expectedFiles ?? []).length, 'FORM_UPLOAD_MISSING');
    for (const file of check.expectedFiles ?? []) {
        requireValue(receipt.files.some(actual => actual.field === file.field && actual.name === file.name
            && actual.sha256 === file.sha256 && actual.bytes === file.bytes), 'FORM_UPLOAD_MISMATCH');
    }
}

export const businessValidators = Object.freeze({ news, 'ui-artifact': uiArtifact, 'form-receipt': formReceipt });

/** Never trust a model's passed/success fields or cached checkResults. Only the harness supplies observation. */
export function assessCase(definition, capture, context, validators = businessValidators) {
    const checks = [];
    const verify = (type, fn) => {
        try { fn(); checks.push({ type, pass: true }); }
        catch (error) {
            // Do not serialize provider errors, answers, URLs, keys or field values into reports.
            const code = /^[A-Z][A-Z0-9_]{2,80}$/.test(error?.message) ? error.message : 'INVALID_EVIDENCE';
            checks.push({ type, pass: false, code });
        }
    };
    verify('completion', () => requireValue(capture?.status === 'DONE' && capture.terminal === true
        && capture.error == null && capture.runId === context.runId && capture.caseId === definition.id, 'INCOMPLETE_OR_WRONG_RUN'));
    verify('slo', () => requireValue(Number.isFinite(capture?.elapsedMs) && capture.elapsedMs >= 0
        && capture.elapsedMs <= definition.sloMs, 'SLO_EXCEEDED_OR_MISSING'));
    verify('observation', () => requireValue(nonempty(context.nonce) && capture?.observation?.nonce === context.nonce
        && capture.observation.caseId === definition.id, 'OBSERVATION_BINDING_MISMATCH'));
    if (definition.maxTokens !== undefined || definition.maxCostMicrousd !== undefined) verify('usage-budget', () => {
        const usage = capture?.observation?.usage;
        requireValue(usage && usage.source === 'provider-meter', 'PROVIDER_USAGE_REQUIRED');
        if (definition.maxTokens !== undefined) requireValue(Number.isSafeInteger(usage.totalTokens)
            && usage.totalTokens >= 0 && usage.totalTokens <= definition.maxTokens, 'TOKEN_BUDGET_EXCEEDED_OR_MISSING');
        if (definition.maxCostMicrousd !== undefined) requireValue(Number.isSafeInteger(usage.costMicrousd)
            && usage.costMicrousd >= 0 && usage.costMicrousd <= definition.maxCostMicrousd
            && usage.pricingRevision === definition.pricingRevision, 'COST_BUDGET_EXCEEDED_OR_MISSING');
    });
    for (const check of definition.checks) verify(check.type, () => {
        requireValue(hasValidator(validators, check.type), 'UNKNOWN_VALIDATOR');
        validators[check.type](check, capture ?? {}, capture?.observation ?? {}, context);
    });
    return { id: definition.id, pass: checks.every(check => check.pass), elapsedMs: capture?.elapsedMs ?? null,
        sloMs: definition.sloMs, evidenceSha256: digest(capture ?? null), checks };
}

export function assessRun(manifest, captures, context) {
    validateManifest(manifest, context.validators ?? businessValidators);
    const errors = [];
    requireValue(Array.isArray(captures), 'CAPTURES_REQUIRED');
    const expected = new Set(manifest.cases.map(c => c.id));
    const ids = captures.map(c => c.caseId);
    if (ids.some(id => !expected.has(id))) errors.push('UNEXPECTED_CASE');
    if (new Set(ids).size !== ids.length) errors.push('DUPLICATE_CASE');
    if (manifest.cases.some(c => !ids.includes(c.id))) errors.push('MISSING_CASE');
    const results = manifest.cases.map(definition => assessCase(definition,
        captures.find(c => c.caseId === definition.id), {
            ...context, artifactDir: path.join(context.artifactRoot, definition.id),
            artifactIdentity: context.artifactIdentities?.[definition.id], nonce: context.nonces[definition.id]
        }, context.validators ?? businessValidators));
    return { schemaVersion: 1, runId: context.runId, mode: context.mode,
        // An adapter's handwritten mode and a clean checkout do not attest a release.
        // A separate trusted CI signer and pinned inputs are required at verification time.
        releaseEligible: false,
        provenance: context.provenance, manifestSha256: digest(manifest),
        expected: manifest.cases.length, completed: captures.length, errors, results,
        pass: errors.length === 0 && results.every(result => result.pass) };
}

export function modelIdentityFingerprint(config) {
    // Whitelist before hashing: never fingerprint/store API keys, tokens, raw endpoint URLs or arbitrary settings.
    requireValue(config && identifier(config.providerId) && typeof config.modelId === 'string'
        && /^[a-zA-Z0-9][a-zA-Z0-9_.:/-]{0,255}$/.test(config.modelId)
        && identifier(config.configurationRevision), 'MODEL_IDENTITY_REQUIRED');
    return digest({ providerId: config.providerId, modelId: config.modelId,
        configurationRevision: config.configurationRevision });
}

export function gitProvenance(root) {
    const git = args => {
        const result = spawnSync('git', args, { cwd: root, encoding: 'utf8', windowsHide: true, maxBuffer: 32 * 1024 * 1024 });
        requireValue(result.status === 0, 'GIT_PROVENANCE_UNAVAILABLE');
        return result.stdout;
    };
    const commit = git(['rev-parse', 'HEAD']).trim();
    const status = git(['status', '--porcelain=v1', '--untracked-files=normal']);
    const diff = git(['diff', 'HEAD', '--binary']);
    const untracked = git(['ls-files', '--others', '--exclude-standard', '-z']).split('\0').filter(Boolean)
        .sort().map(file => [file, digest(fs.readFileSync(path.join(root, file)))]);
    return { commit, dirty: status.length > 0, workingTreeSha256: digest(status + '\n' + diff + JSON.stringify(untracked)) };
}

export function createRun(parent, runId = `eval-${Date.now()}-${crypto.randomBytes(8).toString('hex')}`) {
    requireValue(identifier(runId), 'INVALID_RUN_ID');
    fs.mkdirSync(parent, { recursive: true });
    const directory = path.join(fs.realpathSync(parent), runId);
    // Never reuse or clean an old run directory. Its files cannot become evidence for a new attempt.
    fs.mkdirSync(directory, { mode: 0o700 });
    for (const name of ['artifacts', 'agent-home', 'harness']) fs.mkdirSync(path.join(directory, name), { mode: 0o700 });
    return { runId, directory, artifactRoot: path.join(directory, 'artifacts'),
        agentHome: path.join(directory, 'agent-home'), harnessDir: path.join(directory, 'harness') };
}
