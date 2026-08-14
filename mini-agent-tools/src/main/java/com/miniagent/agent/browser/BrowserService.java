package com.miniagent.agent.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.Locator.AriaSnapshotOptions;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitUntilState;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.common.MessageConstants;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * 浏览器服务：用 Playwright 驱动无头 Chromium，提供页面快照和交互能力。
 *
 * 核心思路（对标 hermes-agent 的 agent-browser）：
 * - 不返回 HTML，而是返回 accessibility tree（aria snapshot）
 * - 每个 task 有独立的浏览器 session
 * - LLM 通过 ref ID（如 @1, @2）定位元素
 */
@Slf4j
@Service
public class BrowserService {

    /** 与 mini-agent-app/pom.xml 中 playwright 依赖版本一致，用于在 classpath 未展开时定位 jar */
    private static final String PLAYWRIGHT_MAVEN_VERSION = "1.49.0";
    private static final int CLICK_TIMEOUT_MS = 2000;
    private static final int EXTRACT_MAX_STEPS = 120;
    private static final int EXTRACT_PAUSE_MS = 50;
    private static final int EXTRACT_MIN_CHARS = 20;
    private static final int EXTRACT_MAX_PAGES = 40;
    private static final int EXTRACT_NAV_PAUSE_MS = 500;
    private static final int EXTRACT_IMAGE_MIN_PX = 80;
    private static final int EXTRACT_MAX_IMAGES = 200;
    private static final int EXTRACT_IMAGE_TIMEOUT_MS = 15000;
    private static final long EXTRACT_IMAGE_MAX_BYTES = 20L * 1024 * 1024;
    private static final String EXTRACT_BLOCK_IMG_PREFIX = "__block__:";
    private static final Pattern EXTRACT_MD_IMG = Pattern.compile(
            "!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final String[] PROXY_ENV_KEYS = {
            "HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"
    };
    private static final String PROXY_BYPASS = "localhost,127.0.0.1,::1";
    private static final String EXTRACT_JS = """
            async () => {
              const maxSteps = __MAX__;
              const pauseMs = __PAUSE__;
              const imgMin = __IMGMIN__;
              const wait = () => new Promise(r => setTimeout(r, pauseMs));
              for (let w = 0; w < 25; w++) {
                const named = document.querySelector(
                  '.bear-web-x-container,.wiki-content,.docx-container');
                if (named && (named.innerText || '').trim().length > 40) {
                  break;
                }
                await wait();
              }
              const chrome = /catalogue-container|sidebar|comment-list/i;
              const pick = () => {
                const named = document.querySelector(
                  '.bear-web-x-container,.wiki-content,.docx-container');
                if (named && named.scrollHeight > named.clientHeight + 40) {
                  return named;
                }
                let best = document.scrollingElement || document.documentElement;
                let bestD = best.scrollHeight - best.clientHeight;
                for (const d of document.querySelectorAll('div')) {
                  const cls = (d.className || '') + '';
                  if (chrome.test(cls)) {
                    continue;
                  }
                  const delta = d.scrollHeight - d.clientHeight;
                  if (delta > bestD + 80) { bestD = delta; best = d; }
                }
                return best;
              };
              const skipLine = t =>
                /^(登录\\/注册|评论（\\d+）|帮助中心|效率指南)$/.test(t);
              const clsOf = el => {
                if (!el) {
                  return '';
                }
                if (el.classList && el.classList.length) {
                  return [...el.classList].join(' ');
                }
                return (el.className || '') + '';
              };
              const bid = el => el.getAttribute('data-block-id')
                  || el.getAttribute('data-record-id') || '';
              const inChrome = el => {
                let p = el;
                while (p && p !== document.body) {
                  if (chrome.test(clsOf(p))) {
                    return true;
                  }
                  p = p.parentElement;
                }
                return false;
              };
              const plain = el => {
                const spans = el.querySelectorAll('span[data-string]');
                if (spans.length) {
                  let s = '';
                  for (const x of spans) {
                    const owner = x.closest(
                        '[data-block-id], [data-record-id]');
                    if (owner && owner !== el) {
                      continue;
                    }
                    s += x.getAttribute('data-string') || x.textContent || '';
                  }
                  if (s) {
                    return s.replace(/\\u200b/g, '');
                  }
                }
                const clone = el.cloneNode(true);
                for (const c of clone.querySelectorAll(
                    'button, svg, img, [class*="toolbar"]')) {
                  c.remove();
                }
                return (clone.innerText || '').replace(/\\u200b/g, '').trim();
              };
              const langOf = el => {
                const t = el.innerText || '';
                const langs = 'Python|JavaScript|TypeScript|Java|Go|Shell|'
                    + 'Bash|SQL|JSON|YAML|HTML|CSS|XML|Kotlin|Rust|'
                    + 'C\\+\\+|C#|Swift|Markdown|Dockerfile|Plain Text';
                const m = t.match(new RegExp('\\b(' + langs + ')\\b', 'i'));
                if (!m) {
                  return '';
                }
                const x = m[1].toLowerCase();
                if (x === 'plain text') {
                  return '';
                }
                if (x === 'c++') {
                  return 'cpp';
                }
                if (x === 'c#') {
                  return 'csharp';
                }
                return x;
              };
              const codeText = el => {
                const pre = el.querySelector('pre');
                if (pre) {
                  return (pre.innerText || '').replace(/\\u200b/g, '');
                }
                const lines = el.querySelectorAll(
                    '.ace_line, .cm-line, .view-line, [class*="code-line"]');
                if (lines.length) {
                  return [...lines].map(x => (x.textContent || '')
                      .replace(/\\u200b/g, '')).join('\\n');
                }
                const clone = el.cloneNode(true);
                for (const c of clone.querySelectorAll(
                    'button, svg, [class*="header"], [class*="toolbar"]')) {
                  c.remove();
                }
                let t = (clone.innerText || '').replace(/\\u200b/g, '');
                const rows = t.split('\\n');
                const dropRe = '代码块|复制|自动换行|Plain Text|Python|'
                    + 'JavaScript|TypeScript|Java|Go|Shell|Bash|'
                    + 'SQL|JSON|YAML|HTML|CSS|XML';
                const drop = new RegExp('^(' + dropRe + ')$', 'i');
                while (rows.length && drop.test(rows[0].trim())) {
                  rows.shift();
                }
                return rows.join('\\n').replace(/\\s+$/, '');
              };
              const fence = (lang, code) => {
                if (!code || !code.replace(/\\s/g, '')) {
                  return '';
                }
                let n = 3;
                const tick = '`';
                while (code.includes(tick.repeat(n))) {
                  n++;
                }
                const b = tick.repeat(n);
                return b + (lang || '') + '\\n'
                    + code.replace(/\\s+$/, '') + '\\n' + b;
              };
              const imgSrc = img => {
                if (!img) {
                  return '';
                }
                const raw = img.currentSrc || img.getAttribute('src')
                    || img.getAttribute('data-src') || '';
                if (!raw || raw.startsWith('data:')
                    || raw.startsWith('blob:')) {
                  return '';
                }
                try {
                  return new URL(raw, location.href).href;
                } catch (e) {
                  return '';
                }
              };
              const contentImg = el => {
                for (const img of el.querySelectorAll('img')) {
                  const r = img.getBoundingClientRect();
                  const w = Math.max(r.width, img.naturalWidth || 0);
                  const h = Math.max(r.height, img.naturalHeight || 0);
                  if (w >= imgMin && h >= 20) {
                    return img;
                  }
                }
                return null;
              };
              const imgMd = el => {
                const img = contentImg(el);
                let alt = (img && (img.alt || img.getAttribute('aria-label')))
                    || '图片';
                alt = String(alt).replace(/[\\]\\r\\n]/g, ' ').trim() || '图片';
                const src = imgSrc(img);
                const id = bid(el);
                if (src) {
                  return '![' + alt + '](' + src + ')';
                }
                if (id) {
                  return '![' + alt + '](__block__:' + id + ')';
                }
                return '';
              };
              const isCode = (el, cls) => {
                if (/docx-code-block|wiki-code-block|neat-code|code-block/i
                    .test(cls)) {
                  return true;
                }
                if (el.querySelector(
                    '[data-block-id], [data-record-id]')) {
                  return false;
                }
                return (el.innerText || '').indexOf('代码块') === 0
                    && el.querySelector('pre, .ace_line, .cm-line');
              };
              const isImage = (el, cls) => {
                if (/docx-image-block|wiki-image-block|image-block/i.test(cls)) {
                  return true;
                }
                return !!contentImg(el) && plain(el).length < 40
                    && !el.querySelector('[data-block-id], [data-record-id]');
              };
              const toMd = el => {
                if (inChrome(el)) {
                  return '';
                }
                const cls = clsOf(el);
                if (isCode(el, cls)) {
                  return fence(langOf(el), codeText(el));
                }
                if (isImage(el, cls)) {
                  return imgMd(el);
                }
                const hm = cls.match(/heading([1-9])/i);
                if (hm) {
                  const t = plain(el);
                  return t ? ('#'.repeat(+hm[1]) + ' ' + t) : '';
                }
                if (/bullet|unordered/i.test(cls) && /block/i.test(cls)) {
                  const t = plain(el);
                  return t ? ('- ' + t) : '';
                }
                if (/ordered|numbered/i.test(cls) && /block/i.test(cls)) {
                  const t = plain(el);
                  return t ? ('1. ' + t) : '';
                }
                if (/quote/i.test(cls) && /block/i.test(cls)) {
                  const t = plain(el);
                  return t ? ('> ' + t) : '';
                }
                if (/text-block|docx-text/i.test(cls)) {
                  return plain(el);
                }
                if (el.querySelector('[data-block-id], [data-record-id]')) {
                  return null;
                }
                const t = plain(el);
                if (!t || skipLine(t)) {
                  return '';
                }
                return t;
              };
              const scroller = pick();
              const root = document.querySelector(
                  '.bear-web-x-container,.wiki-content,.docx-container')
                  || scroller;
              const seen = new Map();
              let blockHits = 0;
              const harvestBlocks = () => {
                for (const el of root.querySelectorAll(
                    '[data-block-id], [data-record-id]')) {
                  const id = bid(el);
                  if (!id) {
                    continue;
                  }
                  const md = toMd(el);
                  if (md === null) {
                    continue;
                  }
                  if (seen.has(id)) {
                    const prev = seen.get(id);
                    if (prev.indexOf('](__block__:') >= 0
                        && md.indexOf('](http') >= 0) {
                      seen.set(id, md);
                    }
                    continue;
                  }
                  seen.set(id, md);
                  blockHits++;
                }
              };
              const harvestOrphans = () => {
                for (const img of root.querySelectorAll('img')) {
                  if (img.closest('[data-block-id], [data-record-id]')) {
                    continue;
                  }
                  if (inChrome(img)) {
                    continue;
                  }
                  const r = img.getBoundingClientRect();
                  if (Math.max(r.width, img.naturalWidth || 0) < imgMin) {
                    continue;
                  }
                  const src = imgSrc(img);
                  if (!src || seen.has('img:' + src)) {
                    continue;
                  }
                  const alt = (img.alt || '图片')
                      .replace(/[\\]\\r\\n]/g, ' ');
                  seen.set('img:' + src, '![' + alt + '](' + src + ')');
                }
                for (const pre of root.querySelectorAll('pre')) {
                  if (pre.closest('[data-block-id], [data-record-id]')) {
                    continue;
                  }
                  if (inChrome(pre)) {
                    continue;
                  }
                  const t = (pre.innerText || '').replace(/\\u200b/g, '');
                  if (!t.trim()) {
                    continue;
                  }
                  const key = 'pre:' + t.slice(0, 80);
                  if (seen.has(key)) {
                    continue;
                  }
                  seen.set(key,
                      fence(langOf(pre.parentElement || pre), t));
                }
              };
              const scrollAll = async (fn) => {
                scroller.scrollTop = 0;
                await wait();
                let stable = 0;
                let lastH = -1;
                for (let i = 0; i < maxSteps; i++) {
                  fn();
                  const h = scroller.scrollHeight;
                  const before = scroller.scrollTop;
                  const step = Math.max(
                    Math.floor(scroller.clientHeight * 0.85), 400);
                  scroller.scrollTop = before + step;
                  await wait();
                  if (scroller.scrollTop <= before && h === lastH) {
                    stable++;
                    if (stable >= 2) {
                      break;
                    }
                  } else {
                    stable = 0;
                  }
                  lastH = h;
                }
                fn();
              };
              await scrollAll(() => {
                harvestBlocks();
                if (blockHits > 0) {
                  harvestOrphans();
                }
              });
              if (blockHits > 0) {
                return [...seen.values()].filter(Boolean).join('\\n\\n');
              }
              const dup = new Set();
              const lines = [];
              const add = raw => {
                if (!raw) {
                  return;
                }
                for (const line of raw.split('\\n')) {
                  const t = line.replace(/\\u200b/g, '').trim();
                  if (!t || t.length > 20000 || skipLine(t)
                      || dup.has(t)) {
                    continue;
                  }
                  dup.add(t);
                  lines.push(t);
                }
              };
              await scrollAll(() => add(scroller.innerText || ''));
              harvestOrphans();
              const extra = [...seen.values()].filter(Boolean);
              if (extra.length === 0) {
                return lines.join('\\n');
              }
              return lines.join('\\n') + '\\n\\n' + extra.join('\\n\\n');
            }
            """;
    private static final String PICK_CAT_FN = """
              const pickCat = () => {
                const named = document.querySelector(
                  '.wiki-tree-inner-container,'
                  + '.workspace-tree-view-container,'
                  + '.workspace-nav-tree-wrapper,'
                  + '[class*="directory-tree"]');
                if (named && named.querySelector('[data-node-uid]')) {
                  return named;
                }
                const nodes = [...document.querySelectorAll(
                  '[class*="catalogue"],[class*="catalog"],'
                  + '[class*="wiki-tree"],[class*="sidebar-tree"],.wiki-catalog')];
                let best = null;
                let bestS = -999;
                for (const el of nodes) {
                  const cls = (el.className || '') + '';
                  if (cls.includes('catalogue__')) {
                    continue;
                  }
                  const r = el.getBoundingClientRect();
                  if (r.width < 40 || r.height < 80 || r.left >= 300) {
                    continue;
                  }
                  const t = el.innerText || '';
                  let s = 0;
                  if (r.left >= 0 && r.left < 300) {
                    s += 8;
                  }
                  if (t.includes('首页') || t.includes('目录')) {
                    s += 10;
                  }
                  if (el.querySelector('[data-node-uid]')) {
                    s += 20;
                  }
                  if (/1\\.1\\.1/.test(t) && !t.includes('首页')) {
                    s -= 8;
                  }
                  s += Math.min(el.querySelectorAll(
                      'button, [data-node-uid]').length, 15) * 0.3;
                  if (s > bestS) {
                    bestS = s;
                    best = el;
                  }
                }
                return best;
              };
              """;
    private static final String TOC_EXPAND_JS = """
            async () => {
              const wait = (ms) => new Promise(r => setTimeout(r, ms));
              let clicks = 0;
              for (let round = 0; round < 12; round++) {
                let n = 0;
                for (const a of document.querySelectorAll(
                    '.workspace-tree-view-node-expand-arrow--collapsed')) {
                  try { a.click(); n++; } catch (e) {}
                }
                for (const el of document.querySelectorAll(
                    '[aria-expanded="false"]')) {
                  try { el.click(); n++; } catch (e) {}
                }
                clicks += n;
                await wait(150);
                if (n === 0) {
                  break;
                }
              }
              return clicks;
            }
            """;
    private static final String TOC_TOKENS_JS = """
            () => {
              const origin = location.origin;
              const seen = new Set();
              const urls = [];
              for (const el of document.querySelectorAll('[data-node-uid]')) {
                const uid = el.getAttribute('data-node-uid') || '';
                const m = uid.match(/wikiToken=([A-Za-z0-9]+)/);
                if (!m) {
                  continue;
                }
                const canon = origin + '/wiki/' + m[1];
                if (seen.has(canon)) {
                  continue;
                }
                seen.add(canon);
                urls.push(canon);
              }
              return urls;
            }
            """;
    private static final String TOC_URLS_JS = """
            () => {
            """ + PICK_CAT_FN + """
              const cat = pickCat() || document.body;
              const host = location.host;
              const seen = new Set();
              const urls = [];
              const add = (raw) => {
                if (!raw) {
                  return;
                }
                try {
                  const u = new URL(raw, location.origin);
                  if (u.host !== host) {
                    return;
                  }
                  const m = u.pathname.match(/\\/wiki\\/([A-Za-z0-9]+)/);
                  if (!m) {
                    return;
                  }
                  const canon = u.origin + '/wiki/' + m[1];
                  if (seen.has(canon)) {
                    return;
                  }
                  seen.add(canon);
                  urls.push(canon);
                } catch (e) {}
              };
              add(location.href);
              for (const a of cat.querySelectorAll('a[href]')) {
                add(a.getAttribute('href'));
              }
              for (const el of cat.querySelectorAll(
                  '[data-href],[data-url],[data-node-token],[data-token]')) {
                add(el.getAttribute('data-href') || el.getAttribute('data-url'));
                const tok = el.getAttribute('data-node-token')
                    || el.getAttribute('data-token');
                if (tok && /^[A-Za-z0-9]{10,}$/.test(tok)) {
                  add('/wiki/' + tok);
                }
              }
              return urls;
            }
            """;
    private static final String TOC_TITLES_JS = """
            async () => {
            """ + PICK_CAT_FN + """
              const wait = (ms) => new Promise(r => setTimeout(r, ms));
              const cat = pickCat();
              if (!cat) {
                return [];
              }
              const skip = /^(目录|Table of contents|登录\\/注册|问问知识库|帮助中心|效率指南)$/;
              const seen = new Set();
              const out = [];
              const labelOf = (el) => {
                const clone = el.cloneNode(true);
                for (const c of clone.querySelectorAll('button, svg, img, i')) {
                  c.remove();
                }
                let t = (clone.innerText || '').replace(/\\u200b/g, '')
                    .replace(/\\s+/g, ' ').trim();
                if (!t) {
                  t = (el.innerText || '').split('\\n')[0]
                      .replace(/\\u200b/g, '').replace(/\\s+/g, ' ').trim();
                }
                return t;
              };
              const harvest = () => {
                for (const el of cat.querySelectorAll(
                    '.workspace-tree-view-node-content, button, a,'
                    + ' [role="treeitem"], [role="link"]')) {
                  const t = labelOf(el);
                  if (!t || t.length > 80 || skip.test(t) || seen.has(t)) {
                    continue;
                  }
                  seen.add(t);
                  out.push(t);
                }
              };
              cat.scrollTop = 0;
              await wait(40);
              harvest();
              for (let i = 0; i < 40; i++) {
                const h = cat.scrollHeight;
                cat.scrollTop += Math.max(cat.clientHeight, 200);
                await wait(40);
                harvest();
                if (cat.scrollTop + cat.clientHeight >= cat.scrollHeight - 2
                    && cat.scrollHeight === h) {
                  break;
                }
              }
              cat.scrollTop = 0;
              return out;
            }
            """;

    private Playwright playwright;
    private Browser browser;
    private final Map<String, Page> pages = new ConcurrentHashMap<>();
    private final ReentrantLock pageLock = new ReentrantLock();

    @Autowired
    private NetworkGuard networkGuard;

    @Value("${agent.browser.headless:true}")
    private boolean headless;

    /**
     * 确保浏览器实例已启动（懒初始化）
     * 首次启动时自动下载 Chromium（约 150MB）
     */
    private synchronized void ensureBrowser() {
        if (Objects.isNull(playwright)) {
            // 检测 Chromium 是否已下载，没有就自动安装
            ensureChromiumInstalled();
            playwright = Playwright.create();
        }
        if (Objects.isNull(browser)) {
            BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions()
                    .setHeadless(headless);
            String proxy = firstProxyEnv();
            if (StringUtils.isNotBlank(proxy)) {
                opts.setProxy(new Proxy(proxy).setBypass(PROXY_BYPASS));
                log.info("浏览器走环境变量代理");
            }
            browser = playwright.chromium().launch(opts);
            log.info("浏览器已启动 (headless={})", headless);
        }
    }

    private static String firstProxyEnv() {
        for (String key : PROXY_ENV_KEYS) {
            String v = System.getenv(key);
            if (StringUtils.isNotBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }

    /**
     * 自动检测并安装 Chromium
     * 调用 Playwright CLI 的 install 功能，等效于 mvn exec:java -Dexec.args="install chromium"
     */
    private void ensureChromiumInstalled() {
        Path browsersRoot = resolveBrowsersRoot();

        // 检查是否有 chromium 目录（版本号因 Playwright 版本而异）
        // 注意：仅判断目录存在不够——残缺安装（chrome.exe 在但 chrome.dll 缺失）会导致
        // 启动时报 "Failed to load Chrome DLL ... 0x7E"。必须校验关键文件完整。
        boolean installed = false;
        if (Files.exists(browsersRoot)) {
            try (var stream = Files.list(browsersRoot)) {
                installed = stream.anyMatch(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("chromium-") && isChromiumDirValid(p);
                });
            } catch (IOException e) {
                // ignore
            }
        }

        if (installed) {
            log.debug("Chromium 已安装且完整（目录 {}）", browsersRoot);
            return;
        }

        log.info("Chromium 未安装或安装残缺（缺 chrome.dll 等核心文件），开始重新下载（约 150MB，请耐心等待）...");

        tryInstallViaCommand();
    }

    /**
     * 校验一个 chromium-* 目录是否是完整可用的安装。
     * 残缺安装的典型特征：chrome.exe（启动壳，约 2-3MB）在，但 chrome.dll（主程序模块，
     * 正常 100MB+）缺失或过小，启动时报 "Failed to load Chrome DLL ... 0x7E"。
     * 这里只校验 Windows 的关键文件；非 Windows 平台保持宽松（只要目录在即认为有效，
     * 交给 Playwright 自身处理）。
     */
    private static boolean isChromiumDirValid(Path chromiumDir) {
        if (!isWindows()) {
            return true; // 非 Windows：可执行文件命名/结构不同，不在此校验
        }
        Path chromeWin = chromiumDir.resolve("chrome-win");
        Path exe = chromeWin.resolve("chrome.exe");
        Path dll = chromeWin.resolve("chrome.dll");
        try {
            if (!Files.isRegularFile(exe) || !Files.isRegularFile(dll)) {
                return false;
            }
            // chrome.dll 正常上百 MB，残缺/占位文件远小于此。用 10MB 作为保守下限。
            return Files.size(dll) > 10_000_000L;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Playwright 默认浏览器根目录：Windows 为 {@code %LOCALAPPDATA%\ms-playwright}，Linux/mac 常为 {@code ~/.cache/ms-playwright}。
     */
    private static Path resolveBrowsersRoot() {
        String override = System.getProperty("playwright.browsers.path");
        if (StringUtils.isNotBlank(override)) {
            return Paths.get(override);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (StringUtils.isNotBlank(local)) {
                return Paths.get(local, "ms-playwright");
            }
        }
        return Paths.get(System.getProperty("user.home"), ".cache", "ms-playwright");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 自动安装 Chromium：优先用当前 JDK 执行 {@code com.microsoft.playwright.CLI}（不要用 {@code java -jar}，官方 jar 无 Runnable 清单）。
     */
    private void tryInstallViaCommand() {
        if (tryInstallViaClasspathCli()) {
            return;
        }

        if (commandOnPath("npx") && tryInstallViaNpx()) {
            return;
        }

        if (commandOnPath("mvn") && tryInstallViaMaven()) {
            return;
        }

        log.error("""
                ═══════════════════════════════════════════
                Chromium 自动安装失败。请在本机手动执行（二选一）：

                  1) 使用当前 JDK（推荐，注意 -cp 不要写成 -jar）：
                     "%JAVA_HOME%\\bin\\java.exe" -cp "<playwright.jar路径>" com.microsoft.playwright.CLI install chromium

                  2) 已安装 Node 时：npx playwright install chromium

                下载慢或超时时可设置环境变量 PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT=600000（毫秒）后重试。
                安装完成后重启应用。
                ═══════════════════════════════════════════
                """);
    }

    private static boolean commandOnPath(String name) {
        try {
            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("where", name)
                    : new ProcessBuilder("which", name);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 使用 java -cp playwright.jar com.microsoft.playwright.CLI install chromium */
    private boolean tryInstallViaClasspathCli() {
        try {
            String playwrightJar = resolvePlaywrightJarPath();
            if (Objects.isNull(playwrightJar)) {
                log.debug("未解析到 playwright jar 路径");
                return false;
            }

            String javaExe = resolveJavaExecutable();
            log.info("找到 playwright jar: {}", playwrightJar);
            log.info("正在通过 CLI 安装 Chromium（约 150MB，弱网请耐心等待或增大 PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT）...");

            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-cp");
            cmd.add(playwrightJar);
            cmd.add("com.microsoft.playwright.CLI");
            cmd.add("install");
            cmd.add("chromium");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            if (Objects.isNull(env.get("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT"))) {
                env.put("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT", "600000");
            }

            Process p = pb.start();

            try (var in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) {
                    String chunk = new String(buf, 0, len, StandardCharsets.UTF_8);
                    for (String line : chunk.split("\\R")) {
                        line = line.trim();
                        if (!StringUtils.isBlank(line)) {
                            log.info("  playwright: {}", line);
                        }
                    }
                }
            }

            boolean done = p.waitFor(600, java.util.concurrent.TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                log.info("Chromium 安装成功！");
                return true;
            }
            log.error("Chromium 安装失败，退出码: {}", done ? p.exitValue() : "超时");
            return false;
        } catch (Exception e) {
            log.debug("CLI classpath 方式安装失败", e);
        }
        return false;
    }

    private static String resolveJavaExecutable() {
        Path home = Paths.get(System.getProperty("java.home", ""));
        Path bin = home.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        if (Files.isRegularFile(bin)) {
            return bin.toAbsolutePath().toString();
        }
        return "java";
    }

    private static String resolvePlaywrightJarPath() {
        String classpath = System.getProperty("java.class.path");
        if (Objects.nonNull(classpath)) {
            for (String entry : classpath.split(File.pathSeparator)) {
                if (entry.contains("playwright") && entry.endsWith(".jar") && Files.isRegularFile(Paths.get(entry))) {
                    return entry;
                }
            }
        }

        String ver = PLAYWRIGHT_MAVEN_VERSION;
        String jarName = "playwright-" + ver + ".jar";
        List<String> fallbacks = new ArrayList<>();
        String m2Local = System.getProperty("maven.repo.local");
        if (StringUtils.isNotBlank(m2Local)) {
            fallbacks.add(Paths.get(m2Local, "com", "microsoft", "playwright", "playwright", ver, jarName).toString());
        }
        fallbacks.add(Paths.get(System.getProperty("user.home"), ".m2", "repository", "com", "microsoft", "playwright", "playwright", ver, jarName).toString());

        for (String fp : fallbacks) {
            if (Files.isRegularFile(Paths.get(fp))) {
                return fp;
            }
        }
        return null;
    }

    /** 方式2：npx playwright install chromium */
    private boolean tryInstallViaNpx() {
        try {
            log.info("通过 npx 安装 Chromium...");
            ProcessBuilder pb = new ProcessBuilder("npx", "playwright", "install", "chromium");
            pb.redirectErrorStream(true);
            Process p = pb.start();

            Map<String, String> env = pb.environment();
            if (Objects.isNull(env.get("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT"))) {
                env.put("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT", "600000");
            }

            try (var in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) {
                    String chunk = new String(buf, 0, len, StandardCharsets.UTF_8);
                    if (chunk.contains("%") || chunk.contains("Downloading") || chunk.contains("installed")) {
                        for (String line : chunk.split("\\R")) {
                            if (!StringUtils.isBlank(line)) {
                                log.info("  {}", line.trim());
                            }
                        }
                    }
                }
            }

            boolean done = p.waitFor(600, java.util.concurrent.TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                log.info("Chromium 安装成功 (npx)");
                return true;
            }
        } catch (Exception e) {
            log.debug("npx 方式安装失败", e);
        }
        return false;
    }

    /** 方式3：mvn exec:java（需本机 PATH 中有 mvn） */
    private boolean tryInstallViaMaven() {
        try {
            log.info("通过 mvn 安装 Chromium...");
            ProcessBuilder pb = new ProcessBuilder("mvn", "-q", "exec:java",
                    "-Dexec.mainClass=com.microsoft.playwright.CLI",
                    "-Dexec.classpathScope=compile",
                    "-Dexec.args=install chromium");
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File(System.getProperty("user.dir")));
            Map<String, String> env = pb.environment();
            if (Objects.isNull(env.get("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT"))) {
                env.put("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT", "600000");
            }
            Process p = pb.start();

            try (var in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                while (in.read(buf) != -1) { /* 读完防阻塞 */ }
            }

            boolean done = p.waitFor(600, java.util.concurrent.TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                log.info("Chromium 安装成功 (mvn)");
                return true;
            }
        } catch (Exception e) {
            log.debug("mvn 方式安装失败", e);
        }
        return false;
    }

    /**
     * 获取或创建一个 page
     */
    private Page getPage(String sessionId) {
        // 空字符串/null → 用 "default" session
        if (StringUtils.isBlank(sessionId)) {
            sessionId = "default";
        }
        return pages.computeIfAbsent(sessionId, id -> {
            ensureBrowser();
            Page page = browser.newPage();
            log.info("创建新浏览器页面: {}", id);
            return page;
        });
    }

    // ==================== 工具方法 ====================

    /**
     * 打开网页
     */
    public String navigate(String sessionId, String url) {
        pageLock.lock();
        try {
            String blocked = networkGuard.validateUrl(url);
            if (Objects.nonNull(blocked)) return blocked;
            Page page = getPage(sessionId);
            if (sameDocPath(page.url(), url)) {
                String title = page.title();
                String snapshot = page.locator("body").ariaSnapshot();
                log.info("已在该页，跳过重新导航: {}", url);
                return "已在该页，未重新加载（避免 SPA 回到首页）。标题: "
                        + title + "\n\n" + formatSnapshot(snapshot);
            }
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(15000));

            String title = page.title();
            String snapshot = page.locator("body").ariaSnapshot();
            log.info("导航到: {} 标题: {}", url, title);

            return "页面标题: " + title + "\n\n" + formatSnapshot(snapshot);

        } catch (Exception e) {
            log.error("导航失败: {}", url, e);
            return String.format(MessageConstants.BROWSER_NAV_FAILED, e.getMessage());
        } finally {
            pageLock.unlock();
        }
    }

    /**
     * 获取页面无障碍树快照
     */
    public String snapshot(String sessionId, boolean full) {
        pageLock.lock();
        try {
            Page page = getPage(sessionId);
            String snapshot;
            if (full) {
                snapshot = page.locator("body").ariaSnapshot(
                        new AriaSnapshotOptions().setTimeout(120_000));
            } else {
                snapshot = page.locator("body").ariaSnapshot();
            }
            return formatSnapshot(snapshot);

        } catch (Exception e) {
            log.error("获取快照失败", e);
            return String.format(MessageConstants.BROWSER_SNAPSHOT_FAILED, e.getMessage());
        } finally {
            pageLock.unlock();
        }
    }

    /**
     * 点击。by：auto|ref|text|role|css|aria。数字 ref 只走快照，失败立即返回（不把编号当 aria-label）。
     */
    public String click(String sessionId, String ref) {
        return click(sessionId, ref, "auto");
    }

    public String click(String sessionId, String ref, String by) {
        if (StringUtils.isBlank(ref)) return MessageConstants.BROWSER_CLICK_FAILED_EMPTY_REF;
        String mode = StringUtils.isBlank(by) ? "auto" : by.trim().toLowerCase(Locale.ROOT);
        pageLock.lock();
        try {
            Page page = getPage(sessionId);
            boolean numeric = ref.chars().allMatch(Character::isDigit);
            if ("ref".equals(mode) || ("auto".equals(mode) && numeric)) {
                return clickBySnapshotRef(page, ref);
            }
            Locator loc = switch (mode) {
                case "text" -> page.getByText(ref, new Page.GetByTextOptions().setExact(true));
                case "role" -> roleLocator(page, ref);
                case "css" -> page.locator(ref).first();
                case "aria" -> page.locator("[aria-label*='" + ref + "'], [placeholder*='" + ref + "']").first();
                case "auto" -> page.getByText(ref, new Page.GetByTextOptions().setExact(true));
                default -> null;
            };
            if (Objects.isNull(loc)) {
                return String.format(MessageConstants.BROWSER_CLICK_FAILED_UNKNOWN_BY, mode);
            }
            return clickAndSnap(page, loc, mode, ref);
        } catch (Exception e) {
            log.error("点击失败: ref={} by={}", ref, mode, e);
            return String.format(MessageConstants.BROWSER_CLICK_FAILED, ref, mode, e.getMessage());
        } finally {
            pageLock.unlock();
        }
    }

    private String clickBySnapshotRef(Page page, String ref) {
        final int refNum;
        try {
            refNum = Integer.parseInt(ref);
        } catch (NumberFormatException e) {
            return String.format(MessageConstants.BROWSER_CLICK_FAILED_NOT_DIGIT, ref);
        }
        String elementInfo = findElementByRef(page, refNum);
        if (Objects.isNull(elementInfo)) return String.format(MessageConstants.BROWSER_CLICK_FAILED_NO_REF, ref);
        log.info("click ref={} 对应元素: {}", ref, elementInfo);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(elementInfo);
        String name = m.find() ? m.group(1) : null;
        if (StringUtils.isBlank(name)) {
            return String.format(MessageConstants.BROWSER_CLICK_FAILED_NO_NAME, ref, elementInfo);
        }
        // 按快照角色选唯一策略；失败直接报错，不静默串联
        Locator loc = elementInfo.contains("button")
                ? page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name))
                : elementInfo.contains("link")
                ? page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(name))
                : page.getByText(name);
        try {
            return clickAndSnap(page, loc, "ref", name);
        } catch (Exception e) {
            return String.format(MessageConstants.BROWSER_CLICK_FAILED_BY_NAME, ref, name, e.getMessage());
        }
    }

    private Locator roleLocator(Page page, String ref) {
        AriaRole role = AriaRole.BUTTON;
        String roleName = ref;
        int eq = ref.indexOf('=');
        if (eq > 0) {
            role = parseAriaRole(ref.substring(0, eq).trim());
            roleName = ref.substring(eq + 1).trim();
        }
        return page.getByRole(role, new Page.GetByRoleOptions().setName(roleName));
    }

    private String clickAndSnap(Page page, Locator locator, String strategy, String label) {
        try {
            locator.scrollIntoViewIfNeeded();
        } catch (Exception ignored) {}
        try {
            locator.click(new Locator.ClickOptions().setTimeout(CLICK_TIMEOUT_MS));
        } catch (Exception clickEx) {
            try {
                locator.evaluate("el => el.click()");
                log.info("click 超时，已改 JS click: {}", label);
            } catch (Exception jsEx) {
                throw clickEx;
            }
        }
        page.waitForTimeout(400);
        return String.format(MessageConstants.BROWSER_CLICK_SUCCESS, strategy, label) + "\n\n"
                + formatSnapshot(page.locator("body").ariaSnapshot());
    }

    static boolean sameDocPath(String currentUrl, String targetUrl) {
        return normPath(currentUrl).equals(normPath(targetUrl));
    }

    private static String normPath(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            String p = java.net.URI.create(url.trim()).getPath();
            return p == null ? url : p;
        } catch (Exception e) {
            return url;
        }
    }

    private static AriaRole parseAriaRole(String raw) {
        try {
            return AriaRole.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (Exception e) {
            return AriaRole.BUTTON;
        }
    }

    /**
     * 在搜索框/输入框中输入文字
     * ref: 快照中的编号（如 "11"）或 placeholder 文本（如 "搜索"）
     * text: 要输入的文字
     */
    public String type(String sessionId, String ref, String text) {
        try {
            Page page = getPage(sessionId);
            int shortTimeout = 3000; // 每个策略最多等3秒，失败立即跳下一个

            // 策略1: ref 是数字 → 在快照中找到该编号对应的元素，用 aria label/name 匹配
            try {
                int refNum = Integer.parseInt(ref);
                String elementInfo = findElementByRef(page, refNum);
                if (Objects.nonNull(elementInfo)) {
                    log.info("ref={} 对应元素: {}", ref, elementInfo);
                    // 尝试用 placeholder / label 找输入框
                    Locator input = findInputByInfo(page, elementInfo);
                    if (Objects.nonNull(input)) {
                        input.fill(text, new Locator.FillOptions().setTimeout(shortTimeout));
                        return String.format(MessageConstants.BROWSER_INPUT_SUCCESS, ref, text) + "\n\n"
                                + formatSnapshot(page.locator("body").ariaSnapshot());
                    }
                }
            } catch (Exception ignored) {}

            // 策略2: ref 是文本 → 用 placeholder 匹配
            try {
                page.getByPlaceholder(ref).fill(text, new Locator.FillOptions().setTimeout(shortTimeout));
                return String.format(MessageConstants.BROWSER_INPUT_SUCCESS_PLACEHOLDER, ref, text) + "\n\n"
                        + formatSnapshot(page.locator("body").ariaSnapshot());
            } catch (Exception ignored) {}

            // 策略3: ref 是文本 → 用 aria label 匹配
            try {
                page.getByLabel(ref).fill(text, new Locator.FillOptions().setTimeout(shortTimeout));
                return String.format(MessageConstants.BROWSER_INPUT_SUCCESS_LABEL, ref, text) + "\n\n"
                        + formatSnapshot(page.locator("body").ariaSnapshot());
            } catch (Exception ignored) {}

            return String.format(MessageConstants.BROWSER_INPUT_FAILED_NOT_FOUND, ref);

        } catch (Exception e) {
            log.error("输入失败: ref={}", ref, e);
            return String.format(MessageConstants.BROWSER_INPUT_FAILED, e.getMessage());
        }
    }

    /**
     * 从无障碍树快照中找到 ref 编号对应的元素描述
     */
    private String findElementByRef(Page page, int refNum) {
        String raw = page.locator("body").ariaSnapshot();
        int currentRef = 0;
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (isSnapshotRefLine(trimmed)) {
                currentRef++;
                if (currentRef == refNum) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    /**
     * 根据元素描述尝试找到输入框
     */
    private Locator findInputByInfo(Page page, String elementInfo) {
        // 提取引号中的文本（如 "搜索"）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(elementInfo);
        String label = m.find() ? m.group(1) : null;

        if (elementInfo.contains("searchbox") || elementInfo.contains("textbox")
                || (Objects.nonNull(label) && (label.contains("搜索") || label.contains("search")))) {
            // 搜索框 / 输入框
            Locator byPlaceholder = page.locator("input[placeholder]").first();
            if (byPlaceholder.count() > 0) return byPlaceholder;
        }

        if (Objects.nonNull(label)) {
            try {
                return page.getByPlaceholder(label);
            } catch (Exception ignored) {}
            try {
                return page.getByLabel(label);
            } catch (Exception ignored) {}
        }

        // fallback: 第一个可见的 input
        Locator firstInput = page.locator("input:visible, textarea:visible").first();
        return firstInput.count() > 0 ? firstInput : null;
    }

    /**
     * 按键盘按键
     */
    public String press(String sessionId, String key) {
        try {
            Page page = getPage(sessionId);
            page.keyboard().press(key);
            return String.format(MessageConstants.BROWSER_PRESS_SUCCESS, key);
        } catch (Exception e) {
            log.error("按键失败: key={}", key, e);
            return String.format(MessageConstants.BROWSER_PRESS_FAILED, e.getMessage());
        }
    }

    /**
     * 滚动页面
     */
    public String scroll(String sessionId, String direction) {
        try {
            Page page = getPage(sessionId);
            if ("up".equalsIgnoreCase(direction)) {
                page.mouse().wheel(0, -500);
            } else {
                page.mouse().wheel(0, 500);
            }
            return String.format(MessageConstants.BROWSER_SCROLL_SUCCESS, direction);
        } catch (Exception e) {
            return String.format(MessageConstants.BROWSER_SCROLL_FAILED, e.getMessage());
        }
    }

    /**
     * 截图
     */
    public String screenshot(String sessionId) {
        pageLock.lock();
        try {
            Page page = getPage(sessionId);
            String path = "screenshot_" + sessionId + ".png";
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get(path))
                    .setFullPage(false));
            return String.format(MessageConstants.BROWSER_SCREENSHOT_SAVED, path);
        } catch (Exception e) {
            return String.format(MessageConstants.BROWSER_SCREENSHOT_FAILED, e.getMessage());
        } finally {
            pageLock.unlock();
        }
    }

    /**
     * 关闭当前 session 的浏览器页面
     */
    public String close(String sessionId) {
        Page page = pages.remove(sessionId);
        if (Objects.nonNull(page)) {
            page.close();
            return String.format(MessageConstants.BROWSER_PAGE_CLOSED, sessionId);
        }
        return "没有找到 session: " + sessionId;
    }

    /**
     * 滚完主内容区（含虚拟列表）并返回去重后的正文。
     * 侧栏有目录时按项点击，一次抽完全部章节。
     */
    public String extractArticleText(String sessionId) {
        return extractArticleText(sessionId, null);
    }

    public String extractArticleText(String sessionId, Path imageDir) {
        pageLock.lock();
        try {
            Page page = getPage(sessionId);
            if (isPasswordGate(page)) {
                return "抽取失败: 当前是登录或密码页，先 browser_type 填密码再抽取";
            }
            String home = page.url();
            waitForWikiTree(page);
            page.evaluate(TOC_EXPAND_JS);
            List<String> urls = jsStringList(page.evaluate(TOC_TOKENS_JS));
            if (urls.size() <= 1) {
                urls = jsStringList(page.evaluate(TOC_URLS_JS));
            }
            log.info("目录链接 {} 条", urls.size());
            StringBuilder all = new StringBuilder();
            Set<String> seen = new LinkedHashSet<>();
            int ok = 0;
            if (urls.size() > 1) {
                for (String url : urls) {
                    if (ok >= EXTRACT_MAX_PAGES) {
                        break;
                    }
                    if (!openWikiUrl(page, url) || isLoginPage(page)
                            || isPasswordGate(page)) {
                        leaveLoginPage(page, home);
                        continue;
                    }
                    String key = wikiKey(page.url());
                    if (!seen.add(key)) {
                        continue;
                    }
                    ok += appendExtract(all, page, imageDir);
                }
            } else {
                List<String> titles = jsStringList(page.evaluate(TOC_TITLES_JS));
                log.info("目录标题 {} 条 {}", titles.size(),
                        titles.size() > 12 ? titles.subList(0, 12) : titles);
                for (String title : titles) {
                    if (ok >= EXTRACT_MAX_PAGES) {
                        break;
                    }
                    try {
                        clickCatalogTitle(page, title);
                    } catch (Exception e) {
                        log.warn("目录项点击失败 title={}: {}", title, e.getMessage());
                        continue;
                    }
                    if (isLoginPage(page) || isPasswordGate(page)) {
                        log.info("跳过登录/密码页 title={}", title);
                        leaveLoginPage(page, home);
                        continue;
                    }
                    String key = wikiKey(page.url());
                    if (!seen.add(key)) {
                        continue;
                    }
                    ok += appendExtract(all, page, imageDir);
                }
            }
            if (all.length() < EXTRACT_MIN_CHARS) {
                return extractCurrentPage(page, imageDir);
            }
            log.info("抽取知识库 chapters={} chars={}", ok, all.length());
            return all.toString();
        } catch (Exception e) {
            return String.format(MessageConstants.BROWSER_EXTRACT_FAILED,
                    e.getMessage());
        } finally {
            pageLock.unlock();
        }
    }

    private String extractCurrentPage(Page page, Path imageDir) {
        String script = EXTRACT_JS
                .replace("__MAX__", String.valueOf(EXTRACT_MAX_STEPS))
                .replace("__PAUSE__", String.valueOf(EXTRACT_PAUSE_MS))
                .replace("__IMGMIN__", String.valueOf(EXTRACT_IMAGE_MIN_PX));
        Object result = page.evaluate(script);
        String body = result == null ? "" : result.toString().trim();
        if (body.length() < EXTRACT_MIN_CHARS) {
            return String.format(MessageConstants.BROWSER_EXTRACT_EMPTY,
                    page.url());
        }
        body = localizeImages(page, body, imageDir);
        String title = StringUtils.defaultString(page.title());
        String url = StringUtils.defaultString(page.url());
        log.info("抽取正文 chars={} url={}", body.length(), url);
        return "# " + title + "\n\n" + url + "\n\n" + body;
    }

    private static List<String> jsStringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && StringUtils.isNotBlank(o.toString())) {
                    out.add(o.toString().trim());
                }
            }
        }
        return out;
    }

    private static boolean isLoginPage(Page page) {
        return isLoginUrl(StringUtils.defaultString(page.url()));
    }

    private static boolean isLoginUrl(String url) {
        return url.contains("accounts.feishu.cn") || url.contains("/accounts/page/login");
    }

    private boolean isPasswordGate(Page page) {
        if (isLoginPage(page)) {
            return true;
        }
        try {
            Locator box = page.locator("input[type=password]");
            return box.count() > 0 && box.first().isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    private void leaveLoginPage(Page page, String home) {
        if (!isLoginPage(page) && !isPasswordGate(page)) {
            return;
        }
        try {
            page.goBack(new Page.GoBackOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(15000));
            page.waitForTimeout(EXTRACT_NAV_PAUSE_MS);
            if ((isLoginPage(page) || isPasswordGate(page)) && !isLoginUrl(home)) {
                page.navigate(home, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(15000));
                page.waitForTimeout(EXTRACT_NAV_PAUSE_MS);
            }
        } catch (Exception e) {
            log.warn("从登录页返回失败: {}", e.getMessage());
        }
    }

    private int appendExtract(StringBuilder all, Page page, Path imageDir) {
        if (isLoginPage(page)) {
            return 0;
        }
        String part = extractCurrentPage(page, imageDir);
        if (part.startsWith("抽取失败")) {
            return 0;
        }
        if (all.length() > 0) {
            all.append("\n\n---\n\n");
        }
        all.append(part);
        return 1;
    }

    /** 已持有 pageLock，禁止再调 navigate()。 */
    private boolean openWikiUrl(Page page, String url) {
        String blocked = networkGuard.validateUrl(url);
        if (blocked != null) {
            log.warn("目录链接被拦截: {} {}", url, blocked);
            return false;
        }
        if (sameDocPath(page.url(), url)) {
            return true;
        }
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(15000));
            page.waitForTimeout(EXTRACT_NAV_PAUSE_MS);
            return true;
        } catch (Exception e) {
            log.warn("打开目录链接失败 url={}: {}", url, e.getMessage());
            return false;
        }
    }

    private static final String TREE_COUNT_JS =
            "() => document.querySelectorAll('[data-node-uid]').length";
    private static final int TREE_WAIT_ROUNDS = 25;
    private static final int TREE_WAIT_MS = 200;

    private void waitForWikiTree(Page page) {
        for (int i = 0; i < TREE_WAIT_ROUNDS; i++) {
            Object n = page.evaluate(TREE_COUNT_JS);
            if (n instanceof Number && ((Number) n).intValue() > 0) {
                return;
            }
            page.waitForTimeout(TREE_WAIT_MS);
        }
    }

    private void clickCatalogTitle(Page page, String title) {
        String before = wikiKey(page.url());
        Object hit = page.evaluate("""
                (want) => {
                """ + PICK_CAT_FN + """
                  const n = String(want || '').replace(/\\s+/g, ' ').trim();
                  if (!n) {
                    return false;
                  }
                  const clickIf = (el) => {
                    const t = (el.innerText || '').replace(/\\s+/g, ' ').trim();
                    if (t === n || t.endsWith(n) || n.endsWith(t) || t.includes(n)) {
                      el.click();
                      return true;
                    }
                    return false;
                  };
                  for (const el of document.querySelectorAll(
                      '.workspace-tree-view-node-content')) {
                    if (clickIf(el)) {
                      return true;
                    }
                  }
                  const cat = pickCat();
                  if (!cat) {
                    return false;
                  }
                  for (const el of cat.querySelectorAll(
                      'button, a, [role="treeitem"], [role="link"]')) {
                    if (clickIf(el)) {
                      return true;
                    }
                  }
                  return false;
                }
                """, title);
        if (!Boolean.TRUE.equals(hit)) {
            Locator loc = page.getByText(title, new Page.GetByTextOptions().setExact(true));
            loc.first().evaluate("el => el.click()");
        }
        page.waitForTimeout(EXTRACT_NAV_PAUSE_MS);
        for (int i = 0; i < 8 && before.equals(wikiKey(page.url())); i++) {
            page.waitForTimeout(200);
        }
    }

    private static String wikiKey(String url) {
        if (url == null) {
            return "";
        }
        try {
            String p = java.net.URI.create(url.trim()).getPath();
            return p == null ? url : p;
        } catch (Exception e) {
            return url;
        }
    }

    private String localizeImages(Page page, String md, Path imageDir) {
        if (imageDir == null || StringUtils.isBlank(md)) {
            return md;
        }
        Matcher m = EXTRACT_MD_IMG.matcher(md);
        if (!m.find()) {
            return md;
        }
        try {
            Files.createDirectories(imageDir);
        } catch (IOException e) {
            log.warn("无法创建图片目录 {}: {}", imageDir, e.getMessage());
            return md;
        }
        String dirName = imageDir.getFileName().toString();
        Map<String, String> cache = new LinkedHashMap<>();
        int n = 0;
        m.reset();
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String alt = m.group(1);
            String url = m.group(2);
            String local = cache.get(url);
            if (local == null) {
                if (n >= EXTRACT_MAX_IMAGES) {
                    local = url;
                } else {
                    n++;
                    local = saveExtractImage(page, url, imageDir, dirName, n);
                    if (local == null) {
                        local = url;
                    }
                }
                cache.put(url, local);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(
                    "![" + alt + "](" + local + ")"));
        }
        m.appendTail(out);
        log.info("抽取图片 {} 张目录={}", cache.size(), imageDir);
        return out.toString();
    }

    private String saveExtractImage(Page page, String url, Path imageDir,
                                    String dirName, int index) {
        String fileName = "img_" + String.format("%03d", index);
        if (url.startsWith(EXTRACT_BLOCK_IMG_PREFIX)) {
            String id = url.substring(EXTRACT_BLOCK_IMG_PREFIX.length());
            Path dest = imageDir.resolve(fileName + ".png");
            if (screenshotBlock(page, id, dest)) {
                return dirName + "/" + dest.getFileName();
            }
            return null;
        }
        if (!allowExtractImageUrl(page.url(), url)) {
            return screenshotFallback(page, url, imageDir, dirName, fileName);
        }
        try {
            APIResponse resp = page.request().get(url, RequestOptions.create()
                    .setTimeout(EXTRACT_IMAGE_TIMEOUT_MS)
                    .setHeader("Referer", page.url()));
            byte[] body = resp.body();
            String ct = "";
            Map<String, String> headers = resp.headers();
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if ("content-type".equalsIgnoreCase(e.getKey())) {
                        ct = StringUtils.defaultString(e.getValue());
                        break;
                    }
                }
            }
            if (resp.status() < 200 || resp.status() >= 300
                    || body == null || body.length == 0
                    || body.length > EXTRACT_IMAGE_MAX_BYTES
                    || !ct.toLowerCase(Locale.ROOT).startsWith("image/")) {
                return screenshotFallback(
                        page, url, imageDir, dirName, fileName);
            }
            String ext = imageExt(ct);
            Path dest = imageDir.resolve(fileName + ext);
            Files.write(dest, body);
            return dirName + "/" + dest.getFileName();
        } catch (Exception e) {
            log.warn("下载图片失败: {}", e.getMessage());
            return screenshotFallback(page, url, imageDir, dirName, fileName);
        }
    }

    private String screenshotFallback(Page page, String url, Path imageDir,
                                      String dirName, String fileName) {
        Path dest = imageDir.resolve(fileName + ".png");
        String sel = "img[src=\"" + cssAttr(url) + "\"]";
        try {
            Locator loc = page.locator(sel);
            if (loc.count() == 0) {
                return null;
            }
            loc.first().scrollIntoViewIfNeeded();
            loc.first().screenshot(new Locator.ScreenshotOptions().setPath(dest));
            return dirName + "/" + dest.getFileName();
        } catch (Exception e) {
            log.warn("截取图片失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean screenshotBlock(Page page, String blockId, Path dest) {
        if (blockId == null || !blockId.matches("[A-Za-z0-9_-]{1,80}")) {
            return false;
        }
        String sel = "[data-block-id=\"" + blockId
                + "\"], [data-record-id=\"" + blockId + "\"]";
        try {
            Locator loc = page.locator(sel);
            if (loc.count() == 0) {
                return false;
            }
            loc.first().scrollIntoViewIfNeeded();
            loc.first().screenshot(new Locator.ScreenshotOptions().setPath(dest));
            return Files.exists(dest);
        } catch (Exception e) {
            log.warn("截取图片块失败 id={}: {}", blockId, e.getMessage());
            return false;
        }
    }

    private static String imageExt(String contentType) {
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("jpeg") || ct.contains("jpg")) {
            return ".jpg";
        }
        if (ct.contains("gif")) {
            return ".gif";
        }
        if (ct.contains("webp")) {
            return ".webp";
        }
        if (ct.contains("svg")) {
            return ".svg";
        }
        return ".png";
    }

    private static String cssAttr(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean allowExtractImageUrl(String pageUrl, String imgUrl) {
        try {
            URI img = URI.create(imgUrl.trim());
            String scheme = StringUtils.defaultString(img.getScheme());
            if (!"http".equalsIgnoreCase(scheme)
                    && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = StringUtils.defaultString(img.getHost())
                    .toLowerCase(Locale.ROOT);
            if (host.isBlank()) {
                return false;
            }
            String pageHost = "";
            if (StringUtils.isNotBlank(pageUrl)) {
                pageHost = StringUtils.defaultString(
                        URI.create(pageUrl.trim()).getHost())
                        .toLowerCase(Locale.ROOT);
            }
            if (host.equals(pageHost)) {
                return true;
            }
            return host.endsWith(".feishu.cn")
                    || host.endsWith(".larksuite.com")
                    || host.endsWith(".feishucdn.com")
                    || host.endsWith(".larksuitecdn.com")
                    || host.endsWith(".bytedance.net");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 在页面执行 JavaScript
     */
    public String evaluate(String sessionId, String expression) {
        pageLock.lock();
        try {
            Page page = getPage(sessionId);
            Object result = page.evaluate(expression);
            return String.format(MessageConstants.BROWSER_EVAL_RESULT,
                    Objects.nonNull(result) ? result.toString() : "null");
        } catch (Exception e) {
            return String.format(MessageConstants.BROWSER_EVAL_FAILED, e.getMessage());
        } finally {
            pageLock.unlock();
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 给无障碍树快照的每一行加编号 ref
     * 原始:  - link "搜索"
     * 处理后: [1] - link "搜索"
     */
    private String formatSnapshot(String snapshot) {
        if (StringUtils.isBlank(snapshot)) {
            return MessageConstants.BROWSER_EMPTY_PAGE;
        }

        StringBuilder sb = new StringBuilder();
        String[] lines = snapshot.split("\n");
        int refNum = 1;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // 只给可点击角色编号；普通 `- text:` 不当 ref，否则编号和点击对不上
            if (isSnapshotRefLine(trimmed)) {
                sb.append(String.format("[%d] %s%n", refNum++, trimmed));
            } else {
                sb.append(line).append("\n");
            }
        }

        return sb.toString();
    }

    /** 快照里可点的角色行才编号。`- text:` 静态文案不算。 */
    public static boolean isSnapshotRefLine(String trimmed) {
        if (trimmed == null) {
            return false;
        }
        int dash = trimmed.indexOf("- ");
        if (dash < 0) {
            return false;
        }
        String rest = trimmed.substring(dash + 2);
        return rest.startsWith("link ") || rest.startsWith("link:")
                || rest.startsWith("button ") || rest.startsWith("button:")
                || rest.startsWith("textbox") || rest.startsWith("searchbox")
                || rest.startsWith("checkbox") || rest.startsWith("combobox")
                || rest.startsWith("radio") || rest.startsWith("heading");
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭所有浏览器会话...");
        pages.values().forEach(Page::close);
        pages.clear();
        if (Objects.nonNull(browser)) browser.close();
        if (Objects.nonNull(playwright)) playwright.close();
    }
}
