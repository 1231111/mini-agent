import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

// Exercise the shipped renderer directly, including historical replies. No DOM,
// backend, network, or invented copy of the production rendering functions.
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const chat = path.join(repository, 'mini-agent-app/src/main/resources/static/js/chat');
const uploadSource = fs.readFileSync(path.join(chat, '04-media.js'), 'utf8');
const definitions = [
    ...['AUDIO_EXTS', 'VIDEO_EXTS'].map(name => {
        const found = uploadSource.match(new RegExp('const ' + name + ' = new Set\\([^;]+;'));
        assert.ok(found, 'Missing upload media extensions: ' + name);
        return found[0];
    }),
    uploadSource.match(/function fileExt\(name\) \{[\s\S]*?\n\}/)?.[0]
];
assert.ok(definitions[2], 'Missing production fileExt helper');
const context = vm.createContext({
    console, URL, currentId: 'owned-session',
    document: { body: null, addEventListener() {} },
    fetch() { assert.fail('Rendering must not fetch external pages or media'); }
});
vm.runInContext(definitions.join('\n'), context);
for (const file of ['13-markdown.js', '14-media-src.js']) {
    vm.runInContext(fs.readFileSync(path.join(chat, file), 'utf8'), context, { filename: file });
}
// File-grid DOM layout is covered in browser tests; all content and URL handling
// preceding that final layout step can run without a browser.
context.document = undefined;
const fence = data => '```miniagent-media\n' + JSON.stringify(data) + '\n```';
const render = text => context.renderMD(text);
const card = data => render(fence(data));
const attrs = (html, name) => [...html.matchAll(new RegExp('\\b' + name + '="([^"\\n]*)"', 'g'))].map(match => match[1]);
const tags = (html, tag) => [...html.matchAll(new RegExp('<' + tag + '\\b[^>]*>', 'gi'))].map(match => match[0]);
const eventAttributes = html => [...html.matchAll(/<[a-z][\w:-]*\b(?:[^"'<>]|"[^"]*"|'[^']*')*>/gi)]
    .flatMap(tag => [...tag[0].matchAll(/\s([^\s"'=<>`/]+)(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'=<>`]+))?/g)]
        .map(attribute => attribute[1]).filter(name => /^on/i.test(name)));

test('verified movie, video and song results render as semantic media cards rather than raw JSON', () => {
    const html = card({ title: '已核验的影视和歌曲', items: [
        { type: 'movie', title: '一部电影', description: '上映信息', posterUrl: 'https://cdn.example.com/poster.jpg',
            pageUrl: 'https://example.com/movie', releaseDate: '2026-11-13' },
        { type: 'video', title: '预告片', mediaUrl: 'https://cdn.example.com/trailer.mp4',
            pageUrl: 'https://example.com/trailer', duration: '02:30' },
        { type: 'song', title: '一首歌曲', creator: '歌手', mediaUrl: 'https://cdn.example.com/song.mp3',
            pageUrl: 'https://example.com/song', source: { label: '官方', url: 'https://example.com/source' } }
    ] });
    assert.match(html, /class="[^"]*media-result-card/);
    assert.equal(tags(html, 'article').filter(tag => /\bmedia-item\b/.test(tag)).length, 3);
    assert.match(html, /一部电影/);
    assert.match(html, /一首歌曲/);
    assert.match(html, /<video\b[^>]*\bcontrols\b/);
    assert.match(html, /<audio\b[^>]*\bcontrols\b/);
    assert.match(html, /\bdata-media-key=/);
    assert.match(html, /class="[^"]*media-page-link/);
    assert.doesNotMatch(html, /class="lang-miniagent-media"/);
    assert.doesNotMatch(html, /\bautoplay(?:\s|=|>)/i);
    assert.doesNotMatch(html, /<iframe\b/i);
});

test('a movie detail page is a clickable viewing entry and never masquerades as playable video', () => {
    const html = card({ items: [{ type: 'movie', title: '院线电影', description: '暂无可验证的播放直链',
        pageUrl: 'https://example.com/movie/123', posterUrl: 'https://cdn.example.com/123.jpg' }] });
    assert.match(html, /<img\b/);
    assert.ok(attrs(html, 'href').includes('https://example.com/movie/123'));
    assert.doesNotMatch(html, /<(?:video|audio|iframe)\b/);
});

test('audio alias and verified extensionless media with explicit MIME types remain playable', () => {
    const html = card({ items: [
        { type: 'audio', title: '音频', mediaUrl: 'https://cdn.example.com/stream?id=1', mimeType: 'audio/mpeg' },
        { type: 'video', title: '视频', mediaUrl: 'https://cdn.example.com/play?id=2', mimeType: 'video/mp4' }
    ] });
    assert.equal(tags(html, 'audio').length, 1);
    assert.equal(tags(html, 'video').length, 1);
});

test('ordinary Markdown audio/video links promote to native players while normal pages stay links', () => {
    const html = render('[歌曲](https://cdn.example.com/song.mp3)\n\n'
        + '[视频](https://cdn.example.com/trailer.mp4?token=public&lang=zh)\n\n'
        + '[详情](https://example.com/watch?v=123)');
    assert.equal(tags(html, 'audio').length, 1);
    assert.equal(tags(html, 'video').length, 1);
    assert.ok(attrs(html, 'href').includes('https://example.com/watch?v=123'));
    assert.match(html, /token=public&amp;lang=zh/);
    assert.doesNotMatch(html, /<img\b/);
});

test('bare media URLs become players and retain URL punctuation and escaped query parameters', () => {
    const html = render('音频：https://cdn.example.com/song.opus?public=1&lang=zh。\n\n'
        + 'https://cdn.example.com/trailer.webm\n\nhttps://example.com/movie');
    assert.equal(tags(html, 'audio').length, 1);
    assert.equal(tags(html, 'video').length, 1);
    assert.ok(attrs(html, 'src').some(value => value.endsWith('public=1&amp;lang=zh')));
    assert.ok(attrs(html, 'href').includes('https://example.com/movie'));
});

test('structured media cards render identically when history or tools provide Windows newlines', () => {
    const text = fence({ title: '历史媒体', items: [{ type: 'song', title: '歌曲',
        mediaUrl: 'https://cdn.example.com/song.mp3' }] });
    assert.equal(render(text.replace(/\n/g, '\r\n')), render(text));
    assert.match(render(text.replace(/\n/g, '\r\n')), /media-result-card/);
});

test('image Markdown syntax dispatches audio and video correctly, including signed query strings', () => {
    const html = render('![音乐](https://cdn.example.com/song.m4a?x=1&y=2)\n\n'
        + '![视频](https://cdn.example.com/trailer.mp4#preview)');
    assert.equal(tags(html, 'audio').length, 1);
    assert.equal(tags(html, 'video').length, 1);
    assert.doesNotMatch(html, /<img\b/);
    assert.match(html, /x=1&amp;y=2/);
});

test('workspace audio paths and Markdown links become authenticated audio players scoped to the session', () => {
    for (const input of ['workspace/song.mp3', '[歌曲](workspace/song.mp3)', '![歌曲](workspace/song.mp3)']) {
        const html = render(input);
        assert.equal(tags(html, 'audio').length, 1, input + '\n' + html);
        assert.match(html, /data-auth-src="\/api\/workspace-media\/song\.mp3\?sid=owned-session"/);
        assert.doesNotMatch(html, /\ssrc="\/api\//);
    }
});

test('generated and historical uploaded media preserve protected endpoint loading', () => {
    const html = render('[生成歌曲](/api/generated-media/7/song.mp3)\n\n'
        + '![已上传视频](/api/conversation-media/owned-session/video.mp4)');
    assert.equal(tags(html, 'audio').length, 1);
    assert.equal(tags(html, 'video').length, 1);
    assert.match(html, /data-auth-src="\/api\/generated-media\/7\/song.mp3"/);
    assert.match(html, /data-auth-src="\/api\/conversation-media\/owned-session\/video.mp4"/);
    assert.doesNotMatch(html, /\ssrc="\/api\//);
});

test('HTML in titles, descriptions and source labels is escaped and never becomes active markup', () => {
    const html = card({ title: '<img src=x onerror=alert(1)>', items: [{ type: 'song',
        title: '<svg onload=alert(2)>', description: '<script>alert(3)</script>',
        mediaUrl: 'https://cdn.example.com/song.mp3',
        source: { label: '<iframe src=x>', url: 'https://example.com/source' } }] });
    assert.match(html, /&lt;(?:svg|img)/);
    assert.doesNotMatch(html, /<(?:script|iframe)\b/i);
    assert.deepEqual(eventAttributes(html), []);
});

test('media card URL fields reject executable protocols, credentials and arbitrary internal endpoints', () => {
    for (const bad of ['javascript:alert(1)', 'data:text/html,<script>alert(1)</script>',
        'file:///C:/Windows/notepad.exe', 'https://user:secret@example.com/song.mp3',
        '//example.com/song.mp3', '/api/settings', 'https://example.com/\nmalformed.mp3']) {
        const html = card({ items: [{ type: 'song', title: '不可信来源', description: '安全降级',
            mediaUrl: bad, posterUrl: bad, pageUrl: bad, source: { label: '来源', url: bad } }] });
        for (const name of ['src', 'href', 'data-auth-src', 'data-auth-poster']) {
            for (const value of attrs(html, name)) {
                assert.ok(!/javascript:|data:text\/html|file:|user:secret|\/api\/settings|malformed/.test(value),
                    name + '=' + value + '\n' + html);
            }
        }
        assert.doesNotMatch(html, /<(?:script|iframe)\b/i);
    }
});

test('a page URL is not fed into a player when no playable source was verified', () => {
    const html = card({ items: [{ type: 'video', title: '页面结果',
        pageUrl: 'https://example.com/watch?id=1', mediaUrl: 'https://example.com/watch?id=1' }] });
    assert.ok(attrs(html, 'href').includes('https://example.com/watch?id=1'));
    assert.doesNotMatch(html, /<(?:video|audio)\b/);
});

test('malformed and oversized structured payloads safely fall back without breaking surrounding content', () => {
    for (const payload of ['{broken', 'null', '[]', '{"items":{}}',
        '{"items":[{"type":"song","title":"' + 'x'.repeat(200_000) + '"}]}']) {
        let html;
        assert.doesNotThrow(() => { html = render('之前的文字\n\n```miniagent-media\n' + payload + '\n```\n\n之后的文字'); });
        assert.match(html, /之前的文字/);
        assert.match(html, /之后的文字/);
        assert.doesNotMatch(html, /<(?:video|audio)\b/);
    }
});

test('unclosed structured media blocks display a compact pending card without leaking partial JSON', () => {
    for (const suffix of ['{"items":[{"type":"song","title":"正在生成',
        JSON.stringify({ items: [{ type: 'song', title: '已经收齐仍在等待闭合', mediaUrl: 'https://cdn.example.com/song.mp3' }] })]) {
        const html = render('检索结果\n\n```miniagent-media\n' + suffix);
        assert.match(html, /media-card-pending/);
        assert.doesNotMatch(html, /\{[\s\S]*"items"/);
        assert.doesNotMatch(html, /<(?:audio|video)\b/);
        assert.match(html, /检索结果/);
    }
});

test('code examples and ordinary page links do not accidentally create multimedia', () => {
    const html = render('```js\nconst demo = "https://cdn.example.com/song.mp3";\n```\n\n'
        + '[官方网站](https://example.com/music)');
    assert.match(html, /<pre>/);
    assert.doesNotMatch(html, /<(?:audio|video)\b/);
    assert.ok(attrs(html, 'href').includes('https://example.com/music'));
});

test('a historical reply renders deterministically with stable media keys', () => {
    const text = fence({ items: [{ type: 'song', title: '可恢复播放条目', mediaUrl: 'https://cdn.example.com/song.mp3' }] });
    const first = render(text);
    const second = render(text);
    assert.equal(first, second);
    const keys = attrs(first, 'data-media-key');
    assert.ok(keys.length > 0, first);
    assert.ok(keys.every(Boolean));
});
