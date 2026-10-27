#!/usr/bin/env node
/**
 * 生成图片预览（lightbox）的浏览器验收页。
 * 断言在无头 Edge 里跑（scripts/browser-qa.mjs）：这些行为依赖真实布局（clientWidth）与
 * 真实的 fetch / Blob / ClipboardItem，静态文本检查覆盖不到。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = fs.readFileSync(
    path.join(root, 'mini-agent-app/src/main/resources/templates/chat.html'), 'utf8');
const boot = `<script>try{sessionStorage.setItem('ma_token','headless-lightbox-test');localStorage.setItem('mini_agent_theme','light');}catch(e){}</script>`;
const injected = `
<script>
(async function () {
  const results = [];
  const assert = (name, condition, detail) => results.push((condition ? 'PASS ' : 'FAIL ') + name + (detail ? ' :: ' + detail : ''));
  const wait = ms => new Promise(r => setTimeout(r, ms));
  const until = async (probe, timeout = 2000) => {
    const end = Date.now() + timeout;
    while (Date.now() < end) { if (probe()) return true; await wait(20); }
    return false;
  };
  const $ = id => document.getElementById(id);

  // 打桩：本站 fetch（保护媒体）用 1:1 的 data URL 代替，其余接口返回空对象
  const canvas = document.createElement('canvas');
  canvas.width = 1600; canvas.height = 900;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#123a5a'; ctx.fillRect(0, 0, 1600, 900);
  ctx.fillStyle = '#8fd3ff'; ctx.fillRect(200, 150, 1200, 600);
  const pngUrl = canvas.toDataURL('image/png');
  const pngBlob = await (await fetch(pngUrl)).blob();
  window.fetch = async url => {
    if (String(url).startsWith('data:image/')) return new Response(pngBlob, { status: 200, headers: { 'Content-Type': 'image/png' } });
    return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
  };
  const alerts = [];
  window.alert = msg => alerts.push(String(msg));

  // 下载：拦截 anchor 点击（无头环境不落盘，但要证明走的是 blob + download 属性）
  const downloads = [];
  HTMLAnchorElement.prototype.click = function () { downloads.push({ href: this.href, name: this.download }); };
  // 复制：拦截剪贴板写入（无头下没有用户手势，真实 clipboard.write 会被拒）
  const clipboardWrites = [];
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { write: async items => { clipboardWrites.push(items); } },
  });

  // 正文里的图：先验证默认展示尺寸
  const wrap = document.createElement('div');
  wrap.className = 'bot-body';
  const chatImg = document.createElement('img');
  chatImg.src = pngUrl;
  wrap.appendChild(chatImg);
  document.body.appendChild(wrap);
  await chatImg.decode();
  const maxH = parseFloat(getComputedStyle(chatImg).maxHeight);
  assert('正文图片按视口高度 72% 限高', Math.abs(maxH - window.innerHeight * 0.72) < 1.5, maxH + 'px / 视口 ' + window.innerHeight);
  assert('正文图片是放大手势光标', getComputedStyle(chatImg).cursor === 'zoom-in', getComputedStyle(chatImg).cursor);

  // 打开预览
  openLightbox(pngUrl);
  const shown = await until(() => $('lightbox').classList.contains('on') && $('lbImg').style.width);
  assert('点击图片后预览层打开', shown);
  const img = $('lbImg');
  const stage = $('lightboxStage');

  // 适应宽度：横图按宽度铺满，高度也放得下，所以两者同时成立
  const fitWidth = parseFloat(img.style.width);
  const fitScale = fitWidth / img.naturalWidth;
  assert('打开时自动适应宽度（宽度不超出舞台）', fitWidth <= stage.clientWidth,
    fitWidth + 'x' + img.style.height + ' / 舞台 ' + stage.clientWidth + 'x' + stage.clientHeight);
  assert('适应宽度不超过 300%（矢量图需要放大）', fitScale <= 3.0001, Math.round(fitScale * 100) + '%');

  // 放大
  changeLightboxScale(0.2);
  const afterZoomIn = parseFloat(img.style.width);
  assert('放大按钮加大图片宽度', afterZoomIn > fitWidth, fitWidth + ' → ' + afterZoomIn);
  assert('缩放比例标签同步更新', $('lbZoomLabel').textContent === Math.round(lightboxScale * 100) + '%', $('lbZoomLabel').textContent);

  // 滚轮
  const beforeWheel = lightboxScale;
  stage.dispatchEvent(new WheelEvent('wheel', { deltaY: -100, bubbles: true, cancelable: true }));
  assert('滚轮向上放大', lightboxScale > beforeWheel, beforeWheel.toFixed(2) + ' → ' + lightboxScale.toFixed(2));
  const afterWheelUp = lightboxScale;
  stage.dispatchEvent(new WheelEvent('wheel', { deltaY: 100, bubbles: true, cancelable: true }));
  assert('滚轮向下缩小', lightboxScale < afterWheelUp, afterWheelUp.toFixed(2) + ' → ' + lightboxScale.toFixed(2));

  // 边界
  setLightboxScale(10);
  assert('缩放上限 400%', $('lbZoomLabel').textContent === '400%' && Math.round(img.naturalWidth * 4) === parseFloat(img.style.width), $('lbZoomLabel').textContent);
  setLightboxScale(0.01);
  assert('缩放下限 5%', $('lbZoomLabel').textContent === '5%', $('lbZoomLabel').textContent);
  assert('放大后舞台可滚动查看', getComputedStyle(stage).overflowY === 'auto' || getComputedStyle(stage).overflow === 'auto',
    getComputedStyle(stage).overflowY);

  // 下载
  await downloadLightboxImage();
  assert('下载生成了文件链接', downloads.length === 1, JSON.stringify(downloads[0] || null));
  assert('下载文件名是 architecture-diagram.png', downloads[0] && downloads[0].name === 'architecture-diagram.png', downloads[0] && downloads[0].name);
  assert('下载地址是 blob:（不是暴露媒体 URL）', !!(downloads[0] && downloads[0].href.startsWith('blob:')), downloads[0] && downloads[0].href.slice(0, 24));
  assert('下载成功给出非阻塞提示', $('lbNotice').textContent === '已下载' && $('lbNotice').classList.contains('on'), $('lbNotice').textContent);

  // 复制
  await copyLightboxImage();
  const items = clipboardWrites[0];
  const item = items && items[0];
  const types = item && item.types ? item.types : [];
  assert('复制写入剪贴板一次', clipboardWrites.length === 1);
  assert('剪贴板内容是 image/png', types.includes('image/png'), types.join(','));
  const copied = item && await item.getType('image/png');
  assert('剪贴板 PNG 有实际字节', !!copied && copied.size > 1000, copied && copied.size + ' bytes');
  assert('复制成功给出非阻塞提示', $('lbNotice').textContent === '已复制', $('lbNotice').textContent);
  assert('全程没有阻塞式 alert 弹窗', alerts.length === 0, alerts.join(' | '));

  // 失败路径：媒体链接失效时必须给出可读原因，而不是静默
  const okFetch = window.fetch;
  window.fetch = async () => new Response('', { status: 403 });
  await downloadLightboxImage();
  assert('下载失败显示错误提示', $('lbNotice').textContent === '下载失败，请检查图片是否可访问' && $('lbNotice').classList.contains('is-error'), $('lbNotice').textContent);
  await copyLightboxImage();
  assert('复制失败显示错误提示', $('lbNotice').textContent === '复制失败，请改用下载', $('lbNotice').textContent);
  window.fetch = okFetch;

  // 回归：高窄长图（架构图的典型尺寸）打开后必须按宽度铺满。
  // 旧实现取 min(宽比, 高比) 再卡 25% 下限，520x3001 的图只显示 130x750，
  // 比对话区里还小 —— 用户点开看到图片缩水，体感就是「点击不能放大」。
  const tall = document.createElement('canvas');
  tall.width = 520; tall.height = 3001;
  const tallCtx = tall.getContext('2d');
  tallCtx.fillStyle = '#ffffff'; tallCtx.fillRect(0, 0, 520, 3001);
  tallCtx.fillStyle = '#1f6feb'; tallCtx.fillRect(20, 20, 480, 40);
  openLightbox(tall.toDataURL('image/png'));
  await until(() => {
    const w = parseFloat($('lbImg').style.width) || 0;
    return $('lbImg').naturalWidth === 520 && w > stage.clientWidth * 0.9;
  });
  const tallImg = $('lbImg');
  const tallWidth = parseFloat(tallImg.style.width) || 0;
  assert('高窄长图按宽度铺满舞台', tallWidth >= stage.clientWidth * 0.9,
    tallWidth + 'px / 舞台 ' + stage.clientWidth + 'px');
  assert('高窄长图纵向超出舞台（靠滚动查看）', parseFloat(tallImg.style.height) > stage.clientHeight,
    tallImg.style.height + ' / 舞台高 ' + stage.clientHeight);
  assert('高窄长图没有被压成缩略图', tallWidth / 520 > 0.5,
    Math.round(tallWidth / 520 * 100) + '%');

  // 关闭
  closeLightbox();
  assert('关闭按钮移除预览层', !$('lightbox').classList.contains('on'));
  assert('关闭时清掉提示文案', $('lbNotice').textContent === '' && !$('lbNotice').classList.contains('on'));

  window.__qaResults = results.join('\\n');
})();
</script>`;
const page = source
    .replace('<head>', '<head>' + boot)
    .replace('</body>', injected + '</body>');
const output = path.join(root, 'target/lightbox-dom-check.html');
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, page, 'utf8');
console.log(output);
