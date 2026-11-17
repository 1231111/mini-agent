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
const newsFence = data => '```miniagent-news\n' + JSON.stringify(data) + '\n```';
const newsCard = data => render(newsFence(data));
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

test('news replies render compact source-linked thumbnails and metadata without raw JSON', () => {
    const html = newsCard({ title: '今日 AI 动态', items: [{ title: '研究团队发布新模型',
        summary: '官方介绍性能和可用范围。', url: 'https://example.com/news?lang=zh&from=agent',
        source: { label: '官方新闻' }, publishedAt: '2026-11-16', thumbnailUrl: 'https://cdn.example.com/news.jpg' }] });
    assert.match(html, /class="news-result-card"/);
    assert.match(html, /class="news-thumbnail"[^>]+href="https:\/\/example.com\/news\?lang=zh&amp;from=agent"/);
    assert.equal(tags(html, 'img').length, 1);
    assert.match(html, /class="news-title-link"/);
    assert.match(html, /官方新闻/);
    assert.match(html, /2026-11-16/);
    assert.match(html, /官方介绍性能/);
    assert.doesNotMatch(html, /lang-miniagent-news|"thumbnailUrl"|<iframe/);
});

test('news dates accept native web_search and common structured publication field names', () => {
    for (const field of ['publishedAt', 'published_at', 'publishedDate', 'published_date',
        'publicationDate', 'publication_date', 'datePublished', 'date_published', 'pubDate', 'pub_date', 'date']) {
        const html = newsCard({ items: [{ title: '新闻', url: 'https://example.com/news', [field]: '2026-11-16' }] });
        assert.match(html, /class="news-published">2026-11-16<\/span>/, field);
    }
    const escaped = newsCard({ items: [{ title: '新闻', publication_date: '<script>date</script>' }] });
    assert.match(escaped, /class="news-published">&lt;script&gt;date&lt;\/script&gt;/);
    assert.doesNotMatch(escaped, /<script\b/);
});

test('remote covers require HTTPS while HTTP source pages remain clickable without rewriting', () => {
    for (const renderCard of [newsCard, card]) {
        const item = { type: 'video', title: '来源配图', url: 'http://example.com/original',
            thumbnail_url: 'http://cdn.example.com/cover.jpg' };
        const html = renderCard({ items: [item] });
        assert.equal(tags(html, 'img').length, 0, html);
        assert.ok(attrs(html, 'href').every(url => url === 'http://example.com/original'), html);
        assert.ok(attrs(html, 'href').length > 0, html);
        assert.doesNotMatch(html, /https:\/\/example.com\/original|https:\/\/cdn.example.com\/cover.jpg/);
        const fallback = renderCard({ items: [{ ...item, image_url: 'https://cdn.example.com/safe.jpg' }] });
        assert.deepEqual(attrs(fallback, 'src'), ['https://cdn.example.com/safe.jpg']);
    }
    const album = card({ items: [{ type: 'song', title: '歌曲', album: {
        cover_url: 'http://cdn.example.com/album.jpg', image_url: 'https://cdn.example.com/album-safe.jpg'
    } }] });
    assert.deepEqual(attrs(album, 'src'), ['https://cdn.example.com/album-safe.jpg']);
});

test('cover HTTPS policy preserves all supported authenticated local image routes', () => {
    for (const url of ['/api/generated-media/7/cover.png', '/api/conversation-media/owned-session/cover.png',
        '/api/workspace-media/%E5%B0%81%E9%9D%A2.png?sid=owned-session']) {
        for (const renderCard of [newsCard, card]) {
            const html = renderCard({ items: [{ type: 'video', title: '本地图', thumbnail_url: url }] });
            assert.deepEqual(attrs(html, 'data-auth-src'), [url], html);
            assert.equal(tags(html, 'img').length, 1, html);
            assert.doesNotMatch(html, /\ssrc="\/api\//);
        }
    }
});

test('news and media share safe camel/snake thumbnail, image, poster and cover aliases', () => {
    for (const field of ['thumbnailUrl', 'thumbnail_url', 'imageUrl', 'image_url', 'posterUrl', 'poster_url', 'coverUrl', 'cover_url']) {
        for (const value of ['https://cdn.example.com/cover.jpg', { url: 'https://cdn.example.com/cover.jpg' }]) {
            const news = newsCard({ items: [{ title: '新闻', url: 'https://example.com/news', [field]: value }] });
            const media = card({ items: [{ type: 'video', title: '视频', page_url: 'https://example.com/watch', [field]: value }] });
            assert.ok(attrs(news, 'src').includes('https://cdn.example.com/cover.jpg'), field + '\n' + news);
            assert.ok(attrs(media, 'src').includes('https://cdn.example.com/cover.jpg'), field + '\n' + media);
        }
    }
});

test('unsafe primary cover values do not override later verified aliases', () => {
    const html = newsCard({ items: [{ title: '新闻', url: 'https://example.com/news',
        posterUrl: 'javascript:alert(1)', thumbnail_url: 'https://cdn.example.com/safe.jpg' }] });
    assert.deepEqual(attrs(html, 'src'), ['https://cdn.example.com/safe.jpg']);
    assert.deepEqual(eventAttributes(html), []);
});

test('news with no source image uses a quiet icon without inventing an image request', () => {
    const html = newsCard({ items: [{ title: '无配图新闻', summary: '来源没有提供配图。',
        url: 'https://news.example.com/item', published_at: '2026-11-16' }] });
    assert.equal(tags(html, 'img').length, 0);
    assert.match(html, /class="news-thumbnail"/);
    assert.match(html, /<svg\b/);
    assert.match(html, /news.example.com/);
    assert.ok(attrs(html, 'href').every(url => url === 'https://news.example.com/item'));
});

test('news group size is bounded and invalid entries do not break valid cards', () => {
    const items = Array.from({ length: 12 }, (_, i) => ({ title: '新闻 ' + i, url: 'https://example.com/' + i }));
    const html = newsCard({ items });
    assert.equal(tags(html, 'article').filter(tag => /\bnews-item\b/.test(tag)).length, 8);
    assert.doesNotMatch(html, /新闻 8|新闻 9|新闻 10|新闻 11/);
    const mixed = newsCard({ items: [null, [], 'invalid', { title: '有效新闻', url: 'https://example.com/valid' }] });
    assert.equal(tags(mixed, 'article').length, 1);
    assert.match(mixed, /有效新闻/);
});

test('news source fallback accepts tool metadata and preserves protected thumbnails', () => {
    const html = newsCard({ items: [{ title: '历史新闻', source: { name: '原始站点' },
        thumbnail_source_url: 'https://example.com/original', thumbnail_source: 'og:image',
        thumbnail_url: '/api/conversation-media/owned-session/news.png' }] });
    assert.ok(attrs(html, 'href').includes('https://example.com/original'));
    assert.match(html, /原始站点/);
    assert.match(html, /data-auth-src="\/api\/conversation-media\/owned-session\/news.png"/);
    assert.doesNotMatch(html, /\ssrc="\/api\//);
});

test('news text and every source/thumbnail alias reject executable markup and unsafe URL schemes', () => {
    for (const bad of ['javascript:alert(1)', 'data:image/svg+xml,<svg onload=alert(1)>',
        'https://name:secret@example.com/news', '//example.com/news', '/api/settings',
        'https://example.com/\nnews']) {
        const html = newsCard({ title: '<script>alert(1)</script>', items: [{
            title: '<img src=x onerror=alert(2)>', summary: '<svg onload=alert(3)>', url: bad,
            source: { label: '<iframe src=x>', url: bad }, publishedAt: '<script>date</script>',
            thumbnail_url: bad, image_url: bad, poster_url: bad, coverUrl: bad, thumbnail_source_url: bad }] });
        assert.deepEqual(attrs(html, 'href'), []);
        assert.deepEqual(attrs(html, 'src'), []);
        assert.deepEqual(attrs(html, 'data-auth-src'), []);
        assert.deepEqual(eventAttributes(html), []);
        assert.doesNotMatch(html, /<(?:script|iframe|img)\b/i);
        assert.match(html, /&lt;img/);
    }
});

test('partial, malformed and excessive news JSON never leaks protocol content to the reply', () => {
    for (const payload of ['{broken', 'null', '[]', '{"items":{}}',
        JSON.stringify({ items: [{ title: 'x'.repeat(100001) }] })]) {
        const html = render('前文\n\n```miniagent-news\n' + payload + '\n```\n\n后文');
        assert.match(html, /前文/);
        assert.match(html, /后文/);
        assert.match(html, /news-card-pending/);
        assert.doesNotMatch(html, /"items"|lang-miniagent-news/);
    }
    const partial = render('检索中\n\n```miniagent-news\n{"items":[{"title":"尚未完整');
    assert.match(partial, /news-card-pending/);
    assert.doesNotMatch(partial, /"items"|尚未完整/);
});

test('news history is deterministic across rerenders and Windows newlines without fetching pages', () => {
    const text = newsFence({ items: [{ title: '可恢复新闻', url: 'https://example.com/news',
        thumbnail_url: 'https://cdn.example.com/news.jpg', published_at: '2026-11-16' }] });
    assert.equal(render(text), render(text));
    assert.equal(render(text), render(text.replace(/\n/g, '\r\n')));
    const ordinary = render('[普通链接](https://example.com/news)');
    assert.doesNotMatch(ordinary, /news-result-card|<img\b/);
});

test('a video with a source thumbnail and only a page URL gets a clickable cover, not an invented player', () => {
    const html = card({ items: [{ type: 'video', title: '观看视频',
        pageUrl: 'https://example.com/watch?id=1', thumbnail_url: 'https://cdn.example.com/video.jpg' }] });
    assert.match(html, /<a\b[^>]*class="media-cover media-cover--video"[^>]*href="https:\/\/example.com\/watch\?id=1"/);
    assert.match(html, /<img\b/);
    assert.doesNotMatch(html, /<(?:video|audio|iframe)\b/);
});

test('song aliases retain the player, linked album cover, artist and album metadata', () => {
    const html = card({ items: [{ type: 'song', title: '一首新歌', artist: '歌手',
        album: { title: '专辑名称', cover_url: 'https://cdn.example.com/album.jpg' },
        page_url: 'https://example.com/song', audio_url: '/api/generated-media/7/song.mp3', mime_type: 'audio/mpeg' }] });
    assert.match(html, /歌手 · 专辑名称/);
    assert.match(html, /class="media-cover media-cover--song"/);
    assert.ok(attrs(html, 'src').includes('https://cdn.example.com/album.jpg'));
    assert.equal(tags(html, 'audio').length, 1);
    assert.match(html, /data-auth-src="\/api\/generated-media\/7\/song.mp3"/);
});

test('source-linked covers skip the image lightbox while standalone workspace images retain it', () => {
    const messageSource = fs.readFileSync(path.join(chat, '12-message.js'), 'utf8');
    const definition = messageSource.match(/function bindLightbox\(row\) \{[\s\S]*?\n\}/)?.[0];
    assert.ok(definition, 'Missing production bindLightbox helper');
    vm.runInContext(definition, context);
    const linkedCover = { style: {}, closest: selector => selector === 'a[href]' ? {} : null };
    const standaloneWorkspaceImage = { style: {}, closest: () => null };
    context.bindLightbox({ querySelectorAll: () => [linkedCover, standaloneWorkspaceImage] });
    assert.equal(linkedCover.onclick, undefined);
    assert.equal(linkedCover.style.cursor, undefined);
    assert.equal(standaloneWorkspaceImage.style.cursor, 'zoom-in');
    assert.equal(typeof standaloneWorkspaceImage.onclick, 'function');
});

test('failed thumbnails expose their icon fallback even when an image fails before hydration', () => {
    const handlers = new Map();
    const image = { tagName: 'IMG', complete: true, naturalWidth: 0, hidden: false,
        getAttribute: name => name === 'src' ? 'https://cdn.example.com/missing.jpg' : null,
        addEventListener: (name, callback) => handlers.set(name, callback) };
    context.hydrateProtectedMedia({
        querySelectorAll: selector => selector === 'audio,video,img[data-media-poster]' ? [image] : [],
        matches: () => false
    });
    assert.equal(image.hidden, true);
    handlers.get('load')();
    assert.equal(image.hidden, false);
    handlers.get('error')();
    assert.equal(image.hidden, true);
});

test('explicit movie entries in a news fence use movie posters and retain their original evidence', () => {
    const html = newsCard({ title: '近一个月上线的电影', items: [
        { type: 'movie', title: '已核实电影', summary: '上映信息已核实。', source: '电影发行方',
            url: 'https://example.com/movie', thumbnail_url: 'https://cdn.example.com/poster.jpg' }
    ] });
    assert.match(html, /class="media-result-card"/);
    assert.match(html, /data-media-type="movie"/);
    assert.match(html, /class="media-kind">电影<\/span>/);
    assert.match(html, /上映信息已核实/);
    assert.match(html, /电影发行方/);
    assert.deepEqual(attrs(html, 'src'), ['https://cdn.example.com/poster.jpg']);
    assert.ok(attrs(html, 'href').includes('https://example.com/movie'));
    assert.doesNotMatch(html, /news-item|news-thumbnail|>新闻<|<(?:audio|video)\b/);
});

test('pure media in either fence uses the same sixteen-item cap while news keeps its eight-item cap', () => {
    const items = Array.from({ length: 20 }, (_, i) => ({ type: 'movie', title: 'Movie ' + i,
        pageUrl: 'https://example.com/movie/' + i }));
    const payload = { title: '电影列表', items };
    const media = card(payload);
    const corrected = newsCard(payload);
    assert.equal(tags(media, 'article').length, 16);
    assert.equal(tags(corrected, 'article').length, 16);
    assert.equal(corrected, media);
    assert.match(corrected, /Movie 15/);
    assert.doesNotMatch(corrected, /Movie 16|Movie 17|Movie 18|Movie 19/);
    const news = newsCard({ items: items.map(({ type, ...item }) => item) });
    assert.equal(tags(news, 'article').length, 8);
    assert.doesNotMatch(news, /Movie 8|Movie 9/);
    const oversized = newsCard({ items: [{ type: 'movie', title: 'x'.repeat(100001) }] });
    assert.match(oversized, /news-card-pending/);
    assert.equal(tags(oversized, 'article').length, 0);
});

test('explicit list media types work in both fences without overriding incompatible item types', () => {
    for (const renderCard of [card, newsCard]) {
        const html = renderCard({ type: 'movie', items: [{ title: '影片', pageUrl: 'https://example.com/movie' }] });
        assert.match(html, /data-media-type="movie"/);
        assert.match(html, /data-cover-state="missing"/);
        assert.match(html, /暂无海报/);
        assert.doesNotMatch(html, /news-thumbnail|<img\b/);
    }
    const incompatible = newsCard({ type: 'movie', items: [{ type: 'article', title: '影评新闻',
        url: 'https://example.com/review' }] });
    assert.match(incompatible, /class="news-item"/);
    assert.doesNotMatch(incompatible, /data-media-type="movie"/);
});

test('mixed structured news and movie entries retain both entries with their explicit card types', () => {
    const html = newsCard({ items: [
        { type: 'movie', title: '上映影片', url: 'https://example.com/movie' },
        { title: '电影产业新闻', summary: '一篇关于电影的新闻。', url: 'https://example.com/news' }
    ] });
    assert.equal(tags(html, 'article').length, 2);
    assert.equal(tags(html, 'article').filter(tag => /data-media-type="movie"/.test(tag)).length, 1);
    assert.equal(tags(html, 'article').filter(tag => /class="news-item"/.test(tag)).length, 1);
    assert.match(html, /暂无海报/);
    assert.match(html, /暂无配图/);
});

test('untyped historical news is never reclassified from movie words in its title or summary', () => {
    const html = newsCard({ title: '近一个月上线的电影新闻', items: [{ title: '《某电影》上映',
        summary: '电影产业分析，不代表一部电影的详情。', url: 'https://example.com/article' }] });
    assert.match(html, /class="news-item"/);
    assert.match(html, /暂无配图/);
    assert.doesNotMatch(html, /data-media-type="movie"|>新闻<|<img\b/);
});

test('movie missing covers and supplied covers start with distinct states and accurate link labels', () => {
    const html = card({ items: [
        { type: 'movie', title: '无海报电影', pageUrl: 'https://example.com/no-poster' },
        { type: 'movie', title: '有海报电影', pageUrl: 'https://example.com/poster',
            posterUrl: 'https://cdn.example.com/poster.jpg' }
    ] });
    assert.equal(attrs(html, 'data-cover-state').filter(state => state === 'missing').length, 1);
    assert.equal(attrs(html, 'data-cover-state').filter(state => state === 'loading').length, 1);
    assert.match(html, /暂无海报/);
    assert.match(html, /aria-label="打开电影原网页：有海报电影"/);
});

test('image failures and successful recovery update a visible status without conflating missing metadata', () => {
    for (const news of [false, true]) {
        const handlers = new Map();
        const attributes = new Map([['data-cover-state', 'loading']]);
        const label = { textContent: '加载中' };
        const cover = {
            setAttribute: (name, value) => attributes.set(name, value),
            querySelector: () => label, classList: { contains: name => news && name === 'news-thumbnail' }
        };
        const image = { tagName: 'IMG', complete: true, naturalWidth: 0, hidden: false,
            closest: () => cover, getAttribute: name => name === 'src' ? 'https://cdn.example.com/poster.jpg' : null,
            addEventListener: (name, callback) => handlers.set(name, callback) };
        context.hydrateProtectedMedia({
            querySelectorAll: selector => selector === 'audio,video,img[data-media-poster]' ? [image] : [],
            matches: () => false
        });
        assert.equal(attributes.get('data-cover-state'), 'failed');
        assert.equal(label.textContent, news ? '配图加载失败' : '封面加载失败');
        assert.equal(image.hidden, true);
        handlers.get('load')();
        assert.equal(attributes.get('data-cover-state'), 'loaded');
        assert.equal(image.hidden, false);
        handlers.get('error')();
        assert.equal(attributes.get('data-cover-state'), 'failed');
        assert.equal(image.hidden, true);
    }
});
