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
 * Markdown 媒体按 URL 扩展名识别；结构化卡片也可提供真实媒体的 MIME 类型。
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
    return /\.(?:mp3|wav|flac|m4a|ogg|oga|opus|aac)$/i.test(clean);
}

function isVideoUrl(url) {
    const clean = String(url).split('?')[0].split('#')[0];
    return /\.(?:mp4|webm|ogv|m4v|mov|avi|wmv)$/i.test(clean);
}

/** Only real web resources and the owner-scoped media endpoints may become src attributes. */
function safeMediaUrl(value) {
    if (typeof value !== 'string' || value.length > 8192 || /[\x00-\x20\\]/.test(value)) return '';
    if (/^\/api\/(?:generated-media|conversation-media)\/[A-Za-z0-9._~-]+\/[A-Za-z0-9._%~-]+$/i.test(value)
            || /^\/api\/workspace-media\/[A-Za-z0-9._%~-]+(?:\?sid=[A-Za-z0-9._%~-]+)?$/i.test(value)) return value;
    try {
        const url = new URL(value);
        return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : '';
    } catch (_) { return ''; }
}

function safeMediaPageUrl(value) {
    const url = safeMediaUrl(value);
    return /^https?:\/\//i.test(url) ? url : '';
}

/** Remote covers follow img-src HTTPS policy; authenticated local media keep their route. */
function safeMediaCoverUrl(value) {
    const url = safeMediaUrl(value);
    return /^http:\/\//i.test(url) ? '' : url;
}

/** Accept tool field aliases without trusting a URL before it passes the same media policy. */
function mediaCardUrl(item, fields, urlPolicy = safeMediaUrl) {
    for (const field of fields) {
        const value = item?.[field];
        const url = urlPolicy(
            value && typeof value === 'object' && !Array.isArray(value) ? value.url : value);
        if (url) return url;
    }
    return '';
}

function mediaCardCover(item) {
    const fields = ['posterUrl', 'poster_url', 'thumbnailUrl', 'thumbnail_url', 'imageUrl', 'image_url',
        'coverUrl', 'cover_url', 'poster', 'thumbnail', 'image', 'cover'];
    return mediaCardUrl(item, fields, safeMediaCoverUrl) || mediaCardUrl(item?.album, fields, safeMediaCoverUrl);
}

function mediaCardText(value, limit = 240) {
    return typeof value === 'string' || typeof value === 'number' ? esc(String(value).slice(0, limit)) : '';
}

/** Use explicit structured types, never title keywords, to select a presentation. */
function mediaCardKind(value) {
    return value === 'audio' ? 'song' : ['movie', 'song', 'video'].includes(value) ? value : '';
}

function normalizedMediaCardItem(item, listType) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return null;
    const kind = mediaCardKind(item.type || listType);
    return kind ? { ...item, type: kind,
        description: item.description || item.summary || item.snippet,
        source: typeof item.source === 'string' ? { label: item.source } : item.source } : null;
}

/** News metadata is supplied by the task; rendering never fetches a page to discover an image. */
function renderNewsCardBlock(language, rawJson) {
    if (String(language).toLowerCase() !== 'miniagent-news') return null;
    const pending = '<div class="media-card-pending news-card-pending" role="status">正在整理新闻…</div>';
    if (rawJson.length > 100000) return '<div class="media-card-pending news-card-pending">新闻内容过长，暂无法展示。</div>';
    let data;
    try { data = JSON.parse(rawJson); } catch (_) { return pending; }
    if (!data || typeof data !== 'object' || !Array.isArray(data.items)) return pending;
    // The 100 KB input limit bounds this inspection. Apply the news limit only
    // after deciding whether the payload actually consists of media results.
    const entries = data.items.filter(item => item && typeof item === 'object' && !Array.isArray(item));
    // Old replies and imperfect model outputs may use the wrong fence while
    // retaining explicit media types. Respect those types without guessing.
    if (entries.length && entries.every(item => normalizedMediaCardItem(item, data.type))) {
        return renderMediaCardBlock('miniagent-media', rawJson);
    }
    const fallback = '<svg viewBox="0 0 40 40" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="7" y="7" width="26" height="26" rx="4"/><path d="M12 13h16M12 19h7v8h-7zM23 19h5M23 23h5M23 27h5"/></svg>';
    const cards = data.items.slice(0, 8).map(item => {
        if (!item || typeof item !== 'object' || Array.isArray(item)) return '';
        const mediaItem = normalizedMediaCardItem(item, data.type);
        if (mediaItem) return renderMediaCardItem(mediaItem);
        const title = mediaCardText(item.title, 200) || '新闻资讯';
        const url = mediaCardUrl(item, ['url', 'pageUrl', 'page_url', 'sourceUrl', 'source_url'], safeMediaPageUrl)
            || mediaCardUrl(item.source, ['url'], safeMediaPageUrl)
            || mediaCardUrl(item, ['thumbnail_source_url'], safeMediaPageUrl);
        const thumbnail = mediaCardCover(item);
        const summary = mediaCardText(item.summary || item.description || item.snippet, 480);
        const sourceValue = typeof item.source === 'string' ? item.source
            : item.source?.label || item.source?.name || item.source?.title;
        const source = mediaCardText(sourceValue, 100) || (url ? esc(new URL(url).hostname.replace(/^www\./, '')) : '来源待核实');
        const published = mediaCardText(item.publishedAt || item.published_at || item.publishedDate
            || item.published_date || item.publicationDate || item.publication_date
            || item.datePublished || item.date_published || item.pubDate || item.pub_date || item.date, 80);
        const thumbnailMarkup = fallback + `<span data-cover-status>${thumbnail ? '加载配图…' : '暂无配图'}</span>` + (thumbnail ? `<img ${mediaSrcAttr(thumbnail)} alt="${escA(typeof item.title === 'string' ? item.title.slice(0, 200) : '新闻配图')}" loading="lazy" decoding="async" referrerpolicy="no-referrer" data-media-poster="1">` : '');
        const coverState = ` data-cover-state="${thumbnail ? 'loading' : 'missing'}"`;
        const cover = url ? `<a class="news-thumbnail"${coverState} href="${escA(url)}" target="_blank" rel="noopener noreferrer" aria-label="打开新闻：${escA(typeof item.title === 'string' ? item.title.slice(0, 200) : '新闻资讯')}">${thumbnailMarkup}</a>`
            : `<div class="news-thumbnail"${coverState}>${thumbnailMarkup}</div>`;
        const heading = url ? `<a class="news-title-link" href="${escA(url)}" target="_blank" rel="noopener noreferrer">${title}</a>` : title;
        return `<article class="news-item">${cover}<div class="news-details"><h3>${heading}</h3>${summary ? `<p class="news-summary">${summary}</p>` : ''}<div class="news-meta"><span class="news-source">${source}</span>${published ? `<span class="news-published">${published}</span>` : ''}${url ? `<a class="news-source-link" href="${escA(url)}" target="_blank" rel="noopener noreferrer" aria-label="查看新闻来源">原文 ↗</a>` : ''}</div></div></article>`;
    }).join('');
    return `<section class="news-result-card" aria-label="新闻资讯"><header class="news-result-heading">${mediaCardText(data.title, 160) || '新闻资讯'}</header><div class="news-result-items">${cards || '<div class="media-card-pending">暂无可展示的新闻。</div>'}</div></section>`;
}

function renderNativeMedia(url, kind, title, posterUrl) {
    const tag = kind === 'audio' ? 'audio' : 'video';
    const label = title || (tag === 'audio' ? '音频' : '视频');
    const poster = tag === 'video' && posterUrl && !posterUrl.startsWith('/api/')
        ? ` poster="${escA(posterUrl)}"` : '';
    return `<div class="media-player-shell"><${tag} controls${tag === 'video' ? ' playsinline class="chat-video"' : ''} preload="metadata" aria-label="${escA(label)}" data-media-key="${escA(tag + '|' + url)}" data-media-url="${escA(url)}"${poster} ${mediaSrcAttr(url)}></${tag}></div>`;
}

function renderMediaCardBlock(language, rawJson) {
    if (String(language).toLowerCase() !== 'miniagent-media') return null;
    const pending = '<div class="media-card-pending" role="status">正在整理媒体内容…</div>';
    if (rawJson.length > 100000) return '<div class="media-card-pending">媒体内容过长，暂无法展示。</div>';
    let data;
    try { data = JSON.parse(rawJson); } catch (_) { return pending; }
    if (!data || typeof data !== 'object' || !Array.isArray(data.items)) return pending;
    const cards = data.items.slice(0, 16).map(item => {
        const mediaItem = normalizedMediaCardItem(item, data.type);
        return mediaItem ? renderMediaCardItem(mediaItem) : '';
    }).join('');
    return `<section class="media-result-card" aria-label="媒体推荐"><header class="media-result-heading">${mediaCardText(data.title, 160) || '为你推荐'}</header><div class="media-result-items">${cards || '<div class="media-card-pending">暂无可展示的媒体内容。</div>'}</div></section>`;
}

function renderMediaCardItem(item) {
    const text = mediaCardText;
    const icon = kind => `<svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">${kind === 'song'
        ? '<path d="M20 32V13l19-4v20M20 17l19-4"/><ellipse cx="14" cy="33" rx="6" ry="4"/><ellipse cx="33" cy="30" rx="6" ry="4"/>'
        : '<rect x="7" y="10" width="34" height="28" rx="5"/><path d="m21 18 10 6-10 6z"/>'}</svg>`;
    const kind = mediaCardKind(item.type);
    if (!kind) return '';
    const title = text(item.title, 160) || (kind === 'movie' ? '电影' : kind === 'song' ? '歌曲' : '视频');
    const poster = mediaCardCover(item);
    const source = mediaCardUrl(item.source, ['url'], safeMediaPageUrl)
        || mediaCardUrl(item, ['sourceUrl', 'source_url'], safeMediaPageUrl);
    const sourceLabel = text(item.source?.label || item.source?.name, 80);
    const page = mediaCardUrl(item, ['pageUrl', 'page_url', 'webpageUrl', 'webpage_url', 'url'], safeMediaPageUrl) || source;
    const media = mediaCardUrl(item, ['mediaUrl', 'media_url', 'audioUrl', 'audio_url', 'videoUrl', 'video_url', 'playUrl', 'play_url']);
    const mimeValue = item.mimeType || item.mime_type;
    const mime = typeof mimeValue === 'string' ? mimeValue.toLowerCase() : '';
    const playable = media && (kind === 'song' ? isAudioUrl(media) || /^audio\/[\w.+-]+(?:;|$)/.test(mime)
        : isVideoUrl(media) || /^video\/[\w.+-]+(?:;|$)/.test(mime));
    const album = typeof item.album === 'object' ? item.album?.title || item.album?.name
        : item.album || item.albumName || item.album_name;
    const meta = [item.creator || item.artist || item.author, kind === 'song' ? album : '',
        item.releaseDate || item.release_date, item.duration].map(value => text(value, 100)).filter(Boolean).join(' · ');
    const coverContent = icon(kind) + `<span data-cover-status>${poster ? '加载封面…' : kind === 'movie' ? '暂无海报' : '暂无封面'}</span>` + (poster
        ? `<img ${mediaSrcAttr(poster)} alt="${escA(typeof item.title === 'string' ? item.title.slice(0, 160) : '媒体封面')}" loading="lazy" decoding="async" referrerpolicy="no-referrer" data-media-poster="1">` : '');
    const coverState = ` data-cover-state="${poster ? 'loading' : 'missing'}"`;
    const cover = page ? `<a class="media-cover media-cover--${kind}"${coverState} href="${escA(page)}" target="_blank" rel="noopener noreferrer" aria-label="打开${kind === 'movie' ? '电影' : kind === 'song' ? '歌曲' : '视频'}原网页：${escA(typeof item.title === 'string' ? item.title.slice(0, 160) : '')}">${coverContent}<span class="media-cover-open" aria-hidden="true">↗</span></a>`
        : `<div class="media-cover media-cover--${kind}"${coverState}>${coverContent}</div>`;
    const player = playable ? renderNativeMedia(media, kind === 'song' ? 'audio' : 'video',
        (kind === 'movie' ? '预告片：' : '') + (typeof item.title === 'string' ? item.title.slice(0, 160) : ''), poster) : '';
    return `<article class="media-item" data-media-type="${kind}"><div class="media-item-top">${cover}<div class="media-details"><span class="media-kind">${kind === 'movie' ? '电影' : kind === 'song' ? '音乐' : '视频'}</span><h3>${title}</h3>${meta ? `<div class="media-meta">${meta}</div>` : ''}${item.description ? `<p class="media-description">${text(item.description, 480)}</p>` : ''}<div class="media-actions">${page ? `<a class="media-page-link" href="${escA(page)}" target="_blank" rel="noopener noreferrer">${kind === 'movie' ? '详情 / 观看入口' : '打开原网页'} <span aria-hidden="true">↗</span></a>` : ''}${source && source !== page ? `<a class="media-source-link" href="${escA(source)}" target="_blank" rel="noopener noreferrer">${sourceLabel || '查看来源'}</a>` : sourceLabel ? `<span class="media-source-label">${sourceLabel}</span>` : ''}</div>${kind === 'song' ? player : ''}</div></div>${kind !== 'song' && player ? (kind === 'movie' ? '<span class="media-preview-label">预告片</span>' : '') + player : ''}</article>`;
}

/** Reuse native players across streamed/final renders so an ongoing playback keeps its DOM and time. */
function setMarkdownContent(root, markup) {
    const players = new Map();
    root.querySelectorAll('audio[data-media-key],video[data-media-key]').forEach(player => {
        const key = player.getAttribute('data-media-key');
        if (!players.has(key)) players.set(key, []);
        players.get(key).push(player);
    });
    const template = document.createElement('template');
    template.innerHTML = markup;
    const replacements = [...template.content.querySelectorAll('audio[data-media-key],video[data-media-key]')];
    const oldChildren = [...root.childNodes];
    root.appendChild(template.content);
    replacements.forEach(player => {
        const existing = players.get(player.getAttribute('data-media-key'))?.shift();
        if (!existing) return;
        // Chromium supports state-preserving moves within the document. Unlike
        // innerHTML or detach/reattach, this keeps an active native player playing.
        if (typeof player.parentNode.moveBefore === 'function' && existing.isConnected) {
            player.parentNode.moveBefore(existing, player);
            player.remove();
        } else player.replaceWith(existing);
    });
    oldChildren.forEach(node => node.remove());
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
        let url = u.url.replace(/&amp;/g, '&');
        const workspaceName = workspaceDownloadPath(url);
        if (workspaceName) {
            const ext = fileExt(workspaceName).toLowerCase();
            const inlineImage = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'avif'].includes(ext);
            const inlineVideo = isVideoUrl(workspaceName);
            if (inlineImage || inlineVideo || isAudioUrl(workspaceName)) {
                url = '/api/workspace-media/' + workspaceName + workspaceSidQuery();
            } else {
                out += s.slice(p, u.end + 1);
                i = u.end + 1;
                continue;
            }
        }
        if (!safeMediaUrl(url) && !/^data:image\/(?:png|jpeg|gif|webp|avif|bmp);base64,[a-z0-9+/=]+$/i.test(url))
            { out += s.slice(p, u.end + 1); i = u.end + 1; continue; }
        if (isAudioUrl(url)) {
            out += renderNativeMedia(url, 'audio', alt.replace(/&(?:amp|lt|gt|quot);/g, ''), '');
        } else if (isVideoUrl(url)) {
            out += renderNativeMedia(url, 'video', alt.replace(/&(?:amp|lt|gt|quot);/g, ''), '');
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
    const origin = `data-media-origin="${escA(url)}"`;
    if (cached && cached.objectUrl) return `${origin} src="${escA(cached.objectUrl)}"`;
    return `${origin} data-auth-src="${escA(url)}"`;
}

/** Bare filenames in prose are code references; only explicit paths or attachment-only lines become files. */
function renderMarkdownCodeToken(codeText, source, offset, tokenLength) {
    const openLabel = source.lastIndexOf('[', offset);
    const closeLabel = source.lastIndexOf(']', offset);
    const labelEnd = source.indexOf('](', offset + tokenLength);
    if (openLabel > closeLabel && labelEnd >= 0 && !source.slice(offset, labelEnd).includes('\n'))
        return '<code>' + codeText + '</code>'; // code inside a link label must never become a nested anchor
    const lineStart = source.lastIndexOf('\n', offset - 1) + 1;
    const lineEnd = source.indexOf('\n', offset + tokenLength);
    const before = source.slice(lineStart, offset).trim();
    const after = source.slice(offset + tokenLength, lineEnd < 0 ? source.length : lineEnd).trim();
    const standalone = /^(?:[-*]|\d+\.)?$/.test(before) && /^[，,。.;；]?$/.test(after);
    const explicitPath = /^(?:workspace[\/\\]|[A-Za-z]:[\/\\]|\/)/i.test(codeText);
    const file = standalone || explicitPath ? inlineMarkdownFileLink(codeText) : null;
    return file || '<code>' + codeText + '</code>';
}

/** Keep prose links inline; promote only attachment-only lines into a compact responsive grid. */
function layoutMarkdownFiles(markup) {
    if (!markup.includes('workspace-download-link') || typeof document === 'undefined') return markup;
    const template = document.createElement('template');
    template.innerHTML = markup;
    const root = template.content;
    const links = [...root.querySelectorAll('a.workspace-download-link')];
    links.forEach(link => link.classList.add('workspace-file-chip'));
    const onlyFiles = node => {
        const copy = node.cloneNode(true);
        copy.querySelectorAll('a.workspace-download-link').forEach(link => link.remove());
        if (copy.querySelector('img,video,audio,pre,code,table,a,button,input')) return false;
        return /^(?:(?:附件|交付文件|生成文件|文件)\s*[:：]?\s*)?[\s，,。.;；·]*$/.test(copy.textContent || '');
    };
    const promote = (node, files) => {
        const grid = document.createElement('div');
        grid.className = 'workspace-attachment-grid';
        grid.setAttribute('aria-label', '任务文件');
        files.forEach(link => {
            link.classList.remove('workspace-file-chip');
            link.classList.add('workspace-attachment-card');
            grid.appendChild(link);
        });
        node.replaceWith(grid);
    };
    if (links.length && onlyFiles(root)) {
        const grid = document.createElement('div');
        grid.className = 'workspace-attachment-grid';
        grid.setAttribute('aria-label', '任务文件');
        links.forEach(link => {
            link.classList.remove('workspace-file-chip');
            link.classList.add('workspace-attachment-card');
            grid.appendChild(link);
        });
        root.replaceChildren(grid);
        return template.innerHTML;
    }
    // Lists containing only files are one attachment group, without empty bullets or dangling punctuation.
    [...root.querySelectorAll('ul,ol')].forEach(list => {
        const files = [...list.querySelectorAll('a.workspace-download-link')];
        if (files.length && onlyFiles(list)) promote(list, files);
    });
    [...root.querySelectorAll('p')].forEach(paragraph => {
        const files = [...paragraph.querySelectorAll('a.workspace-file-chip')];
        if (files.length && onlyFiles(paragraph)) promote(paragraph, files);
    });
    // Consecutive standalone file paragraphs should form one grid.
    [...root.querySelectorAll('.workspace-attachment-grid')].forEach(grid => {
        while (grid.nextSibling && (grid.nextSibling.nodeType === 3 && !grid.nextSibling.textContent.trim()
                || grid.nextSibling.nodeType === 1 && grid.nextSibling.matches('br,.workspace-attachment-grid'))) {
            const next = grid.nextSibling;
            if (next.nodeType === 1 && next.matches('.workspace-attachment-grid')) grid.append(...next.children);
            next.remove();
        }
    });
    return template.innerHTML;
}

