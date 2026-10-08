# -*- coding: utf-8 -*-
"""exec 三档策略的前端验证：结构 + 交互 + 用真实后端响应渲染。

为什么用注入副本而不是直接开真实页面：要看的是前端自己的行为，
不需要登录态。断言一律调用页面自己的函数（toggleExecMenu / applyExecPolicyView），
不重新实现一遍逻辑 —— 重实现等于同义反复，验不出东西。

喂给 applyExecPolicyView 的 JSON 直接取自 e2e_exec_policy.py 的真实 HTTP 响应，
不是手搓的形状 —— 否则字段名写错也能"通过"。

三处注入（都不改被测代码）：
  1. `<head>` 后：预置 sessionStorage 的 ma_token，否则 chat.html 的认证早绑定守卫
     会 `location.replace('/login')`，dump 出来的是错误页。
  2. `</body>` 前：测试脚本本体。
  3. `<body>`：固定 data-theme="light"，避免跟随系统。

产物全部落在 .verify/execdom/sub/，本脚本自己不在那个目录里（否则会被 rmtree 删掉）。
"""
import os
import shutil
import subprocess
import sys

MSEDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
ROOT = r"D:\AI\miniagent"
SRC = os.path.join(ROOT, "mini-agent-app", "src", "main", "resources", "templates", "chat.html")
OUT = os.path.join(ROOT, ".verify", "execdom", "sub")
INJECTED = os.path.join(OUT, "injected.html")
SHOT = os.path.join(OUT, "exec-menu.png")
DUMP = os.path.join(OUT, "dump.html")

BOOTSTRAP = """<script>
/* 只为本测试准备"已登录"的浏览器环境：chat.html 的认证早绑定守卫读 sessionStorage.ma_token，
   没有就 window.location.replace('/login')。这里预置一个假 token，让页面正常渲染骨架。 */
(function () {
  try {
    sessionStorage.setItem('ma_token', 'headless-dom-test-token');
    localStorage.setItem('mini_agent_theme', 'light');
  } catch (e) {}
})();
</script>
"""

TEST_JS = r"""
<script>
(function () {
  const R = [];
  const ok = (label, cond, detail) =>
      R.push((cond ? 'PASS | ' : 'FAIL | ') + label + (detail ? '  -> ' + detail : ''));

  // ---- 1. 结构 ----
  const sel   = document.getElementById('execSelector');
  const menu  = document.getElementById('execMenu');
  const btn   = document.getElementById('execPolicyBtn');
  const label = document.getElementById('execPolicyLabel');
  const hint  = document.getElementById('execGlobalHint');
  ok('execSelector / execMenu / execPolicyBtn / execPolicyLabel 都存在',
     !!(sel && menu && btn && label),
     'sel=' + !!sel + ' menu=' + !!menu + ' btn=' + !!btn + ' label=' + !!label);

  const items = Array.from(document.querySelectorAll('#execMenu .perm-item'));
  ok('#execMenu 恰好 4 项', items.length === 4, '实际 = ' + items.length);
  const execs = items.map(el => el.dataset.exec);
  ok('data-exec 覆盖 block/ask/allow/default',
     ['block','ask','allow','default'].every(v => execs.indexOf(v) >= 0),
     '实际 = ' + execs.join(','));
  const itemTexts = items.map(el => (el.textContent || '').trim());
  ok('三档中文与后端 labelZh 一致（禁止/需批准/放行）',
     itemTexts.length > 3
     && itemTexts[0].indexOf('禁止') === 0
     && itemTexts[1].indexOf('需批准') === 0
     && itemTexts[2].indexOf('放行') === 0,
     itemTexts.join(' / '));
  ok('第四项是「跟随全局」并带 hint 节点',
     itemTexts.length > 3 && itemTexts[3].indexOf('跟随全局') === 0 && !!hint,
     itemTexts.length > 3 ? itemTexts[3] : 'n/a');

  const onclicks = items.map(el => el.getAttribute('onclick') || '');
  ok('4 项都绑了 selectExecPolicy(...)',
     onclicks.length === 4 && onclicks.every(c => c.indexOf('selectExecPolicy(') === 0),
     onclicks.join(' | '));

  ok('页面暴露 toggleExecMenu / selectExecPolicy / applyExecPolicyView / resetExecPolicyView',
     typeof window.toggleExecMenu === 'function'
     && typeof window.selectExecPolicy === 'function'
     && typeof window.applyExecPolicyView === 'function'
     && typeof window.resetExecPolicyView === 'function',
     [window.toggleExecMenu, window.selectExecPolicy,
      window.applyExecPolicyView, window.resetExecPolicyView].map(t => typeof t).join(','));

  // ---- 2. 交互：开关 + 与其他三个菜单互斥 ----
  const others = ['roleMenu', 'permMenu', 'confirmMenu'];
  const otherEls = others.map(id => document.getElementById(id));
  ok('另外三个菜单节点都存在', otherEls.every(Boolean),
     others.map((id, i) => id + '=' + !!otherEls[i]).join(' '));

  otherEls.forEach(el => el.classList.add('show'));
  window.toggleExecMenu();
  ok('toggleExecMenu 打开 execMenu', menu.classList.contains('show'));
  ok('打开 execMenu 会把另外三个菜单关掉',
     otherEls.every(el => !el.classList.contains('show')),
     others.map((id, i) => id + '=' + otherEls[i].classList.contains('show')).join(' '));
  window.toggleExecMenu();
  ok('再 toggle 一次关闭 execMenu', !menu.classList.contains('show'));

  // 反向：打开别的菜单要关掉 execMenu
  menu.classList.add('show');
  window.toggleRoleMenu();
  ok('打开 roleMenu 会关掉 execMenu', !menu.classList.contains('show'));
  menu.classList.add('show');
  window.togglePermMenu();
  ok('打开 permMenu 会关掉 execMenu', !menu.classList.contains('show'));
  menu.classList.add('show');
  window.toggleConfirmMenu();
  ok('打开 confirmMenu 会关掉 execMenu', !menu.classList.contains('show'));
  otherEls.forEach(el => el.classList.remove('show'));

  // ---- 3. 用真实后端响应渲染 ----
  // 取自 e2e_exec_policy.py 实际拿到的 PUT execPolicy=block 响应里的 data 段
  const REAL_BLOCK = {
    success: true, sessionId: 's_smoke', mode: 'default', label: '默认',
    planApproved: false, planActive: false, askGrantedTools: [],
    confirmPolicy: 'dangerous', confirmPolicyLabel: '需确认',
    execPolicyOverride: 'block',
    execPolicyGlobal: 'allow', execPolicyGlobalLabel: '放行',
    execPolicyEffective: 'block', execPolicyEffectiveLabel: '禁止',
    execPolicyFollowsGlobal: false,
    execPolicyOptions: [
      {value: 'block', label: '禁止'}, {value: 'ask', label: '需批准'}, {value: 'allow', label: '放行'}
    ]
  };
  window.applyExecPolicyView(REAL_BLOCK);
  ok('override=block 时按钮文字 = 禁止', label.textContent.trim() === '禁止',
     '实际 = ' + JSON.stringify(label.textContent));
  ok('override=block 时按钮高亮 active', btn.classList.contains('active'),
     'class = ' + btn.className);
  ok('override=block 时只有 block 项被标 active',
     items.filter(el => el.classList.contains('active')).map(el => el.dataset.exec).join(',') === 'block',
     items.map(el => el.dataset.exec + '=' + el.classList.contains('active')).join(' '));
  ok('hint 显示全局默认 = 放行', (hint.textContent || '').indexOf('放行') >= 0,
     '实际 = ' + JSON.stringify(hint.textContent));

  const REAL_FOLLOW = Object.assign({}, REAL_BLOCK, {
    execPolicyOverride: '', execPolicyEffective: 'allow',
    execPolicyEffectiveLabel: '放行', execPolicyFollowsGlobal: true
  });
  window.applyExecPolicyView(REAL_FOLLOW);
  ok('跟随全局时按钮文字带「跟随·」前缀（否则用户会以为是自己设的）',
     label.textContent.trim() === '跟随·放行', '实际 = ' + JSON.stringify(label.textContent));
  ok('跟随全局时按钮不高亮', !btn.classList.contains('active'));
  ok('跟随全局时 default 项被标 active',
     items.filter(el => el.classList.contains('active')).map(el => el.dataset.exec).join(',') === 'default',
     items.map(el => el.dataset.exec + '=' + el.classList.contains('active')).join(' '));

  window.applyExecPolicyView(Object.assign({}, REAL_BLOCK, {
    execPolicyOverride: 'ask', execPolicyEffective: 'ask', execPolicyEffectiveLabel: '需批准'
  }));
  ok('ask 档按钮文字 = 需批准', label.textContent.trim() === '需批准',
     '实际 = ' + JSON.stringify(label.textContent));
  ok('ask 档按钮不高亮（不是异常态）', !btn.classList.contains('active'), 'class = ' + btn.className);

  // ---- 4. 无会话时复位 ----
  window.resetExecPolicyView();
  ok('resetExecPolicyView 把按钮文字复位成 执行',
     label.textContent.trim() === '执行', '实际 = ' + JSON.stringify(label.textContent));
  ok('resetExecPolicyView 时不残留高亮与 hint',
     !btn.classList.contains('active') && (hint.textContent || '') === '',
     'hint = ' + JSON.stringify(hint.textContent));
  ok('resetExecPolicyView 后 active 回到 default 项',
     items.filter(el => el.classList.contains('active')).map(el => el.dataset.exec).join(',') === 'default');

  // ---- 落盘：同步段先写 ----
  const pre = document.createElement('pre');
  pre.id = '__result';
  pre.textContent = 'BEGIN\n' + R.join('\n') + '\nEND';
  document.body.appendChild(pre);
  // 截图那次用 #shot 打开：结果块很长，留着会把页面挤窄，看不清菜单排版。
  if (location.hash === '#shot') {
    pre.style.display = 'none';
  }

  // 截图前把菜单打开，并切到 block 档让 UI 有内容可看
  window.applyExecPolicyView(REAL_BLOCK);
  window.toggleExecMenu();
})();
</script>
"""


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)

    html = open(SRC, encoding="utf-8").read()
    if "</body>" not in html or "<head>" not in html:
        print("锚点缺失，终止")
        return 2
    injected = html.replace("<head>", "<head>\n" + BOOTSTRAP, 1)
    injected = injected.replace("</body>", TEST_JS + "\n</body>", 1)
    injected = injected.replace("<body", '<body data-theme="light"', 1)
    open(INJECTED, "w", encoding="utf-8").write(injected)
    print("注入页: %s" % INJECTED)

    ud = os.path.join(OUT, "ud")
    common = [MSEDGE, "--headless=new", "--disable-gpu", "--no-sandbox",
              "--no-first-run", "--user-data-dir=" + ud,
              "--window-size=1440,900", "--virtual-time-budget=20000"]

    p = subprocess.run(common + ["--dump-dom", "file:///" + INJECTED.replace("\\", "/")],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    open(DUMP, "w", encoding="utf-8").write(p.stdout or "")
    print("dump 非空: %s (%d bytes)" % (bool(p.stdout), len(p.stdout or "")))

    shot_dir = os.path.join(OUT, "shotud")
    subprocess.run([MSEDGE, "--headless=new", "--disable-gpu", "--no-sandbox",
                    "--no-first-run", "--user-data-dir=" + shot_dir,
                    "--window-size=1440,900", "--hide-scrollbars",
                    "--screenshot=" + SHOT,
                    "file:///" + INJECTED.replace("\\", "/") + "#shot"],
                   capture_output=True, text=True, encoding="utf-8", errors="replace")
    print("截图: %s" % (SHOT if os.path.exists(SHOT) else "未生成"))

    shutil.rmtree(ud, ignore_errors=True)
    shutil.rmtree(shot_dir, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
