/* =========================================================================
   MARKDOWN RENDERER
   ========================================================================= */
function escA(s) { return String(s).replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;'); }
function esc(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
/**
 * 属性值补引号：只转义引号，**不碰 &**。
 * 走到这里的 alt 已经过 renderMD 第 2 步的全文 esc()，再转一次 & 会让 `&lt;` 变成字面量显示。
 *
 * 这个函数此前只被 parseMediaBang 引用、从来没有定义 —— 因为图片分支一直没被真正走到
 * （之前后端 415 / 模型只写工作区路径），所以没人发现。2026-10-22 出图打通后第一次进这个分支，
 * 直接 `ReferenceError: escQ is not defined`，整条回复渲染失败，界面上就是一行红字。
 */
function escQ(s) { return String(s).replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }

function balancedUrl(s, open) {
    let d = 0;
    for (let k = open; k < s.length; k++) {
        if (s[k] === '(') d++;
        else if (s[k] === ')') { d--; if (d === 0) return { end: k, url: s.slice(open + 1, k) }; }
    }
    return null;
}

/**
 * `![alt](url)` → 图片或音频。
 *
 * 同一个 markdown 语法承担两种交付物：图片出 <img>，音频出 <audio controls>。
 * 判据只看 URL 扩展名（复用上传校验用的 AUDIO_EXTS，避免两套扩展名清单漂移），
 * 所以 song_generate 返回的 `![生成的歌曲](/api/generated-media/<owner>/xxx.mp3)`
 * 会渲染成播放器；以前一律拼 <img>，音频就变成一张碎图。
 *
 * 放行的 URL 前缀是白名单而不是"看起来像路径就行"：
 * - /api/generated-media/<owner>/<file>：图片/音频后端产出
 * - /api/conversation-media/<sid>/<file>：用户上传的附件
 * - /api/workspace-media/<file>：write_file / render_diagram 落在工作区的图片
 *   （模型画图降级成手写 SVG 时的唯一回显通道；工作区没有静态资源映射，别指望相对路径能加载）
 * 工作区那条允许百分号编码，中文文件名（`%E5%B0%81...svg`）才过得了这里的正则。
 */
function isAudioUrl(url) {
    const clean = String(url).split('?')[0].split('#')[0];
    return AUDIO_EXTS.has(fileExt(clean));
}

function parseMediaBang(s) {
    let out = '', i = 0;
    while (i < s.length) {
        const p = s.indexOf('![', i);
        if (p === -1) { out += s.slice(i); break; }
        out += s.slice(i, p);
        const rb = s.indexOf(']', p + 2);
        if (rb === -1 || s[rb + 1] !== '(') { out += s[p]; i = p + 1; continue; }
        const alt = s.slice(p + 2, rb);
        const u = balancedUrl(s, rb + 1);
        if (!u) { out += s[p]; i = p + 1; continue; }
        const url = u.url.replace(/&amp;/g, '&');
        if (!/^https?:\/\//i.test(url) && !/^\/api\/generated-media\/[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/i.test(url) && !/^\/api\/conversation-media\/[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/i.test(url) && !/^\/api\/workspace-media\/[A-Za-z0-9._%~-]+$/i.test(url) && !/^data:image\//i.test(url))
            { out += s.slice(p, u.end + 1); i = u.end + 1; continue; }
        if (isAudioUrl(url)) {
            out += `<audio controls preload="metadata" ${mediaSrcAttr(url)}></audio>`;
        } else {
            // alt 取自已经过 renderMD 全文转义文本，这里只需补引号（再走 escA 会把 &lt; 二次转义成字面量）
            out += `<img ${mediaSrcAttr(url)} alt="${escQ(alt)}" referrerpolicy="no-referrer" loading="lazy">`;
        }
        i = u.end + 1;
    }
    return out;
}

/**
 * 媒体地址落成属性：站内受保护媒体写成 `data-auth-src`，等 JS 用带 Bearer 的 fetch 取回后再挂 `src`。
 *
 * <p>为什么不能直接 `<img src="/api/...">`：服务端只认 `Authorization: Bearer` 头
 * （{@code JwtSessionService#extractBearerToken}，cookie 与 `access_token` 查询参数两条通路早已移除），
 * 而 `<img>`/`<audio>` 发出的请求**带不了自定义头** —— 结果就是 401 + 破图。
 * 站外 https 链接不需要凭证，保持原样。
 *
 * <p>另一种情况：这张图**已经取回来过**（{@link MEDIA_BLOB_CACHE} 里有 objectURL）。
 * 那就不再走 `data-auth-src`，直接把 objectURL 写成 `src` —— 重新渲染时浏览器从内存解码，
 * 尺寸立刻就位，不会出现「先 0 高、解码后撑开」的跳动。流式重画末尾段落时这条很关键。
 */
function mediaSrcAttr(url) {
    if (!/^\/api\//i.test(url)) return `src="${escA(url)}"`;
    // 已经取回来的直接挂 objectURL：重新渲染时浏览器从内存解码，不会出现「无尺寸 → 撑开」的跳动。
    // 还在路上的留 data-auth-src，由 hydrateProtectedMedia 挂同一个 promise 的结果。
    const cached = MEDIA_BLOB_CACHE.get(url);
    if (cached && cached.objectUrl) return `src="${escA(cached.objectUrl)}"`;
    return `data-auth-src="${escA(url)}"`;
}

