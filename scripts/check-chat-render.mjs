#!/usr/bin/env node
/**
 * chat.html 渲染冒烟检查（不依赖浏览器、不依赖后端）。
 *
 * 为什么需要它：`node --check` 只查语法，查不出 **未定义的函数引用**。
 * 2026-10-22 实际踩到：`parseMediaBang` 里写了 `escQ(alt)`，而 `escQ` 从来没定义过 ——
 * 语法完全合法，直到模型第一次返回 `![生成的图片](/api/generated-media/...)` 才在
 * 渲染时抛 `ReferenceError: escQ is not defined`，整条回复变成一行红字。
 *
 * 做法：从模板源里切出渲染函数原文（不重写实现），在 node 里跑真实输入。
 * 用法：node scripts/check-chat-render.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const template = path.join(root, 'mini-agent-app/src/main/resources/templates/chat.html');
const src = fs.readFileSync(template, 'utf8');

/** 按起始标记切出一个完整函数体（花括号配平）。 */
function slice(startMarker) {
    const start = src.indexOf(startMarker);
    if (start < 0) throw new Error(`未找到函数: ${startMarker}（改动模板后请同步更新本脚本）`);
    let depth = 0;
    let started = false;
    for (let i = start; i < src.length; i++) {
        if (src[i] === '{') {
            depth++;
            started = true;
        } else if (src[i] === '}') {
            depth--;
            if (started && depth === 0) return src.slice(start, i + 1);
        }
    }
    throw new Error(`花括号不闭合: ${startMarker}`);
}

const pieces = [
    "const AUDIO_EXTS = new Set(['mp3', 'wav', 'flac', 'm4a', 'ogg']);",
    // mediaSrcAttr 依赖这个缓存；真实实现里它在 IIFE 作用域，这里给同形替身
    'const MEDIA_BLOB_CACHE = new Map();',
    slice('function fileExt(name)'),
    slice('function escA(s)'),
    slice('function esc(s)'),
    slice('function escQ(s)'),
    slice('function balancedUrl(s, open)'),
    slice('function isAudioUrl(url)'),
    slice('function mediaSrcAttr(url)'),
    slice('function parseMediaBang(s)'),
    slice('function workspaceDownloadPath(href)'),
    // 下载链接要带会话参数（会话可绑定自己的目录），缺了它会 ReferenceError
    'let currentId = null;',
    slice('function workspaceSidQuery()'),
    slice('function linkWorkspacePaths(s)'),
    slice('function parseBracketImg(s, label)'),
    slice('function parseLinks(s)'),
    slice('function renderMD(t)'),
    slice('function renderMDStreaming(t)'),
    slice('function dropLeadBlanks(s)'),
    slice('function splitStreamingMarkdown(text)'),
];

const sandbox = {};
new Function('exports', pieces.join('\n\n')
    + '\nexports.parseMediaBang = parseMediaBang;'
    + '\nexports.renderMD = renderMD;'
    + '\nexports.linkWorkspacePaths = linkWorkspacePaths;'
    + '\nexports.workspaceDownloadPath = workspaceDownloadPath;'
    + '\nexports.splitStreamingMarkdown = splitStreamingMarkdown;'
    + '\nexports.mediaSrcAttr = mediaSrcAttr;'
    + '\nexports.renderMDStreaming = renderMDStreaming;'
    + '\nexports.dropLeadBlanks = dropLeadBlanks;'
    + '\nexports.MEDIA_BLOB_CACHE = MEDIA_BLOB_CACHE;'
    + '\nexports.downloadClickHandler = true;')(sandbox);
const render = sandbox.parseMediaBang;

const cases = [
    {
        name: 'Markdown 工作区文档链接走受保护下载端点',
        input: '[workspace/冠虹尽头.docx](workspace/冠虹尽头.docx)',
        check: (h) => h.includes('data-auth-download="/api/workspace-download/%E5%86%A0%E8%99%B9%E5%B0%BD%E5%A4%B4.docx"')
            && h.includes('data-download-name="冠虹尽头.docx"')
            && h.includes('class="workspace-download-link"'),
    },
    {
        name: '纯文本 workspace/文件名 自动转换成下载链接',
        input: '文件：workspace/冠虹尽头.docx（8.2 KB，创建成功）',
        check: (h) => h.includes('data-auth-download="/api/workspace-download/%E5%86%A0%E8%99%B9%E5%B0%BD%E5%A4%B4.docx"')
            && h.includes('data-download-name="冠虹尽头.docx"')
            && h.includes('class="workspace-download-link"'),
    },
    {
        name: '拒绝下载隐藏文件与目录路径',
        input: '[secret](workspace/.env)',
        check: (h) => !h.includes('data-auth-download') && h.includes('[secret]'),
    },
    {
        name: '生成图走 /api/generated-media → 受保护媒体用 data-auth-src',
        input: '![生成的图片](/api/generated-media/7/img_1792656111658_7413.png)',
        check: (h) => h.includes('<img data-auth-src="/api/generated-media/7/img_1792656111658_7413.png"')
            && h.includes('alt="生成的图片"')
            && !/\ssrc="\/api\//.test(h),
    },
    {
        name: '音频仍是播放器',
        input: '![生成的歌曲](/api/generated-media/7/song_1.mp3)',
        check: (h) => h.includes('<audio controls'),
    },
    {
        name: '工作区图片走 /api/workspace-media',
        input: '![图](/api/workspace-media/cyberpunk_city_night.svg)',
        check: (h) => h.includes('<img data-auth-src="/api/workspace-media/cyberpunk_city_night.svg"'),
    },
    {
        name: '中文文件名（百分号编码）',
        input: '![图](/api/workspace-media/%E7%A7%8B%E6%97%A5.svg)',
        check: (h) => h.includes('%E7%A7%8B'),
    },
    {
        name: '站外 https 地址保持 src（不需要凭证）',
        input: '![图](https://example.com/a.png)',
        check: (h) => h.includes('<img src="https://example.com/a.png"') && !h.includes('data-auth-src'),
    },
    {
        name: 'alt 里的引号被转义，不截断属性',
        input: '![带"引号"的图](/api/generated-media/7/a.png)',
        check: (h) => h.includes('alt="带&quot;引号&quot;的图"'),
    },
    {
        name: 'alt 已转义过，不能二次转义 &',
        input: '![a &lt;b&gt;](/api/generated-media/7/a.png)',
        check: (h) => h.includes('alt="a &lt;b&gt;"') && !h.includes('&amp;lt;'),
    },
    {
        name: '非白名单地址不渲染成 img',
        input: '![x](/etc/passwd)',
        check: (h) => !h.includes('<img'),
    },
];

/* ---- splitStreamingMarkdown：流式增量渲染的切点 ---- */

/** 切点必须满足的三个不变量，任一破坏都会让正文跳 / 丢内容。 */
const splitCases = [
    {
        name: '空串不切',
        input: '',
        check: (r) => r.frozen === '' && r.tail === '',
    },
    {
        name: '单段无空行：全部留在 tail（还没到能定稿的时候）',
        input: '正在输入的这句话',
        check: (r) => r.frozen === '' && r.tail === '正在输入的这句话',
    },
    {
        name: '两段：切在最后一个空行，空行归 tail',
        input: '第一段\n\n第二段',
        check: (r) => r.frozen === '第一段' && r.tail === '\n\n第二段',
    },
    {
        name: '围栏已闭合时整块一起定稿，切点不落在围栏里',
        input: '前文\n\n```java\nint a = 1;\n\nint b = 2;\n```\n\n后文',
        // 关键不变量：frozen 里的 ``` 必须成对（不能把围栏切成两半），tail 紧接其后
        check: (r) => (r.frozen.match(/```/g) || []).length % 2 === 0
            && r.frozen.startsWith('前文')
            && r.frozen.endsWith('```')
            && r.tail === '\n\n后文',
    },
    {
        name: '末尾未闭合围栏之后的空行不算切点',
        input: '前文\n\n```java\nint a = 1;\n\nint b = 2;',
        check: (r) => r.frozen === '前文' && r.tail.includes('int b = 2;'),
    },
    {
        name: '拼接无损：frozen + tail 必须等于原文',
        input: 'a\n\nb\n\nc',
        check: (r, input) => r.frozen + r.tail === input && r.frozen === 'a\n\nb' && r.tail === '\n\nc',
    },
    {
        name: '只有空行时整段留在 tail',
        input: '\n\n',
        check: (r) => r.frozen === '' && r.tail === '\n\n',
    },
];

/* ---- mediaSrcAttr：已取回的媒体直接给 objectURL，避免重画时重新下载 ---- */
const srcCases = [
    {
        name: '缓存里有 objectURL → 直接写 src（重画不闪）',
        input: '/api/generated-media/7/a.png',
        setup: (url) => sandbox.MEDIA_BLOB_CACHE.set(url, { promise: Promise.resolve('blob:x'), objectUrl: 'blob:http://localhost/abc' }),
        check: (h) => h === 'src="blob:http://localhost/abc"',
    },
    {
        name: '缓存里只有 promise（还在下载）→ 保持 data-auth-src',
        input: '/api/generated-media/7/b.png',
        setup: (url) => sandbox.MEDIA_BLOB_CACHE.set(url, { promise: new Promise(() => {}), objectUrl: null }),
        check: (h) => h === 'data-auth-src="/api/generated-media/7/b.png"',
    },
];

let failed = 0;

function report(ok, name, input, extra) {
    if (ok) {
        console.log(`PASS   ${name}`);
    } else {
        console.error(`FAIL   ${name}\n       ${input}\n       → ${extra}`);
        failed++;
    }
}

for (const c of cases) {
    let html;
    try {
        const prepared = sandbox.linkWorkspacePaths(c.input);
        html = prepared.startsWith('!')
            ? sandbox.parseMediaBang(prepared)
            : sandbox.renderMD(prepared);
    } catch (e) {
        console.error(`THROW  ${c.name}\n       ${c.input}\n       → ${e}`);
        failed++;
        continue;
    }
    report(c.check(html), c.name, c.input, html);
}

for (const c of splitCases) {
    let r;
    try {
        r = sandbox.splitStreamingMarkdown(c.input);
    } catch (e) {
        console.error(`THROW  ${c.name}\n       → ${e}`);
        failed++;
        continue;
    }
    report(c.check(r, c.input), c.name, JSON.stringify(c.input), JSON.stringify(r));
}

/*
 * 前缀性（增量追加的命门）：真实流式是「同一个 string 逐字变长」。把一段带围栏的文本
 * 逐字喂进去，每一步的 frozen 都必须以前一步的 frozen 开头 —— 一旦倒退，
 * paintAnswerNow 的增量追加就会把重复内容粘上去，或者直接丢内容。
 */
{
    const full = '开头一段\n\n```java\nint a = 1;\n\nint b = 2;\n```\n\n中间一段\n\n结尾![图](/api/workspace-media/a.svg)\n';
    let prev = '';
    let broke = null;
    for (let i = 1; i <= full.length; i++) {
        const r = sandbox.splitStreamingMarkdown(full.slice(0, i));
        if (!r.frozen.startsWith(prev)) { broke = { i, prev, now: r.frozen }; break; }
        prev = r.frozen;
    }
    report(!broke, '逐字增长时 frozen 单调延长（前缀性）',
        broke ? `第 ${broke.i} 个字符处倒退` : `${full.length} 步`,
        broke ? `旧 frozen=${JSON.stringify(broke.prev)} 新 frozen=${JSON.stringify(broke.now)}` : '');
}

for (const c of srcCases) {
    if (c.setup) c.setup(c.input);
    let h;
    try {
        h = sandbox.mediaSrcAttr(c.input);
    } catch (e) {
        console.error(`THROW  ${c.name}\n       → ${e}`);
        failed++;
        continue;
    }
    report(c.check(h), c.name, c.input, h);
}

/*
 * 增量块单独渲染时的两个坑：
 * 1) 片段以空行开头，renderMD 会把 `\n\n` 变成 `</p><p>`，而片段里没有可闭合的 `<p>`，
 *    按 HTML 解析规则游离的 `</p>` 会凭空造出一个空 `<p></p>` —— 段间多 10px。
 * 2) 剥空行时不能顺手把内容行的缩进也吃掉。
 */
{
    const dropped = sandbox.dropLeadBlanks('\n\n  缩进行\n下一行');
    report(dropped === '  缩进行\n下一行', 'dropLeadBlanks 只吃行首空行，保住内容缩进',
        JSON.stringify('\n\n  缩进行\n下一行'), JSON.stringify(dropped));

    const { tail } = sandbox.splitStreamingMarkdown('第一段\n\n第二段');
    const raw = sandbox.renderMDStreaming(tail);
    const fixed = sandbox.renderMDStreaming(sandbox.dropLeadBlanks(tail));
    report(raw.startsWith('</p>') && fixed === '<p>第二段</p>',
        'tail 先剥空行再渲染：不产生游离的 </p>',
        JSON.stringify(tail), `raw=${JSON.stringify(raw)} fixed=${JSON.stringify(fixed)}`);
}

if (failed > 0) {
    console.error(`\n失败 ${failed} 项`);
    process.exit(1);
}
console.log('\n全部通过');
