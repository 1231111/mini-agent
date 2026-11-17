#!/usr/bin/env node
/** Verify signed release inputs. Scanning and signing belong to trusted CI. */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const fail = code => { throw new Error(code); };
const DAY = 86400000;
const now = Date.now();
const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const validHash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const validCommit = value => typeof value === 'string' && [40, 64].includes(value.length)
  && !/[^a-f0-9]/.test(value);
const nonempty = value => typeof value === 'string' && value.trim().length > 0;
const allowed = new Set(['artifact', 'manifest', 'public-key', 'signature',
  'dependency-report', 'container-report', 'waiver-public-key', 'expected-source-commit']);
const args = new Map();
let trustedReleaseKey;

function flag(name, optional = false) {
  const value = args.get(name);
  if (!value && !optional) fail('MISSING_' + name.replaceAll('-', '_').toUpperCase());
  return value ? path.resolve(value) : null;
}
function read(file, limit, code) {
  const stat = fs.statSync(file);
  if (!stat.isFile() || stat.size > limit) fail(code);
  return fs.readFileSync(file);
}
function json(bytes, code) {
  try {
    const value = JSON.parse(bytes.toString('utf8'));
    if (!value || typeof value !== 'object' || Array.isArray(value)) fail(code);
    return value;
  } catch { fail(code); }
}
function hashFile(file) {
  const hash = crypto.createHash('sha256');
  const fd = fs.openSync(file, 'r');
  try {
    const buffer = Buffer.alloc(1024 * 1024);
    let count;
    while ((count = fs.readSync(fd, buffer, 0, buffer.length, null)) !== 0) {
      hash.update(buffer.subarray(0, count));
    }
    return hash.digest('hex');
  } finally { fs.closeSync(fd); }
}
function keyFrom(file, code) {
  let key;
  try { key = crypto.createPublicKey(read(file, 16384, code)); } catch { fail(code); }
  if (key.asymmetricKeyType !== 'ed25519') fail(code);
  return key;
}
function fresh(value, maxAge, code) {
  const timestamp = typeof value === 'string' ? Date.parse(value) : NaN;
  if (!Number.isFinite(timestamp) || new Date(timestamp).toISOString() !== value
      || !(now - timestamp >= -300000 && maxAge >= now - timestamp)) fail(code);
}
function resolveEntry(root, entry, code) {
  if (!entry || !nonempty(entry.path) || !validHash(entry.sha256)
      || path.isAbsolute(entry.path) || path.win32.isAbsolute(entry.path)
      || entry.path.includes(String.fromCharCode(92)) || entry.path.includes(':')
      || entry.path.includes(String.fromCharCode(0))
      || entry.path.split('/').some(part => !part || part === '..' || part === '.')) fail(code);
  const candidate = path.resolve(root, entry.path);
  if (fs.lstatSync(candidate).isSymbolicLink()) fail(code);
  const file = fs.realpathSync(candidate);
  const relative = path.relative(root, file);
  if (relative === '..' || relative.startsWith('..' + path.sep) || path.isAbsolute(relative)) fail(code);
  if (!fs.statSync(file).isFile()) fail(code);
  return file;
}
function waiverPayload(finding, report) {
  const waiver = finding.waiver;
  return Buffer.from(JSON.stringify({ findingId: finding.id, severity: finding.severity,
    reportKind: report.kind, artifactSha256: report.artifactSha256,
    sourceCommit: report.sourceCommit, reason: waiver.reason, approvedBy: waiver.approvedBy,
    ticket: waiver.ticket, expiresAt: waiver.expiresAt }));
}
function verifyWaiver(finding, report, kind) {
  const waiver = finding.waiver;
  const expires = waiver && typeof waiver.expiresAt === 'string' ? Date.parse(waiver.expiresAt) : NaN;
  if (!waiver || !nonempty(waiver.reason) || !(waiver.reason.trim().length >= 16)
      || !nonempty(waiver.approvedBy) || !nonempty(waiver.ticket)
      || !Number.isFinite(expires) || new Date(expires).toISOString() !== waiver.expiresAt) {
    fail(kind + '_WAIVER_INVALID');
  }
  if (!(expires > now && now + 30 * DAY >= expires)) fail(kind + '_WAIVER_EXPIRED_OR_TOO_LONG');
  const approvalKeyPath = flag('waiver-public-key', true);
  if (!approvalKeyPath) fail(kind + '_WAIVER_APPROVAL_KEY_MISSING');
  const approvalKey = keyFrom(approvalKeyPath, 'WAIVER_SIGNING_KEY_INVALID');
  if (approvalKey.export({ type: 'spki', format: 'der' }).equals(
      trustedReleaseKey.export({ type: 'spki', format: 'der' }))) fail('WAIVER_KEY_NOT_INDEPENDENT');
  if (!nonempty(waiver.approvalSignature)) fail(kind + '_WAIVER_SIGNATURE_INVALID');
  const signature = Buffer.from(waiver.approvalSignature, 'base64');
  if (signature.length !== 64 || signature.toString('base64') !== waiver.approvalSignature
      || !crypto.verify(null, waiverPayload(finding, report), approvalKey, signature)) fail(kind + '_WAIVER_SIGNATURE_INVALID');
}
function verifyReport(file, reference, kind, artifactHash, sourceCommit) {
  const bytes = read(file, 32 * 1024 * 1024, kind + '_REPORT_INVALID');
  if (digest(bytes) !== reference.sha256) fail(kind + '_REPORT_HASH_MISMATCH');
  const report = json(bytes, kind + '_REPORT_INVALID');
  if (report.schemaVersion !== 1 || report.status !== 'pass'
      || report.kind !== kind.toLowerCase() || report.generatedBy !== 'controlled-ci'
      || !nonempty(report.scanId) || !Array.isArray(report.findings)
      || !Number.isSafeInteger(report.scannedTargets) || !(report.scannedTargets > 0)
      || !report.scanner || !nonempty(report.scanner.name) || !nonempty(report.scanner.version)) {
    fail(kind + '_REPORT_INVALID');
  }
  if (report.sourceCommit !== sourceCommit || report.artifactSha256 !== artifactHash) {
    fail(kind + '_REPORT_RELEASE_MISMATCH');
  }
  fresh(report.generatedAt, DAY, kind + '_REPORT_STALE_OR_FUTURE');
  fresh(report.scanner.databaseUpdatedAt, 3 * DAY, kind + '_SCANNER_DATABASE_STALE_OR_FUTURE');
  const severities = new Set(['critical', 'high', 'medium', 'low', 'info']);
  for (const finding of report.findings) {
    if (!finding || !nonempty(finding.id) || !severities.has(finding.severity)
        || !['open', 'ignored-with-expiry'].includes(finding.status)) fail(kind + '_FINDING_INVALID');
    if (finding.status === 'ignored-with-expiry') verifyWaiver(finding, report, kind);
    else if (['critical', 'high'].includes(finding.severity)) fail(kind + '_HIGH_FINDING');
  }
}

try {
  let offset = 2;
  while (process.argv.length > offset) {
    const option = process.argv[offset], value = process.argv[offset + 1];
    if (!option.startsWith('--') || !allowed.has(option.slice(2)) || !nonempty(value)
        || value.startsWith('--') || args.has(option.slice(2))) fail('INVALID_ARGUMENTS');
    args.set(option.slice(2), value);
    offset += 2;
  }
  const expectedSourceCommit = args.get('expected-source-commit');
  if (!expectedSourceCommit) fail('MISSING_EXPECTED_SOURCE_COMMIT');
  if (!validCommit(expectedSourceCommit)) fail('INVALID_EXPECTED_SOURCE_COMMIT');
  const artifact = fs.realpathSync(flag('artifact'));
  const manifestFile = fs.realpathSync(flag('manifest'));
  const signatureFile = flag('signature');
  const reportFiles = new Map(['DEPENDENCY', 'CONTAINER'].map(kind =>
    [kind, fs.realpathSync(flag(kind.toLowerCase() + '-report'))]));
  const bytes = read(manifestFile, 1024 * 1024, 'RELEASE_MANIFEST_INVALID');
  const signature = read(signatureFile, 1024, 'RELEASE_SIGNATURE_INVALID');
  trustedReleaseKey = keyFrom(flag('public-key'), 'RELEASE_SIGNING_KEY_INVALID');
  if (signature.length !== 64 || !crypto.verify(null, bytes, trustedReleaseKey, signature)) fail('RELEASE_SIGNATURE_INVALID');
  const manifest = json(bytes, 'RELEASE_MANIFEST_INVALID');
  if (manifest.schemaVersion !== 2 || !validCommit(manifest.sourceCommit)
      || !Array.isArray(manifest.files) || !manifest.files.length
      || !Array.isArray(manifest.scanReports) || manifest.scanReports.length !== 2) fail('RELEASE_MANIFEST_INVALID');
  if (manifest.sourceCommit !== expectedSourceCommit) fail('RELEASE_SOURCE_COMMIT_MISMATCH');
  fresh(manifest.createdAt, DAY, 'RELEASE_MANIFEST_STALE_OR_FUTURE');
  const root = fs.realpathSync(path.dirname(manifestFile));
  const paths = new Set();
  let artifactHash;
  for (const entry of manifest.files) {
    const file = resolveEntry(root, entry, 'RELEASE_FILE_INVALID');
    if (paths.has(file)) fail('RELEASE_FILE_DUPLICATE');
    paths.add(file);
    if (!Number.isSafeInteger(entry.bytes) || !(entry.bytes >= 0)
        || fs.statSync(file).size !== entry.bytes || hashFile(file) !== entry.sha256) fail('RELEASE_ARTIFACT_HASH_MISMATCH');
    if (file === artifact) artifactHash = entry.sha256;
  }
  if (!artifactHash) fail('RELEASE_ARTIFACT_NOT_IN_MANIFEST');
  const reportKinds = new Set();
  for (const reference of manifest.scanReports) {
    if (!['dependency', 'container'].includes(reference?.kind)) fail('RELEASE_SCAN_REFERENCES_INVALID');
    const kind = reference.kind.toUpperCase();
    if (!reportFiles.has(kind) || reportKinds.has(kind)) fail('RELEASE_SCAN_REFERENCES_INVALID');
    reportKinds.add(kind);
    const file = resolveEntry(root, reference, 'RELEASE_SCAN_REFERENCE_INVALID');
    if (file !== reportFiles.get(kind)) fail(kind + '_REPORT_PATH_MISMATCH');
    verifyReport(file, reference, kind, artifactHash, manifest.sourceCommit);
  }
  process.stdout.write('release verification passed' + String.fromCharCode(10));
} catch (error) {
  process.stderr.write(String(error.message) + String.fromCharCode(10));
  process.exitCode = 1;
}
