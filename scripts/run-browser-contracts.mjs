#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { discoverBrowserContracts, browserContractArguments, mavenInvocation, validateBrowserReports } from './lib/browser-contracts.mjs';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let list = false, dryRun = false, evidenceDir;
try {
    for (const argument of process.argv.slice(2)) {
        if (argument === '--list') list = true;
        else if (argument === '--dry-run') dryRun = true;
        else if (argument.startsWith('--evidence-dir=')) {
            if (evidenceDir || !argument.slice(15)) throw new Error('INVALID_EVIDENCE_DIRECTORY');
            evidenceDir = path.resolve(argument.slice(15));
        } else throw new Error('UNKNOWN_BROWSER_CONTRACT_ARGUMENT');
    }
    const tests = discoverBrowserContracts(repository);
    if (list) {
        console.log(JSON.stringify(tests, null, 2));
    } else {
        evidenceDir ||= path.join(repository, '.verify/browser-contracts', randomUUID());
        const args = browserContractArguments(tests, evidenceDir);
        if (dryRun) {
            console.log(JSON.stringify({ tests, evidenceDir, mavenArguments: args }, null, 2));
        } else {
            // Fresh reports prevent an old successful run masking a skipped/missing class.
            fs.mkdirSync(path.dirname(evidenceDir), { recursive: true });
            fs.mkdirSync(evidenceDir);
            fs.writeFileSync(path.join(evidenceDir, 'manifest.json'), JSON.stringify({ tests, mavenArguments: args }, null, 2));
            console.log(`Running ${tests.length} local Chromium contract classes. Evidence: ${evidenceDir}`);
            const invocation = mavenInvocation(repository, args);
            const result = spawnSync(invocation.command, invocation.args, {
                cwd: repository, stdio: 'inherit', windowsHide: true, timeout: 1_080_000,
                env: { ...process.env, PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD: '1' }
            });
            if (result.error || result.signal || result.status !== 0) {
                console.error('BROWSER_CONTRACT_MAVEN_FAILED');
                process.exitCode = Number.isInteger(result.status) && result.status > 0 ? result.status : 1;
            } else {
                const summary = validateBrowserReports(tests, evidenceDir);
                fs.writeFileSync(path.join(evidenceDir, 'summary.json'), JSON.stringify(summary, null, 2));
                console.log(`PASS: ${summary.classes} Chromium contract classes, ${summary.tests} tests; none skipped.`);
            }
        }
    }
} catch (error) {
    console.error(error.message);
    process.exitCode = 2;
}
