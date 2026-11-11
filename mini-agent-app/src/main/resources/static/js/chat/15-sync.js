/* =========================================================================
   BACKEND SYNC — 切会话时从后端拉取最新消息
   ========================================================================= */
let pollingTimers = {};  // sessionId -> intervalId

async function syncFromBackend(sid) {
    // Trigger a re-render of the conversation from backend data
    try {
        const resp = await fetch('/api/conversations/' + encodeURIComponent(sid));
        if (!resp.ok) return { synced: false };
        const conv = await apiJson(resp);
        if (!conv || !conv.messages || !conv.messages.length) return { synced: false };
        return { synced: true };
    } catch (e) {
        console.warn('syncFromBackend failed:', e);
        return { synced: false };
    }
}

async function checkAndPollTask(sid) {
    try {
        const resp = await fetch('/api/conversations/' + encodeURIComponent(sid) + '/task');
        if (!resp.ok) return;
        const status = await apiJson(resp) || {};
        if (status.running) {
            taskIsActive = true;
            taskRunningSessionId = sid;
            taskPauseRequested = status.pauseRequested === true;
            taskPausedAtCheckpoint = status.pausedAtCheckpoint === true;
            if (typeof syncTaskControlButton === 'function') syncTaskControlButton();
            // 任务正在运行 → 重连事件流，先回放已产出内容，再继续实时跟到结束
            await reconnectStream(sid);
        } else if (taskRunningSessionId === sid) {
            taskIsActive = false;
            taskRunningSessionId = null;
            taskPauseRequested = false;
            taskPausedAtCheckpoint = false;
            if (typeof syncTaskControlButton === 'function') syncTaskControlButton();
        }
    } catch (e) {
        console.warn('checkAndPollTask failed:', e);
    }
}

/**
 * 重连正在运行的会话事件流：GET .../messages/stream，复用 consumeStream 渲染。
 * 收到 gone（缓冲已驱逐）则回退轮询 + 数据库加载。
 */
async function reconnectStream(sid) {
    try {
        const resp = await fetch('/api/conversations/' + encodeURIComponent(sid) + '/messages/stream');
        if (!resp.ok || !resp.body) {
            unlockComposer();
            showTaskRunningHint(sid);
            startPolling(sid);
            return;
        }
        const epoch = ++streamEpoch;
        setStreaming(true);
        if (typeof refreshTaskPauseState === 'function') refreshTaskPauseState(sid);
        const botBody = renderBotMsg('<div class="typing-dots"><span></span><span></span><span></span></div>', true);
        await consumeStream(resp.body.getReader(), botBody, sid);
        if (isStreaming && epoch === streamEpoch) unlockComposer();
    } catch (e) {
        console.warn('reconnectStream failed:', e);
        unlockComposer();
        showTaskRunningHint(sid);
        startPolling(sid);
    }
}

/** 重连时若该 bot 行前面尚无用户气泡，补渲染一个（在该 bot 行之前插入）。 */
function renderUserEchoIfNeeded(botBody, text) {
    if (!text) return;
    const botRow = botBody.closest('.bot-row');
    // botRow 必须真的挂在 $msg 下才能当锚点（botRow 是 null 之外的同源隐患）
    if (!botRow || botRow.parentNode !== $msg) return;
    const prev = botRow.previousElementSibling;
    if (prev && prev.classList.contains('user-row')) return; // 已有用户气泡
    const row = document.createElement('div');
    row.className = 'msg-row user-row';
    row.innerHTML = '<div class="msg-inner"><div class="user-bubble">'
        + esc(text).replace(/\n/g, '<br>')
        + '</div><div class="user-avatar" aria-label="用户"><svg viewBox="0 0 24 24" aria-hidden="true">'
        + '<path d="M12 12a4.2 4.2 0 1 0 0-8.4A4.2 4.2 0 0 0 12 12Zm0 2.1c-4.3 0-7.8 2.2-7.8 4.9v1h15.6v-1c0-2.7-3.5-4.9-7.8-4.9Z"/>'
        + '</svg></div></div>';
    insertAt($msg, row, botRow);
}

function showTaskRunningHint(sid) {
    // 在消息区底部加一个 "正在处理..." 提示
    const existing = document.getElementById('task-running-hint');
    if (existing) return;
    const hint = document.createElement('div');
    hint.id = 'task-running-hint';
    hint.className = 'msg-row bot-row';
    hint.innerHTML = `<div class="msg-inner">
        <div class="bot-avatar" aria-label="MiniAgent"><svg viewBox="0 0 24 24" aria-hidden="true"><path class="ai-mark" d="M12 2.5 14.25 9.75 21.5 12l-7.25 2.25L12 21.5l-2.25-7.25L2.5 12l7.25-2.25L12 2.5Z"/><path d="M19.5 3.5v4M17.5 5.5h4M5 17v4M3 19h4"/></svg></div>
        <div class="bot-content">
            <div class="bot-body">
                <div class="typing-dots"><span></span><span></span><span></span></div>
                <span class="progress-hint">任务仍在运行中，请稍候...</span>
            </div>
        </div>
    </div>`;
    $msg.appendChild(hint);
    scrollToBottom();
}

function removeTaskRunningHint() {
    document.getElementById('task-running-hint')?.remove();
}

function startPolling(sid) {
    if (pollingTimers[sid]) return; // 已在轮询
    pollingTimers[sid] = setInterval(async () => {
        try {
            const resp = await fetch('/api/conversations/' + encodeURIComponent(sid) + '/task');
            const status = await apiJson(resp) || {};
            if (!status.running) {
                if (taskRunningSessionId === sid) {
                    taskIsActive = false;
                    taskRunningSessionId = null;
                    taskPauseRequested = false;
                    taskPausedAtCheckpoint = false;
                    if (typeof syncTaskControlButton === 'function') syncTaskControlButton();
                }
                stopPolling(sid);
                unlockComposer();
                if (sid === currentId) await switchConv(sid, true);
            }
        } catch (e) {
            console.warn('poll error:', e);
        }
    }, 10000);  // SSE 不可用时低频检查，限制页面与网络负载
}

function stopPolling(sid) {
    if (pollingTimers[sid]) {
        clearInterval(pollingTimers[sid]);
        delete pollingTimers[sid];
    }
}

