#!/usr/bin/env node
/**
 * 生成"会话工作目录"的浏览器验收页。
 *
 * 这里验的是前端契约：菜单开关、失败原因回显、保存后标签/高亮、恢复默认、
 * 以及工作区下载链接是否带上会话参数（不带的话绑定了目录的会话下载必然 404）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = fs.readFileSync(
    path.join(root, 'mini-agent-app/src/main/resources/templates/chat.html'), 'utf8');
const boot = `<script>try{sessionStorage.setItem('ma_token','headless-workspace-test');localStorage.setItem('mini_agent_theme','light');}catch(e){}</script>`;
const injected = `
<script>
(async function () {
  const results = [];
  const assert = (name, condition, detail) => results.push((condition ? 'PASS ' : 'FAIL ') + name + (detail ? ' :: ' + detail : ''));
  const $ = id => document.getElementById(id);
  const wait = ms => new Promise(r => setTimeout(r, ms));
  const until = async (probe, timeout = 1500) => {
    const end = Date.now() + timeout;
    while (Date.now() < end) { if (probe()) return true; await wait(20); }
    return false;
  };

  window.__wsState = { path: null, effectivePath: 'C:/data/workspace', defaultPath: 'C:/data/workspace', missing: false };
  window.__wsCalls = [];
  const json = (data, status = 200) => new Response(JSON.stringify({ success: status < 400, data, code: status < 400 ? undefined : 'CONFIG_INVALID', message: status < 400 ? undefined : data.reason }), { status, headers: { 'Content-Type': 'application/json' } });
  window.fetch = async (url, options = {}) => {
    const method = (options.method || 'GET').toUpperCase();
    window.__wsCalls.push(method + ' ' + url);
    if (String(url).endsWith('/workspace')) {
      if (method === 'PUT') {
        const body = JSON.parse(options.body || '{}');
        if (String(body.path).includes('bad')) return json({ reason: '目录不存在：bad' }, 400);
        window.__wsState = { path: body.path, effectivePath: body.path, defaultPath: 'C:/data/workspace', missing: false };
        return json(window.__wsState);
      }
      if (method === 'DELETE') {
        window.__wsState = { path: null, effectivePath: 'C:/data/workspace', defaultPath: 'C:/data/workspace', missing: false };
        return json(window.__wsState);
      }
      return json(window.__wsState);
    }
    return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
  };

  // 需要一个"当前会话"才能让工作目录接口有意义
  currentId = 's_ws_test';

  await loadWorkspace();
  assert('未绑定时显示默认工作区', $('wsCurrent').textContent === 'C:/data/workspace', $('wsCurrent').textContent);
  assert('未绑定时按钮不进入 active', !$('wsBtn').classList.contains('active'));

  toggleWsMenu();
  assert('点击后菜单展开', $('wsMenu').classList.contains('show'));
  assert('打开菜单会拉取当前目录', window.__wsCalls.some(c => c.endsWith('/workspace')), window.__wsCalls.join(' | '));
  assert('网页版隐藏原生选目录按钮', $('wsPickBtn').style.display === 'none', $('wsPickBtn').style.display);

  // 校验失败：服务端返回的原因必须原样显示，不能只写"保存失败"
  $('wsInput').value = 'bad-path';
  await saveWorkspace();
  assert('校验失败显示服务端原因', $('wsHint').textContent.includes('目录不存在'), $('wsHint').textContent);
  assert('失败提示用错误色', $('wsHint').classList.contains('is-error'));

  // 成功路径
  $('wsInput').value = 'D:/projects/demo';
  await saveWorkspace();
  assert('保存走 PUT', window.__wsCalls.includes('PUT /api/conversations/s_ws_test/workspace'), window.__wsCalls.join(' | '));
  assert('保存后按钮高亮', $('wsBtn').classList.contains('active'));
  assert('保存后标签显示目录名', $('wsLabel').textContent === 'demo', $('wsLabel').textContent);
  assert('保存后当前路径更新', $('wsCurrent').textContent === 'D:/projects/demo', $('wsCurrent').textContent);
  assert('保存成功后清掉错误色', !$('wsHint').classList.contains('is-error'));

  // 恢复默认
  await resetWorkspace();
  assert('恢复默认走 DELETE', window.__wsCalls.includes('DELETE /api/conversations/s_ws_test/workspace'), window.__wsCalls.join(' | '));
  assert('恢复默认后按钮取消高亮', !$('wsBtn').classList.contains('active'));
  assert('恢复默认后标签回到工作区', $('wsLabel').textContent === '工作区', $('wsLabel').textContent);
  assert('恢复默认后输入框清空', $('wsInput').value === '');

  // 下载链接要带会话参数，否则绑定了目录的会话下载必然 404。
  // 链接是渲染期生成的（linkWorkspacePaths 只产出 markdown），所以要看渲染后的 HTML。
  assert('下载链接带 sid 参数', workspaceSidQuery() === '?sid=s_ws_test', workspaceSidQuery());
  const html = renderMD('产物：workspace/报告.docx');
  assert('正文里的工作区文件链接带上 sid', html.includes('/api/workspace-download/') && html.includes('sid=s_ws_test'), html);

  // 切会话必须重新取目录，不能把上一个会话的目录显示成当前目录
  window.__wsState = { path: 'E:/other', effectivePath: 'E:/other', defaultPath: 'C:/data/workspace', missing: false };
  await loadWorkspace();
  assert('切会话后重新拉取并刷新显示', $('wsCurrent').textContent === 'E:/other', $('wsCurrent').textContent);

  // 绑定目录被删：要明确告诉用户"已回退"，不能静默显示默认目录
  window.__wsState = { path: 'E:/gone', effectivePath: 'C:/data/workspace', defaultPath: 'C:/data/workspace', missing: true };
  await loadWorkspace();
  assert('目录失效时提示回退', $('wsHint').textContent.includes('已不存在') && $('wsHint').classList.contains('is-error'), $('wsHint').textContent);

  // 收尾摆成可截图的状态（菜单展开、路径已填），供人工看一眼实际样子
  window.__wsState = { path: 'D:/projects/demo', effectivePath: 'D:/projects/demo', defaultPath: 'C:/data/workspace', missing: false };
  $('wsInput').value = 'D:/projects/demo';
  await loadWorkspace();
  if (!$('wsMenu').classList.contains('show')) toggleWsMenu();

  window.__qaResults = results.join('\\n');
})();
</script>`;
const page = source
    .replace('<head>', '<head>' + boot)
    .replace('</body>', injected + '</body>');
const output = path.join(root, 'target/workspace-ui-check.html');
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, page, 'utf8');
console.log(output);
