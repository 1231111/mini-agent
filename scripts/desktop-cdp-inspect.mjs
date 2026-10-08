/* 检查「打包后的 Electron 壳里，渲染层到底走到哪一步了」。
 *
 * 为什么需要它：
 *   后端能不能起来有 /actuator/health 可查，shell 也能直跑 jar 验证；
 *   但「窗口里现在是登录页，还是卡在启动页」只有渲染层自己知道。
 *   让人按 F12 去看，等于把验证外包给用户。这个脚本接 CDP 进去直接读。
 *
 * 用法（先按下面「两个必要前提」把壳起起来）：
 *   node scripts/desktop-cdp-inspect.mjs "document.title"
 *   CDP_PORT=9334 node scripts/desktop-cdp-inspect.mjs "<js 表达式>"
 *   CDP_NAV=file:///.../index.html node scripts/desktop-cdp-inspect.mjs "<js 表达式>"
 *
 *   表达式可以是 async 的（awaitPromise）。CDP_NAV 会用 Page.navigate 先跳转，
 *   用来把运行中的窗口导航到「改好的源文件」上直接验证，不必先重打包。
 *   注意：不要在表达式里写 location.href = '...' —— 那是异步的，连接一关就丢了，
 *   实测表现是「看起来没跳」。
 *
 * ── 两个必要前提，缺一个你会得到完全错误的结论 ──
 *
 * 1) 摘掉 ELECTRON_RUN_AS_NODE。
 *    带了这个变量时 <你的 exe> 会退化成纯 Node 进程：没有窗口、没有输出，
 *    看起来像「应用卡住了」，实际根本没进 Electron 主进程。
 *    带参数启动会直接报 `<exe>: bad option: --xxx` —— 这句文案来自 Node 的参数
 *    解析器，不是 Electron 的，看到它就能确认是被劫持了。
 *
 *        env -u ELECTRON_RUN_AS_NODE -u NODE_OPTIONS ./MiniAgent.exe ...
 *
 * 2) 加 --disable-gpu。
 *    无可用 GPU 的环境（虚拟机 / 无桌面会话 / 部分远程桌面）里 GPU 进程会崩：
 *        ERROR:gpu_process_host.cc  GPU process exited unexpectedly: exit_code=-1073741819
 *        FATAL:gpu_data_manager_impl_private.cc  GPU process isn't usable. Goodbye.
 *    (-1073741819 = 0xC0000005 = ACCESS_VIOLATION)
 *    Electron 在 GPU 不可用时的默认行为是**整个进程退出**，不是降级软件渲染。
 *
 * 3) --user-data-dir 指到临时目录，作用有两个，都要：
 *    ① 换掉单实例锁的作用域 —— 否则已有实例在跑时新进程 requestSingleInstanceLock()
 *       失败会直接 app.quit()，你什么都看不到；
 *    ② 不污染真实用户配置（窗口位置、端口会被改写）。
 *
 * 完整启动示例：
 *   cd mini-agent-desktop/dist/win-unpacked
 *   env -u ELECTRON_RUN_AS_NODE -u NODE_OPTIONS ./MiniAgent.exe \
 *     --remote-debugging-port=9333 --user-data-dir=/tmp/_edbg \
 *     --disable-gpu --disable-gpu-compositing --disable-software-rasterizer --no-sandbox
 *
 * 还要注意：进程要挂在常驻的后台任务里跑。命令行工具常常把子进程连同 job 一起
 * 回收，否则你会在两次调用之间看到「进程莫名其妙没了」，误判成应用自己退了。
 *
 * 一个实测有效的技巧：Runtime.enable 会**重放**已经发生过的 exception / console
 * 消息。所以「页面里到底有没有抛过异常」不需要复现故障 —— 连上去那一刻就能拿到。
 */
const PORT = Number(process.env.CDP_PORT || 9333);

const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const page = targets.find((t) => t.type === 'page');
if (!page) {
  console.error('没有 page 类型的目标：', JSON.stringify(targets, null, 2));
  console.error('检查一下 exe 是不是被 ELECTRON_RUN_AS_NODE 劫持了（见文件头）。');
  process.exit(1);
}

const ws = new WebSocket(page.webSocketDebuggerUrl);
let seq = 0;
const pending = new Map();
const consoleEvents = [];

ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg);
    pending.delete(msg.id);
    return;
  }
  if (msg.method === 'Runtime.exceptionThrown') {
    const d = msg.params.exceptionDetails;
    consoleEvents.push('[exception] ' + (d.exception?.description || d.text));
  }
  if (msg.method === 'Runtime.consoleAPICalled') {
    consoleEvents.push(
      `[console.${msg.params.type}] ` +
      msg.params.args.map((a) => (a.value !== undefined ? String(a.value) : a.description || a.type)).join(' ')
    );
  }
  if (msg.method === 'Log.entryAdded') {
    consoleEvents.push(`[log.${msg.params.entry.level}] ${msg.params.entry.text}`);
  }
};

await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });

const send = (method, params) =>
  new Promise((resolve) => {
    const myId = ++seq;
    pending.set(myId, resolve);
    ws.send(JSON.stringify({ id: myId, method, params }));
  });

await send('Runtime.enable', {});
await send('Log.enable', {});

if (process.env.CDP_NAV) {
  await send('Page.enable', {});
  const nav = await send('Page.navigate', { url: process.env.CDP_NAV });
  console.log('=== Page.navigate ===');
  console.log(JSON.stringify(nav.result, null, 2));
  await new Promise((r) => setTimeout(r, 2500));
}

const expr = process.argv[2] || 'JSON.stringify({url: location.href, title: document.title}, null, 2)';
const out = await send('Runtime.evaluate', {
  expression: expr,
  returnByValue: true,
  awaitPromise: true,
  timeout: 8000
});

console.log('=== evaluate 结果 ===');
console.log(JSON.stringify(out.result ?? out, null, 2));

// 等一会收事件。enable 会把「之前已经发生」的消息也补发过来，
// 所以这里拿到的往往是页面加载期的真实报错。
await new Promise((r) => setTimeout(r, 1500));
if (consoleEvents.length) {
  console.log('=== 页面控制台事件 ===');
  for (const e of consoleEvents) console.log(e);
} else {
  console.log('=== 页面控制台无异常/无输出 ===');
}

ws.close();
process.exit(0);
