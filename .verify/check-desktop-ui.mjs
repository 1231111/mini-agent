/* 前端静态校验：内联脚本语法 + 每个 getElementById 的 id 是否真的存在。
 *
 * 为什么单列一条"id 是否存在"：这类拼写错误在运行时的表现是
 * 「TypeError: Cannot set properties of null」并让整段脚本中断 ——
 * 与 2026-09-28 那次 SyntaxError 一样，界面会静默停在启动页。
 * 语法检查抓不到它，跑起来才知道，而跑之前在这里查一遍成本几乎为零。
 *
 * 注意：本脚本只能证明"语法正确 + id 存在"。
 * 它证明不了「顶层标识符是否与 contextBridge 的全局名冲突」——
 * 那是解析期的全局声明冲突，静态抽出来检查永远是 OK 的（详见技能第 18 节）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const file = process.argv[2] || 'mini-agent-desktop/src/index.html';
const html = fs.readFileSync(file, 'utf8');

const blocks = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
console.log(`script 块数量: ${blocks.length}`);

const tmp = path.join('.verify', '_syntaxcheck');
fs.rmSync(tmp, { recursive: true, force: true });
fs.mkdirSync(tmp, { recursive: true });

let failed = 0;

blocks.forEach((body, i) => {
  const p = path.join(tmp, `s${i}.cjs`);
  fs.writeFileSync(p, body);
  try {
    // stdio 必须显式指定 stdin 为 'ignore'。
    // 默认是 ['pipe','pipe','pipe']，而在本机这套 Windows 环境里，
    // 子进程的 stdin 是匿名管道时 spawn 会直接失败：status=null、
    // error.code='EBUSY'、stderr 全空 —— 看起来像被检查的脚本有问题，
    // 其实根本没跑起来。这一点与 .verify/check-workflow.mjs 里的 tar 是同一个坑，
    // 不属于某个具体程序（node 自己也中招）。
    execFileSync(process.execPath, ['--check', p], { stdio: ['ignore', 'pipe', 'pipe'] });
    console.log(`  [OK]   块 #${i} 语法通过（${body.length} B）`);
  } catch (e) {
    failed++;
    console.log(`  [FAIL] 块 #${i} 语法错误：\n${e.stderr?.toString() || e.message}`);
  }
});

// 页面里真实存在的 id
const declaredIds = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map((m) => m[1]));

// 脚本运行时自己造出来的 id（如看门狗的 bootWatch）。它们不在 HTML 里，
// 但引用它们是对的，不能被判成"缺失"。
const dynamicIds = new Set([...html.matchAll(/\.id\s*=\s*['"]([^'"]+)['"]/g)].map((m) => m[1]));

console.log(`页面声明的 id: ${[...declaredIds].join(', ')}`);

const used = new Set([...html.matchAll(/getElementById\(\s*['"]([^'"]+)['"]\s*\)/g)].map((m) => m[1]));

const missing = [...used].filter((id) => !declaredIds.has(id) && !dynamicIds.has(id));
console.log(`脚本引用的 id 数量: ${used.size}（其中 ${[...used].filter((i) => dynamicIds.has(i)).length} 个由脚本运行时创建）`);
if (missing.length) {
  failed++;
  console.log(`  [FAIL] 以下 id 被引用但页面里不存在: ${missing.join(', ')}`);
} else {
  console.log('  [OK]   所有被引用的 id 都能被解析到');
}

// 反向也看一眼：声明了但从没用过的 id（多为死代码或改名残留，只提示不判失败）
const unused = [...declaredIds].filter((id) => !used.has(id));
if (unused.length) console.log(`  [提示] 声明了但脚本未引用的 id: ${unused.join(', ')}`);

fs.rmSync(tmp, { recursive: true, force: true });
console.log(failed ? `\n结果: FAIL（${failed} 项）` : '\n结果: 全部通过');
process.exit(failed ? 1 : 0);
