import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

// Real Markdown, reference hydration and download handlers; no browser dependency.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const chat = path.join(root, 'mini-agent-app/src/main/resources/static/js/chat');
const listeners = new Map();
let requests = [];
let responseStatus = 200;
const context = vm.createContext({
    console: { ...console, warn() {} },
    currentId: 'owned-session',
    AUDIO_EXTS: new Set(['mp3', 'wav']), VIDEO_EXTS: new Set(['mp4', 'webm']),
    fileExt: value => String(value).split('.').pop().toLowerCase(),
    document: {
        body: null,
        addEventListener(name, handler) { listeners.set(name, handler); },
        createElement() { throw new Error('Unexpected DOM creation in failure path'); }
    },
    window: {
        MiniAuth: { token: () => 'test-token' },
        alert() { throw new Error('A file error must not open a misleading modal'); },
        location: { replace() { throw new Error('Unexpected login redirect'); } }
    },
    fetch: async (url, options) => {
        requests.push({ url, method: options?.method || 'GET' });
        return { status: responseStatus, ok: responseStatus >= 200 && responseStatus < 300 };
    },
    setTimeout() {}, clearTimeout() {},
    URL, Date
});
for (const file of ['13-markdown.js', '14-media-src.js', '12-message.js']) {
    vm.runInContext(fs.readFileSync(path.join(chat, file), 'utf8'), context, { filename: file });
}

const inline = text => String.fromCharCode(96) + text + String.fromCharCode(96);
const tick = () => new Promise(resolve => setImmediate(resolve));
// This suite checks reference recognition and authenticated downloads. Attachment
// grid layout needs a real DOM and is covered by the browser render checks.
function render(text) {
    const document = context.document;
    try {
        context.document = undefined;
        return context.renderMD(text);
    } finally {
        context.document = document;
    }
}
function candidate(name) {
    const html = render(inline(name));
    const match = html.match(/data-workspace-file-check="([^"]+)"/);
    assert.ok(match, html);
    const url = match[1].replace(/&amp;/g, '&');
    const attrs = new Map([
        ['data-workspace-file-check', url], ['data-file-type', '测试文件'],
        ['aria-disabled', 'true'], ['href', '#'], ['data-download-name', name]
    ]);
    const hint = { textContent: '', setAttribute() {} };
    return {
        isConnected: true,
        getAttribute: name => attrs.get(name) || null,
        setAttribute: (name, value) => attrs.set(name, value),
        removeAttribute: name => attrs.delete(name),
        querySelector: () => hint,
        querySelectorAll: () => [],
        matches: selector => selector === '[data-workspace-file-check]' && attrs.has('data-workspace-file-check'),
        hint, url, attrs
    };
}

{
    const html = render(inline('start_*.bat') + '、' + inline('package.json'));
    assert.match(html, /<code>start_\*\.bat<\/code>/);
    assert.match(html, /<code>package\.json<\/code>/);
    assert.doesNotMatch(html, /start_.*<em>/);
    assert.doesNotMatch(html, /data-auth-download|data-workspace-file-check/);
    assert.match(render(inline('package.json')), /data-workspace-file-check/);
    assert.doesNotMatch(render(inline('start_*.bat')), /data-auth-download|data-workspace-file-check/);
    console.log('PASS: prose filenames and wildcards stay literal; standalone exact filenames are checked');
}
{
    const html = render(inline('<img src=x onerror=alert(1)>.html'));
    assert.doesNotMatch(html, /<img src=x/);
    assert.match(html, /&lt;img/);
    console.log('PASS: protected inline code remains escaped');
}
{
    requests = [];
    responseStatus = 404;
    const first = candidate('README.md');
    const second = candidate('README.md');
    context.hydrateWorkspaceFileReferences(first);
    context.hydrateWorkspaceFileReferences(second);
    await tick();
    assert.equal(requests.length, 1, 'checks share a cached HEAD request');
    assert.equal(requests[0].method, 'HEAD');
    assert.equal(first.getAttribute('data-auth-download'), null);
    assert.equal(first.getAttribute('aria-disabled'), 'true');
    assert.match(first.hint.textContent, /未提供下载/);
    console.log('PASS: missing source references do not pretend to be downloadable artifacts');
}
{
    responseStatus = 200;
    const file = candidate('generated_ui.html');
    context.hydrateWorkspaceFileReferences(file);
    await tick();
    assert.match(file.getAttribute('data-auth-download'), /sid=owned-session/);
    assert.equal(file.getAttribute('aria-disabled'), null);
    assert.match(file.hint.textContent, /点击下载/);
    console.log('PASS: a confirmed file enables its authenticated session download');
}
{
    const file = candidate('D:\\authorized-project\\src\\app.js');
    assert.match(file.url, /^\/api\/workspace-download\?path=D%3A%2Fauthorized-project%2Fsrc%2Fapp.js&sid=/);
    assert.equal(file.getAttribute('data-auth-download'), null);
    console.log('PASS: absolute references preserve their full path for server boundary validation');
}
{
    const file = candidate('workspace/docs/readme.md');
    assert.match(file.url, /^\/api\/workspace-download\?path=docs%2Freadme.md&sid=/);
    const html = render('交付文件： workspace/docs/readme.md');
    assert.match(html, /data-workspace-file-check="\/api\/workspace-download\?path=docs%2Freadme.md&amp;sid=/);
    console.log('PASS: nested workspace references resolve relative to the workspace root');
}
{
    vm.runInContext('currentId = null', context);
    const html = render(inline('README.md'));
    assert.doesNotMatch(html, /data-workspace-file-check|data-auth-download/);
    vm.runInContext("currentId = 'owned-session'", context);
    console.log('PASS: references without an owned session cannot become download links');
}
{
    assert.match(context.fileDownloadErrorMessage(401), /登录/);
    assert.doesNotMatch(context.fileDownloadErrorMessage(404), /登录/);
    assert.match(context.fileDownloadErrorMessage(400), /通配符/);
    const file = candidate('removed_after_check.md');
    file.setAttribute('data-auth-download', file.url);
    responseStatus = 404;
    let prevented = false;
    await listeners.get('click')({
        target: { closest: () => file },
        preventDefault() { prevented = true; }
    });
    assert.ok(prevented);
    assert.match(file.hint.textContent, /没有这个文件/);
    assert.equal(file.getAttribute('data-auth-download'), null);
    assert.equal(file.getAttribute('aria-disabled'), 'true');
    console.log('PASS: stale downloads show the actual file error in place without a login modal');
}
