# -*- coding: utf-8 -*-
"""把 task-pin 的验证脚本注入 chat.html 副本，供无头浏览器执行。
被测代码零改动：测试直接调用页面自身的 renderUserMsg / buildUserRow / updateTaskPin。"""
import io, os, sys

SRC = r"D:\AI\miniagent\mini-agent-app\src\main\resources\templates\chat.html"
OUT = r"D:\AI\miniagent\_pin_verify\pin_verify.html"

TEST = r"""
<script>
(function () {
  const R = [];
  const ok = (n, pass, extra) => R.push((pass ? 'PASS' : 'FAIL') + ' | ' + n + (extra ? ' || ' + extra : ''));
  const $pin    = document.getElementById('taskPin');
  const pinText = () => document.getElementById('taskPinText').textContent;
  const pinIdx  = () => document.getElementById('taskPinIdx').textContent;
  const pinOn   = () => $pin.classList.contains('show');
  const rows    = () => Array.from($msg.querySelectorAll('.user-row'));
  const TALL    = n => '<div style="height:' + n + 'px"></div>';

  // 每条回答固定 900px（高于消息区视口），保证"滚到底"必然落在最后一条任务的回答内部
  function seed() {
    $msg.innerHTML = '';
    for (let i = 1; i <= 4; i++) {
      renderUserMsg('\u4efb\u52a1' + i + '\uff1a\u8bf7\u5904\u7406\u7b2c ' + i + ' \u9879\u9700\u6c42');
      const bd = renderBotMsg(TALL(900), false);
      bd.insertAdjacentHTML('afterbegin', '<p>\u56de\u7b54' + i + '</p>');
    }
  }

  seed();
  ok('\u4efb\u52a1\u6761\u4e0d\u5728 #messages \u5185\uff08prepend \u4e0d\u4f1a\u628a\u5b83\u9876\u6389\uff09',
     !$msg.contains($pin));

  $msg.scrollTop = 0; updateTaskPin();
  ok('\u6eda\u5230\u9876 \u2192 \u547d\u4e2d\u7b2c 1 \u6761', pinOn() && pinText().indexOf('\u4efb\u52a11') === 0 && pinIdx() === '1/4',
     pinIdx() + ' ' + pinText());

  $msg.scrollTop = $msg.scrollHeight; updateTaskPin();
  ok('\u6eda\u5230\u5e95 \u2192 \u547d\u4e2d\u7b2c 4 \u6761', pinOn() && pinIdx() === '4/4', pinIdx() + ' ' + pinText());

  // 任务条文字必须和消息内容对齐同一列（.msg-inner 内边距 20px，别漏）
  {
    const tag = document.querySelector('.task-pin-tag');
    const av  = document.querySelector('.bot-row .bot-avatar');
    const dl = tag.getBoundingClientRect().left - av.getBoundingClientRect().left;
    // 容差 4px：#messages 的 webkit 滚动条占 6px 布局宽，.msg-inner 在 (W-6) 里居中、
    // 任务条在 W 里居中，理论上永远差 3px。要消掉就得让任务条跟着滚动条宽度走，
    // 反而把两处 CSS 耦死，不值得。
    const sbw = $msg.offsetWidth - $msg.clientWidth;
    ok('\u4efb\u52a1\u6761\u6587\u5b57\u4e0e\u6d88\u606f\u5185\u5bb9\u5de6\u5bf9\u9f50\uff08\u5bb9\u5dee 4px\uff09', Math.abs(dl) <= 4,
       'delta=' + dl.toFixed(2) + 'px \u6eda\u52a8\u6761\u5bbd=' + sbw + 'px');
  }

  rows()[2].scrollIntoView({ block: 'start' }); updateTaskPin();
  const d = rows()[2].getBoundingClientRect().top - $msg.getBoundingClientRect().top;
  ok('\u7b2c 3 \u6761\u9876\u5230\u4e0a\u6cbf \u2192 \u547d\u4e2d\u7b2c 3 \u6761', pinIdx() === '3/4',
     'rowTop-cTop=' + d.toFixed(1) + ' idx=' + pinIdx());

  $msg.scrollTop -= 20; updateTaskPin();
  ok('\u56de\u6eda 20px\uff08\u7b2c3\u6761\u9876\u8fb9\u843d\u5230\u4e0a\u6cbf\u4e0b\u65b9\uff09\u2192 \u9000\u56de\u7b2c 2 \u6761', pinIdx() === '2/4',
     'idx=' + pinIdx() + ' ' + pinText());

  $msg.scrollTop += 20; updateTaskPin();
  ok('\u518d\u5411\u524d 20px \u2192 \u56de\u5230\u7b2c 3 \u6761', pinIdx() === '3/4', 'idx=' + pinIdx());

  $msg.insertBefore(buildUserRow('\u4efb\u52a10\uff1a\u66f4\u65e9\u7684\u9700\u6c42', []), $msg.firstChild);
  $msg.scrollTop = 0; updateTaskPin();
  ok('prepend \u5386\u53f2 \u2192 \u8ba1\u6570\u53d8 1/5 \u4e14\u547d\u4e2d\u65b0\u9996\u6761',
     pinIdx() === '1/5' && pinText().indexOf('\u4efb\u52a10') === 0, pinIdx() + ' ' + pinText());

  ok('data-task \u5df2\u5165\u5230 user-row', rows()[1].dataset.task === '\u4efb\u52a11\uff1a\u8bf7\u5904\u7406\u7b2c 1 \u9879\u9700\u6c42',
     JSON.stringify(rows()[1].dataset.task));

  $msg.innerHTML = ''; updateTaskPin();
  ok('\u7a7a\u4f1a\u8bdd/Welcome \u2192 \u4efb\u52a1\u6761\u9690\u85cf', !pinOn(), 'show=' + pinOn());

  renderUserMsg('', null, 1, ['a.png']); updateTaskPin();
  ok('\u7eaf\u9644\u4ef6\u65e0\u6587\u672c \u2192 \u5360\u4f4d\u6587\u6848', pinOn() && pinText().indexOf('\uff08\u672c\u6761\u65e0\u6587\u672c') === 0, pinText());

  // 先把同步结论落盘，避免异步段没跑完就 dump 导致丢结果
  const pre = document.createElement('pre');
  pre.id = '__pin_result';
  pre.textContent = 'BEGIN\n' + R.join('\n') + '\nEND';
  document.body.appendChild(pre);

  // 端到端：不手调 updateTaskPin，只派发 scroll 事件，走真实监听器 + 真实 rAF
  seed();
  $msg.scrollTop = 0; updateTaskPin();
  $msg.scrollTop = $msg.scrollHeight;
  $msg.dispatchEvent(new Event('scroll'));

  let done = false;
  function wiring() {
    if (done) return;
    done = true;
    ok('\u7aef\u5230\u7aef\uff1ascroll \u4e8b\u4ef6 \u2192 rAF \u540e\u4efb\u52a1\u6761\u5df2\u66f4\u65b0', pinIdx() === '4/4', 'idx=' + pinIdx());
    pre.textContent = 'BEGIN\n' + R.join('\n') + '\nEND';
  }
  requestAnimationFrame(() => requestAnimationFrame(wiring));
  setTimeout(wiring, 200);
})();
</script>
"""

with io.open(SRC, "r", encoding="utf-8") as f:
    html = f.read()

assert "</body>" in html, "no </body>"
html = html.replace("</body>", TEST + "\n</body>", 1)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with io.open(OUT, "w", encoding="utf-8") as f:
    f.write(html)

print("written:", OUT, len(html))
