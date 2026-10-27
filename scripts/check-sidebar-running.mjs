#!/usr/bin/env node
/**
 * 生成侧栏运行态指示器的浏览器验收页。
 * 真正的断言在无头 Edge 里跑（scripts/browser-qa.mjs），这里只负责把真实模板 + 打桩数据拼成页面。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const templatePath = path.join(root, 'mini-agent-app/src/main/resources/templates/chat.html');
const source = fs.readFileSync(templatePath, 'utf8');
const boot = `<script>try{sessionStorage.setItem('ma_token','headless-sidebar-test');localStorage.setItem('mini_agent_theme','light');}catch(e){}</script>`;
const injected = `
<script>
(async function () {
  const results = [];
  const assert = (name, condition, detail) => results.push((condition ? 'PASS ' : 'FAIL ') + name + (detail ? ' :: ' + detail : ''));
  const row = () => document.querySelector('.sb-item[data-session-id="running-session"]');
  const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
  const until = async (probe, timeout = 1500) => {
    const end = Date.now() + timeout;
    while (Date.now() < end) { if (probe()) return true; await wait(20); }
    return false;
  };
  window.__sidebarTaskRunning = true;
  window.fetch = async url => {
    if (url === '/api/conversations') {
      return new Response(JSON.stringify([{sessionId:'running-session',title:'运行中的任务',updatedAt:Date.now()}]), {status:200,headers:{'Content-Type':'application/json'}});
    }
    if (String(url).endsWith('/task')) {
      return new Response(JSON.stringify({running:window.__sidebarTaskRunning}), {status:200,headers:{'Content-Type':'application/json'}});
    }
    return new Response('{}', {status:200,headers:{'Content-Type':'application/json'}});
  };
  await renderSidebar();
  // 指示器由 renderSidebar 内部异步触发刷新，这里等它自己出现（验证自动触发，不是手工调用）
  const appeared = await until(() => !!row()?.querySelector('.sb-running-indicator'));
  const activeRow = row();
  const indicator = activeRow && activeRow.querySelector('.sb-running-indicator');
  assert('运行中会话显示指示器', appeared && !!indicator, indicator && indicator.getAttribute('aria-label'));
  assert('指示器位于标题和操作按钮之间', !!(indicator && indicator.previousElementSibling?.classList.contains('sb-item-title') && indicator.nextElementSibling?.classList.contains('sb-item-actions')));
  assert('运行态标记设在对应会话行', !!(activeRow && activeRow.classList.contains('is-running')));
  assert('指示器带 role=img + aria-label', !!(indicator && indicator.getAttribute('role') === 'img' && indicator.getAttribute('aria-label') === '任务运行中'));
  assert('指示器有循环旋转动画', !!indicator && getComputedStyle(indicator).animationName !== 'none',
    indicator && getComputedStyle(indicator).animationName);
  updateSidebarRunningIndicator(activeRow);
  assert('重复更新不会重复插入指示器', activeRow.querySelectorAll('.sb-running-indicator').length === 1);
  window.__sidebarTaskRunning = false;
  await refreshSidebarRunningSessions();
  assert('任务结束后移除指示器', !activeRow.querySelector('.sb-running-indicator'));
  assert('任务结束后清除运行态 class', !activeRow.classList.contains('is-running'));
  window.__qaResults = results.join('\\n');
})();
</script>`;
const page = source
  .replace('<head>', '<head>' + boot)
  .replace('</body>', injected + '</body>');
const output = path.join(root, 'target/sidebar-running-check.html');
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, page, 'utf8');
console.log(output);
