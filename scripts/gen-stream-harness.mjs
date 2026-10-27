#!/usr/bin/env node
/**
 * 生成一个离线的流式绘制对照页面：
 *   old = 每次全量 innerHTML = renderMDStreaming(全文)
 *   new = 当前实现（splitStreamingMarkdown + 只追加定稿块 + 只重画 tail）
 *
 * 渲染函数直接从 chat.html 里切出来，不重写实现。
 * 页面跑完把测量结果写进 <pre id="out">，用 --dump-dom 取回。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const src = fs.readFileSync(path.join(root, 'mini-agent-app/src/main/resources/templates/chat.html'), 'utf8');

function slice(marker) {
    const start = src.indexOf(marker);
    if (start < 0) throw new Error('未找到: ' + marker);
    let depth = 0, started = false;
    for (let i = start; i < src.length; i++) {
        if (src[i] === '{') { depth++; started = true; }
        else if (src[i] === '}') { depth--; if (started && depth === 0) return src.slice(start, i + 1); }
    }
    throw new Error('不闭合: ' + marker);
}

const fns = [
    "const AUDIO_EXTS = new Set(['mp3','wav','flac','m4a','ogg']);",
    'const MEDIA_BLOB_CACHE = new Map();',
    slice('function fileExt(name)'),
    slice('function escA(s)'),
    slice('function esc(s)'),
    slice('function escQ(s)'),
    slice('function balancedUrl(s, open)'),
    slice('function isAudioUrl(url)'),
    slice('function mediaSrcAttr(url)'),
    slice('function parseMediaBang(s)'),
    slice('function parseBracketImg(s, label)'),
    slice('function parseLinks(s)'),
    slice('function renderMD(t)'),
    slice('function renderMDStreaming(t)'),
    slice('function dropLeadBlanks(s)'),
    slice('function splitStreamingMarkdown(text)'),
].join('\n\n');

/* 一次模拟流：先文字，再一张图，再文字 —— 正是「有图时上下跳」的场景 */
const CHUNKS = [
    '下面是结果：\n\n',
    '![生成的图片](/api/generated-media/7/img_1792656111658_7413.png)',
    '\n\n',
    '这张图说明',
    '了整体结构。',
    '\n\n',
    '第二段文字继续输出。',
    '\n\n',
    '第三段也来了。',
];

const page = `<!DOCTYPE html><html><head><meta charset="utf-8">
<style>
  body{margin:0;background:#111;color:#ddd;font:15px/1.7 system-ui}
  .bot-body{width:640px;padding:16px}
  .bot-body p{margin:10px 0}
  .bot-body img{max-width:100%;display:block;margin:14px 0;border-radius:12px;
    /* 关键：还没拿到 src 的图没有尺寸，拿到之后撑到 180px —— 正是线上跳动的机制 */
    height:0}
  .bot-body img[src]{height:180px}
  .md-tail:empty{display:none}
  #out{position:fixed;left:0;top:0;opacity:0;pointer-events:none}
</style></head><body>
<div class="bot-body md-body" id="old"></div>
<div class="bot-body md-body" id="new"></div>
<pre id="out"></pre>
<script>
${fns}

const CHUNKS = ${JSON.stringify(CHUNKS)};

/* ---- old：每帧整段重算 ---- */
function paintOld(root, text) {
  root.innerHTML = renderMDStreaming(text);
}

/* old 代表改造前的代码：它根本没有「取回后复用」这层，所以画它的时候把缓存清空，
   否则会凭空继承 new 写进去的缓存，对照就失真了。 */
function paintOldIsolated(root, text) {
  const saved = new Map(MEDIA_BLOB_CACHE);
  MEDIA_BLOB_CACHE.clear();
  try { paintOld(root, text); }
  finally { MEDIA_BLOB_CACHE.clear(); saved.forEach((v, k) => MEDIA_BLOB_CACHE.set(k, v)); }
}

/* ---- new：与 paintAnswerNow 同构（定稿块只追加，只重画 tail） ---- */
function makePainter(root) {
  root.innerHTML = '';
  const tail = document.createElement('div');
  tail.className = 'md-tail';
  root.appendChild(tail);
  let frozenCur = '', tailCur = '';

  return {
    paint(text) {
      const split = splitStreamingMarkdown(text);
      if (split.frozen !== frozenCur) {
        if (split.frozen.startsWith(frozenCur)) {
          const delta = split.frozen.slice(frozenCur.length);
          if (delta) tail.insertAdjacentHTML('beforebegin', renderMD(dropLeadBlanks(delta)));
        } else {
          root.innerHTML = '';
          root.appendChild(tail);
        }
        frozenCur = split.frozen;
      }
      if (split.tail !== tailCur) {
        tail.innerHTML = renderMDStreaming(dropLeadBlanks(split.tail));
        tailCur = split.tail;
      }
    },
    state() { return { frozen: frozenCur, tail: tailCur }; }
  };
}

const oldRoot = document.getElementById('old');
const newRoot = document.getElementById('new');
const np = makePainter(newRoot);

const debug = [];
try {
let text = '';
const rows = [];
/* 把「已画出来的第一个 p / 第一个 img」钉下来，看后续帧里它还是不是同一个节点 */
let oldFirstP = null, oldFirstImg = null;
let newFirstP = null, newFirstImg = null;
let oldPChurn = 0, newPChurn = 0, oldImgChurn = 0, newImgChurn = 0;
let oldHeightChanges = 0, newHeightChanges = 0;
let oldPendingFrames = 0, newPendingFrames = 0;   // 重绘后又变成「未加载」的帧数
let oldJump = 0, newJump = 0;                     // 相邻帧之间正文高度的最大回落
let prevOldH = null, prevNewH = null;

for (const c of CHUNKS) {
  text += c;
  paintOldIsolated(oldRoot, text);
  np.paint(text);

  const op = oldRoot.querySelector('p');
  const oi = oldRoot.querySelector('img');
  const npEl = newRoot.querySelector('p');
  const ni = newRoot.querySelector('img');
  /* 认定方式：和上一帧的同一个位置的节点比，不是同一个对象就算「被重建」 */
  if (op) { if (oldFirstP && op !== oldFirstP) oldPChurn++; oldFirstP = op; }
  if (npEl) { if (newFirstP && npEl !== newFirstP) newPChurn++; newFirstP = npEl; }
  if (oi) { if (oldFirstImg && oi !== oldFirstImg) oldImgChurn++; oldFirstImg = oi; }
  if (ni) { if (newFirstImg && ni !== newFirstImg) newImgChurn++; newFirstImg = ni; }

  /* 模拟「浏览器异步解码完成」：重绘后把还没有 src 的图补上 src。
     节点如果被重画，它就会重新变成未加载状态 —— 这就是跳动的来源。 */
  const pendingOld = oldRoot.querySelectorAll('img:not([src])').length;
  const pendingNew = newRoot.querySelectorAll('img:not([src])').length;
  if (pendingOld) oldPendingFrames++;
  if (pendingNew) newPendingFrames++;
  const ohBefore = oldRoot.getBoundingClientRect().height;
  const nhBefore = newRoot.getBoundingClientRect().height;
  oldRoot.querySelectorAll('img[data-auth-src]').forEach(el => { el.src = 'blob:decoded'; });
  newRoot.querySelectorAll('img[data-auth-src]').forEach(el => {
    const u = el.getAttribute('data-auth-src');
    el.removeAttribute('data-auth-src');
    el.src = 'blob:decoded';
    // #cache 模式：模拟线上的 MEDIA_BLOB_CACHE —— 取回过的图，重画时直接带 src 建节点
    if (location.hash === '#cache') {
      MEDIA_BLOB_CACHE.set(u, { promise: Promise.resolve('blob:decoded'), objectUrl: 'blob:decoded' });
    }
  });
  const oh = oldRoot.getBoundingClientRect().height;
  const nh = newRoot.getBoundingClientRect().height;
  if (prevOldH !== null && oh < prevOldH - 0.5) { oldHeightChanges++; oldJump = Math.max(oldJump, prevOldH - oh); }
  if (prevNewH !== null && nh < prevNewH - 0.5) { newHeightChanges++; newJump = Math.max(newJump, prevNewH - nh); }
  prevOldH = oh; prevNewH = nh;
  rows.push([text.length, Math.round(ohBefore), Math.round(oh), Math.round(nhBefore), Math.round(nh),
    newRoot.querySelectorAll('img').length,
    ni ? (ni.closest('.md-tail') ? 'tail' : 'frozen') : '-']);
}

/* 增量渲染的关键性质：定稿块的数量随文本单调增加，且已有定稿块从不被重画 */
const frozenBlocks = newRoot.querySelectorAll(':scope > :not(.md-tail)').length;
const tailEl = newRoot.querySelector('.md-tail');
const tailIsLast = newRoot.lastElementChild === tailEl;

document.getElementById('out').textContent = JSON.stringify({
  frames: CHUNKS.length,
  old: { firstPReplaced: oldPChurn, firstImgReplaced: oldImgChurn, shrinkFrames: oldHeightChanges,
         maxShrinkPx: Math.round(oldJump), unloadedFrames: oldPendingFrames, finalHeight: prevOldH },
  neu: { firstPReplaced: newPChurn, firstImgReplaced: newImgChurn, shrinkFrames: newHeightChanges,
         maxShrinkPx: Math.round(newJump), unloadedFrames: newPendingFrames, finalHeight: prevNewH },
  frozenBlocks, tailIsLast,
  rows
});
} catch (e) {
  document.getElementById('out').textContent = 'ERROR ' + e.message + ' | ' + e.stack;
}
</script>
</body></html>`;

fs.writeFileSync(path.join(root, 'target/chat-check/stream-harness.html'), page);
console.log('harness written');
