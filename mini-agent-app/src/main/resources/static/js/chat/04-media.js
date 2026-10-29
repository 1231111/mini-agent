/* =========================================================================
   FILE / DRAG
   ========================================================================= */
document.addEventListener('dragover', e => { e.preventDefault(); document.getElementById('dragOverlay').classList.add('on'); });
document.addEventListener('dragleave', e => { if (!e.relatedTarget) document.getElementById('dragOverlay').classList.remove('on'); });
document.addEventListener('drop', e => {
    e.preventDefault();
    document.getElementById('dragOverlay').classList.remove('on');
    const files = Array.from(e.dataTransfer.files || []);
    files.forEach(addFile);
});

const UPLOAD_MAX_BYTES = 700 * 1024 * 1024;
const AUDIO_EXTS = new Set(['mp3', 'wav', 'flac', 'm4a', 'ogg']);
const VIDEO_EXTS = new Set(['mp4', 'mov', 'avi', 'wmv']);

function fileExt(name) {
    const i = (name || '').lastIndexOf('.');
    return i < 0 ? '' : name.slice(i + 1).toLowerCase();
}
function isAudioFile(f) {
    if (!f) return false;
    if ((f.type || '').startsWith('audio/')) return AUDIO_EXTS.has(fileExt(f.name)) || !fileExt(f.name);
    return AUDIO_EXTS.has(fileExt(f.name));
}
function isVideoFile(f) {
    if (!f) return false;
    if ((f.type || '').startsWith('video/')) return VIDEO_EXTS.has(fileExt(f.name)) || !fileExt(f.name);
    return VIDEO_EXTS.has(fileExt(f.name));
}
function isNativeMediaFile(f) { return isAudioFile(f) || isVideoFile(f); }

/**
 * 添加文件到上传队列
 * @param {File} file - 要上传的文件对象
 */
function addFile(file) {
    // 1. 验证文件大小和格式
    if (file.size > UPLOAD_MAX_BYTES) {
        alert('文件过大（上限 700MB）: ' + file.name);
        return;
    }
    if ((file.type || '').startsWith('audio/') || (file.type || '').startsWith('video/')) {
        if (!isNativeMediaFile(file)) {
            alert('不支持的音视频格式（音频 mp3/wav/flac/m4a/ogg；视频 mp4/mov/avi/wmv）');
            return;
        }
    }
    
    // 2. 添加到文件列表
    const idx = selectedFiles.length;
    selectedFiles.push(file);
    
    // 3. 根据文件类型处理
    if (file.type.startsWith('image/')) {
        // 图片：预览
        previewImageFile(file);
    } else {
        // 音视频：上传
        previewNonImageFile();
        uploadFile(file, idx);
    }
    
    // 4. 更新UI状态
    updateUIAfterFileAdd();
}

/**
 * 预览图片文件
 */
function previewImageFile(file) {
    const reader = new FileReader();
    reader.onload = ev => {
        previewSrcs.push(ev.target.result);
        renderImgPreview();
    };
    reader.readAsDataURL(file);
}

/**
 * 预览非图片文件（音视频）
 */
function previewNonImageFile() {
    previewSrcs.push(null);
    renderImgPreview();
    uploadingCount++;
    updateSendButton();
}

/**
 * 更新添加文件后的UI状态
 */
function updateUIAfterFileAdd() {
    document.getElementById('imgBtn').classList.add('on');
    document.getElementById('inputBox').classList.add('has-preview');
    onInputChange();
}

/**
 * 上传文件（带CSRF保护）
 * @param {File} file - 要上传的文件
 * @param {number} idx - 文件在列表中的索引
 */
function uploadFile(file, idx) {
    // 1. 先拿到会话 id —— 上传要落到某个会话目录下，而 id 由服务端签发
    ensureSessionId()
        .then(sid => {
            // 2. 会话 id 就绪，执行上传
            performFileUpload(file, idx, sid);
        })
        .catch(err => {
            console.error('[Upload] Failed to resolve session id:', err);
            uploadingCount--;
            updateFileProgress(idx, 0, 'error', '会话创建失败');
            updateSendButton();
        });
}

/**
 * 取当前会话 id。会话 id 由服务端签发（POST /api/conversations），
 * 签发时即把 userId 写进 chat_conversations —— 此后所有归属校验都是严格判等，
 * 不再需要「这个 id 被别人占了吗」这种猜测式放行。
 *
 * 一次会话只签发一次：currentId 存在就直接返回，避免每次上传/发送都新建一条空会话。
 * @returns {Promise<string>} 会话 id
 */
async function ensureSessionId() {
    if (currentId) {
        return currentId;
    }
    const resp = await fetch('/api/conversations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
    });
    if (!resp.ok) {
        throw new Error('create conversation failed: HTTP ' + resp.status);
    }
    const data = unwrapApiBody(await resp.json());
    const sid = data && data.sessionId;
    if (!sid) {
        throw new Error('create conversation returned no sessionId');
    }
    currentId = sid;
    localStorage.setItem(LS_CUR, sid);
    renderSidebar();
    // 新会话没有会话级覆盖，但要把"全局默认"和"最终生效"拉回来渲染按钮。
    await refreshExecPolicyView();
    return sid;
}

/**
 * 执行文件上传
 * @param {File} file - 要上传的文件
 * @param {number} idx - 文件在列表中的索引
 * @param {string} sessionId - 服务端签发的会话 id
 */
function performFileUpload(file, idx, sessionId) {
    // 1. 创建 FormData
    const formData = createUploadFormData(file, sessionId);
    
    // 2. 创建并配置 XMLHttpRequest
    const xhr = createUploadXhr(sessionId);
    
    // 3. 设置请求头
    setUploadHeaders(xhr);
    
    // 4. 设置事件处理器
    setUploadEventHandlers(xhr, file, idx);
    
    // 5. 发送请求
    xhr.send(formData);
}

/**
 * 创建上传用的 FormData
 */
function createUploadFormData(file, sessionId) {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('sessionId', sessionId);
    return fd;
}

/**
 * 创建并配置上传用的 XMLHttpRequest
 */
function createUploadXhr(sessionId) {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/conversations/' + encodeURIComponent(sessionId) + '/files');
    return xhr;
}

/**
 * 设置上传请求头。只带 Bearer —— cookie 与查询参数两条鉴权通路已随无状态改造移除。
 */
function setUploadHeaders(xhr) {
    const jwtToken = window.MiniAuth.token();
    if (jwtToken) {
        xhr.setRequestHeader('Authorization', 'Bearer ' + jwtToken);
    }
}

/**
 * 设置上传事件处理器
 */
function setUploadEventHandlers(xhr, file, idx) {
    // 上传进度
    xhr.upload.onprogress = e => {
        if (e.lengthComputable) {
            const pct = Math.round(e.loaded / e.total * 100);
            updateFileProgress(idx, pct, 'uploading');
        }
    };
    
    // 请求完成
    xhr.onload = () => handleUploadSuccess(xhr, file, idx);
    
    // 网络错误
    xhr.onerror = () => handleUploadError(idx, '网络错误');
}

/**
 * 处理上传成功响应
 */
function handleUploadSuccess(xhr, file, idx) {
    uploadingCount--;
    
    // 解析响应
    let data = null;
    try { 
        data = JSON.parse(xhr.responseText || '{}'); 
    } catch (e) {
        console.error('[Upload] Failed to parse response:', e);
    }
    
    // 检查响应状态
    if (xhr.status === 200 && data && data.success) {
        handleUploadSuccessResponse(data, file, idx);
    } else {
        handleUploadErrorResponse(xhr, data, idx);
    }
    
    updateSendButton();
}

/**
 * 处理上传成功的响应数据
 */
function handleUploadSuccessResponse(data, file, idx) {
    const d = unwrapApiBody(data) || data;
    
    // 保存文件引用
    uploadedFileRefs[idx] = {
        filePath: d.filePath,
        filename: d.filename,
        mimeType: d.mimeType,
        fileSize: d.fileSize,
        kind: d.kind || (isAudioFile(file) ? 'audio' : (isVideoFile(file) ? 'video' : null)),
        extractedTextPath: d.extractedTextPath || null
    };
    
    // 显示成功提示
    const doneHint = d.kind
        ? (d.kind === 'video' ? '视频已上传' : '音频已上传')
        : (d.extractedTextPath ? '已解析' : null);
    updateFileProgress(idx, 100, 'done', doneHint);
}

/**
 * 处理上传错误响应
 */
function handleUploadErrorResponse(xhr, data, idx) {
    const msg = (data && data.message)
        || (xhr.status === 413 ? '文件过大（上限 700MB）' : ('上传失败 HTTP ' + xhr.status));
    updateFileProgress(idx, 0, 'error', msg);
}

/**
 * 处理上传错误
 */
function handleUploadError(idx, errorMsg) {
    uploadingCount--;
    updateFileProgress(idx, 0, 'error', errorMsg);
    updateSendButton();
}

function updateFileProgress(idx, pct, status, errMsg) {
    const el = document.getElementById('file-progress-' + idx);
    if (!el) return;
    const pBar = el.querySelector('.upload-progress-bar');
    const sEl = el.querySelector('.upload-status');
    if (status === 'uploading') {
        pBar.style.width = pct + '%';
        pBar.classList.remove('done');
        pBar.classList.add('uploading');
        sEl.textContent = pct + '%';
        sEl.className = 'upload-status uploading';
    } else if (status === 'done') {
        pBar.style.width = '100%';
        pBar.classList.remove('uploading');
        pBar.classList.add('done');
        sEl.textContent = errMsg || '已上传';
        sEl.className = 'upload-status done';
    } else if (status === 'error') {
        el.querySelector('.upload-status').textContent = '失败: ' + (errMsg || '');
        el.querySelector('.upload-status').className = 'upload-status error';
    }
}

function updateSendButton() {
    const btn = document.getElementById('sendBtn');
    if (btn) btn.disabled = uploadingCount > 0;
}


function renderImgPreview() {
    const bar = document.getElementById('imgPreview');
    bar.classList.toggle('active', selectedFiles.length > 0);
    bar.innerHTML = '';
    selectedFiles.forEach((f, i) => {
        const item = document.createElement('div');
        item.className = 'img-thumb';
        if (previewSrcs[i]) {
            item.innerHTML = `
                <img src="${previewSrcs[i]}" alt="">
                <span class="img-fname" title="${escA(f.name)}">${esc(f.name)}</span>
                <button class="rm-img" title="移除">
                    <svg fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24" width="13" height="13">
                        <path d="M18 6 6 18M6 6l12 12"/>
                    </svg>
                </button>`;
        } else {
            const ext = f.name.split('.').pop().toUpperCase();
            const badge = isAudioFile(f) ? 'AUD' : (isVideoFile(f) ? 'VID' : ext);
            const progressId = 'file-progress-' + i;
            const isUploaded = uploadedFileRefs[i] != null;
            item.innerHTML = `
                <div class="thumb-badge">${esc(badge)}</div>
                <div style="flex:1;min-width:0">
                    <span class="img-fname" title="${escA(f.name)}">${esc(f.name)}</span>
                    <div id="${progressId}">
                        <div class="upload-progress"><div class="upload-progress-bar ${isUploaded ? 'done' : ''}" style="width:${isUploaded ? '100' : '0'}%"></div></div>
                        <div class="upload-status ${isUploaded ? 'done' : ''}">${isUploaded ? '已上传' : '等待上传'}</div>
                    </div>
                </div>
                <button class="rm-img" title="移除">
                    <svg fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24" width="13" height="13">
                        <path d="M18 6 6 18M6 6l12 12"/>
                    </svg>
                </button>`;
        }
        item.querySelector('.rm-img').onclick = () => removeImageAt(i);
        bar.appendChild(item);
    });
}

function onFileSelected(e) {
    const files = Array.from(e.target.files || []);
    files.forEach(addFile);
    e.target.value = '';
}

function removeImageAt(idx) {
    selectedFiles.splice(idx, 1);
    previewSrcs.splice(idx, 1);
    uploadedFileRefs.splice(idx, 1);
    if (selectedFiles.length === 0) {
        document.getElementById('imgBtn').classList.remove('on');
        document.getElementById('inputBox').classList.remove('has-preview');
    }
    renderImgPreview();
    onInputChange();
}

function removeImage() {
    selectedFiles = [];
    previewSrcs = [];
    uploadedFileRefs = [];
    document.getElementById('imgBtn').classList.remove('on');
    document.getElementById('inputBox').classList.remove('has-preview');
    renderImgPreview();
    onInputChange();
}

/* =========================================================================
   LIGHTBOX
   ========================================================================= */
let lightboxScale = 1;
function openLightbox(src) {
    const image = document.getElementById('lbImg');
    image.src = src; image.onload = fitLightboxImage;
    document.getElementById('lightbox').classList.add('on');
    requestAnimationFrame(fitLightboxImage);
}
function setLightboxScale(scale) {
    const image = document.getElementById('lbImg');
    // 下限 0.05 而不是 0.25：超宽长图（如 5000px 宽的架构图）按宽度适配时比值可能只有 0.1，
    // 卡在 0.25 会直接溢出舞台，横向也被裁掉。
    lightboxScale = Math.max(0.05, Math.min(4, scale));
    image.style.width = Math.round(image.naturalWidth * lightboxScale) + 'px';
    image.style.height = Math.round(image.naturalHeight * lightboxScale) + 'px';
    document.getElementById('lbZoomLabel').textContent = Math.round(lightboxScale * 100) + '%';
}
/**
 * 适配宽度，不是「整图塞进窗口」。
 *
 * 高窄图（520×3001 的架构图）按整图适配时高度比只有 0.26，打开后比对话区里还小 ——
 * 用户点开看到图片缩水，体感就是「点击不能放大」。宽度铺满 + 纵向滚动才可读，
 * 这也是图片查看器和 PDF 阅读器的默认行为。
 *
 * 上限是 3 倍而不是 100%：SVG 的 naturalWidth 等于 viewBox 尺寸（架构图常是 500 多），
 * 限制在原始尺寸会让矢量图在预览层里比对话区还小。位图大于舞台时这个上限不生效，
 * 小于舞台时才放大 —— 那正是「适配宽度」该有的样子。
 */
function fitLightboxImage() {
    const stage = document.getElementById('lightboxStage');
    const image = document.getElementById('lbImg');
    if (!image.naturalWidth || !stage) return;
    setLightboxScale(Math.min((stage.clientWidth - 24) / image.naturalWidth, 3));
    stage.scrollTop = 0; stage.scrollLeft = 0;
}
function changeLightboxScale(delta) { setLightboxScale(lightboxScale + delta); }
let lightboxNoticeTimer = null;
/** 工具栏内的非阻塞提示；元素缺失时退回 alert，避免反馈静默丢失。 */
function showLightboxNotice(text, isError) {
    const notice = document.getElementById('lbNotice');
    if (!notice) { window.alert(text); return; }
    notice.textContent = text;
    notice.classList.toggle('is-error', !!isError);
    notice.classList.add('on');
    if (lightboxNoticeTimer) clearTimeout(lightboxNoticeTimer);
    lightboxNoticeTimer = setTimeout(() => {
        notice.classList.remove('on');
        lightboxNoticeTimer = null;
    }, 1800);
}
async function downloadLightboxImage() {
    const image = document.getElementById('lbImg');
    try {
        const response = await fetch(image.src);
        if (!response.ok) throw new Error('download failed');
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        const ext = (blob.type.split('/')[1] || 'png').replace('svg+xml', 'svg');
        link.href = url; link.download = 'architecture-diagram.' + ext;
        document.body.appendChild(link); link.click(); link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        showLightboxNotice('已下载');
    } catch (_) { showLightboxNotice('下载失败，请检查图片是否可访问', true); }
}
async function copyLightboxImage() {
    const image = document.getElementById('lbImg');
    try {
        const response = await fetch(image.src);
        if (!response.ok) throw new Error('image fetch failed');
        const blob = await response.blob();
        const png = blob.type === 'image/png' ? blob : await new Promise((resolve, reject) => {
            const canvas = document.createElement('canvas'); canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
            canvas.getContext('2d').drawImage(image, 0, 0);
            canvas.toBlob(value => value ? resolve(value) : reject(new Error('conversion failed')), 'image/png');
        });
        await navigator.clipboard.write([new ClipboardItem({ 'image/png': png })]);
        showLightboxNotice('已复制');
    } catch (_) { showLightboxNotice('复制失败，请改用下载', true); }
}
document.getElementById('lightboxStage').addEventListener('wheel', event => {
    if (!document.getElementById('lightbox').classList.contains('on')) return;
    event.preventDefault(); changeLightboxScale(event.deltaY < 0 ? 0.1 : -0.1);
}, { passive: false });
window.addEventListener('resize', () => { if (document.getElementById('lightbox').classList.contains('on')) fitLightboxImage(); });
function closeLightbox() {
    document.getElementById('lightbox').classList.remove('on');
    const notice = document.getElementById('lbNotice');
    if (notice) { notice.classList.remove('on', 'is-error'); notice.textContent = ''; }
    if (lightboxNoticeTimer) { clearTimeout(lightboxNoticeTimer); lightboxNoticeTimer = null; }
}
document.addEventListener('keydown', e => {
    if (e.key === 'Escape') { closeLightbox(); removeCtxMenu(); }
    if (document.getElementById('lightbox').classList.contains('on') && (e.ctrlKey || e.metaKey) && e.key === '+') changeLightboxScale(0.2);
    if (document.getElementById('lightbox').classList.contains('on') && (e.ctrlKey || e.metaKey) && e.key === '-') changeLightboxScale(-0.2);
});

