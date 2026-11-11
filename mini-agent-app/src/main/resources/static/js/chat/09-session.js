/* =========================================================================
   NEW CHAT / SWITCH CONVERSATION
   ========================================================================= */
function startNewChat(doFocus = true) {
    if (currentId) stopPolling(currentId);
    removeTaskRunningHint();
    pendingTodoConfirm = null;
    setStreaming(false);
    currentId = null;
    if (typeof syncTaskControlButton === 'function') syncTaskControlButton();
    if (typeof syncConversationHeader === 'function') syncConversationHeader();
    localStorage.removeItem(LS_CUR);
    resetExecPolicyView();
    paintWorkspaceView(null);
    closeWsMenu();
    $msg.innerHTML = WELCOME;
    renderSidebar();
    // 右侧执行详情是会话级视图：新会话等于空 execution，必须重算，
    // 否则会继续显示上一个会话的步骤。14a-inspector.js 后加载，用 typeof 兜住。
    if (typeof scheduleInspectorRefresh === 'function') scheduleInspectorRefresh();
    if (doFocus) $inp.focus();
}

async function switchConv(id, force) {
    if (id === currentId && !force) return;
    if (currentId && currentId !== id) stopPolling(currentId);
    pendingTodoConfirm = null;
    setStreaming(false);
    currentId = id;
    if (typeof syncTaskControlButton === 'function') syncTaskControlButton();
    if (typeof syncConversationHeader === 'function') syncConversationHeader();
    localStorage.setItem(LS_CUR, id);
    removeTaskRunningHint();

    // Reset pagination state
    window._msgPage = 0;
    window._msgHasMore = true;
    window._msgLoading = false;

    releaseAttachmentObjectUrls($msg);
    $msg.innerHTML = '';
    await loadMoreMessages(id, false);
    checkAndPollTask(id);
    renderSidebar();
    // 同上：步骤、工具、记忆三块都跟着会话走，切会话必须换数据源
    if (typeof scheduleInspectorRefresh === 'function') scheduleInspectorRefresh();
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
                    renderUserMsg(m.content || '', null, 0, [], false,
                        imgs.map(src => ({ src, name: attachmentNameFromSource(src) })));
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
    const textHtml = text ? esc(text).replace(/\n/g, '<br>') : '';
    row.innerHTML = `<div class="msg-inner"><div class="user-bubble">${textHtml}</div><div class="user-avatar" aria-label="用户"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 12a4.2 4.2 0 1 0 0-8.4A4.2 4.2 0 0 0 12 12Zm0 2.1c-4.3 0-7.8 2.2-7.8 4.9v1h15.6v-1c0-2.7-3.5-4.9-7.8-4.9Z"/></svg></div></div>`;
    if (imgs.length) {
        const grid = document.createElement('div');
        grid.className = 'attachment-preview-grid';
        imgs.forEach(src => grid.appendChild(createAttachmentPreview(src)));
        row.querySelector('.user-bubble').insertBefore(grid, row.querySelector('.user-bubble').firstChild);
    }
    bindLightbox(row);
    hydrateProtectedMedia(row);
    return row;
}

function buildBotRow(text) {
    const row = document.createElement('div');
    row.className = 'msg-row bot-row';
    const bid = 'bb_' + Date.now() + '_' + Math.random().toString(36).slice(2,5);
    row.innerHTML = '<div class="msg-inner"><div class="bot-avatar" aria-label="MiniAgent"><svg viewBox="0 0 24 24" aria-hidden="true"><path class="ai-mark" d="M12 2.5 14.25 9.75 21.5 12l-7.25 2.25L12 21.5l-2.25-7.25L2.5 12l7.25-2.25L12 2.5Z"/><path d="M19.5 3.5v4M17.5 5.5h4M5 17v4M3 19h4"/></svg></div><div class="bot-content"><div class="bot-body" id="' + bid + '"><div class="answer-stream md-body has-content">' + renderMD(text) + '</div></div><div class="bot-actions"><button class="ba-btn" onclick="copyBotMsg(this)" title="复制"><svg fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>复制</button></div></div></div>';
    return row;
}

function renderConvMessages(id) {
    const conv = getConv(id);
    releaseAttachmentObjectUrls($msg);
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

