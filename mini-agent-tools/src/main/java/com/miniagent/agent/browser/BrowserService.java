package com.miniagent.agent.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.Locator.AriaSnapshotOptions;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.common.MessageConstants;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * - LLM 通过绑定观察版本与 DOM 节点的 ref（如 b12.3）定位元素
 */
@Slf4j
@Service
public class BrowserService {

    /** 与 mini-agent-tools/pom.xml 中 playwright 依赖版本一致，用于在 classpath 未展开时定位 jar */
    private static final String PLAYWRIGHT_MAVEN_VERSION = "1.59.0";
    private static final int CLICK_TIMEOUT_MS = 2000;
    private static final int CLICK_ACTIONABILITY_TIMEOUT_MS = 3000;
    private static final int CLICK_DISPATCH_TIMEOUT_MS = 5000;
    private static final int CLICK_OBSERVATION_TIMEOUT_MS = 3000;
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

    /**
     * Playwright 完整 Chromium 的版本化目录前缀。
     *
     * <p>目录名由 playwright-core 的 registry 拼出：{@code name.replace(/-/g, '_') + '-' + revision}
     * （lib/server/registry/index.js 里构造 {@code dir} 的那一行），所以 chromium → {@code chromium-1148}。
     */
    private static final String CHROMIUM_DIR_PREFIX = "chromium-";

    /**
     * Playwright headless shell 的版本化目录前缀。
     *
     * <p>同一套拼接规则下 chromium-headless-shell → {@code chromium_headless_shell-1148}：
     * 连字符被换成下划线。因此它与 {@link #CHROMIUM_DIR_PREFIX} 不会互相误匹配
     * ——Playwright 自己的 {@code isBrowserDirectory()} 用的也是这条规则
     * （{@code baseName.startsWith(browserName.replace(/-/g, '_') + '-')}）。
     */
    private static final String HEADLESS_SHELL_DIR_PREFIX = "chromium_headless_shell-";

    /**
     * 判定「主程序模块是否完整」的体积下限。
     *
     * <p>两类包的主模块正常都在百 MB 量级（完整包是 chrome.dll，headless shell 是
     * headless_shell.exe），残缺或占位文件远小于此。用 10MB 作为保守下限：
     * 只要存在性检查挡不住「文件在但内容被截断」这种残缺。
     */
    private static final long BROWSER_MAIN_MODULE_MIN_BYTES = 10_000_000L;

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
    /** Includes queued work and work whose caller has already stopped waiting. */
    private final Map<String, Integer> pendingSessionOperations = new ConcurrentHashMap<>();
    private final Map<String, BrowserContext> sessionContexts = new LinkedHashMap<>();
    private final Map<String, String> statePersistenceWarnings = new LinkedHashMap<>();
    /** Disk deletion has committed; the old authenticated context must never be used or saved again. */
    private final Set<String> pendingSessionClears = new LinkedHashSet<>();
    private final Map<String, PointerPosition> lastPointerPositions = new ConcurrentHashMap<>();
    private final Map<String, Observation> observations = new LinkedHashMap<>();
    private final ReentrantLock pageLock = new ReentrantLock();
    private final ObjectMapper resultJson = new ObjectMapper();
    private volatile Thread actorThread;
    private long observationSequence;
    // Playwright Java objects must always be used from the thread that created them.
    private final ThreadPoolExecutor actor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), work -> {
                Thread thread = new Thread(() -> {
                    actorThread = Thread.currentThread();
                    work.run();
                }, "miniagent-browser");
                thread.setDaemon(true);
                // Tool workers may originate in ForkJoinPool.commonPool(), whose
                // context loader cannot see Spring Boot's nested dependencies.
                // Playwright uses the context loader to locate driver/platform.
                thread.setContextClassLoader(BrowserService.class.getClassLoader());
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private record ObservedElement(String description, ElementHandle handle) {}
    private static final class PageDiagnostics {
        final List<String> consoleErrors = new ArrayList<>();
        final List<String> pageErrors = new ArrayList<>();
        final Set<String> requestOrigins = new LinkedHashSet<>();
        final List<Map<String, String>> blockedRequests = new ArrayList<>();
    }
    private final Map<String, PageDiagnostics> pageDiagnostics = new ConcurrentHashMap<>();
    private record FrameDocument(Frame frame, ElementHandle root, String url) { }
    private record Observation(String id, String url, String rawSnapshot, Map<String, ObservedElement> elements,
                               String formFingerprint, List<FrameDocument> frameDocuments) {
        Observation(String id, String url, String rawSnapshot, Map<String, ObservedElement> elements) {
            this(id, url, rawSnapshot, elements, null, List.of());
        }
    }

    /** A caller may stop preparation without interrupting Playwright's shared event loop. */
    private static final class ActorInputGate {
        private static final int STOPPED = 1, INPUT_STARTED = 2;
        private final AtomicInteger state = new AtomicInteger();
        void stop() { state.getAndUpdate(current -> current | STOPPED); }
        boolean stopped() { return (state.get() & STOPPED) != 0; }
        boolean inputStarted() { return (state.get() & INPUT_STARTED) != 0; }
        boolean beginInput() {
            int current;
            do {
                current = state.get();
                if ((current & STOPPED) != 0) return false;
            } while (!state.compareAndSet(current, current | INPUT_STARTED));
            return true;
        }
    }
    private final ThreadLocal<ActorInputGate> actorInputGate = new ThreadLocal<>();

    private static final class ActorInputStopped extends RuntimeException { }

    /** Every distinct input in a multi-step operation needs its own cancellation boundary. */
    private void requireActorInput() {
        ActorInputGate gate = actorInputGate.get();
        if (gate != null && !gate.beginInput()) throw new ActorInputStopped();
    }

    private void checkActorPreparation() {
        ActorInputGate gate = actorInputGate.get();
        if (gate != null && gate.stopped()) throw new ActorInputStopped();
    }

    private String onActor(Callable<String> operation) {
        return onActor(operation, () -> {});
    }

    private String onActor(Callable<String> operation, Runnable completed) {
        if (Thread.currentThread() == actorThread) {
            try { return operation.call(); }
            catch (RuntimeException error) { throw error; }
            catch (Exception error) { throw new IllegalStateException(error); }
            finally { completed.run(); }
        }
        if (Thread.currentThread().isInterrupted()) {
            completed.run();
            return browserError("CANCELLED", "浏览器操作在派发前已取消");
        }
        ActorInputGate inputGate = new ActorInputGate();
        AtomicInteger state = new AtomicInteger(); // queued / running / cancelled / finished
        Future<String> pending;
        try {
            pending = actor.submit(() -> {
                try {
                    if (!state.compareAndSet(0, 1)) return browserError("CANCELLED", "浏览器操作在执行前已取消");
                    actorInputGate.set(inputGate);
                    try { return operation.call(); }
                    finally { actorInputGate.remove(); state.set(3); }
                } finally {
                    // A stopped caller does not imply that Playwright has finished its input.
                    completed.run();
                }
            });
        } catch (RuntimeException | Error rejected) {
            completed.run();
            throw rejected;
        }
        try {
            return pending.get();
        } catch (InterruptedException interrupted) {
            inputGate.stop(); // Stops later input boundaries, including after an earlier input.
            boolean beforeStart = state.compareAndSet(0, 2);
            Thread.currentThread().interrupt();
            // Never interrupt the shared Playwright event loop: an already-dispatched click
            // may have committed. The tool pipeline must retain that uncertainty.
            return beforeStart ? browserError("CANCELLED", "浏览器操作在执行前已取消")
                    : "{\"success\":false,\"status\":\"unknown\",\"errorCode\":\"OUTCOME_UNKNOWN\","
                    + "\"error\":\"浏览器操作已派发，尚未确认结果；请重新观察，禁止直接重试\"}";
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        }
    }

    private String browserError(String code, String message) {
        return resultJson.createObjectNode().put("success", false).put("errorCode", code)
                .put("error", message).toString();
    }

    /** The key must be the trusted session resource key used to dispatch browser tools. */
    public boolean hasPendingOperations(String trustedSessionResourceKey) {
        return trustedSessionResourceKey != null
                && pendingSessionOperations.getOrDefault(trustedSessionResourceKey, 0) > 0;
    }

    private String onTrackedSessionActor(String sessionId, Callable<String> operation) {
        String rootResource = BrowserSessionIdentity.operationGroupResourceKey(sessionId);
        List<String> activityKeys = sessionId.equals(rootResource)
                ? List.of(sessionId) : List.of(sessionId, rootResource);
        activityKeys.forEach(key -> pendingSessionOperations.merge(key, 1, Integer::sum));
        return onActor(operation, () -> activityKeys.forEach(activityKey ->
                pendingSessionOperations.computeIfPresent(activityKey,
                        (key, count) -> count == 1 ? null : count - 1)));
    }

    private String onSessionActor(String sessionId, Callable<String> operation) {
        return onTrackedSessionActor(sessionId, () -> {
            if (pendingSessionClears.contains(sessionId)) return browserError("CONFLICT",
                    "LOGIN_STATE_CLEAR_PENDING: 已清除保存的登录态，但旧页面关闭未完成；请显式重新清除或关闭当前浏览器会话");
            String result;
            try { result = operation.call(); }
            catch (ActorInputStopped stopped) { result = browserError("CANCELLED", "浏览器操作已取消"); }
            finally { persistSessionState(sessionId); }
            ActorInputGate gate = actorInputGate.get();
            if (gate != null && gate.stopped()) {
                invalidateObservation(sessionId);
                return gate.inputStarted() ? unknownAction("浏览器操作已停止，先前派发的输入可能已生效")
                        : browserError("CANCELLED", "浏览器操作在实际输入前已取消；恢复任务时请重新观察");
            }
            String warning = statePersistenceWarnings.get(sessionId);
            if (warning == null) return result;
            // Keep success/unknown and the original action result intact; this is not an input failure.
            try {
                var parsed = resultJson.readTree(result);
                if (parsed != null && parsed.isObject()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) parsed).put("statePersistenceWarning", warning);
                    return parsed.toString();
                }
            } catch (Exception ignored) { /* Legacy browser responses are plain text. */ }
            return result + "\n[浏览器登录态恢复警告] " + warning;
        });
    }

    private void persistSessionState(String sessionId) {
        if (sessionStateStore == null || pendingSessionClears.contains(sessionId)) return;
        BrowserContext context = sessionContexts.get(sessionId);
        if (context == null) return;
        try {
            sessionStateStore.save(sessionId, context.storageState());
            statePersistenceWarnings.remove(sessionId);
        } catch (Exception failed) {
            // An action may already have taken effect. Never turn a save failure into an input retry.
            statePersistenceWarnings.put(sessionId, "登录态保存失败；当前页面操作未回滚，重启后可能需要重新登录");
            log.warn("浏览器会话登录态保存失败 session={} type={}", sessionId, failed.getClass().getSimpleName());
        }
    }

    public String navigate(String sessionId, String url) { return onSessionActor(sessionId, () -> navigateOnActor(sessionId, url)); }
    public String snapshot(String sessionId, boolean full) { return onSessionActor(sessionId, () -> snapshotOnActor(sessionId, full)); }
    public String formInspect(String sessionId) { return onSessionActor(sessionId, () -> formInspectOnActor(sessionId)); }
    public String formVerify(String sessionId, String ref, String expectedValue, Boolean expectedChecked,
                             List<String> expectedValues, List<String> expectedFiles) {
        return onSessionActor(sessionId, () -> formVerifyOnActor(sessionId, ref, expectedValue, expectedChecked, expectedValues, expectedFiles));
    }
    public String click(String sessionId, String ref) { return click(sessionId, ref, "auto"); }
    public String click(String sessionId, String ref, String by) { return onSessionActor(sessionId, () -> clickOnActor(sessionId, ref, by)); }
    public String type(String sessionId, String ref, String text) { return onSessionActor(sessionId, () -> typeOnActor(sessionId, ref, text)); }
    public String select(String sessionId, String ref, List<String> values, String by) {
        return onSessionActor(sessionId, () -> selectOnActor(sessionId, ref, values, by));
    }
    public String setChecked(String sessionId, String ref, boolean checked) {
        return onSessionActor(sessionId, () -> setCheckedOnActor(sessionId, ref, checked));
    }
    public String upload(String sessionId, String ref, List<Path> trustedPaths) {
        return onSessionActor(sessionId, () -> uploadOnActor(sessionId, ref, trustedPaths));
    }
    public String press(String sessionId, String key) { return onSessionActor(sessionId, () -> pressOnActor(sessionId, key)); }
    public String scroll(String sessionId, String direction) { return onSessionActor(sessionId, () -> scrollOnActor(sessionId, direction)); }
    public String screenshot(String sessionId, Path workspaceRoot) { return onSessionActor(sessionId, () -> screenshotOnActor(sessionId, workspaceRoot)); }
    public String close(String sessionId) { return onTrackedSessionActor(sessionId, () -> closeOnActor(sessionId)); }
    public String extractArticleText(String sessionId) { return extractArticleText(sessionId, null); }
    public String extractArticleText(String sessionId, Path imageDir) { return onSessionActor(sessionId, () -> extractArticleTextOnActor(sessionId, imageDir)); }
    public String evaluate(String sessionId, String expression) { return onSessionActor(sessionId, () -> evaluateOnActor(sessionId, expression)); }

    /** Explicitly discard this chat's saved authentication; never implied by an ordinary close. */
    public String clearSessionState(String sessionId) {
        return onTrackedSessionActor(sessionId, () -> {
            try {
                if (sessionStateStore == null) return browserError("DEPENDENCY_UNAVAILABLE", "浏览器登录态持久化未配置");
                // Delete first: a failure must leave the still-live context available for diagnosis.
                sessionStateStore.clear(sessionId);
                pendingSessionClears.add(sessionId);
                closeSessionContext(sessionId);
                statePersistenceWarnings.remove(sessionId);
                return "已清除此聊天的浏览器登录态；下次打开网页需要重新登录。";
            } catch (Exception failed) {
                return browserError("EXECUTION_FAILED", pendingSessionClears.contains(sessionId)
                        ? "已清除保存的登录态，但旧页面关闭失败；此会话已锁定，请显式重试清除或关闭，未自动重试"
                        : "清除浏览器登录态失败，原会话仍可使用，未自动重试");
            }
        });
    }

    private record PointerPosition(double x, double y, int viewportWidth, int viewportHeight) {}

    @Autowired
    private NetworkGuard networkGuard;

    @Autowired(required = false)
    private BrowserSessionStateStore sessionStateStore;

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
            // Browser installation is controlled above, with the exact dependency revision and headless mode.
            // Driver initialization must not silently install Firefox/WebKit/full Chromium as well.
            playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        }
        if (Objects.isNull(browser) || (sessionStateStore != null && !browser.isConnected())) {
            if (browser != null) {
                for (String sid : new ArrayList<>(observations.keySet())) invalidateObservation(sid);
                pages.clear(); sessionContexts.clear(); pageDiagnostics.clear();
            }
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
     * 自动检测并安装「当前 headless 配置真正会用到的那个」Chromium 包。
     * 走 Playwright CLI 的 install 功能，等效于 mvn exec:java -Dexec.args="install chromium"。
     *
     * <p><b>为什么必须区分两个包。</b>Playwright 1.49 起把 Chromium 拆成
     * {@code chromium} 与 {@code chromium-headless-shell} 两个独立的下载物，用哪个完全由
     * headless 选项决定 —— playwright-core 的 lib/server/chromium/chromium.js 里就一行：
     * <pre>
     *   getExecutableName(options) {
     *     if (options.channel) return options.channel;
     *     return options.headless ? 'chromium-headless-shell' : 'chromium';
     *   }
     * </pre>
     * 本类只设了 headless、没设 channel，于是：
     * <ul>
     *   <li>{@code agent.browser.headless=true}（桌面档与 prod 档都写死 true，
     *       且 ProductionReadinessValidator 在 prod 下会拒绝 false）→ 用 chromium-headless-shell；</li>
     *   <li>{@code agent.browser.headless=false} → 用完整的 chromium。</li>
     * </ul>
     *
     * <p><b>照旧只认 {@code chromium-} 会出事。</b>出厂安装包默认只随包分发 headless shell
     * （完整包 314MB，headless 下永远不会被加载，构建脚本用 {@code --only-shell} 跳过），
     * 此时 {@code chromium-<rev>} 目录本就不存在。若仍按完整包去找，会把
     * 「headless shell 明明在位」误判成「未安装」，接着在离线客户机上打出一串误导性的
     * 下载失败——而真正的浏览器工具其实完全可用。
     */
    private void ensureChromiumInstalled() {
        Path browsersRoot = resolveBrowsersRoot();
        String expectedDirectory = expectedBrowserDirectory(headless);

        // Match this dependency's revision; an older intact cache cannot satisfy the current driver.
        // 注意：仅判断目录存在不够——残缺安装（完整包表现为 chrome.exe 在但 chrome.dll
        // 缺失或截断）会导致启动时报 "Failed to load Chrome DLL ... 0x7E"。必须校验关键文件。
        boolean installed = isBrowserDirValid(browsersRoot.resolve(expectedDirectory), headless);

        if (installed) {
            log.debug("浏览器已就绪（{}，目录 {}）",
                    headless ? "chromium-headless-shell" : "chromium", browsersRoot);
            return;
        }

        // 路径由外部指定（系统属性 / PLAYWRIGHT_BROWSERS_PATH）说明浏览器是随应用分发的，
        // 这里找不到就是安装包不完整。此时再去联网下载等于把"包坏了"伪装成"首次启动慢"，
        // 而离线客户机上那条路必然失败，用户最终看到的仍然是一句读不懂的启动错误。
        // 直接说清原因，比偷偷下一次上百 MB 有用。
        if (isBrowsersRootExternallyProvided()) {
            log.error("""
                    ═══════════════════════════════════════════
                    随包分发的浏览器不可用：{}
                    缺少 {} 目录 —— {}
                    应用安装包不完整，或该目录被清理过。
                    浏览器类工具将不可用，其余功能不受影响。
                    修复方式：重新安装 MiniAgent。
                    ═══════════════════════════════════════════
                    """, browsersRoot, expectedDirectory,
                    headless
                            ? "headless 模式所需的 headless shell"
                            : "有头模式所需的完整 Chromium；本安装包默认只含 headless shell，"
                                    + "把 agent.browser.headless 设回 true 即可恢复浏览器工具");
            return;
        }

        log.info("{} 未安装或安装残缺，开始重新下载，请耐心等待 ...",
                headless ? "Chromium headless shell" : "Chromium");

        try {
            tryInstallViaCommand();
            checkInstallerInterrupted();
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("浏览器安装已取消，未继续尝试其他安装方式", cancelled);
        }
    }

    /** Read the revision shipped by this dependency, so an older valid cache cannot satisfy a newer driver. */
    private static String expectedBrowserDirectory(boolean headlessShell) {
        String name = headlessShell ? "chromium-headless-shell" : "chromium";
        for (String platform : List.of("win32_x64", "darwin-arm64", "darwin", "linux", "linux-arm64")) {
            String resource = "driver/" + platform + "/package/browsers.json";
            try (var input = BrowserService.class.getClassLoader().getResourceAsStream(resource)) {
                if (input == null) continue;
                var browsers = new ObjectMapper().readTree(input).path("browsers");
                for (var browserEntry : browsers) {
                    if (name.equals(browserEntry.path("name").asText())) {
                        String revision = browserEntry.path("revision").asText();
                        if (!revision.matches("[0-9]+")) throw new IOException("Invalid browser revision");
                        return name.replace('-', '_') + "-" + revision;
                    }
                }
            } catch (IOException invalid) { throw new IllegalStateException("无法读取当前Playwright的浏览器版本清单", invalid); }
        }
        throw new IllegalStateException("缺少当前Playwright的浏览器版本清单，不能复用不匹配的浏览器");
    }

    /**
     * 校验一个浏览器目录是否完整可用。
     *
     * <p><b>两类包的核心文件完全不同，不能共用一套判定。</b>文件名取自 playwright-core 的
     * lib/server/registry/index.js 里 {@code EXECUTABLE_PATHS}：
     * <table border="1">
     *   <caption>两类包的布局差异</caption>
     *   <tr><th>包</th><th>启动文件</th><th>主程序模块</th></tr>
     *   <tr><td>chromium</td><td>{@code chrome-win/chrome.exe}（约 3MB）</td>
     *       <td>{@code chrome-win/chrome.dll}（约 240MB）</td></tr>
     *   <tr><td>chromium-headless-shell</td>
     *       <td colspan="2">{@code chrome-win/headless_shell.exe}（约 170MB）
     *           ——它既是启动文件也是主模块，<b>既没有 chrome.exe 也没有 chrome.dll</b></td></tr>
     * </table>
     * 所以「启动壳在、主模块缺失」这个残缺特征只对完整包成立；对 headless shell 而言
     * 可执行文件本身就是主模块，按大小卡一道就够。
     *
     * <p>非 Windows 上刻意保持宽松（只挡住空目录）：可执行文件的命名与目录层级在不同
     * Playwright 版本、不同发行版之间并不一致，校验写得太具体会把本来可用的安装误判成残缺，
     * 代价是一次上百 MB 的重复下载 —— 比"漏掉一次残缺检测"严重得多。
     *
     * @param headlessShell 该目录是 chromium-headless-shell（true）还是完整 chromium（false）
     */
    private static boolean isBrowserDirValid(Path dir, boolean headlessShell) {
        if (!isWindows()) {
            try (var s = Files.list(dir)) {
                return s.findAny().isPresent();
            } catch (IOException e) {
                return false;
            }
        }

        Path chromeWin = dir.resolve(headlessShell ? "chrome-headless-shell-win64" : "chrome-win64");
        boolean modernLayout = Files.isDirectory(chromeWin);
        if (!modernLayout) chromeWin = dir.resolve("chrome-win");
        Path main = headlessShell
                ? chromeWin.resolve(modernLayout ? "chrome-headless-shell.exe" : "headless_shell.exe")
                : chromeWin.resolve("chrome.dll");

        try {
            // 完整包多一个启动壳；headless shell 没有这一层，它的 exe 就是 main。
            if (!headlessShell && !Files.isRegularFile(chromeWin.resolve("chrome.exe"))) {
                return false;
            }
            if (!Files.isRegularFile(main)) {
                return false;
            }
            return Files.size(main) > BROWSER_MAIN_MODULE_MIN_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 解析 Playwright 的浏览器根目录。
     *
     * <p><b>为什么顺序是这样。</b>这个路径必须和 Playwright 自己找浏览器的路径一致 ——
     * 不一致的后果不是"多下一份"，而是"文件明明在却报 Executable doesn't exist"，
     * 而且从日志上看不出是哪边算错了。所以：
     * <ol>
     *   <li>{@code playwright.browsers.path} 系统属性 —— 显式传给本 JVM 的，优先级最高，
     *       便于排障时临时指到别处；</li>
     *   <li>{@code PLAYWRIGHT_BROWSERS_PATH} 环境变量 —— <b>这是 Playwright 官方认的变量</b>，
     *       桌面壳就是靠它把后端指到随包目录（resources/browsers）；</li>
     *   <li>各平台的默认缓存目录 —— 开发态不走前两条，落到这里。</li>
     * </ol>
     *
     * <p><b>macOS 的默认目录不是 {@code ~/.cache}。</b>Playwright 在 darwin 上用的是
     * {@code ~/Library/Caches/ms-playwright}。照搬 Linux 的约定会让两份逻辑指向不同位置：
     * 我们去 {@code ~/.cache} 找、Playwright 去 {@code ~/Library/Caches} 找，
     * 于是每次启动都判成"未安装"并触发一次 150MB 的重复下载。
     */
    private static Path resolveBrowsersRoot() {
        String override = System.getProperty("playwright.browsers.path");
        if (StringUtils.isNotBlank(override)) {
            return Paths.get(override);
        }
        String fromEnv = envBrowsersPath();
        if (Objects.nonNull(fromEnv)) {
            return Paths.get(fromEnv);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (StringUtils.isNotBlank(local)) {
                return Paths.get(local, "ms-playwright");
            }
        } else if (os.contains("mac") || os.contains("darwin")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Caches", "ms-playwright");
        }
        return Paths.get(System.getProperty("user.home"), ".cache", "ms-playwright");
    }

    /**
     * 读 {@code PLAYWRIGHT_BROWSERS_PATH} 环境变量，返回 null 表示"没设或不适用"。
     *
     * <p>{@code 0} 是 Playwright 的保留值，语义是"浏览器就装在当前 npm 包的 node_modules 里"，
     * 它不是路径。当成路径用会得到工作目录下一个名叫 {@code 0} 的目录。
     */
    private static String envBrowsersPath() {
        String v = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (StringUtils.isBlank(v) || "0".equals(v.trim())) {
            return null;
        }
        return v.trim();
    }

    /** 浏览器根目录是否由外部指定（系统属性或环境变量），而非平台默认位置。 */
    private static boolean isBrowsersRootExternallyProvided() {
        return StringUtils.isNotBlank(System.getProperty("playwright.browsers.path"))
                || Objects.nonNull(envBrowsersPath());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 自动安装 Chromium：优先用当前 JDK 执行 {@code com.microsoft.playwright.CLI}（不要用 {@code java -jar}，官方 jar 无 Runnable 清单）。
     */
    private void tryInstallViaCommand() throws InterruptedException {
        checkInstallerInterrupted();
        if (tryInstallViaClasspathCli()) {
            return;
        }

        checkInstallerInterrupted();
        if (commandOnPath("npx") && tryInstallViaNpx()) {
            return;
        }

        checkInstallerInterrupted();
        if (commandOnPath("mvn") && tryInstallViaMaven()) {
            return;
        }

        checkInstallerInterrupted();
        // 手动指引里也要带上 --only-shell，否则用户照着敲会把 314MB 的完整包又装回来 ——
        // 那正是本次安装要避免的东西。
        String onlyShell = headless ? " --only-shell" : "";
        log.error("""
                ═══════════════════════════════════════════
                浏览器自动安装失败。请在本机手动执行（二选一）：

                  1) 使用当前 JDK（推荐，注意 -cp 不要写成 -jar）：
                     "%JAVA_HOME%\\bin\\java.exe" -cp "<playwright、driver、driver-bundle三组件的classpath>" com.microsoft.playwright.CLI install chromium{}

                  2) 已安装 Node 时：npx --yes playwright@{} install chromium{}

                下载慢或超时时可设置环境变量 PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT=600000（毫秒）后重试。
                安装完成后重启应用。
                ═══════════════════════════════════════════
                """, onlyShell, PLAYWRIGHT_MAVEN_VERSION, onlyShell);
    }

    /**
     * install 命令的补充参数。
     *
     * <p>headless 时补 {@code --only-shell}：只下 headless shell（约 190MB），跳过 headless
     * 模式下永远不会被加载的完整包（约 314MB）。理由见 {@link #ensureChromiumInstalled()}。
     * 对出厂形态这不算"顺手省流量"式的优化 —— 装进去的是一份必然不被读取的 314MB。
     *
     * <p>注意 {@code --only-shell} 是 Playwright 1.49 起提供的选项。
     * 升级 Playwright 时若该选项被改名，这里会退化成"命令报错→走下一个兜底"，不会静默装错包。
     */
    private List<String> installArgsForCurrentMode() {
        return headless ? List.of("--only-shell") : List.of();
    }

    /**
     * 委托给 {@link com.miniagent.agent.tool.HostCommand#onPath(String)}。
     * 这里曾有一份自己的实现；现在 RenderDiagramTool 也要做同样的探测，
     * 两份拷贝就是两次漂移的机会，统一到一处。
     */
    private static boolean commandOnPath(String name) {
        return com.miniagent.agent.tool.HostCommand.onPath(name);
    }

    /** CLI needs all three exact-version components; the playwright jar alone cannot initialize its driver. */
    private boolean tryInstallViaClasspathCli() throws InterruptedException {
        try {
            String installerClasspath = resolvePlaywrightInstallerClasspath();
            if (Objects.isNull(installerClasspath)) {
                log.debug("未找到当前版本完整的Playwright安装器三组件");
                return false;
            }

            String javaExe = resolveJavaExecutable();
            log.info("已定位当前版本Playwright安装器三组件");
            log.info("正在通过 CLI 安装浏览器（{}），弱网请耐心等待或增大 PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT ...",
                    headless ? "headless shell" : "完整 Chromium");

            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-cp");
            cmd.add(installerClasspath);
            cmd.add("com.microsoft.playwright.CLI");
            cmd.add("install");
            cmd.add("chromium");
            cmd.addAll(installArgsForCurrentMode());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("PLAYWRIGHT_SKIP_BROWSER_GC", "1");
            if (Objects.isNull(env.get("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT"))) {
                env.put("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT", "600000");
            }

            int exitCode = runBrowserInstaller(pb, "playwright");
            if (verifiedInstallation(exitCode)) {
                log.info("Chromium 安装成功！");
                return true;
            }
            log.error("Chromium 安装失败，退出码: {}", exitCode);
            return false;
        } catch (InterruptedException cancelled) {
            throw cancelled;
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

    private static String resolvePlaywrightInstallerClasspath() {
        List<Path> repositories = new ArrayList<>();
        String m2Local = System.getProperty("maven.repo.local");
        if (StringUtils.isNotBlank(m2Local)) repositories.add(Paths.get(m2Local));
        repositories.add(Paths.get(System.getProperty("user.home"), ".m2", "repository"));
        return BrowserInstallerSupport.resolveClasspath(PLAYWRIGHT_MAVEN_VERSION, System.getProperty("java.class.path"), repositories);
    }

    private boolean verifiedInstallation(int exitCode) {
        String expected = expectedBrowserDirectory(headless);
        boolean valid = BrowserInstallerSupport.installationSucceeded(exitCode, resolveBrowsersRoot(), expected,
                candidate -> isBrowserDirValid(candidate, headless));
        if (exitCode == 0 && !valid) log.warn("安装器已退出但当前版本浏览器仍不可用，要求目录: {}", expected);
        return valid;
    }

    /** 方式2：npx playwright install chromium */
    private boolean tryInstallViaNpx() throws InterruptedException {
        try {
            log.info("通过 npx 安装浏览器（{}）...", headless ? "headless shell" : "完整 Chromium");

            List<String> cmd = BrowserInstallerSupport.npxCommand(PLAYWRIGHT_MAVEN_VERSION, headless, isWindows());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            // env 必须在 start() 之前设好：ProcessBuilder.start() 那一刻就把环境快照给了子进程，
            // start() 之后再改 pb.environment() 不会传下去（这里原先是 start() 在前、设环境在后，
            // 那句超时等于没写）。
            Map<String, String> env = pb.environment();
            env.put("PLAYWRIGHT_SKIP_BROWSER_GC", "1");
            if (Objects.isNull(env.get("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT"))) {
                env.put("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT", "600000");
            }

            int exitCode = runBrowserInstaller(pb, "npx");
            if (verifiedInstallation(exitCode)) {
                log.info("浏览器安装成功 (npx)");
                return true;
            }
        } catch (InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            log.debug("npx 方式安装失败", e);
        }
        return false;
    }

    /** 方式3：mvn exec:java（需本机 PATH 中有 mvn） */
    private boolean tryInstallViaMaven() throws InterruptedException {
        try {
            log.info("通过 mvn 安装浏览器（{}）...", headless ? "headless shell" : "完整 Chromium");
            ProcessBuilder pb = new ProcessBuilder("mvn", "-q", "exec:java",
                    "-Dexec.mainClass=com.microsoft.playwright.CLI",
                    "-Dexec.classpathScope=compile",
                    "-Dexec.args=install chromium" + (headless ? " --only-shell" : ""));
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File(System.getProperty("user.dir")));
            Map<String, String> env = pb.environment();
            env.put("PLAYWRIGHT_SKIP_BROWSER_GC", "1");
            if (Objects.isNull(env.get("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT"))) {
                env.put("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT", "600000");
            }
            int exitCode = runBrowserInstaller(pb, "mvn");
            if (verifiedInstallation(exitCode)) {
                log.info("Chromium 安装成功 (mvn)");
                return true;
            }
        } catch (InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            log.debug("mvn 方式安装失败", e);
        }
        return false;
    }

    private static void checkInstallerInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("浏览器安装已取消");
    }

    private static int runBrowserInstaller(ProcessBuilder builder, String backend)
            throws IOException, InterruptedException, java.util.concurrent.TimeoutException {
        return BrowserInstallerSupport.runInstaller(builder, java.time.Duration.ofMinutes(10), chunk -> {
            for (String line : chunk.split("\\R")) {
                if (StringUtils.isNotBlank(line)) log.info("  {}: {}", backend, line.trim());
            }
        });
    }

    /**
     * 获取或创建一个 page
     */
    private Page getPage(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("浏览器操作需要明确的可信会话");
        }
        if (pendingSessionClears.contains(sessionId))
            throw new IllegalStateException("LOGIN_STATE_CLEAR_PENDING: 旧页面关闭未完成，请显式重试清除或关闭浏览器会话");
        // A disconnected browser invalidates all maps; do it before computeIfAbsent mutates pages.
        if (sessionStateStore != null) ensureBrowser();
        Page current = pages.get(sessionId);
        if (current != null && !current.isClosed()) return current;
        if (current != null) { pages.remove(sessionId); invalidateObservation(sessionId); }
        return pages.computeIfAbsent(sessionId, id -> {
            if (sessionStateStore == null) ensureBrowser();
            BrowserContext context = sessionContexts.get(id);
            if (context == null) {
                try {
                    String state = sessionStateStore == null ? null : sessionStateStore.load(id).orElse(null);
                    Browser.NewContextOptions options = new Browser.NewContextOptions()
                            .setServiceWorkers(ServiceWorkerPolicy.BLOCK);
                    if (state != null) options.setStorageState(state);
                    context = browser.newContext(options);
                    try {
                        new BrowserNetworkBoundary(context, networkGuard,
                                (surface, origin) -> addBlockedRequest(id, surface, origin)).install();
                    } catch (RuntimeException boundaryFailed) {
                        try { context.close(); } catch (Exception ignored) { }
                        throw boundaryFailed;
                    }
                    sessionContexts.put(id, context);
                } catch (IOException invalid) {
                    throw new IllegalStateException(invalid.getMessage(), invalid);
                }
            }
            Page page = context.newPage();
            pageDiagnostics.put(id, new PageDiagnostics());
            page.onConsoleMessage(message -> {
                if ("error".equals(message.type())) addPageError(id, false, message.text());
            });
            page.onPageError(message -> addPageError(id, true, message));
            page.onRequest(request -> {
                try {
                    URI uri = URI.create(request.url());
                    PageDiagnostics diagnostics = pageDiagnostics.get(id);
                    if (diagnostics != null && uri.getHost() != null && diagnostics.requestOrigins.size() < 20) {
                        diagnostics.requestOrigins.add(uri.getScheme() + "://" + uri.getHost()
                                + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
                    }
                } catch (IllegalArgumentException ignored) { }
            });
            log.info("创建新浏览器页面: {}", id);
            return page;
        });
    }

    // ==================== 工具方法 ====================

    /**
     * 打开网页
     */
    private String navigateOnActor(String sessionId, String url) {
        pageLock.lock();
        try {
            String blocked = networkGuard.validateUrl(url);
            if (Objects.nonNull(blocked)) {
                return browserError("PERMISSION_DENIED", blocked);
            }
            Page page = getPage(sessionId);
            if (sameDocPath(page.url(), url)) {
                String title = page.title();
                String snapshot = page.locator("body").ariaSnapshot();
                log.info("已在该页，跳过重新导航: {}", url);
                return "已在该页，未重新加载（避免 SPA 回到首页）。标题: "
                        + title + "\n\n" + observe(sessionId, page, snapshot);
            }
            invalidateObservation(sessionId);
            pageDiagnostics.put(sessionId, new PageDiagnostics());
            requireActorInput();
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(15000));

            String title = page.title();
            String snapshot = page.locator("body").ariaSnapshot();
            log.info("导航到: {} 标题: {}", url, title);

            return "页面标题: " + title + "\n\n" + observe(sessionId, page, snapshot);

        } catch (Exception e) {
            log.error("导航失败: {}", url, e);
            return browserError("EXECUTION_FAILED", String.format(MessageConstants.BROWSER_NAV_FAILED, e.getMessage()));
        } finally {
            pageLock.unlock();
        }
    }

    /**
     * 获取页面无障碍树快照
     */
    private String snapshotOnActor(String sessionId, boolean full) {
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
            return observe(sessionId, page, snapshot);

        } catch (Exception e) {
            log.error("获取快照失败", e);
            return browserError("EXECUTION_FAILED", String.format(MessageConstants.BROWSER_SNAPSHOT_FAILED, e.getMessage()));
        } finally {
            pageLock.unlock();
        }
    }

    /**
     * 点击。by：auto|ref|text|role|css|aria。数字 ref 只走快照，失败立即返回（不把编号当 aria-label）。
     */
    private String clickOnActor(String sessionId, String ref, String by) {
        if (StringUtils.isBlank(ref)) {
            return browserError("INVALID_ARGUMENT", MessageConstants.BROWSER_CLICK_FAILED_EMPTY_REF);
        }
        String mode = StringUtils.isBlank(by) ? "auto" : by.trim().toLowerCase(Locale.ROOT);
        pageLock.lock();
        try {
            Page page = getPage(sessionId);
            boolean reference = ref.startsWith("b") && ref.contains(".") || ref.chars().allMatch(Character::isDigit);
            if ("ref".equals(mode) || ("auto".equals(mode) && reference)) {
                return clickBySnapshotRef(sessionId, page, ref);
            }
            Locator loc = switch (mode) {
                case "text" -> page.getByText(ref, new Page.GetByTextOptions().setExact(true));
                case "role" -> roleLocator(page, ref);
                case "css" -> page.locator(ref);
                case "aria" -> page.getByLabel(ref, new Page.GetByLabelOptions().setExact(true));
                case "auto" -> page.getByText(ref, new Page.GetByTextOptions().setExact(true));
                default -> null;
            };
            if (Objects.isNull(loc)) {
                return browserError("INVALID_ARGUMENT", String.format(MessageConstants.BROWSER_CLICK_FAILED_UNKNOWN_BY, mode));
            }
            if (loc.count() != 1) return browserError("CONFLICT", "点击目标匹配不唯一或不存在，请重新观察");
            return clickAndSnap(sessionId, page, loc, mode, ref);
        } catch (Exception e) {
            // Target resolution and initialization have not invoked any input API.
            return clickFailure(sessionId, pages.get(sessionId), false, "target_resolution", e);
        } finally {
            pageLock.unlock();
        }
    }

    private String clickBySnapshotRef(String sessionId, Page page, String ref) {
        ObservedElement element = resolveObservedElement(sessionId, page, ref);
        if (element == null) return staleObservation();
        return clickHandleAndSnap(sessionId, page, element.handle(), "ref", ref, true);
    }

    private Locator roleLocator(Page page, String ref) {
        AriaRole role = AriaRole.BUTTON;
        String roleName = ref;
        int eq = ref.indexOf('=');
        if (eq > 0) {
            role = parseAriaRole(ref.substring(0, eq).trim());
            roleName = ref.substring(eq + 1).trim();
        }
        return page.getByRole(role, new Page.GetByRoleOptions().setName(roleName).setExact(true));
    }

    private String clickAndSnap(String sessionId, Page page, Locator locator, String strategy, String label) {
        ElementHandle target = locator.elementHandle(new Locator.ElementHandleOptions().setTimeout(CLICK_ACTIONABILITY_TIMEOUT_MS));
        if (target == null) return browserError("CONFLICT", "点击目标已消失，请重新观察");
        try { return clickHandleAndSnap(sessionId, page, target, strategy, label, false); }
        finally { try { target.dispose(); } catch (Exception ignored) { } }
    }

    private String clickHandleAndSnap(String sessionId, Page page, ElementHandle target,
                                      String strategy, String label, boolean observedRef) {
        boolean dispatched = false;
        String phase = "actionability";
        try {
            ActorInputGate inputGate = actorInputGate.get();
            if (inputGate != null && inputGate.stopped()) return cancelledClick(sessionId);
            // Playwright's trial runs visibility/stability/enabled/hit-target checks
            // without sending input. Its failure is evidence that no click was invoked.
            target.click(new ElementHandle.ClickOptions().setTrial(true).setTimeout(CLICK_ACTIONABILITY_TIMEOUT_MS));
            if (inputGate != null && inputGate.stopped()) return cancelledClick(sessionId);
            phase = "target_revalidation";
            // Trial may scroll and run page script. Never carry a stale ref across it.
            if (observedRef && resolveObservedElement(sessionId, page, label) == null) return staleObservation();
            if (!Boolean.TRUE.equals(target.evaluate("el => el.isConnected"))) return staleObservation();
            var box = target.boundingBox();
            phase = "dispatch";
            // After this boundary even a TimeoutError can follow delivered input.
            // There is no JS/force/fallback click and no automatic second dispatch.
            if (inputGate != null && !inputGate.beginInput()) return cancelledClick(sessionId);
            dispatched = true;
            target.click(new ElementHandle.ClickOptions().setTimeout(CLICK_DISPATCH_TIMEOUT_MS));
            rememberPointer(sessionId, page, box);
            phase = "observation";
            String raw = page.locator("body").ariaSnapshot(new AriaSnapshotOptions().setTimeout(CLICK_OBSERVATION_TIMEOUT_MS));
            return String.format(MessageConstants.BROWSER_CLICK_SUCCESS, strategy, label) + "\n\n"
                    + observe(sessionId, page, raw);
        } catch (Exception failed) {
            return clickFailure(sessionId, page, dispatched, phase, failed);
        }
    }

    private String cancelledClick(String sessionId) {
        invalidateObservation(sessionId);
        return resultJson.createObjectNode().put("success", false).put("status", "cancelled")
                .put("errorCode", "CANCELLED").put("inputDispatched", false).put("phase", "before_dispatch")
                .put("error", "点击在实际输入前已取消；恢复任务时请重新观察").toString();
    }

    private String clickFailure(String sessionId, Page page, boolean dispatched, String phase, Exception failed) {
        String diagnosticId = java.util.UUID.randomUUID().toString();
        log.warn("浏览器点击失败 diagnosticId={} session={} phase={} inputDispatched={}",
                diagnosticId, sessionId, phase, dispatched, failed);
        invalidateObservation(sessionId);
        var result = resultJson.createObjectNode().put("success", false)
                .put("status", dispatched ? "unknown" : "failed")
                .put("errorCode", dispatched ? "OUTCOME_UNKNOWN" : "CONFLICT")
                .put("inputDispatched", dispatched).put("phase", phase).put("diagnosticId", diagnosticId)
                .put("retriable", false).put("nextAction", "browser_snapshot")
                .put("error", dispatched ? "点击可能已生效，当前页面结果尚未确认；请重新观察实际状态，禁止直接重复点击"
                        : "点击未执行：目标暂不可操作或页面状态已变化；请重新观察后选择可操作目标");
        // A failed trial can be recovered by the normal observe/decide loop. Do not
        // turn a snapshot error into an unknown click or attempt input here.
        if (!dispatched && page != null) {
            try {
                if (!page.isClosed()) result.put("observation", observe(sessionId, page, page.locator("body").ariaSnapshot(
                        new AriaSnapshotOptions().setTimeout(CLICK_OBSERVATION_TIMEOUT_MS))));
            } catch (Exception observationFailed) {
                log.debug("点击恢复观察失败 diagnosticId={}", diagnosticId, observationFailed);
            }
        }
        return result.toString();
    }

    static boolean sameDocPath(String currentUrl, String targetUrl) {
        if (currentUrl == null || targetUrl == null || currentUrl.isBlank() || targetUrl.isBlank()) return false;
        try {
            // Origin, query and fragment matter: different hosts with the same path are different pages.
            return URI.create(currentUrl.trim()).normalize().equals(URI.create(targetUrl.trim()).normalize());
        } catch (Exception ignored) { return false; }
    }

    private void rememberPointer(String sessionId, Page page, com.microsoft.playwright.options.BoundingBox box) {
        if (box == null) return;
        var viewport = page.viewportSize();
        if (viewport != null && viewport.width > 0 && viewport.height > 0) {
            lastPointerPositions.put(sessionId, new PointerPosition(box.x + box.width / 2,
                    box.y + box.height / 2, viewport.width, viewport.height));
        }
    }

    private String unknownAction(String message) {
        return resultJson.createObjectNode().put("success", false).put("status", "unknown")
                .put("errorCode", "OUTCOME_UNKNOWN")
                .put("error", message + "；请重新观察实际状态，禁止直接重复操作").toString();
    }

    private String staleObservation() {
        return browserError("CONFLICT", "STALE_OBSERVATION: ref 已过期或页面发生变化，请先 browser_snapshot 再使用新的 ref");
    }

    private ObservedElement resolveObservedElement(String sessionId, Page page, String ref) {
        Observation observation = observations.get(sessionId);
        if (observation == null || !observation.elements().containsKey(ref)) return null;
        try {
            for (FrameDocument document : observation.frameDocuments()) {
                assertFrameAccess(document.frame());
                if (document.frame().isDetached() || !document.url().equals(document.frame().url())
                        || !Boolean.TRUE.equals(document.root().evaluate("el => el.isConnected"))) {
                    invalidateObservation(sessionId);
                    return null;
                }
            }
        if (!observation.url().equals(page.url())
                || !interactionFingerprint(observation.rawSnapshot())
                    .equals(interactionFingerprint(page.locator("body").ariaSnapshot()))) {
            invalidateObservation(sessionId);
            return null;
        }
        if (observation.formFingerprint() != null
                && !observation.formFingerprint().equals(formFingerprint(page))) {
            invalidateObservation(sessionId);
            return null;
        }
        ObservedElement element = observation.elements().get(ref);
        if (!Boolean.TRUE.equals(element.handle().evaluate("el => el.isConnected"))) {
            invalidateObservation(sessionId);
            return null;
        }
        return element;
        } catch (RuntimeException changedFrame) {
            // A frame navigation destroys its execution context. This is a stale read,
            // not evidence that the requested click/fill/upload has been dispatched.
            invalidateObservation(sessionId);
            return null;
        }
    }

    /** Live clocks/telemetry do not change a DOM ref; changed controls and link targets do. */
    private static List<String> interactionFingerprint(String snapshot) {
        return snapshot.lines().map(String::stripTrailing)
                .filter(line -> isSnapshotRefLine(line.trim())
                        || line.trim().startsWith("- /url:")
                        || line.trim().startsWith("- option"))
                .toList();
    }

    private void invalidateObservation(String sessionId) {
        Observation old = observations.remove(sessionId);
        if (old != null) old.elements().values().forEach(element -> {
            try { element.handle().dispose(); } catch (Exception ignored) {}
        });
        if (old != null) old.frameDocuments().forEach(document -> {
            try { document.root().dispose(); } catch (Exception ignored) {}
        });
    }

    private void addPageError(String sessionId, boolean uncaught, String message) {
        PageDiagnostics diagnostics = pageDiagnostics.get(sessionId);
        if (diagnostics == null || message == null) return;
        List<String> errors = uncaught ? diagnostics.pageErrors : diagnostics.consoleErrors;
        if (errors.size() < 20) errors.add(message.substring(0, Math.min(message.length(), 500)));
    }

    private void addBlockedRequest(String sessionId, String surface, String origin) {
        PageDiagnostics diagnostics = pageDiagnostics.get(sessionId);
        if (diagnostics != null && diagnostics.blockedRequests.size() < 20)
            diagnostics.blockedRequests.add(Map.of("surface", surface, "origin", origin));
    }

    private String diagnosticsText(String sessionId) {
        PageDiagnostics diagnostics = pageDiagnostics.get(sessionId);
        if (diagnostics == null) return "";
        return "\nbrowserDiagnostics=" + resultJson.valueToTree(Map.of(
                "scope", "Observed since last navigation; up to 20 entries per category; request origins only",
                "consoleErrors", diagnostics.consoleErrors,
                "pageErrors", diagnostics.pageErrors,
                "requestOrigins", diagnostics.requestOrigins,
                "blockedRequests", diagnostics.blockedRequests,
                "statePersistenceWarning", statePersistenceWarnings.getOrDefault(sessionId, ""))) + "\n";
    }

    /** Bind references to the exact DOM nodes observed, never to a future snapshot's line numbers. */
    private String observe(String sessionId, Page page, String rawSnapshot) {
        invalidateObservation(sessionId);
        String id = "b" + (++observationSequence);
        Map<String, ObservedElement> elements = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder("observationId=" + id + " url=" + page.url() + "\n");
        int number = 0;
        for (String line : rawSnapshot.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            ElementHandle handle = isSnapshotRefLine(trimmed) ? observedHandle(page, trimmed) : null;
            if (handle == null) {
                text.append(line).append('\n');
            } else {
                String ref = id + "." + (++number);
                elements.put(ref, new ObservedElement(trimmed, handle));
                text.append('[').append(ref).append("] ").append(trimmed).append('\n');
            }
        }
        observations.put(sessionId, new Observation(id, page.url(), rawSnapshot, Map.copyOf(elements)));
        return redactPasswordValues(page, text + diagnosticsText(sessionId));
    }

    private String redactPasswordValues(Page page, String text) {
        List<Locator> scopes = new ArrayList<>();
        List<Frame> frames = page.frames();
        if (frames == null || frames.isEmpty()) scopes.add(page.locator("input[type=password]"));
        else for (Frame frame : frames) if (!frame.isDetached()) scopes.add(frame.locator("input[type=password]"));
        for (Locator passwords : scopes) {
        if (passwords == null) continue; // mock-only pages may not implement this locator
        Object raw = passwords.evaluateAll("els => els.map(el => el.value).filter(Boolean)");
        if (raw instanceof List<?> values) {
            for (Object value : values) {
                String secret = String.valueOf(value);
                if (!secret.isEmpty()) {
                    text = redactSnapshotText(text, secret);
                    // ariaSnapshot can quote or escape a password rather than emitting it literally.
                    String encoded = resultJson.valueToTree(secret).toString();
                    if (encoded.length() > 2) text = redactSnapshotText(text, encoded.substring(1, encoded.length() - 1));
                }
            }
        }
        }
        return text;
    }

    private String redactSnapshotText(String snapshot, String secret) {
        String[] lines = snapshot.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            // A short password may equal a digit in b12.3. Preserve the tool's
            // observation/ref syntax while redacting the untrusted page text.
            int prefix = lines[i].matches("^\\[b[0-9]+\\.[0-9]+].*") ? lines[i].indexOf(']') + 1 : 0;
            lines[i] = lines[i].substring(0, prefix) + lines[i].substring(prefix).replace(secret, "[密码已隐藏]");
        }
        return String.join("\n", lines);
    }

    private ElementHandle observedHandle(Page page, String description) {
        Matcher role = Pattern.compile("^-\\s+([a-z]+)(?:\\s+\"((?:\\\\.|[^\"\\\\])*)\")?").matcher(description);
        if (!role.find()) return null;
        try {
            AriaRole ariaRole = AriaRole.valueOf(role.group(1).toUpperCase(Locale.ROOT));
            Page.GetByRoleOptions options = new Page.GetByRoleOptions().setExact(true);
            if (role.group(2) != null) options.setName(resultJson.readValue("\"" + role.group(2) + "\"", String.class));
            Locator locator = page.getByRole(ariaRole, options);
            return locator.count() == 1 ? locator.elementHandle() : null;
        } catch (Exception ignored) { return null; }
    }

    private ElementHandle observedHandle(Frame frame, String description) {
        Matcher role = Pattern.compile("^-\\s+([a-z]+)(?:\\s+\"((?:\\\\.|[^\"\\\\])*)\")?").matcher(description);
        if (!role.find()) return null;
        try {
            AriaRole ariaRole = AriaRole.valueOf(role.group(1).toUpperCase(Locale.ROOT));
            Frame.GetByRoleOptions options = new Frame.GetByRoleOptions().setExact(true);
            if (role.group(2) != null) options.setName(resultJson.readValue("\"" + role.group(2) + "\"", String.class));
            Locator locator = frame.getByRole(ariaRole, options);
            return locator.count() == 1 ? locator.elementHandle() : null;
        } catch (Exception ignored) { return null; }
    }

    private record FormField(ElementHandle handle, Map<String, Object> state) {}
    private record FormScan(List<FormField> fields, List<FrameDocument> documents,
                            List<Map<String, Object>> frames, int candidateCount, boolean truncated) { }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFormField(ElementHandle handle) {
        return new LinkedHashMap<>((Map<String, Object>) handle.evaluate(BrowserFormSupport.READ_FIELD));
    }

    private FormScan scanFormFields(Page page) {
        List<FormField> fields = new ArrayList<>();
        List<FrameDocument> documents = new ArrayList<>();
        List<Map<String, Object>> frameStates = new ArrayList<>();
        int scanned = 0, total = 0;
        boolean truncated = false;
        try {
            List<Frame> frames = page.frames();
            if (frames == null || frames.isEmpty()) frames = Collections.singletonList(page.mainFrame());
            int frameCount = Math.min(frames.size(), BrowserFormSupport.MAX_FRAMES);
            truncated = frames.size() > frameCount;
            for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            Frame frame = frames.get(frameIndex);
            if (frame == null || frame.isDetached()) throw new IllegalStateException("页面框架发生变化，请重新识别表单");
            assertFrameAccess(frame);
            String path = framePath(frame);
            boolean frameVisible = isFrameVisible(frame);
            ElementHandle root = frame.locator(":root").elementHandle();
            if (root == null) throw new IllegalStateException("页面框架尚未加载，请稍后重新识别表单");
            documents.add(new FrameDocument(frame, root, frame.url()));
            String aria = frameAriaSnapshot(frame);
            Map<String, Object> frameState = new LinkedHashMap<>();
            frameState.put("path", path); frameState.put("name", frame.name()); frameState.put("url", frame.url());
            frameState.put("interactions", interactionFingerprint(aria));
            frameStates.add(frameState);
            Locator candidates = frame.locator(BrowserFormSupport.SELECTOR);
            int available = candidates.count();
            total += available;
            int count = Math.min(available, BrowserFormSupport.MAX_FIELDS - scanned);
            truncated |= available > count;
            for (int i = 0; i < count; i++) {
                scanned++;
                ElementHandle handle = candidates.nth(i).elementHandle();
                if (handle == null) continue;
                try {
                    Map<String, Object> state = readFormField(handle);
                    state.put("framePath", path); state.put("frameName", frame.name()); state.put("frameUrl", frame.url());
                    if (Boolean.TRUE.equals(state.get("visible")) && !frameVisible) state.put("visible", false);
                    if (Boolean.TRUE.equals(state.get("visible")) || "file".equals(state.get("type"))) {
                        fields.add(new FormField(handle, state));
                        handle = null; // ownership transferred to the result
                    }
                } finally { if (handle != null) handle.dispose(); }
            }
            }
            return new FormScan(fields, documents, frameStates, total, truncated);
        } catch (RuntimeException error) {
            fields.forEach(field -> { try { field.handle().dispose(); } catch (Exception ignored) {} });
            documents.forEach(document -> { try { document.root().dispose(); } catch (Exception ignored) {} });
            throw error;
        }
    }

    private String framePath(Frame frame) {
        // A path is the location in this observation's tree, not a durable frame identity.
        // Only opaque refs plus the bound document handle authorize later actions.
        List<Integer> indices = new ArrayList<>();
        for (Frame current = frame; current.parentFrame() != null; current = current.parentFrame()) {
            int index = current.parentFrame().childFrames().indexOf(current);
            if (index < 0) throw new IllegalStateException("页面框架已被替换，请重新识别表单");
            indices.add(0, index);
        }
        return "main" + indices.stream().map(index -> "/" + index).collect(java.util.stream.Collectors.joining());
    }

    private String frameAriaSnapshot(Frame frame) {
        Locator body = frame.locator("body");
        // SVG/XML/PDF embeds can have a document root without an HTML body.
        return body.count() == 0 ? "" : body.ariaSnapshot();
    }

    private boolean isFrameVisible(Frame frame) {
        for (Frame current = frame; current.parentFrame() != null; current = current.parentFrame()) {
            ElementHandle embedding = current.frameElement();
            try { if (!embedding.isVisible()) return false; }
            finally { embedding.dispose(); }
        }
        return true;
    }

    private void assertFrameAccess(Frame frame) {
        for (Frame current = frame; current != null; current = current.parentFrame()) {
            String url = current.url();
            // Inline documents have no independently addressable endpoint. Their entire
            // embedding chain must still terminate at a URL accepted by the network policy.
            if (current.parentFrame() != null && ("about:blank".equals(url)
                    || "about:srcdoc".equals(url) || url.startsWith("data:"))) continue;
            String blocked = networkGuard == null ? null : networkGuard.validateUrl(url);
            if (blocked != null) throw new SecurityException("框架 URL 被网络策略拒绝：" + blocked);
        }
    }

    private String formFingerprint(FormScan scan) {
        return resultJson.valueToTree(Map.of("fields", scan.fields().stream().map(FormField::state).toList(),
                "frames", scan.frames(), "candidateCount", scan.candidateCount(), "truncated", scan.truncated())).toString();
    }

    private String formFingerprint(Page page) {
        FormScan scan = scanFormFields(page);
        try { return formFingerprint(scan); }
        finally { disposeFormScan(scan); }
    }

    private void disposeFormScan(FormScan scan) {
        scan.fields().forEach(field -> { try { field.handle().dispose(); } catch (Exception ignored) {} });
        scan.documents().forEach(document -> { try { document.root().dispose(); } catch (Exception ignored) {} });
    }

    private String formInspectOnActor(String sessionId) {
        FormScan scan = null;
        List<ElementHandle> frameHandles = new ArrayList<>();
        boolean retained = false;
        try {
            Page page = getPage(sessionId);
            scan = scanFormFields(page);
            String raw = page.locator("body").ariaSnapshot();
            String snapshot = observe(sessionId, page, raw);
            Observation base = observations.get(sessionId);
            Map<String, ObservedElement> elements = new LinkedHashMap<>(base.elements());
            StringBuilder frameSnapshot = new StringBuilder(snapshot);
            int index = elements.size();
            for (FrameDocument document : scan.documents()) {
                Frame frame = document.frame();
                if (frame.parentFrame() == null || !isFrameVisible(frame)) continue;
                frameSnapshot.append("\nframe=").append(framePath(frame)).append(" url=").append(frame.url()).append('\n');
                for (String line : frameAriaSnapshot(frame).split("\n")) {
                    String trimmed = line.trim();
                    ElementHandle handle = isSnapshotRefLine(trimmed) ? observedHandle(frame, trimmed) : null;
                    if (handle == null) frameSnapshot.append(line).append('\n');
                    else {
                        String ref = base.id() + "." + (++index);
                        elements.put(ref, new ObservedElement(trimmed, handle));
                        frameHandles.add(handle);
                        frameSnapshot.append('[').append(ref).append("] ").append(trimmed).append('\n');
                    }
                }
            }
            var result = resultJson.createObjectNode().put("success", true).put("observationId", base.id())
                    .put("url", page.url()).put("snapshot", redactPasswordValues(page, frameSnapshot.toString()))
                    .put("truncated", scan.truncated()).put("candidateCount", scan.candidateCount())
                    .put("scope", "Current document, open shadow roots and attached frames; bounded to 200 candidates across 64 frames")
                    .put("message", "按字段类型填写；页面/值/约束变化后重新识别。valid只反映当前浏览器约束，不证明提交成功。");
            var output = result.putArray("fields");
            for (FormField field : scan.fields()) {
                String ref = base.id() + "." + (++index);
                elements.put(ref, new ObservedElement("form field " + field.state().get("label"), field.handle()));
                var node = resultJson.valueToTree(field.state());
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("ref", ref);
                output.add(node);
            }
            result.set("frames", resultJson.valueToTree(scan.frames().stream().map(frame -> Map.of(
                    "path", frame.get("path"), "name", frame.get("name"), "url", frame.get("url"))).toList()));
            String fingerprint = formFingerprint(scan);
            observations.put(sessionId, new Observation(base.id(), base.url(), raw, Map.copyOf(elements), fingerprint, scan.documents()));
            retained = true;
            return result.toString();
        } catch (Exception error) {
            invalidateObservation(sessionId);
            return browserError(error instanceof SecurityException ? "PERMISSION_DENIED" : "EXECUTION_FAILED",
                    "识别表单失败：" + error.getMessage());
        } finally {
            if (!retained && scan != null) disposeFormScan(scan);
            if (!retained) frameHandles.forEach(handle -> { try { handle.dispose(); } catch (Exception ignored) {} });
        }
    }

    private String formVerifyOnActor(String sessionId, String ref, String expectedValue, Boolean expectedChecked,
                                     List<String> expectedValues, List<String> expectedFiles) {
        int expectations = (expectedValue == null ? 0 : 1) + (expectedChecked == null ? 0 : 1)
                + (expectedValues == null ? 0 : 1) + (expectedFiles == null ? 0 : 1);
        if (StringUtils.isBlank(ref) || expectations > 1)
            return browserError("INVALID_ARGUMENT", "需要最新字段ref，四种expected参数最多传一种");
        try {
            Page page = getPage(sessionId);
            ObservedElement element = resolveObservedElement(sessionId, page, ref);
            if (element == null) return staleObservation();
            if (!Boolean.TRUE.equals(element.handle().evaluate("(el, selector) => el.matches(selector)", BrowserFormSupport.SELECTOR)))
                return browserError("INVALID_ARGUMENT", "目标不是可核验的表单字段，请先browser_form_inspect");
            Map<String, Object> state = readFormField(element.handle());
            if (Boolean.TRUE.equals(state.get("valueRedacted")) && expectations > 0)
                return browserError("PERMISSION_DENIED", "密码仅返回是否已填写，不能回显或对照密码内容");
            boolean matched = true;
            if (expectedValue != null) {
                if (Boolean.TRUE.equals(state.get("valueTruncated")))
                    return browserError("INVALID_ARGUMENT", "字段内容超出回读上限，不能确认完全匹配");
                matched = expectedValue.equals(state.get("value"));
            } else if (expectedChecked != null) {
                Object checked = state.get("checked");
                if (!(checked instanceof Boolean) && !"true".equals(checked) && !"false".equals(checked))
                    return browserError("INVALID_ARGUMENT", "该字段没有可核验的勾选状态");
                matched = expectedChecked.equals(checked instanceof Boolean ? checked : Boolean.valueOf(checked.toString()));
            } else if (expectedValues != null) {
                matched = sameFormValues(expectedValues, state.get("selectedValues"));
            } else if (expectedFiles != null) {
                if (!"file".equals(state.get("type")))
                    return browserError("INVALID_ARGUMENT", "该字段不是文件上传控件");
                var files = resultJson.valueToTree(state.get("files"));
                List<String> names = new ArrayList<>(); files.forEach(file -> names.add(file.path("name").asText()));
                matched = sameFormValues(expectedFiles, names);
            }
            var validity = resultJson.valueToTree(state.get("validity"));
            boolean valid = !validity.isObject() || validity.path("valid").asBoolean(false);
            String ariaInvalid = Objects.toString(state.get("ariaInvalid"), "false");
            valid &= ariaInvalid.isBlank() || "false".equals(ariaInvalid);
            // Custom controls have no browser ValidityState. Do not bless a
            // required empty value/unchecked control merely because it is ARIA.
            if (validity.isNull() && Boolean.TRUE.equals(state.get("required"))) {
                Object checked = state.get("checked");
                valid &= checked == null ? StringUtils.isNotBlank(Objects.toString(state.get("value"), ""))
                        : Boolean.TRUE.equals(checked) || "true".equals(checked);
            }
            boolean success = matched && valid;
            var result = resultJson.createObjectNode().put("success", success).put("matched", matched).put("valid", valid)
                    .put("ref", ref).put("message", success ? "字段实际值及当前约束核验通过；尚未证明提交成功"
                            : "字段值不匹配或页面校验未通过，请根据实际状态修正");
            result.set("field", resultJson.valueToTree(state));
            result.put("validationCoverage", validity.isObject() ? "browser_constraints_and_aria_invalid"
                    : "aria_required_and_invalid_only; application rules still require page confirmation");
            if (!success) result.put("errorCode", "CONFLICT");
            return result.toString();
        } catch (Exception error) {
            return browserError("EXECUTION_FAILED", "回读表单字段失败：" + error.getMessage());
        }
    }

    private boolean sameFormValues(List<String> expected, Object rawActual) {
        if (expected.stream().anyMatch(Objects::isNull) || !(rawActual instanceof List<?> actual)) return false;
        List<String> a = new ArrayList<>(expected);
        List<String> b = actual.stream().map(String::valueOf).sorted().toList();
        Collections.sort(a);
        return a.equals(b);
    }

    private static AriaRole parseAriaRole(String raw) {
        try {
            return AriaRole.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (Exception e) {
            return AriaRole.BUTTON;
        }
    }

    @SuppressWarnings("unchecked")
    private String selectOnActor(String sessionId, String ref, List<String> values, String by) {
        String mode = by == null || by.isBlank() ? "value" : by.trim().toLowerCase(Locale.ROOT);
        if (!validFormActionRef(ref) || values == null || values.size() > 50
                || values.stream().anyMatch(Objects::isNull) || !Set.of("value", "label").contains(mode))
            return browserError("INVALID_ARGUMENT", "请选择最新表单 ref，最多提供50个明确选项，by仅支持value或label；空列表仅用于清空多选框");
        boolean dispatched = false;
        pageLock.lock();
        try {
            Page page = getPage(sessionId);
            ObservedElement element = resolveObservedElement(sessionId, page, ref);
            if (element == null) return staleObservation();
            Map<String, Object> info = readFormField(element.handle());
            boolean nativeSelect = "select".equals(info.get("tag"));
            if (Boolean.TRUE.equals(info.get("valueRedacted")))
                return browserError("PERMISSION_DENIED", "密码字段不能使用下拉选项回读接口");
            if (!nativeSelect && !Boolean.TRUE.equals(info.get("customSelectSupported")))
                return browserError("INVALID_ARGUMENT", "自定义下拉需要唯一 aria-controls/aria-owns listbox、明确 aria-selected 状态和单选语义；请重新观察后操作");
            if (Boolean.TRUE.equals(info.get("disabled")))
                return browserError("CONFLICT", "下拉框处于禁用状态，未执行选择");
            if (Boolean.TRUE.equals(info.get("optionsTruncated")))
                return browserError("CONFLICT", "下拉选项超过回读上限，无法确认唯一匹配，未执行选择");
            if (!Boolean.TRUE.equals(info.get("multiple")) && values.size() != 1)
                return browserError("INVALID_ARGUMENT", "单选下拉框只能指定一个选项");
            List<Map<String, Object>> options = (List<Map<String, Object>>) info.get("options");
            LinkedHashSet<String> expected = new LinkedHashSet<>();
            for (String value : values) {
                List<Map<String, Object>> matches = options.stream().filter(option -> value.equals(option.get(mode))).toList();
                if (matches.size() != 1)
                    return browserError("CONFLICT", "选项不存在或匹配不唯一，未执行选择；请重新识别表单并提供唯一 value");
                if (Boolean.TRUE.equals(matches.get(0).get("disabled")))
                    return browserError("CONFLICT", "选项已禁用或隐藏，未执行选择");
                String selectedValue = (String) matches.get(0).get("value");
                if (options.stream().filter(option -> selectedValue.equals(option.get("value"))).count() != 1)
                    return browserError("CONFLICT", "选项 value 重复，无法唯一选择，请使用页面控件人工确认");
                expected.add(selectedValue);
            }
            List<String> before = selectedValues(element.handle());
            if (!new LinkedHashSet<>(before).equals(expected)) {
                if (nativeSelect) {
                    SelectOption[] selection = expected.stream().map(value -> new SelectOption().setValue(value)).toArray(SelectOption[]::new);
                    requireActorInput();
                    dispatched = true;
                    element.handle().selectOption(selection, new ElementHandle.SelectOptionOptions().setTimeout(CLICK_TIMEOUT_MS));
                } else {
                    JSHandle target = element.handle().evaluateHandle(BrowserFormSupport.CUSTOM_OPTION_HANDLE,
                            Map.of("by", mode, "value", values.get(0)));
                    try {
                        ElementHandle option = target.asElement();
                        if (option == null) return browserError("CONFLICT", "关联选项已变化或不唯一，未执行选择");
                        if (!option.isVisible() || Boolean.TRUE.equals(info.get("customPopupHidden"))) {
                            if (!"combobox".equals(info.get("role")))
                                return browserError("CONFLICT", "关联列表不可见，请先展开控件后重新识别表单");
                            requireActorInput();
                            dispatched = true;
                            element.handle().click(new ElementHandle.ClickOptions().setTimeout(CLICK_TIMEOUT_MS));
                        }
                        // Expansion may replace the list, change its ownership or leave it ARIA-hidden.
                        // Keep the original target and verify the current association before dispatching.
                        JSHandle ready = element.handle().ownerFrame().waitForFunction(BrowserFormSupport.CUSTOM_OPTION_READY,
                                Map.of("control", element.handle(), "target", option, "by", mode, "value", values.get(0)),
                                new Frame.WaitForFunctionOptions().setTimeout(CLICK_TIMEOUT_MS));
                        ready.dispose();
                        requireActorInput();
                        dispatched = true;
                        option.click(new ElementHandle.ClickOptions().setTimeout(CLICK_TIMEOUT_MS));
                    } finally { target.dispose(); }
                }
            }
            List<String> actual = selectedValues(element.handle());
            if (!new LinkedHashSet<>(actual).equals(expected)) {
                invalidateObservation(sessionId);
                return unknownAction("选择后回读值与目标不一致");
            }
            return formActionResult(sessionId, page, "下拉选项已回读核验", "selectedValues", actual);
        } catch (Exception error) {
            invalidateObservation(sessionId);
            return dispatched ? unknownAction("下拉选择结果尚未确认：" + error.getMessage())
                    : browserError("EXECUTION_FAILED", "无法检查下拉框：" + error.getMessage());
        } finally { pageLock.unlock(); }
    }

    @SuppressWarnings("unchecked")
    private List<String> selectedValues(ElementHandle handle) {
        Map<String, Object> state = readFormField(handle);
        if (!"select".equals(state.get("tag")) && !Boolean.TRUE.equals(state.get("customSelectSupported")))
            throw new IllegalStateException("自定义下拉缺少可核验的关联选项状态");
        return (List<String>) state.get("selectedValues");
    }

    @SuppressWarnings("unchecked")
    private String setCheckedOnActor(String sessionId, String ref, boolean checked) {
        if (!validFormActionRef(ref)) return browserError("INVALID_ARGUMENT", "勾选状态需要最新表单完整 ref");
        boolean dispatched = false;
        pageLock.lock();
        try {
            Page page = getPage(sessionId);
            ObservedElement element = resolveObservedElement(sessionId, page, ref);
            if (element == null) return staleObservation();
            Map<String, Object> info = readFormField(element.handle());
            String type = String.valueOf(info.get("type"));
            boolean nativeCheck = "input".equals(info.get("tag")) && Set.of("checkbox", "radio").contains(type);
            String role = String.valueOf(info.get("role"));
            Object state = info.get("checked");
            if (!nativeCheck && (!Set.of("checkbox", "radio", "switch").contains(role)
                    || !("true".equals(state) || "false".equals(state))))
                return browserError("INVALID_ARGUMENT", "目标需要原生复选/单选框或明确 aria-checked=true/false 的 checkbox/radio/switch；不猜测 mixed 状态");
            if (Boolean.TRUE.equals(info.get("disabled")))
                return browserError("CONFLICT", "目标处于禁用状态，未更改勾选状态");
            boolean before = Boolean.TRUE.equals(state) || "true".equals(state);
            if (("radio".equals(type) || "radio".equals(role)) && before && !checked)
                return browserError("INVALID_ARGUMENT", "单选框不能直接取消；请选择同一组选项中的其他单选框");
            if (before != checked) {
                requireActorInput();
                dispatched = true;
                if (nativeCheck) element.handle().setChecked(checked, new ElementHandle.SetCheckedOptions().setTimeout(CLICK_TIMEOUT_MS));
                else element.handle().click(new ElementHandle.ClickOptions().setTimeout(CLICK_TIMEOUT_MS));
            }
            Object actualState = readFormField(element.handle()).get("checked");
            boolean actual = Boolean.TRUE.equals(actualState) || "true".equals(actualState);
            if (!(actualState instanceof Boolean || "true".equals(actualState) || "false".equals(actualState)) || actual != checked) {
                invalidateObservation(sessionId);
                return unknownAction("勾选动作后的实际状态与目标不一致");
            }
            return formActionResult(sessionId, page, "勾选状态已回读核验", "checked", actual);
        } catch (Exception error) {
            invalidateObservation(sessionId);
            return dispatched ? unknownAction("勾选状态尚未确认：" + error.getMessage())
                    : browserError("EXECUTION_FAILED", "无法检查勾选状态：" + error.getMessage());
        } finally { pageLock.unlock(); }
    }

    @SuppressWarnings("unchecked")
    private String uploadOnActor(String sessionId, String ref, List<Path> trustedPaths) {
        if (!validFormActionRef(ref) || trustedPaths == null || trustedPaths.isEmpty() || trustedPaths.size() > 20)
            return browserError("INVALID_ARGUMENT", "上传需要最新表单完整 ref 及 1-20 个已授权工作区文件");
        if (trustedPaths.stream().anyMatch(path -> path == null || !path.isAbsolute()
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)))
            return browserError("INVALID_ARGUMENT", "上传目标必须是已授权工作区内可读取的普通文件，不能使用目录或符号链接");
        boolean dispatched = false;
        pageLock.lock();
        try {
            Page page = getPage(sessionId);
            ObservedElement element = resolveObservedElement(sessionId, page, ref);
            if (element == null) return staleObservation();
            Map<String, Object> info = (Map<String, Object>) element.handle().evaluate("""
                    el => ({tag:el.tagName.toLowerCase(),type:el.type,multiple:!!el.multiple,
                      directory:!!el.webkitdirectory,disabled:el.matches(':disabled') || el.getAttribute('aria-disabled')==='true'})
                    """);
            if (!"input".equals(info.get("tag")) || !"file".equals(info.get("type")))
                return browserError("INVALID_ARGUMENT", "目标不是文件上传输入框，请先识别表单获取 type=file 的 ref");
            if (Boolean.TRUE.equals(info.get("directory")))
                return browserError("INVALID_ARGUMENT", "暂不支持目录上传，请选择普通文件上传控件");
            if (Boolean.TRUE.equals(info.get("disabled")))
                return browserError("CONFLICT", "上传控件已禁用，未选择文件");
            if (!Boolean.TRUE.equals(info.get("multiple")) && trustedPaths.size() != 1)
                return browserError("INVALID_ARGUMENT", "该上传控件仅接受单个文件");
            List<Map<String, Object>> expected = new ArrayList<>();
            for (Path path : trustedPaths) expected.add(Map.of("name", path.getFileName().toString(), "size", Files.size(path)));
            requireActorInput();
            dispatched = true;
            element.handle().setInputFiles(trustedPaths.toArray(Path[]::new),
                    new ElementHandle.SetInputFilesOptions().setTimeout(CLICK_TIMEOUT_MS));
            List<Map<String, Object>> actual = (List<Map<String, Object>>) element.handle().evaluate(
                    "el => Array.from(el.files, f=>({name:f.name,size:f.size}))");
            if (!sameUploadedFiles(expected, actual)) {
                invalidateObservation(sessionId);
                return unknownAction("上传控件回读的文件名或大小与目标不一致");
            }
            return formActionResult(sessionId, page, "文件已选择并回读核验；是否已上传到服务器仍需检查页面回执", "files", actual);
        } catch (Exception error) {
            invalidateObservation(sessionId);
            return dispatched ? unknownAction("文件选择或上传结果尚未确认：" + error.getMessage())
                    : browserError("EXECUTION_FAILED", "无法检查上传控件：" + error.getMessage());
        } finally { pageLock.unlock(); }
    }

    private static boolean sameUploadedFiles(List<Map<String, Object>> expected, List<Map<String, Object>> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).get("name").equals(actual.get(i).get("name"))
                    || !(actual.get(i).get("size") instanceof Number actualSize)
                    || ((Number) expected.get(i).get("size")).longValue() != actualSize.longValue()) return false;
        }
        return true;
    }

    private static boolean validFormActionRef(String ref) {
        return ref != null && ref.matches("b[0-9]+\\.[0-9]+");
    }

    private String formActionResult(String sessionId, Page page, String message, String key, Object value) {
        String snapshot = observe(sessionId, page, page.locator("body").ariaSnapshot());
        var result = resultJson.createObjectNode().put("success", true).put("message", message).put("snapshot", snapshot);
        result.set(key, resultJson.valueToTree(value));
        return result.toString();
    }

    /**
     * 在搜索框/输入框中输入文字
     * ref: 快照中的编号（如 "11"）或 placeholder 文本（如 "搜索"）
     * text: 要输入的文字
     */
    private String typeOnActor(String sessionId, String ref, String text) {
        try {
            Page page = getPage(sessionId);
            if (ref == null || ref.isBlank()) return browserError("EXECUTION_FAILED", "输入需要明确的快照 ref 或输入框名称");
            if (ref.startsWith("b") && ref.contains(".") || ref.chars().allMatch(Character::isDigit)) {
                ObservedElement element = resolveObservedElement(sessionId, page, ref);
                if (element == null) return staleObservation();
                requireActorInput();
                element.handle().fill(text, new ElementHandle.FillOptions().setTimeout(CLICK_TIMEOUT_MS));
            } else {
                Locator input = page.getByPlaceholder(ref, new Page.GetByPlaceholderOptions().setExact(true));
                if (input.count() != 1) input = page.getByLabel(ref, new Page.GetByLabelOptions().setExact(true));
                if (input.count() != 1) return browserError("CONFLICT", "输入框匹配不唯一或不存在，请重新获取快照");
                requireActorInput();
                input.fill(text, new Locator.FillOptions().setTimeout(CLICK_TIMEOUT_MS));
            }
            return "输入成功: ref=" + ref + "\n\n" + observe(sessionId, page, page.locator("body").ariaSnapshot());
        } catch (Exception e) {
            invalidateObservation(sessionId);
            return unknownAction("输入结果尚未确认：" + e.getMessage());
        }
    }

    /**
     * 按键盘按键
     */
    private String pressOnActor(String sessionId, String key) {
        try {
            Page page = getPage(sessionId);
            requireActorInput();
            page.keyboard().press(key);
            return String.format(MessageConstants.BROWSER_PRESS_SUCCESS, key) + "\n\n"
                    + observe(sessionId, page, page.locator("body").ariaSnapshot());
        } catch (Exception e) {
            log.error("按键失败: key={}", key, e);
            invalidateObservation(sessionId);
            return unknownAction("按键结果尚未确认：" + e.getMessage());
        }
    }

    /**
     * 滚动页面
     */
    private String scrollOnActor(String sessionId, String direction) {
        try {
            Page page = getPage(sessionId);
            requireActorInput();
            if ("up".equalsIgnoreCase(direction)) {
                page.mouse().wheel(0, -500);
            } else {
                page.mouse().wheel(0, 500);
            }
            return String.format(MessageConstants.BROWSER_SCROLL_SUCCESS, direction) + "\n\n"
                    + observe(sessionId, page, page.locator("body").ariaSnapshot());
        } catch (Exception e) {
            invalidateObservation(sessionId);
            return unknownAction("滚动结果尚未确认：" + e.getMessage());
        }
    }

    /**
     * 截图
     */
    private String screenshotOnActor(String sessionId, Path workspaceRoot) {
        pageLock.lock();
        try {
            Page page = getPage(sessionId);
            Path target = screenshotOutputPath(workspaceRoot, sessionId);
            String baseName = target.getFileName().toString().replaceFirst("\\.png$", "");
            target = target.resolveSibling(baseName + "_" + java.util.UUID.randomUUID() + ".png");
            String filename = target.getFileName().toString();
            Path root = workspaceRoot.toAbsolutePath().normalize();
            byte[] screenshot = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
            PointerPosition pointer = lastPointerPositions.get(sessionId);
            if (pointer != null) {
                try {
                    screenshot = applyPointerSpotlight(screenshot, pointer);
                } catch (Exception overlayError) {
                    log.debug("截图指针高亮渲染失败，保留原截图: {}", overlayError.getMessage());
                }
            }
            Files.createDirectories(root);
            Files.write(target, screenshot);
            var result = resultJson.createObjectNode().put("success", true)
                    .put("message", String.format(MessageConstants.BROWSER_SCREENSHOT_SAVED, "workspace/" + filename))
                    .put("path", target.toString()).put("url", page.url());
            result.putArray("tool_images").addObject().put("path", target.toString()).put("mimeType", "image/png");
            return result.toString();
        } catch (Exception e) {
            return browserError("EXECUTION_FAILED", String.format(MessageConstants.BROWSER_SCREENSHOT_FAILED, e.getMessage()));
        } finally {
            pageLock.unlock();
        }
    }

    static Path screenshotOutputPath(Path workspaceRoot, String sessionId) throws IOException {
        if (workspaceRoot == null) throw new IOException("workspace 路径为空");
        String safeSessionId = String.valueOf(sessionId == null ? "default" : sessionId)
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path target = root.resolve("screenshot_" + safeSessionId + ".png").normalize();
        if (!target.startsWith(root)) throw new IOException("截图路径超出 workspace");
        return target;
    }

    static byte[] applyPointerSpotlight(byte[] screenshotBytes, double x, double y,
                                        int viewportWidth, int viewportHeight) throws IOException {
        return applyPointerSpotlight(screenshotBytes,
                new PointerPosition(x, y, viewportWidth, viewportHeight));
    }

    private static byte[] applyPointerSpotlight(byte[] screenshotBytes, PointerPosition pointer) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(screenshotBytes));
        if (image == null || pointer.viewportWidth() <= 0 || pointer.viewportHeight() <= 0) return screenshotBytes;

        double scaleX = image.getWidth() / (double) pointer.viewportWidth();
        double scaleY = image.getHeight() / (double) pointer.viewportHeight();
        float scale = (float) Math.max(0.5, Math.min(scaleX, scaleY));
        float centerX = (float) (pointer.x() * scaleX);
        float centerY = (float) (pointer.y() * scaleY);
        float radius = 76f * scale;

        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setPaint(new RadialGradientPaint(
                    new Point2D.Float(centerX, centerY), radius,
                    new float[]{0f, .42f, 1f},
                    new Color[]{new Color(48, 126, 246, 72), new Color(48, 126, 246, 34), new Color(48, 126, 246, 0)}));
            g.fill(new Ellipse2D.Float(centerX - radius, centerY - radius, radius * 2, radius * 2));

            g.setStroke(new BasicStroke(1.8f * scale));
            g.setColor(new Color(55, 132, 255, 105));
            float ring = 36f * scale;
            g.draw(new Ellipse2D.Float(centerX - ring, centerY - ring, ring * 2, ring * 2));
            g.setColor(new Color(55, 132, 255, 48));
            ring = 52f * scale;
            g.draw(new Ellipse2D.Float(centerX - ring, centerY - ring, ring * 2, ring * 2));

            int[] xs = {0, 0, 5, 9, 13, 9, 16};
            int[] ys = {0, 18, 13, 22, 20, 11, 11};
            for (int i = 0; i < xs.length; i++) {
                xs[i] = Math.round(centerX + xs[i] * scale);
                ys[i] = Math.round(centerY + ys[i] * scale);
            }
            Polygon cursor = new Polygon(xs, ys, xs.length);
            g.setColor(new Color(255, 255, 255, 235));
            g.setStroke(new BasicStroke(3f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(cursor);
            g.setColor(new Color(20, 25, 37, 245));
            g.fill(cursor);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * 关闭当前 session 的浏览器页面
     */
    private String closeOnActor(String sessionId) {
        boolean existed = pages.containsKey(sessionId) || sessionContexts.containsKey(sessionId);
        boolean cleared = pendingSessionClears.contains(sessionId);
        persistSessionState(sessionId);
        try { closeSessionContext(sessionId); }
        catch (Exception failed) { return browserError("EXECUTION_FAILED", "关闭浏览器页面失败；保留会话引用供后续显式关闭"); }
        if (existed) return String.format(MessageConstants.BROWSER_PAGE_CLOSED, sessionId)
                + (cleared ? "；已清除此聊天的登录态" : sessionStateStore == null ? "" : statePersistenceWarnings.containsKey(sessionId)
                ? "；登录态保存失败，重启后可能需要重新登录" : "；已保留此聊天的登录态");
        return "没有找到 session: " + sessionId;
    }

    private void closeSessionContext(String sessionId) {
        invalidateObservation(sessionId);
        Page page = pages.get(sessionId);
        BrowserContext context = sessionContexts.get(sessionId);
        // Keep the live handle if closing fails so explicit close/clear can be attempted again.
        if (context != null) context.close();
        else if (page != null && !page.isClosed()) page.close();
        pages.remove(sessionId);
        sessionContexts.remove(sessionId);
        pendingSessionClears.remove(sessionId);
        pageDiagnostics.remove(sessionId);
        lastPointerPositions.remove(sessionId);
    }

    /**
     * 滚完主内容区（含虚拟列表）并返回去重后的正文。
     * 侧栏有目录时按项点击，一次抽完全部章节。
     */
    private String extractArticleTextOnActor(String sessionId, Path imageDir) {
        pageLock.lock();
        try {
            invalidateObservation(sessionId);
            Page page = getPage(sessionId);
            if (isPasswordGate(page)) {
                return browserError("PERMISSION_DENIED", "抽取失败: 当前是登录或密码页，先 browser_type 填密码再抽取");
            }
            String home = page.url();
            waitForWikiTree(page);
            requireActorInput();
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
                    checkActorPreparation();
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
                    checkActorPreparation();
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
            return browserError("EXECUTION_FAILED", String.format(MessageConstants.BROWSER_EXTRACT_FAILED,
                    e.getMessage()));
        } finally {
            pageLock.unlock();
        }
    }

    private String extractCurrentPage(Page page, Path imageDir) {
        String script = EXTRACT_JS
                .replace("__MAX__", String.valueOf(EXTRACT_MAX_STEPS))
                .replace("__PAUSE__", String.valueOf(EXTRACT_PAUSE_MS))
                .replace("__IMGMIN__", String.valueOf(EXTRACT_IMAGE_MIN_PX));
        requireActorInput();
        Object result = page.evaluate(script);
        String body = result == null ? "" : result.toString().trim();
        if (body.length() < EXTRACT_MIN_CHARS) {
            return browserError("EMPTY_RESULT", String.format(MessageConstants.BROWSER_EXTRACT_EMPTY,
                    page.url()));
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
            requireActorInput();
            page.goBack(new Page.GoBackOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(15000));
            page.waitForTimeout(EXTRACT_NAV_PAUSE_MS);
            if ((isLoginPage(page) || isPasswordGate(page)) && !isLoginUrl(home)) {
                requireActorInput();
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
            requireActorInput();
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
            checkActorPreparation();
            Object n = page.evaluate(TREE_COUNT_JS);
            if (n instanceof Number && ((Number) n).intValue() > 0) {
                return;
            }
            page.waitForTimeout(TREE_WAIT_MS);
        }
    }

    private void clickCatalogTitle(Page page, String title) {
        String before = wikiKey(page.url());
        requireActorInput();
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
            requireActorInput();
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
        if (!allowExtractImageUrl(page.url(), url) || networkGuard.validateUrl(url) != null) {
            return screenshotFallback(page, url, imageDir, dirName, fileName);
        }
        try {
            APIResponse resp = page.request().get(url, RequestOptions.create()
                    .setTimeout(EXTRACT_IMAGE_TIMEOUT_MS)
                    // APIRequestContext does not pass through BrowserContext.route; never follow unchecked redirects.
                    .setMaxRedirects(0)
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
    private String evaluateOnActor(String sessionId, String expression) {
        pageLock.lock();
        try {
            invalidateObservation(sessionId);
            Page page = getPage(sessionId);
            requireActorInput();
            Object result = page.evaluate(expression);
            return String.format(MessageConstants.BROWSER_EVAL_RESULT,
                    Objects.nonNull(result) ? result.toString() : "null");
        } catch (Exception e) {
            return unknownAction(String.format(MessageConstants.BROWSER_EVAL_FAILED, e.getMessage()));
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
        try { onActor(() -> { shutdownOnActor(); return "closed"; }); }
        finally { actor.shutdown(); }
    }

    private void shutdownOnActor() {
        log.info("关闭所有浏览器会话...");
        for (String session : new ArrayList<>(observations.keySet())) invalidateObservation(session);
        for (String session : new ArrayList<>(sessionContexts.keySet())) persistSessionState(session);
        sessionContexts.values().forEach(context -> {
            try { context.close(); }
            catch (Exception failed) { log.warn("关闭浏览器会话失败 type={}", failed.getClass().getSimpleName()); }
        });
        sessionContexts.clear();
        pages.values().forEach(page -> {
            try { if (!page.isClosed()) page.close(); }
            catch (Exception failed) { log.warn("关闭浏览器页面失败 type={}", failed.getClass().getSimpleName()); }
        });
        pages.clear();
        pageDiagnostics.clear();
        try { if (Objects.nonNull(browser)) browser.close(); }
        finally { if (Objects.nonNull(playwright)) playwright.close(); }
    }
}
