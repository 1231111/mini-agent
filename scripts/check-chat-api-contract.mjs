#!/usr/bin/env node
/*
 * 守住 chat.html 与后端 ApiResponse 的响应契约。
 *
 * 为什么需要这个检查：
 *   com.miniagent.common.ApiResponse 的成功体是 {"success":true,"data":...}，
 *   code / message 因 @JsonInclude(NON_NULL) **整个键都不出现**；code 在失败时是
 *   "AUTH.01.01" 这样的**字符串错误码**，从来不是数字 0。
 *   页面曾经按 `body.code !== 0` 判成功 —— 浏览器里拿到 undefined，任何成功响应
 *   都判成失败。线上表现是：批准已经生效、工具已经在跑，页面仍弹「批准失败: HTTP 200」，
 *   同一处错判还让执行策略按钮切完不刷新、并且总是提示切换失败。
 *
 * 这个脚本只做静态检查（不需要浏览器），跑法：
 *   node scripts/check-chat-api-contract.mjs
 * 退出码非 0 表示契约被破坏。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
// 可选参数只用于「拿一份被改坏的副本验证这条检查本身有效」，正常跑不传。
const templatePath = process.argv[2]
  ? resolve(process.argv[2])
  : resolve(here, '../mini-agent-app/src/main/resources/templates/chat.html');
const source = readFileSync(templatePath, 'utf8');

const failures = [];
const checks = [];

/** 去掉注释，避免把「不要写 code === 0」这句说明本身当成违规。 */
const stripped = source
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/(^|[^:])\/\/[^\n]*/g, '$1');

function must(name, ok, detail) {
  checks.push({ name, ok, detail });
  if (!ok) failures.push(detail || name);
}

// 1) 不允许再用数字 0 判成功：成功时 code 键根本不存在。
const numericCode = [...stripped.matchAll(/\.code\s*[!=]==?\s*0\b/g)].map((m) => m[0]);
must('没有 .code === 0 / !== 0 这类判定', numericCode.length === 0,
  `发现 ${numericCode.length} 处数字 code 判定: ${numericCode.join(', ')}`);

// 2) 统一判定函数必须存在，且实现按 success 布尔字段。
must('存在 apiSuccess()', /function\s+apiSuccess\s*\(/.test(stripped));
must('apiSuccess 按 success === true 判定',
  /function\s+apiSuccess\s*\([^)]*\)\s*\{[\s\S]{0,200}?\.success\s*===\s*true/.test(stripped));

// 3) 批准请求必须带一次性 token（服务端拒绝只有工具名的请求）。
must('grantAskTool 提交 grantToken',
  /action:\s*'grant_ask'[\s\S]{0,120}?grantToken/.test(stripped));

// 4) 失败时不能只剩一个已作废的「批准并继续」按钮。
must('存在「知道了」这类只读提示按钮', /id="askNoticeActions"/.test(source));

const pad = (s, n) => s + ' '.repeat(Math.max(0, n - s.length));
let failed = 0;
for (const c of checks) {
  console.log(`${c.ok ? 'PASS' : 'FAIL'}  ${pad(c.name, 46)}${c.ok ? '' : c.detail || ''}`);
  if (!c.ok) failed++;
}
console.log(`\n${checks.length - failed}/${checks.length} passed  (${templatePath})`);
process.exit(failed === 0 ? 0 : 1);
