/* =========================================================================
   NEW CHAT / SWITCH CONVERSATION
   ========================================================================= */
function startNewChat(doFocus = true) {
    if (currentId) stopPolling(currentId);
    removeTaskRunningHint();
    pendingTodoConfirm = null;
    setStreaming(false);
    currentId = null;
    localStorage.removeItem(LS_CUR);
    resetExecPolicyView();
    paintWorkspaceView(null);
    closeWsMenu();
    $msg.innerHTML = WELCOME;
    renderSidebar();
    if (doFocus) $inp.focus();
}

async function switchConv(id, force) {
    if (id === currentId && !force) return;
    if (currentId && currentId !== id) stopPolling(currentId);
    pendingTodoConfirm = null;
    setStreaming(false);
    currentId = id;
    localStorage.setItem(LS_CUR, id);
    removeTaskRunningHint();

    // Reset pagination state
    window._msgPage = 0;
    window._msgHasMore = true;
    window._msgLoading = false;

    $msg.innerHTML = '';
    await loadMoreMessages(id, false);
    checkAndPollTask(id);
    renderSidebar();
    // 工作目录是会话级状态，切会话必须重新取，否则会把上一个会话的目录显示成当前目录
    loadWorkspace();
    // 将会话权限模式与本地选择对齐（并刷新 Plan banner）
    if (permissionMode) {
        try {
            const pResp = await fetch(permissionUrl(), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    sessionId: currentId,
                    mode: permissionMode,
                    confirmPolicy: confirmPolicy
                })
            });
            // 这次 PUT 不回传 execPolicy，所以不会动会话里的执行策略覆盖；
            // 顺手拿响应里的权限视图刷新 exec 按钮，省一次 GET。
            const pData = await readApiBody(pResp);
            if (apiSuccess(pData) && pData.data) applyExecPolicyView(pData.data);
            else await refreshExecPolicyView();
        } catch (_) {
            await refreshExecPolicyView();
        }
    } else {
        await refreshExecPolicyView();
    }
    refreshPlanBanner();
}
async function loadMoreMessages(sessionId, prepend) {
    if (window._msgLoading || !window._msgHasMore) return;
    window._msgLoading = true;

    try {
        const page = window._msgPage || 0;
        const resp = await fetch('/api/conversations/' + encodeURIComponent(sessionId)
            + '/messages?page=' + page + '&size=20');
        if (!resp.ok) { window._msgLoading = false; return; }
        const data = await apiJson(resp) || {};

        window._msgHasMore = data.hasMore;
        window._msgPage = page + 1;

        const rawTasks = data.tasks || [];
        // Convert tasks to messages format: question→user, answer→assistant
        const messages = [];
        rawTasks.forEach(t => {
            const imgs = Array.isArray(t.images) ? t.images.filter(Boolean) : [];
            if (t.question || imgs.length)
                messages.push({ role: 'user', content: t.question || '', images: imgs });
            if (t.answer) messages.push({ role: 'assistant', content: t.answer });
        });

        if (prepend) {
            // Save scroll position
            const oldHeight = $msg.scrollHeight;
            const oldScroll = $msg.scrollTop;

            // Prepend messages before existing ones
            const frag = document.createDocumentFragment();
            messages.forEach(m => {
                if (m.role === 'user') {
                    frag.appendChild(buildUserRow(m.content || '', m.images || []));
                } else {
                    frag.appendChild(buildBotRow(m.content || ''));
                }
            });
            insertAt($msg, frag, $msg.firstChild);

            // Maintain scroll position
            $msg.scrollTop = oldScroll + ($msg.scrollHeight - oldHeight);
        } else {
            // Initial load: render normally
            messages.forEach(m => {
                if (m.role === 'user') {
                    const imgs = m.images || [];
                    renderUserMsg(m.content || '', imgs[0] || null, imgs.length, []);
                } else {
                    const bd = renderBotMsg(renderMD(m.content || ''), false);
                    bindLightbox(bd.closest('.bot-row'));
                }
            });
            scrollToBottom(false, true);
        }
    } catch (e) {
        console.warn('Failed to load messages:', e);
    }
    window._msgLoading = false;
}

function buildUserRow(text, images = []) {
    const row = document.createElement('div');
    row.className = 'msg-row user-row';
    row.dataset.task = text || '';   // 同上，历史 prepend 路径
    const imgs = Array.isArray(images) ? images.filter(Boolean) : [];
    const imgHtml = imgs[0]
        ? `<img src="${escA(imgs[0])}" alt="上传图片">`
            + (imgs.length > 1
                ? `<div style="font-size:11px;color:var(--text2);margin-top:4px">附 ${imgs.length} 张图片</div>`
                : '')
        : '';
    const textHtml = text ? esc(text).replace(/\n/g, '<br>') : '';
    row.innerHTML = `<div class="msg-inner"><div class="user-bubble">${imgHtml}${textHtml}</div></div>`;
    return row;
}

function buildBotRow(text) {
    const row = document.createElement('div');
    row.className = 'msg-row bot-row';
    const bid = 'bb_' + Date.now() + '_' + Math.random().toString(36).slice(2,5);
    row.innerHTML = '<div class="msg-inner"><div class="bot-avatar">🤖</div><div class="bot-content"><div class="bot-body" id="' + bid + '">' + renderMD(text) + '</div><div class="bot-actions"><button class="ba-btn" onclick="copyBotMsg(this)" title="复制"><svg fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>复制</button></div></div></div>';
    return row;
}

function renderConvMessages(id) {
    const conv = getConv(id);
    $msg.innerHTML = '';
    if (!conv || !conv.messages?.length) {
        $msg.innerHTML = WELCOME;
    } else {
        conv.messages.forEach(m => {
            if (m.role === 'user') renderUserMsg(m.text || '', m.imgSrc || null);
            else {
                const bd = renderBotMsg(m.html || renderMD(m.text || ''), false);
                bindLightbox(bd.closest('.bot-row'));
            }
        });
    }
    scrollToBottom(false, true);
}

