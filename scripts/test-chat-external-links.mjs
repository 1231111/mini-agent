import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const { normalizeWebLink, openWebLink, installExternalLinkHandling } = require('../mini-agent-desktop/src/external-links.cjs');
const source = fs.readFileSync(path.join(repository, 'mini-agent-app/src/main/resources/static/js/chat/12-message.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

function renderer(openExternal) {
    const listeners = new Map();
    const document = {
        addEventListener: (name, listener) => listeners.set(name, listener),
        createElement: () => ({ className: '', textContent: '', setAttribute() {} })
    };
    const window = {
        location: new URL('http://127.0.0.1:8080/chat'),
        alert: () => assert.fail('Opening a reply link must not display a modal'),
        ...(openExternal ? { electronAPI: { openExternal } } : {})
    };
    const scope = vm.createContext({ document, window, URL, console, Promise });
    vm.runInContext(source, scope);
    const link = (href, options = {}) => {
        const attrs = new Map([['href', href]]);
        if (options.download) attrs.set('data-auth-download', '/api/workspace-download?path=report.html');
        return {
            getAttribute: name => attrs.get(name) ?? null,
            setAttribute: (name, value) => attrs.set(name, value),
            removeAttribute: name => attrs.delete(name),
            hasAttribute: name => attrs.has(name),
            closest: selector => selector === '.bot-body' && options.outside !== true ? {} : null,
            after(notice) {
                this.nextElementSibling = notice;
                notice.classList = { contains: name => notice.className === name };
                notice.remove = () => { this.nextElementSibling = null; };
            }
        };
    };
    const click = anchor => {
        const event = { target: { closest: () => anchor }, defaultPrevented: false,
            preventDefault() { this.defaultPrevented = true; } };
        return { event, handled: scope.handleChatExternalLink(event) };
    };
    return { scope, link, click };
}

test('all external link entrypoints accept Chinese web URLs and reject executable protocols or embedded credentials', () => {
    const normalized = normalizeWebLink('https://example.com/电影?片名=你好&lang=zh#预告片');
    assert.equal(new URL(normalized).searchParams.get('片名'), '你好');
    for (const url of ['file:///C:/Windows/notepad.exe', 'javascript:alert(1)', 'data:text/html,x',
        'ms-settings:privacy', '//example.com/', 'https://name:secret@example.com/',
        'https:\\example.com', 'https://example.com/\nmalformed', null, { toString: () => 'https://example.com' }])
        assert.equal(normalizeWebLink(url), null, String(url));
});

test('main process blocks popups and sends external navigation to the OS exactly once without replacing chat', async () => {
    const events = new Map(); const opened = [];
    let popup;
    installExternalLinkHandling({ setWindowOpenHandler: fn => { popup = fn; }, on: (name, fn) => events.set(name, fn) },
        async url => { opened.push(url); }, () => 'http://127.0.0.1:8080');
    assert.deepEqual(popup({ url: 'https://example.com/trailer' }), { action: 'deny' });
    await tick(); assert.deepEqual(opened, ['https://example.com/trailer']);
    let blocked = 0;
    events.get('will-navigate')({ preventDefault: () => blocked++ }, 'https://example.com/watch');
    events.get('will-navigate')({ preventDefault: () => blocked++ }, 'http://127.0.0.1:8080/knowledge');
    events.get('will-redirect')({ preventDefault: () => blocked++ }, 'file:///C:/private.txt');
    await tick();
    assert.equal(blocked, 2);
    assert.deepEqual(opened, ['https://example.com/trailer', 'https://example.com/watch']);
});

test('shell rejection returns a user message without exposing the native error or retrying', async () => {
    let calls = 0; let detailed;
    const result = await openWebLink('https://example.com', async () => { calls++; throw new Error('native private detail'); }, e => { detailed = e; });
    assert.equal(result.ok, false); assert.equal(calls, 1);
    assert.match(result.error, /默认浏览器/); assert.doesNotMatch(result.error, /native private detail/);
    assert.equal(detailed.message, 'native private detail');
});

test('reply links use the desktop bridge once, including nested click targets, without a popup', async () => {
    const opened = []; let finish;
    const env = renderer(url => { opened.push(url); return new Promise(resolve => { finish = resolve; }); });
    const anchor = env.link('https://example.com/歌曲?id=123');
    assert.equal(env.click(anchor).event.defaultPrevented, true);
    assert.equal(env.click(anchor).event.defaultPrevented, true);
    await tick(); assert.equal(opened.length, 1);
    assert.equal(new URL(opened[0]).pathname, '/%E6%AD%8C%E6%9B%B2');
    finish({ ok: true }); await tick();
    assert.equal(anchor.getAttribute('data-external-opening'), null);
});

test('an unsuccessful open is explained inline and can be explicitly retried', async () => {
    let calls = 0;
    const env = renderer(async () => ++calls === 1 ? { ok: false, error: '无法打开系统浏览器。' } : { ok: true });
    const anchor = env.link('https://example.com/movie');
    env.click(anchor); await tick();
    assert.equal(calls, 1); assert.match(anchor.nextElementSibling.textContent, /系统浏览器/);
    env.click(anchor); await tick();
    assert.equal(calls, 2); assert.equal(anchor.nextElementSibling, null);
});

test('ordinary browser links keep native navigation; protected downloads and internal pages keep their own route', () => {
    const env = renderer();
    const anchor = env.link('https://example.com/watch');
    assert.equal(env.click(anchor).handled, false); assert.equal(anchor.target, '_blank'); assert.equal(anchor.rel, 'noopener noreferrer');
    assert.equal(env.click(env.link('/knowledge')).handled, false);
    assert.equal(env.click(env.link('https://example.com/file', { download: true })).handled, false);
    assert.equal(env.click(env.link('https://example.com/file', { outside: true })).handled, false);
    const credential = env.link('https://user:secret@example.com/watch');
    assert.equal(env.click(credential).event.defaultPrevented, true);
    assert.match(credential.nextElementSibling.textContent, /账号密码/);
});
