/* =========================================================================
   STREAMING STATE & MESSAGE INJECTION
   ========================================================================= */
/**
 * 安全插入：ref 必须是 parent 的子节点才能当锚点，否则退回 appendChild。
 *
 * 这是「ref 不是 parent 子节点」那类 DOMException 的唯一收口。此前每个 ensure* 各自
 * 写裸 insertBefore 且对锚点的归属做假设，panelEl 一旦被搬进 liveDock，
 * ensureTimeline 就会拿 dock 的兄弟当 bot-body 的锚点，整个流式渲染当场崩掉。
 */
function insertAt(parent, node, ref) {
    if (!parent || !node) return;
    if (ref && ref.parentNode === parent) parent.insertBefore(node, ref);
    else parent.appendChild(node);
}

/** One renderer for SSE and persisted turns. Only real DAG/todo items enter here. */
function compactTaskFailure(detail) {
    const text = String(detail || '');
    if (/stack=['"]|\n\s*at\s|Error\s*\{[\r\n]|playwright-java-|ElementHandleDispatcher\./.test(text)) {
        return '操作未能完成，详细原因请查看工具执行日志。若结果尚未确认，请先核验实际页面，避免重复操作。';
    }
    return text;
}

function renderTaskProgress(botBody, items, options = {}) {
    if (!botBody || !Array.isArray(items)) return null;
    let card = botBody.querySelector('.todo-panel');
    if (!items.length) { if (card) card.remove(); return null; }
    if (!card) {
        card = document.createElement('section');
        card.className = 'todo-panel';
        card.setAttribute('aria-label', '任务节点进度');
        const progress = document.createElement('div');
        progress.className = 'todo-progress-card';
        const top = document.createElement('div');
        top.className = 'todo-progress-top';
        for (const name of ['todo-progress-title', 'todo-progress-meta']) {
            const el = document.createElement(name.endsWith('meta') ? 'span' : 'div');
            el.className = name; top.appendChild(el);
        }
        const track = document.createElement('div');
        track.className = 'todo-progress-track';
        track.setAttribute('role', 'progressbar');
        track.setAttribute('aria-label', '已完成的任务节点');
        track.setAttribute('aria-valuemin', '0');
        track.setAttribute('aria-valuemax', '100');
        const fill = document.createElement('div');
        fill.className = 'todo-progress-fill';
        track.appendChild(fill); progress.append(top, track);
        const list = document.createElement('ol'); list.className = 'todo-list';
        card.append(progress, list);
    }
    // Move even an existing card: token/todo arrival order must never change layout.
    insertAt(botBody, card, botBody.querySelector('.answer-stream, .user-question-card, .permission-request-card, .think-panel'));
    const scheduled = items.filter(item => item.status !== 'cancelled');
    const completed = scheduled.filter(item => item.status === 'completed').length;
    const total = scheduled.length;
    const percent = total ? Math.round(completed * 100 / total) : 0;
    const live = options.live === true;
    const current = items.find(i => i.status === 'awaiting_confirm')
        || items.find(i => i.status === 'in_progress') || items.find(i => i.status === 'blocked')
        || items.find(i => i.status === 'pending');
    let heading, state;
    const needsReview = options.reason === 'OUTCOME_UNKNOWN';
    if (needsReview) {
        heading = '操作结果待核验，已停止自动重试'; state = 'blocked';
    } else if (options.waiting || options.status === 'WAITING') {
        heading = current ? '等待你的操作：' + current.content : '任务等待你的操作'; state = 'waiting';
    } else if (options.incomplete) {
        heading = '任务未完成，连接已中断'; state = 'blocked';
    } else if (options.failed || options.status === 'FAILURE' || options.status === 'FAILED') {
        heading = '任务执行失败'; state = 'blocked';
    } else if (options.status === 'CANCELED' || options.status === 'CANCELLED') {
        heading = '任务已取消'; state = 'idle';
    } else if (!total) {
        heading = '任务步骤已取消'; state = 'idle';
    } else if (completed === total) {
        heading = '所有任务节点已完成'; state = 'done';
    } else if (current && current.status === 'awaiting_confirm') {
        heading = '等待确认：' + current.content; state = 'waiting';
    } else if (current && current.status === 'blocked') {
        heading = '步骤受阻：' + current.content; state = 'blocked';
    } else if (live && current) {
        heading = (current.status === 'in_progress' ? '正在执行：' : '准备执行：') + current.content; state = 'running';
    } else {
        heading = options.cancelled ? '任务已停止' : '任务步骤待继续'; state = 'idle';
    }
    card.dataset.state = state;
    card.querySelector('.todo-progress-title').textContent = heading;
    card.querySelector('.todo-progress-meta').textContent = completed + '/' + total + ' 已完成';
    const fill = card.querySelector('.todo-progress-fill');
    fill.style.width = percent + '%';
    fill.parentElement.setAttribute('aria-valuenow', String(percent));
    const list = card.querySelector('.todo-list'); list.innerHTML = '';
    const labels = { completed: '已完成', in_progress: live ? '进行中' : '待继续', pending: '待执行',
        awaiting_confirm: '待确认', blocked: '受阻', cancelled: '已取消' };
    for (const item of items) {
        const status = item.status || 'pending';
        const li = document.createElement('li');
        li.className = 'todo-item ' + status + (!live && status === 'in_progress' ? ' idle' : '');
        li.dataset.nodeId = String(item.id);
        const mark = document.createElement('span'); mark.className = 'todo-mark';
        mark.setAttribute('aria-hidden', 'true');
        const text = document.createElement('span'); text.className = 'todo-text'; text.textContent = item.content || '';
        const label = document.createElement('span'); label.className = 'todo-node-state';
        label.textContent = needsReview && ['blocked', 'in_progress'].includes(status) ? '待核验'
            : !live && (options.failed || options.status === 'FAILURE') && status === 'in_progress' ? '已中断'
            : labels[status] || '待执行';
        li.append(mark, text, label);
        if (status === 'awaiting_confirm' && options.allowConfirm) {
            const button = document.createElement('button'); button.type = 'button';
            button.className = 'todo-confirm-btn'; button.textContent = '确认并继续'; button.disabled = live;
            button.onclick = function (event) { event.preventDefault(); event.stopPropagation(); confirmTodoAndContinue(item.id); };
            li.appendChild(button);
        }
        list.appendChild(li);
    }
    let reviewButton = card.querySelector('.outcome-review-open');
    if (needsReview && !live) {
        if (!reviewButton) {
            reviewButton = document.createElement('button'); reviewButton.type = 'button';
            reviewButton.className = 'outcome-review-open'; reviewButton.textContent = '核验实际结果';
            reviewButton.onclick = () => showOutcomeReview(botBody);
            card.appendChild(reviewButton);
        }
    } else if (reviewButton) reviewButton.remove();
    return card;
}

function restoreTaskProgress(botBody, task) {
    if (!botBody || !task) return;
    const row = botBody.closest('.bot-row');
    if (row && task.id != null) row.dataset.chatTaskId = String(task.id);
    const snapshot = task.progress;
    if (!snapshot) return;
    botBody.dataset.taskStatus = snapshot.status || '';
    botBody.dataset.executionId = snapshot.executionId || '';
    botBody.dataset.taskReason = snapshot.reason || '';
    renderTaskProgress(botBody, snapshot.items, { status: snapshot.status, reason: snapshot.reason, live: false, allowConfirm: false });
}

/** Human review is inline and never silently dispatches another click or submission. */
async function showOutcomeReview(botBody) {
    const sessionId = currentId;
    let panel = botBody.querySelector('.outcome-review');
    if (panel) return;
    panel = document.createElement('section'); panel.className = 'outcome-review';
    panel.setAttribute('aria-label', '核验操作结果'); botBody.appendChild(panel);
    const notice = document.createElement('p'); notice.textContent = '正在读取待核验步骤…'; panel.appendChild(notice);
    const offerReload = () => {
        if (panel.querySelector('.outcome-review-reload')) return;
        const reload = document.createElement('button'); reload.type = 'button';
        reload.className = 'outcome-review-reload'; reload.textContent = '重新读取任务状态';
        reload.onclick = () => { panel.remove(); showOutcomeReview(botBody); };
        panel.appendChild(reload);
    };
    try {
        const response = await fetch('/api/conversations/' + encodeURIComponent(sessionId) + '/outcome-review');
        const body = await response.json();
        if (currentId !== sessionId || !panel.isConnected) return;
        if (!response.ok || !body.success) throw new Error(body.message || '无法读取待核验步骤');
        const review = body.data;
        if (botBody.dataset.executionId && botBody.dataset.executionId !== review.executionId)
            throw new Error('这张卡片属于历史任务，请在当前任务中核验。');
        if (!review.nodes?.length) throw new Error('当前没有结果待核验的步骤，请刷新任务状态。');
        notice.textContent = '请先查看实际页面或业务回执。核验只处理原步骤状态，不会立即执行操作。确认已生效时仍须通过原验收条件，可能调用你配置的评判模型。';
        const form = document.createElement('form');
        const nodeLabel = document.createElement('label'); nodeLabel.textContent = '待核验步骤';
        const node = document.createElement('select');
        review.nodes.forEach(item => { const option = document.createElement('option'); option.value = item.id;
            option.textContent = item.name; node.appendChild(option); }); nodeLabel.appendChild(node);
        const decisionLabel = document.createElement('label'); decisionLabel.textContent = '实际核验结果';
        const decision = document.createElement('select');
        [['', '请选择核验结果'], ['CONFIRMED_APPLIED', '已确认操作生效，提交证据验收'],
            ['CONFIRMED_NOT_APPLIED', '已确认完全未生效，允许后续重新尝试']].forEach(([value, title]) => {
            const option = document.createElement('option'); option.value = value; option.textContent = title; decision.appendChild(option);
        }); decision.required = true; decisionLabel.appendChild(decision);
        const evidenceLabel = document.createElement('label'); evidenceLabel.textContent = '核验依据（回执编号、实际状态或已检查的文件）';
        const evidence = document.createElement('textarea'); evidence.required = true; evidence.maxLength = 8000;
        evidence.rows = 3; evidenceLabel.appendChild(evidence);
        const confirmLabel = document.createElement('label'); confirmLabel.className = 'outcome-review-confirm';
        const confirmed = document.createElement('input'); confirmed.type = 'checkbox'; confirmed.required = true;
        const confirmText = document.createElement('span'); confirmText.textContent = '我已检查实际结果；结果仍不确定时不提交核验';
        confirmLabel.append(confirmed, confirmText);
        const submit = document.createElement('button'); submit.type = 'submit'; submit.textContent = '提交核验';
        const status = document.createElement('p'); status.setAttribute('role', 'status');
        form.append(nodeLabel, decisionLabel, evidenceLabel, confirmLabel, submit, status); panel.appendChild(form);
        form.onsubmit = async event => {
            event.preventDefault();
            if (!confirmed.checked || !decision.value || !evidence.value.trim() || currentId !== sessionId) return;
            submit.disabled = true; status.textContent = '正在验收核验依据…';
            try {
                const result = await fetch('/api/conversations/' + encodeURIComponent(sessionId) + '/outcome-review', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ executionId: review.executionId, version: review.version,
                        planVersion: review.planVersion, nodeId: node.value, decision: decision.value,
                        evidence: evidence.value.trim(), confirmed: true })
                });
                const resolved = await result.json();
                if (!result.ok || !resolved.success) throw new Error(resolved.message || '核验未通过');
                if (currentId !== sessionId || !panel.isConnected) return;
                const progress = resolved.data.progress;
                renderTaskProgress(botBody, progress.items, { status: progress.status, reason: progress.reason, live: false });
                form.remove();
                if (resolved.data.nodes?.length) {
                    notice.textContent = '核验已记录。原任务还有操作结果待核验，请继续检查其他步骤。';
                    offerReload();
                } else notice.textContent = '核验已记录。请在输入框发送“继续”，从原任务的未完成步骤继续执行。';
                botBody.dataset.taskReason = progress.reason || '';
            } catch (error) {
                status.textContent = compactTaskFailure(error.message); submit.disabled = false; offerReload();
            }
        };
    } catch (error) { notice.textContent = compactTaskFailure(error.message); offerReload(); }
}

/**
 * 流结束后的收尾。
 *
 * think-panel 的归属固定在 bot-body，不再在 liveDock 与消息区之间搬运：
 * 跨容器搬运是「归属动态变化」的根源，读它的人必须同时知道它此刻在哪，
 * 而所有插入点都按「它在 bot-body」写死了。这里只清掉历史上被搬进 dock 的残留面板。
 */
function parkLiveThink() {
    const dock = document.getElementById('liveDock');
    if (!dock) return;
    dock.querySelectorAll('.think-panel').forEach(function (p) { p.remove(); });
}

function setStreaming(on) {
    // 起算点只打一次：从「非流式」切进「流式」时才重置，
    // 这样中途重连（再次进 consumeStream）不会把计时清零。
    if (on && !isStreaming) { turnStartedAt = Date.now(); turnFinishedAt = 0; }
    isStreaming = on;
    if (on && (!taskIsActive || taskRunningSessionId !== currentId)) {
        taskIsActive = true;
        taskRunningSessionId = currentId;
        taskPauseRequested = false;
        taskPausedAtCheckpoint = false;
    }
    if (!on) {
        if (taskPausePollTimer) clearInterval(taskPausePollTimer);
        taskPausePollTimer = null;
    }
    if (typeof syncTaskControlButton === 'function') syncTaskControlButton();
    // 计划卡片只在收到 todo 事件时重绘；一轮结束时不会自己刷新，
    // 不主动降下来就会一直显示成「进行中」。
    if (typeof refreshTodoIdleState === 'function') refreshTodoIdleState();
    if (!on && thinkTickTimer) {
        // 兜底：取消/断流路径不走 finish()，秒表不能继续空转
        clearInterval(thinkTickTimer);
        thinkTickTimer = null;
    }
    $send.title = on ? '追加说明 (Enter)' : '发送 (Enter)';
    if (!on) parkLiveThink();
    const dock = document.getElementById('liveDock');
    if (dock) {
        dock.hidden = !on;
        if (on) setLiveStatus('思考中');
    }
    onInputChange();
    refreshConfirmBanner();
    if (typeof syncConversationHeader === 'function') syncConversationHeader();
}

function syncTaskControlButton() {
    const button = document.getElementById('taskPauseBtn');
    const label = document.getElementById('taskPauseLabel');
    if (!button) return;
    button.hidden = !(taskIsActive && currentId && taskRunningSessionId === currentId);
    button.classList.toggle('is-paused', taskPauseRequested);
    if (taskPauseRequested && taskPausedAtCheckpoint) {
        if (label) label.textContent = '继续任务';
        button.title = '任务已暂停，点击继续执行';
    } else if (taskPauseRequested) {
        if (label) label.textContent = '正在暂停';
        button.title = '将在当前模型或工具调用结束后暂停；点击可继续';
    } else {
        if (label) label.textContent = '暂停任务';
        button.title = '暂停当前任务';
    }
}

function setLiveStatus(text) {
    const el = document.getElementById('liveStatus');
    if (el && el.textContent !== text) el.textContent = text;
}

/**
 * 时长文案。口径沿用 WorkBuddy 的 bln()：
 * 满 1 小时 →「Nh Nm」，满 1 分钟 →「Nm Ss」，否则 →「Ns」。
 */
function fmtDuration(ms) {
    if (!(ms > 0)) return '0s';
    const total = Math.floor(ms / 1000);
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    if (h > 0) return m > 0 ? h + 'h' + m + 'm' : h + 'h';
    if (m > 0) return s > 0 ? m + 'm' + s + 's' : m + 'm';
    return s + 's';
}

function paintContext(used, limit) {
    if (!limit || limit <= 0) return;
    const ring = document.getElementById('ctxRing');
    const arc = document.getElementById('ctxArc');
    const tip = document.getElementById('ctxTip');
    if (!ring || !arc || !tip) return;
    const pct = Math.max(0, Math.min(100, Math.round(used * 100 / limit)));
    const circ = 2 * Math.PI * 8;
    arc.setAttribute('stroke-dasharray', (circ * pct / 100).toFixed(2) + ' ' + circ.toFixed(2));
    const fmt = function (n) {
        return n >= 1000 ? Math.round(n / 1000) + 'K' : String(n);
    };
    tip.innerHTML = '<div class="ctx-pct">' + pct + '% 上下文已用</div>'
        + '<div class="ctx-num">' + fmt(used) + ' / ' + fmt(limit) + '</div>';
    ring.hidden = false;
}

function unlockComposer() {
    setStreaming(false);
    removeTaskRunningHint();
}

/**
 * 追加消息（流式期间用户补充）。
 *
 * 这是**乐观更新**：先把消息画到界面上、再发请求。所以投递结果必须回写到那条消息下面 ——
 * 否则失败时界面上留下一条和成功一模一样的消息，用户只能猜「它到底进没进上下文」。
 *
 * 另外「成功」只代表**入队成功**。真正的注入发生在 AgentLoop 的轮次边界
 * （`takePendingUserMessages`，每轮开头取一次），若当前那一轮 LLM 调用很久，
 * 生效可能晚上几分钟（实测有一条等了 5 分半）。所以提示语要说清「当前步骤结束后生效」，
 * 不能让用户以为已经生效了。
 */
async function appendUserMessage(text, sessionId, questionCard) {
    beginUserQuestionAnswer(questionCard);
    const row = renderUserMsg(text, null, 0, [], true);
    const status = setInjectStatus(row, 'sending', '发送中…');
    $inp.value = '';
    $inp.style.height = 'auto';
    onInputChange();
    scrollToBottom(false, true);
    try {
        const resp = await fetch(
            '/api/conversations/' + encodeURIComponent(sessionId) + '/messages', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: text })
        });
        const data = await resp.json().catch(() => null);
        if (data && data.success) {
            setInjectStatus(status, 'ok', '已加入，将在当前步骤结束后生效');
            if (questionCard) markUserQuestionAnswered(text, questionCard);
        } else {
            const why = (data && (data.message || data.error)) || ('HTTP ' + resp.status);
            setInjectStatus(status, 'failed', '未送达：' + why + '。请直接重新发送。');
            resetUserQuestionCard(questionCard, '回复未送达，请重试。');
        }
    } catch (e) {
        setInjectStatus(status, 'failed', '未送达（网络异常）。请直接重新发送。');
        resetUserQuestionCard(questionCard, '回复未送达，请检查网络后重试。');
    }
}

/** 在一条用户消息下方挂投递状态；已有则原地改写。返回该元素以便后续更新。 */
function setInjectStatus(row, cls, text) {
    if (!row || !row.appendChild) return null;
    // 调用方会把上一次返回的状态元素再传回来，此时它自己就是状态行。
    // 早先靠「querySelector 找不到就新建、随后又被 textContent 覆盖掉」的巧合才没出错，太脆。
    if (row.classList && row.classList.contains('inject-status')) {
        row.className = 'inject-status ' + cls;
        row.textContent = text;
        return row;
    }
    let el = row.querySelector('.inject-status');
    if (!el) {
        el = document.createElement('div');
        // 挂在 .user-row 上（不是 .msg-inner：那是 display:flex 的单行容器，
        // 塞进去会变成和气泡并排的第二个 flex 项）。位置由下面的 CSS 收进内容列。
        row.appendChild(el);
    }
    el.className = 'inject-status ' + cls;
    el.textContent = text;
    return el;
}

