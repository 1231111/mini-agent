#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

// No server, browser session, personal database, credentials or model API is needed.
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const scripts = path.join(repository, 'scripts');
const regressions = fs.readdirSync(scripts).filter(name => /^test-.*\.mjs$/.test(name)).sort()
    .map(name => path.join(scripts, name));
if (!regressions.length) { console.error('NO_CLIENT_REGRESSIONS_FOUND'); process.exit(2); }
const files = [...regressions,
    path.join(repository, 'mini-agent-desktop/test/window-bounds.test.cjs'),
    path.join(repository, 'scripts/agent-eval/quality-gate.test.mjs')];
for (const file of files) {
    if (!fs.statSync(file).isFile()) { console.error('MISSING_REQUIRED_REGRESSION'); process.exit(2); }
}
const result = spawnSync(process.execPath, ['--test', ...files], {
    cwd: repository, stdio: 'inherit', windowsHide: true, timeout: 600_000
});
process.exitCode = result.status === 0 ? 0 : result.status || 1;
