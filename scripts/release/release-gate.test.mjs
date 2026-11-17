import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const verifier = fileURLToPath(new URL('./verify-release.mjs', import.meta.url));
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const DAY = 86400000;

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'miniagent-release-test-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const release = path.join(root, 'release');
  fs.mkdirSync(path.join(release, 'reports'), { recursive: true });
  fs.mkdirSync(path.join(root, 'unrelated'));
  const artifact = path.join(release, 'package.jar');
  const artifactBytes = Buffer.from('Release verifier fixture, not a scanned production artifact');
  fs.writeFileSync(artifact, artifactBytes);
  const signing = crypto.generateKeyPairSync('ed25519');
  const approving = crypto.generateKeyPairSync('ed25519');
  const publicKey = path.join(root, 'release-key.pem');
  const waiverKey = path.join(root, 'waiver-key.pem');
  fs.writeFileSync(publicKey, signing.publicKey.export({ type: 'spki', format: 'pem' }));
  fs.writeFileSync(waiverKey, approving.publicKey.export({ type: 'spki', format: 'pem' }));
  const manifestFile = path.join(release, 'manifest.json');
  const signatureFile = path.join(release, 'manifest.sig');
  const timestamp = new Date().toISOString();
  const sourceCommit = 'a'.repeat(40);
  const reports = Object.fromEntries(['dependency', 'container'].map(kind => [kind, {
    schemaVersion: 1, kind, status: 'pass', generatedBy: 'controlled-ci', scanId: 'fixture-' + kind,
    generatedAt: timestamp, sourceCommit, artifactSha256: hash(artifactBytes), scannedTargets: 1,
    scanner: { name: 'test-only-' + kind, version: '1.0.0', databaseUpdatedAt: timestamp }, findings: []
  }]));
  const manifest = { schemaVersion: 2, sourceCommit, createdAt: timestamp,
    files: [{ path: 'package.jar', sha256: hash(artifactBytes), bytes: artifactBytes.length }], scanReports: [] };
  const f = { root, release, artifact, manifest, reports, manifestFile, signatureFile, publicKey, waiverKey,
    reportFile: kind => path.join(release, 'reports', kind + '.json'),
    signOnly() {
      const bytes = Buffer.from(JSON.stringify(manifest));
      fs.writeFileSync(manifestFile, bytes);
      fs.writeFileSync(signatureFile, crypto.sign(null, bytes, signing.privateKey));
    },
    refresh() {
      manifest.scanReports = Object.entries(reports).map(([kind, report]) => {
        const bytes = Buffer.from(JSON.stringify(report));
        fs.writeFileSync(f.reportFile(kind), bytes);
        return { kind, path: 'reports/' + kind + '.json', sha256: hash(bytes) };
      });
      f.signOnly();
    },
    waive(finding, overrides = {}) {
      finding.status = 'ignored-with-expiry';
      finding.waiver = { reason: 'Reviewed compensating control under security ticket',
        approvedBy: 'security-reviewer', ticket: 'SEC-100',
        expiresAt: new Date(Date.now() + 2 * DAY).toISOString(), ...overrides };
      const waiver = finding.waiver;
      const payload = Buffer.from(JSON.stringify({ findingId: finding.id, severity: finding.severity,
        reportKind: reports.dependency.kind, artifactSha256: reports.dependency.artifactSha256,
        sourceCommit: reports.dependency.sourceCommit, reason: waiver.reason, approvedBy: waiver.approvedBy,
        ticket: waiver.ticket, expiresAt: waiver.expiresAt }));
      waiver.approvalSignature = crypto.sign(null, payload, approving.privateKey).toString('base64');
    },
    run(options = {}) {
      const args = [verifier, '--artifact', artifact, '--manifest', options.manifest || manifestFile,
        '--signature', signatureFile, '--public-key', publicKey,
        '--dependency-report', options.dependencyReport || f.reportFile('dependency'),
        '--container-report', f.reportFile('container')];
      if (options.expectedSourceCommit !== false) {
        args.push('--expected-source-commit', options.expectedSourceCommit ?? sourceCommit);
      }
      if (options.waiverKey !== false) args.push('--waiver-public-key', options.waiverKey || waiverKey);
      args.push(...options.extraArgs || []);
      return spawnSync(process.execPath, args, { cwd: options.cwd || path.join(root, 'unrelated'),
        encoding: 'utf8', timeout: 10000 });
    }
  };
  f.refresh();
  return f;
}
function passes(f, options) {
  const result = f.run(options);
  assert.equal(result.error, undefined);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /release verification passed/);
}
function fails(f, code, options) {
  const result = f.run(options);
  assert.equal(result.error, undefined);
  assert.notEqual(result.status, 0, result.stdout);
  assert.match(result.stderr, new RegExp(code));
}

test('signed fixture resolves artifact and reports relative to manifest from another cwd', t => passes(fixture(t)));
test('the manifest CLI argument itself may be relative to caller cwd', t => {
  const f = fixture(t); passes(f, { cwd: f.root, manifest: 'release/manifest.json' });
});
test('release without waived findings needs no approval key', t => passes(fixture(t), { waiverKey: false }));
test('tampered artifact size is rejected', t => {
  const f = fixture(t); fs.appendFileSync(f.artifact, 'tampered'); fails(f, 'RELEASE_ARTIFACT_HASH_MISMATCH');
});
test('tampered artifact hash is rejected when its size stays unchanged', t => {
  const f = fixture(t), bytes = fs.readFileSync(f.artifact);
  bytes[0] ^= 1; fs.writeFileSync(f.artifact, bytes); fails(f, 'RELEASE_ARTIFACT_HASH_MISMATCH');
});
test('tampered manifest commit is rejected by the signature', t => {
  const f = fixture(t); f.manifest.sourceCommit = 'b'.repeat(40);
  fs.writeFileSync(f.manifestFile, JSON.stringify(f.manifest)); fails(f, 'RELEASE_SIGNATURE_INVALID');
});
test('every manifest field is signed including extra metadata', t => {
  const f = fixture(t); f.manifest.unsignedClaim = 'must not be ignored';
  fs.writeFileSync(f.manifestFile, JSON.stringify(f.manifest)); fails(f, 'RELEASE_SIGNATURE_INVALID');
});
test('a fabricated signature cannot certify self-declared pass reports', t => {
  const f = fixture(t); fs.writeFileSync(f.signatureFile, Buffer.alloc(64)); fails(f, 'RELEASE_SIGNATURE_INVALID');
});
test('tampered scan report cannot reuse a valid manifest signature', t => {
  const f = fixture(t); fs.appendFileSync(f.reportFile('dependency'), ' '); fails(f, 'DEPENDENCY_REPORT_HASH_MISMATCH');
});
test('same report bytes at an unsigned reference path cannot replace the signed report', t => {
  const f = fixture(t), copy = path.join(f.root, 'copy.json');
  fs.copyFileSync(f.reportFile('dependency'), copy);
  fails(f, 'DEPENDENCY_REPORT_PATH_MISMATCH', { dependencyReport: copy });
});
for (const missing of ['findings', 'scannedTargets', 'scanner', 'scanId']) {
  test('signed pass flag without ' + missing + ' is insufficient', t => {
    const f = fixture(t); delete f.reports.dependency[missing]; f.refresh(); fails(f, 'DEPENDENCY_REPORT_INVALID');
  });
}
test('zero scanned targets is not a completed scan', t => {
  const f = fixture(t); f.reports.dependency.scannedTargets = 0; f.refresh(); fails(f, 'DEPENDENCY_REPORT_INVALID');
});
test('report for a different artifact is rejected', t => {
  const f = fixture(t); f.reports.dependency.artifactSha256 = 'b'.repeat(64); f.refresh();
  fails(f, 'DEPENDENCY_REPORT_RELEASE_MISMATCH');
});
test('report for a different commit is rejected', t => {
  const f = fixture(t); f.reports.container.sourceCommit = 'b'.repeat(40); f.refresh();
  fails(f, 'CONTAINER_REPORT_RELEASE_MISMATCH');
});
for (const severity of ['high', 'critical']) {
  test('unwaived ' + severity + ' findings stop release', t => {
    const f = fixture(t); f.reports.dependency.findings = [{ id: 'CVE-fixture', severity, status: 'open' }];
    f.refresh(); fails(f, 'DEPENDENCY_HIGH_FINDING');
  });
}
test('ignored status without waiver cannot hide a high finding', t => {
  const f = fixture(t);
  f.reports.dependency.findings = [{ id: 'CVE-fixture', severity: 'high', status: 'ignored-with-expiry' }];
  f.refresh(); fails(f, 'DEPENDENCY_WAIVER_INVALID');
});
test('valid independently signed unexpired waiver is accepted', t => {
  const f = fixture(t), finding = { id: 'CVE-fixture', severity: 'high' };
  f.waive(finding); f.reports.dependency.findings = [finding]; f.refresh(); passes(f);
});
test('expired waiver fails even when its approval signature is valid', t => {
  const f = fixture(t), finding = { id: 'CVE-fixture', severity: 'high' };
  f.waive(finding, { expiresAt: new Date(Date.now() - DAY).toISOString() });
  f.reports.dependency.findings = [finding]; f.refresh(); fails(f, 'DEPENDENCY_WAIVER_EXPIRED_OR_TOO_LONG');
});
test('waiver longer than thirty days is rejected', t => {
  const f = fixture(t), finding = { id: 'CVE-fixture', severity: 'high' };
  f.waive(finding, { expiresAt: new Date(Date.now() + 31 * DAY).toISOString() });
  f.reports.dependency.findings = [finding]; f.refresh(); fails(f, 'DEPENDENCY_WAIVER_EXPIRED_OR_TOO_LONG');
});
test('forged waiver signature fails even in release-signed report', t => {
  const f = fixture(t), finding = { id: 'CVE-fixture', severity: 'high' };
  f.waive(finding); finding.waiver.approvalSignature = Buffer.alloc(64).toString('base64');
  f.reports.dependency.findings = [finding]; f.refresh(); fails(f, 'DEPENDENCY_WAIVER_SIGNATURE_INVALID');
});
test('waiver requires separately trusted approval key', t => {
  const f = fixture(t), finding = { id: 'CVE-fixture', severity: 'high' };
  f.waive(finding); f.reports.dependency.findings = [finding]; f.refresh();
  fails(f, 'DEPENDENCY_WAIVER_APPROVAL_KEY_MISSING', { waiverKey: false });
});
test('same key cannot serve as both release signer and independent approver', t => {
  const f = fixture(t), finding = { id: 'CVE-fixture', severity: 'high' };
  f.waive(finding); f.reports.dependency.findings = [finding]; f.refresh();
  fails(f, 'WAIVER_KEY_NOT_INDEPENDENT', { waiverKey: f.publicKey });
});
test('waiver approval is bound to finding severity', t => {
  const f = fixture(t), finding = { id: 'CVE-fixture', severity: 'high' };
  f.waive(finding); finding.severity = 'critical';
  f.reports.dependency.findings = [finding]; f.refresh(); fails(f, 'DEPENDENCY_WAIVER_SIGNATURE_INVALID');
});
test('waiver without useful reason is rejected', t => {
  const f = fixture(t), finding = { id: 'CVE-fixture', severity: 'high' };
  f.waive(finding, { reason: 'ok' }); f.reports.dependency.findings = [finding]; f.refresh();
  fails(f, 'DEPENDENCY_WAIVER_INVALID');
});
test('old vulnerability databases stop release', t => {
  const f = fixture(t); f.reports.container.scanner.databaseUpdatedAt = new Date(Date.now() - 4 * DAY).toISOString();
  f.refresh(); fails(f, 'CONTAINER_SCANNER_DATABASE_STALE_OR_FUTURE');
});
test('future scan times stop release', t => {
  const f = fixture(t); f.reports.dependency.generatedAt = new Date(Date.now() + DAY).toISOString();
  f.refresh(); fails(f, 'DEPENDENCY_REPORT_STALE_OR_FUTURE');
});
test('stale scan times stop release', t => {
  const f = fixture(t); f.reports.dependency.generatedAt = new Date(Date.now() - 2 * DAY).toISOString();
  f.refresh(); fails(f, 'DEPENDENCY_REPORT_STALE_OR_FUTURE');
});
test('unknown finding severities fail closed', t => {
  const f = fixture(t); f.reports.dependency.findings = [{ id: 'CVE-fixture', severity: 'not-high', status: 'open' }];
  f.refresh(); fails(f, 'DEPENDENCY_FINDING_INVALID');
});
test('a resolved flag cannot bypass evidence about an existing high finding', t => {
  const f = fixture(t); f.reports.dependency.findings = [{ id: 'CVE-fixture', severity: 'high', status: 'resolved' }];
  f.refresh(); fails(f, 'DEPENDENCY_FINDING_INVALID');
});
test('signed paths cannot escape manifest directory', t => {
  const f = fixture(t); f.manifest.files[0].path = '../release/package.jar'; f.signOnly(); fails(f, 'RELEASE_FILE_INVALID');
});
test('duplicate file entries are rejected', t => {
  const f = fixture(t); f.manifest.files.push({ ...f.manifest.files[0] }); f.signOnly(); fails(f, 'RELEASE_FILE_DUPLICATE');
});
test('both scan kinds must be covered exactly once', t => {
  const f = fixture(t); f.manifest.scanReports[1] = { ...f.manifest.scanReports[0] };
  f.signOnly(); fails(f, 'RELEASE_SCAN_REFERENCES_INVALID');
});
test('old manifest schema cannot silently reuse the incomplete signature contract', t => {
  const f = fixture(t); f.manifest.schemaVersion = 1; f.signOnly(); fails(f, 'RELEASE_MANIFEST_INVALID');
});
test('duplicate CLI options fail instead of replacing a trusted input', t => {
  const f = fixture(t); fails(f, 'INVALID_ARGUMENTS', { extraArgs: ['--public-key', f.publicKey] });
});
test('a matching expected commit from the caller is accepted as a string', t => {
  passes(fixture(t), { expectedSourceCommit: 'a'.repeat(40) });
});
test('missing trusted expected commit fails closed', t => {
  fails(fixture(t), 'MISSING_EXPECTED_SOURCE_COMMIT', { expectedSourceCommit: false });
});
for (const invalid of ['main', 'a'.repeat(39), 'a'.repeat(41), 'A'.repeat(40),
  'a'.repeat(63), 'a'.repeat(65), ' ' + 'a'.repeat(40), 'g'.repeat(40),
  'a'.repeat(40) + String.fromCharCode(10), 'a'.repeat(40) + String.fromCharCode(13),
  'a'.repeat(64) + String.fromCharCode(10), 'a'.repeat(39) + String.fromCharCode(10),
  'a'.repeat(63) + String.fromCharCode(10)]) {
  test('invalid expected commit is rejected: ' + JSON.stringify(invalid), t => {
    fails(fixture(t), 'INVALID_EXPECTED_SOURCE_COMMIT', { expectedSourceCommit: invalid });
  });
}
test('an empty expected commit argument is rejected', t => {
  fails(fixture(t), 'INVALID_ARGUMENTS', { expectedSourceCommit: '' });
});
test('valid signatures and internally consistent reports for another commit still fail this release', t => {
  const f = fixture(t), otherCommit = 'b'.repeat(40);
  f.manifest.sourceCommit = otherCommit;
  for (const report of Object.values(f.reports)) report.sourceCommit = otherCommit;
  f.refresh();
  passes(f, { expectedSourceCommit: otherCommit });
  fails(f, 'RELEASE_SOURCE_COMMIT_MISMATCH');
});
test('a trusted SHA-256 Git commit is accepted without path conversion', t => {
  const f = fixture(t), expected = 'b'.repeat(64);
  f.manifest.sourceCommit = expected;
  for (const report of Object.values(f.reports)) report.sourceCommit = expected;
  f.refresh(); passes(f, { expectedSourceCommit: expected });
});
test('scan reference kind spelling agrees with the published schema', t => {
  const f = fixture(t); f.manifest.scanReports[0].kind = 'DEPENDENCY'; f.signOnly();
  fails(f, 'RELEASE_SCAN_REFERENCES_INVALID');
});
