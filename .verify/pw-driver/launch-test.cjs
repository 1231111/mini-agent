/**
 * 实证：Playwright 的 headless:true 到底需不需要完整 Chromium。
 *
 * 对照组设计：
 *   headless=true  → 期望 SUCCESS（源码 chromium.js:305 说用 chromium-headless-shell）
 *   headless=false → 期望 FAIL   （同一份只含 headless shell 的 browsers 根目录下，
 *                                  证明确实是"完整 Chromium 只在有头模式才需要"，
 *                                  排除"测试本身恒过"的假象）
 *
 * 用法：PLAYWRIGHT_BROWSERS_PATH=<dir> node launch-test.cjs [headed]
 */
const { chromium } = require('./pw/package');

(async () => {
  const headless = process.argv[2] !== 'headed';
  const root = process.env.PLAYWRIGHT_BROWSERS_PATH;
  console.log('PLAYWRIGHT_BROWSERS_PATH =', root);
  console.log('headless =', headless);

  let browser;
  try {
    browser = await chromium.launch({ headless });
  } catch (e) {
    console.log('launch 失败:', String(e.message).split('\n').slice(0, 4).join(' | '));
    process.exit(headless ? 1 : 42); // 42 = 对照组预期失败
  }

  const page = await browser.newPage();
  await page.goto('data:text/html,<h1>miniagent</h1>');
  const text = await page.textContent('h1');
  console.log('页面内容:', text);
  console.log('浏览器版本:', browser.version());
  await browser.close();

  console.log(headless ? 'PASS  headless=true 在只有 headless shell 的环境下可跑通'
                       : 'UNEXPECTED  headless=false 居然也跑通了');
  process.exit(headless ? 0 : 2);
})().catch((e) => {
  console.error('未捕获异常:', e.message);
  process.exit(99);
});
