#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = fs.readFileSync(path.join(root, 'mini-agent-app/src/main/resources/templates/chat.html'), 'utf8');
const checks = [
    ['lightbox contains zoom controls', source.includes('onclick="changeLightboxScale(0.2)"') && source.includes('id="lbZoomLabel"')],
    ['lightbox can fit an image to stage width', source.includes('onclick="fitLightboxImage()">适应宽度</button>')],
    ['lightbox provides image download', source.includes('onclick="downloadLightboxImage()">下载</button>') && source.includes("link.download = 'architecture-diagram.' + ext")],
    ['lightbox copies image data to clipboard', source.includes('onclick="copyLightboxImage()">复制</button>') && source.includes("navigator.clipboard.write([new ClipboardItem({ 'image/png': png })])")],
    ['wheel zoom is bound to the preview stage', source.includes("document.getElementById('lightboxStage').addEventListener('wheel'")],
    ['zoom has bounded scale and scrollable stage', source.includes('Math.max(0.05, Math.min(4, scale))') && source.includes('overflow: auto;')],
];

const mainScriptStart = source.lastIndexOf('<script th:inline="none">', source.indexOf('function openLightbox'));
const mainScriptEnd = source.indexOf('</script>', mainScriptStart);
const mainScript = source.slice(source.indexOf('>', mainScriptStart) + 1, mainScriptEnd);
if (mainScriptStart < 0 || mainScriptEnd < 0) throw new Error('找不到包含 lightbox 的内联脚本');
try {
    new Function(mainScript);
    checks.push(['inline JavaScript parses', true]);
} catch (error) {
    checks.push(['inline JavaScript parses', false]);
    console.error(error.stack || error);
}

let failed = 0;
for (const [name, ok] of checks) {
    console.log(`${ok ? 'PASS' : 'FAIL'} ${name}`);
    if (!ok) failed++;
}
process.exitCode = failed ? 1 : 0;
