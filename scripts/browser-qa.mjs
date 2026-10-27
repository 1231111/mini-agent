#!/usr/bin/env node
/**
 * 无头 Edge 行为验收 harness。
 *
 * 为什么不用 --dump-dom：它只在 load 事件后拍一次快照，异步断言（fetch、图片 decode、
 * requestAnimationFrame）基本都来不及跑完，结果永远是「没抓到」而不是「失败」。
 *
 * 这里起一个本地 HTTP 源（页面用真实 http:// 而不是 file://，否则 /js/security.js 加载不到、
 * 页面自举路径和线上不一致），再用 CDP 连上无头 Edge，轮询页面里的 window.__qaResults。
 *
 * 用法：node scripts/browser-qa.mjs <被测页面路径> [超时毫秒] [截图输出路径]
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const pagePath = process.argv[2];
const timeoutMs = Number(process.argv[3] || 30000);
const shotPath = process.argv[4];
if (!pagePath) {
    console.error('用法: node scripts/browser-qa.mjs <html 文件> [超时毫秒]');
    process.exit(2);
}
const page = fs.readFileSync(pagePath, 'utf8');
const securityJs = fs.readFileSync(
    path.join(root, 'mini-agent-app/src/main/resources/static/js/security.js'), 'utf8');

const MIME = { '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8' };

const server = http.createServer((req, res) => {
    const url = (req.url || '/').split('?')[0];
    if (url === '/' || url === '/index.html') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(page);
        return;
    }
    if (url === '/js/security.js') {
        res.writeHead(200, { 'Content-Type': MIME['.js'] });
        res.end(securityJs);
        return;
    }
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end('{}');
});

const EDGE_CANDIDATES = [
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
];
const browserPath = EDGE_CANDIDATES.find(p => fs.existsSync(p));
if (!browserPath) {
    console.error('未找到 Edge/Chrome 可执行文件');
    process.exit(2);
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

function listen() {
    return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));
}

async function waitFile(file, deadline) {
    while (Date.now() < deadline) {
        try {
            const text = fs.readFileSync(file, 'utf8').trim();
            if (text) return text.split(/\r?\n/);
        } catch (_) { /* 还没写出来 */ }
        await sleep(50);
    }
    throw new Error('等待 DevToolsActivePort 超时: ' + file);
}

async function cdp(wsUrl, message) {
    const socket = new WebSocket(wsUrl);
    const pending = new Map();
    let nextId = 1;
    await new Promise((resolve, reject) => {
        socket.addEventListener('open', resolve, { once: true });
        socket.addEventListener('error', () => reject(new Error('CDP WebSocket 连接失败')), { once: true });
    });
    socket.addEventListener('message', event => {
        let payload;
        try { payload = JSON.parse(event.data); } catch (_) { return; }
        if (!payload.id || !pending.has(payload.id)) return;
        const { resolve, reject } = pending.get(payload.id);
        pending.delete(payload.id);
        if (payload.error) reject(new Error(payload.error.message));
        else resolve(payload.result);
    });
    const send = (method, params = {}) => new Promise((resolve, reject) => {
        const id = nextId++;
        pending.set(id, { resolve, reject });
        socket.send(JSON.stringify({ id, method, params }));
    });
    try {
        return await message(send);
    } finally {
        socket.close();
    }
}

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'ma-qa-'));
const port = await listen();
const url = `http://127.0.0.1:${port}/`;
const child = spawn(browserPath, [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--remote-debugging-port=0',
    `--user-data-dir=${userDataDir}`,
    '--window-size=1280,900',
    'about:blank',
], { stdio: 'ignore' });

let exitCode = 1;
try {
    const deadline = Date.now() + timeoutMs;
    const [devtoolsPort] = await waitFile(path.join(userDataDir, 'DevToolsActivePort'), deadline);
    const endpoints = await (await fetch(`http://127.0.0.1:${devtoolsPort}/json/list`)).json();
    const target = endpoints.find(t => t.type === 'page');
    if (!target) throw new Error('没有可用的 page target');

    const results = await cdp(target.webSocketDebuggerUrl, async send => {
        await send('Page.enable');
        await send('Runtime.enable');
        await send('Page.navigate', { url });
        while (Date.now() < deadline) {
            const probe = await send('Runtime.evaluate', {
                expression: 'window.__qaResults || ""',
                returnByValue: true,
            });
            const value = probe?.result?.value;
            if (value) {
                if (shotPath) {
                    const shot = await send('Page.captureScreenshot', { format: 'png' });
                    fs.mkdirSync(path.dirname(path.resolve(shotPath)), { recursive: true });
                    fs.writeFileSync(shotPath, Buffer.from(shot.data, 'base64'));
                    console.log('截图: ' + shotPath);
                }
                return value;
            }
            await sleep(100);
        }
        const err = await send('Runtime.evaluate', {
            expression: 'document.body ? document.body.innerText.slice(0, 600) : "no body"',
            returnByValue: true,
        });
        throw new Error('页面未产出结果。页面可见文本片段：' + (err?.result?.value || ''));
    });

    const lines = String(results).split('\n').filter(Boolean);
    let failed = 0;
    for (const line of lines) {
        if (!line.startsWith('PASS')) failed++;
        console.log(line);
    }
    console.log(`\n${lines.length - failed}/${lines.length} 通过`);
    exitCode = failed || lines.length === 0 ? 1 : 0;
} catch (error) {
    console.error('FAIL 验收执行失败: ' + error.message);
    exitCode = 1;
} finally {
    child.kill();
    server.close();
    await sleep(150);
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (_) { /* 临时目录可能被浏览器占用 */ }
}

process.exit(exitCode);
