# -*- coding: utf-8 -*-
"""生成用于截图的注入页：真实页面 + 假数据，任务条停在中间某条任务上。"""
import io, os, sys

SRC = r"D:\AI\miniagent\mini-agent-app\src\main\resources\templates\chat.html"
OUT = r"D:\AI\miniagent\_pin_verify\pin_shot.html"

TAIL = r"""
<script>
(function () {
  // 截图时不希望任务条被 rAF 时序影响，先固定主题
  document.documentElement.setAttribute('data-theme', '__THEME__');

  const ANSWER = 520;   // 每条回答高度 < 视口，便于同屏看到多条任务的分界
  const lines = n => '<p>' + Array.from({length: n}, (_, i) =>
      '\u7b2c ' + (i + 1) + ' \u6b65\uff1a\u68c0\u67e5\u6a21\u5757\u5e76\u8f93\u51fa\u7ed3\u679c').join('</p><p>') + '</p>';

  $msg.innerHTML = '';
  const tasks = [
    '\u628a mini-agent \u7684\u4efb\u52a1\u62c6\u89e3\u5668\u6539\u6210 DAG\uff0c\u5e76\u8865\u9f50\u5355\u5143\u6d4b\u8bd5',
    '\u5b9a\u4f4d SecurityConfig \u4e3a\u4ec0\u4e48\u53cd\u590d\u62a5 CSRF \u9519\uff0c\u7ed9\u51fa\u6839\u56e0\u4e0e\u4fee\u590d\u8def\u5f84',
    '\u4e3a trace \u53ef\u89c6\u5316\u52a0\u4e0a\u6bcf\u8f6e\u7684 token \u9884\u7b97\u6761',
    '\u628a agent memory \u6a21\u5757\u7684\u6ce8\u5165\u7b56\u7565\u6539\u6210\u53ef\u914d\u7f6e\u9879',
    '\u6574\u7406\u62db\u5546\u5c40\u6295\u6807\u7684\u6280\u672f\u65b9\u6848\u7ae0\u8282'
  ];
  tasks.forEach((t, i) => {
    renderUserMsg(t);
    const bd = renderBotMsg('<div style="height:' + ANSWER + 'px"></div>', false);
    bd.insertAdjacentHTML('afterbegin', lines(6));
    bindLightbox(bd.closest('.bot-row'));
  });

  // 停在「第 3 条任务的回答中段」：验证任务条显示 3/5
  const rows = Array.from($msg.querySelectorAll('.user-row'));
  rows[2].scrollIntoView({ block: 'start' });
  $msg.scrollTop += ANSWER * 0.6;
  updateTaskPin();
  document.getElementById('scrollBtn').classList.add('visible');
})();
</script>
"""

html = io.open(SRC, "r", encoding="utf-8").read()
for theme in ("light", "dark"):
    out = OUT.replace(".html", "_" + theme + ".html")
    io.open(out, "w", encoding="utf-8").write(
        html.replace("</body>", TAIL.replace("__THEME__", theme) + "\n</body>", 1))
    print("written:", out)
