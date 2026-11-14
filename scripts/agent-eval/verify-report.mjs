#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { businessValidators, hasValidator } from './quality-gate.mjs';
import { verify, createPublicKey } from 'node:crypto';

function canonical(value) {
    if (Array.isArray(value)) return value.map(canonical);
    if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
    return value;
}
export function attestationPayload(report) {
    const { attestation, ...body } = report;
    return Buffer.from(JSON.stringify(canonical(body)));
}

/** Read-only verification. Never retries cases or contacts an agent/model. */
export function verifyReport(report, { requireLive = false, commit, manifestSha256,
    adapterSha256, targetSha256, trustedPublicKey } = {}) {
    if (report?.schemaVersion !== 1 || !Number.isSafeInteger(report.expected) || report.expected < 1
        || report.completed !== report.expected || !Array.isArray(report.errors) || report.errors.length
        || !Array.isArray(report.results) || report.results.length !== report.expected
        || new Set(report.results.map(r => r.id)).size !== report.expected) return false;
    if (!report.provenance?.git?.commit || typeof report.provenance.git.dirty !== 'boolean'
        || !/^[a-f0-9]{64}$/.test(report.provenance.modelIdentitySha256)
        || !/^[a-f0-9]{64}$/.test(report.manifestSha256)) return false;
    if (commit && report.provenance.git.commit !== commit) return false;
    if (manifestSha256 && report.manifestSha256 !== manifestSha256) return false;
    if (requireLive) {
        if (!commit || !manifestSha256 || !adapterSha256 || !targetSha256 || !trustedPublicKey
            || report.mode !== 'live' || report.provenance.git.dirty
            || report.provenance.adapterSha256 !== adapterSha256 || report.provenance.targetSha256 !== targetSha256
            || report.attestation?.algorithm !== 'ed25519' || typeof report.attestation.signature !== 'string') return false;
        try {
            const key = trustedPublicKey.type === 'public' ? trustedPublicKey : createPublicKey(trustedPublicKey);
            if (key.asymmetricKeyType !== 'ed25519'
                || !verify(null, attestationPayload(report), key, Buffer.from(report.attestation.signature, 'base64'))) return false;
        } catch { return false; }
    }
    return report.pass === true && report.results.every(result => result.pass === true && Number.isFinite(result.elapsedMs)
        && result.elapsedMs >= 0 && result.elapsedMs <= result.sloMs
        && /^[a-f0-9]{64}$/.test(result.evidenceSha256)
        && Array.isArray(result.checks) && result.checks.length >= 4
        && ['completion', 'slo', 'observation'].every(type => result.checks.some(c => c.type === type && c.pass === true))
        && result.checks.some(check => hasValidator(businessValidators, check.type))
        && result.checks.every(check => check.pass === true));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        const args = process.argv.slice(2);
        const reportPath = args.shift();
        const options = {};
        while (args.length) {
            const flag = args.shift();
            if (flag === '--require-live') options.requireLive = true;
            else if (flag === '--commit' && args.length) options.commit = args.shift();
            else if (flag === '--manifest-sha256' && args.length) options.manifestSha256 = args.shift();
            else if (flag === '--adapter-sha256' && args.length) options.adapterSha256 = args.shift();
            else if (flag === '--target-sha256' && args.length) options.targetSha256 = args.shift();
            else if (flag === '--trusted-public-key' && args.length) options.trustedPublicKey = fs.readFileSync(args.shift());
            else throw new Error();
        }
        if (!reportPath) throw new Error();
        const pass = verifyReport(JSON.parse(fs.readFileSync(reportPath, 'utf8').replace(/^\uFEFF/, '')), options);
        console.log(`VERIFY ${pass ? 'PASS' : 'FAIL'}`);
        process.exitCode = pass ? 0 : 1;
    } catch { console.error('REPORT_MISSING_OR_INVALID'); process.exitCode = 2; }
}
