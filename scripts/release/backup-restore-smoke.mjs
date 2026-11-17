#!/usr/bin/env node
/**
 * A deterministic backup/restore rehearsal for release CI.  The production
 * deployment supplies its DB/object-store backup command; this check proves
 * the archive manifest, checksums and restore path fail closed before release.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'miniagent-backup-'));
const source = path.join(root, 'source');
const archive = path.join(root, 'backup.tar');
const restore = path.join(root, 'restore');
fs.mkdirSync(path.join(source, 'planner'), { recursive: true });
fs.writeFileSync(path.join(source, 'planner', 'state.json'), '{"version":1,"status":"RUNNING"}\n');
fs.writeFileSync(path.join(source, 'memory.json'), '{"facts":["stable"]}\n');
const digest = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const files = [];
for (const relative of ['planner/state.json', 'memory.json']) {
  const file = path.join(source, relative);
  files.push({ path: relative, sha256: digest(file), bytes: fs.statSync(file).size });
}
const manifest = path.join(source, 'MANIFEST.json');
fs.writeFileSync(manifest, JSON.stringify({ schemaVersion: 1, files }, null, 2));
if (process.platform === 'win32') {
  execFileSync('tar', ['-cf', archive, '-C', source, '.'], { stdio: 'ignore' });
  fs.mkdirSync(restore);
  execFileSync('tar', ['-xf', archive, '-C', restore], { stdio: 'ignore' });
} else {
  execFileSync('tar', ['-cf', archive, '-C', source, '.'], { stdio: 'ignore' });
  fs.mkdirSync(restore);
  execFileSync('tar', ['-xf', archive, '-C', restore], { stdio: 'ignore' });
}
const restoredManifest = JSON.parse(fs.readFileSync(path.join(restore, 'MANIFEST.json'), 'utf8'));
for (const item of restoredManifest.files ?? []) {
  const file = path.join(restore, item.path);
  if (!fs.statSync(file).isFile() || digest(file) !== item.sha256
      || fs.statSync(file).size !== item.bytes) throw new Error(`BACKUP_RESTORE_MISMATCH:${item.path}`);
}
process.stdout.write(`backup restore smoke passed (${files.length} files)\n`);
