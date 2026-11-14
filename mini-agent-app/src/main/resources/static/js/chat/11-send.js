/* =========================================================================
   SEND MESSAGE
   ========================================================================= */
async function send() {
    const text = $inp.value.trim();
    const questionCard = activeUserQuestionCard && activeUserQuestionCard.isConnected
            && activeUserQuestionCard.dataset.sessionId === currentId
        ? activeUserQuestionCard : null;
    if (!text && selectedFiles.length === 0) return;
    if (uploadingCount > 0) { alert('文件上传中，请等待'); return; }
    // 发出任何消息（包括批准）都算离开「待确认」状态：确认条只是当轮的动作入口。
    const planCard = document.getElementById('planConfirm');
    if (planCard) planCard.classList.remove('show');

    // 执行中追加模式：agent 正在运行时，发送追加消息
    if (isStreaming && currentId && text) {
        if (questionCard && selectedFiles.length === 0) {
            answerUserQuestion(text, questionCard);
        } else {
            appendUserMessage(text, currentId, questionCard);
        }
        return;
    }
    if (isStreaming) return;

    // 会话 id 由服务端签发：已有会话直接用，首次发言先建一条。
    // 放在动 UI 之前 —— 建会话失败时不该已经把输入框清空了。
    let sid;
    try {
        sid = await ensureSessionId();
    } catch (err) {
        console.error('[Send] ensure session failed', err);
        alert('创建会话失败，请稍后重试');
        return;
    }

    // Hide welcome
    document.getElementById('welcomeScreen')?.remove();

    const imgSrcs = previewSrcs.slice();
    const attachments = selectedFiles.map((file, index) => ({
        name: file.name,
        type: file.type,
        size: file.size,
        src: file.type.startsWith('image/')
            ? (imgSrcs[index] || '')
            : URL.createObjectURL(file)
    }));
    renderUserMsg(text, null, 0, [], false, attachments);
    addMsgToConv('user', text, null, imgSrcs[0] || null);

    const filesCopy = selectedFiles.slice();
    const refsCopy = uploadedFileRefs.filter(r => r);
    removeImage();
    $inp.value = '';
    $inp.style.height = 'auto';
    onInputChange();
    scrollToBottom(false, true);

    // Bot placeholder
    const botBody = renderBotMsg('<div class="typing-dots"><span></span><span></span><span></span></div>', true);
    setStreaming(true);

    // POST JSON 交互 — 替代 SSE 流式
    sendChatPost(text, sid, filesCopy, refsCopy, botBody, questionCard);
}

/** 把非 2xx 响应正文带回聊天区，便于分辨应用限流和网关/服务端错误。 */
async function chatHttpErrorMessage(resp) {
    let raw = '';
    try { raw = await resp.text(); } catch (_) { /* 状态码仍足以说明失败 */ }
    let detail = '';
    const contentType = (resp.headers.get('Content-Type') || '').toLowerCase();
    if (raw && contentType.includes('json')) {
        try {
            const body = JSON.parse(raw);
            const value = body && (body.message || body.error_description || body.error || body.detail);
            detail = typeof value === 'string' ? value
                : value && typeof value === 'object' ? (value.message || JSON.stringify(value)) : '';
        } catch (_) { /* malformed JSON; show the bounded plain response below */ }
    } else if (raw && !/^\s*</.test(raw)) {
        detail = raw.trim();
    }
    if (/^rate limit exceeded$/i.test(detail)) {
        detail = resp.headers.get('X-RateLimit-Source') === 'miniagent-api'
            ? '本地接口请求过于频繁，请稍后重试。'
            : '请求过于频繁，请稍后重试。';
    } else if (!detail && resp.status === 429) {
        detail = '请求过于频繁，请稍后重试。';
    }
    detail = String(detail || '').replace(/[\r\n\t]+/g, ' ').trim().slice(0, 320);
    const retryAfter = Number(resp.headers.get('Retry-After'));
    const retryHint = Number.isFinite(retryAfter) && retryAfter > 0
        ? '（建议 ' + Math.ceil(retryAfter) + ' 秒后重试）' : '';
    return '❌ HTTP ' + resp.status + (detail ? '：' + detail : '') + retryHint;
}

/** POST /api/conversations/{id}/messages/stream，读 SSE 流式响应 */
async function sendChatPost(text, sessionId, files, refs, botBody, questionCard) {
    const epoch = ++streamEpoch;
    const loadEpoch = window._msgLoadEpoch;
    const isCurrentSend = () => sessionId === currentId
        && loadEpoch === window._msgLoadEpoch && epoch === streamEpoch;
    beginUserQuestionAnswer(questionCard);
    try {
        let images = [];
        let fileAttachments = [];
        const allRefs = refs || [];
        const mediaRefs = allRefs.filter(r => r && (r.kind === 'audio' || r.kind === 'video'));
        const fileRefs = allRefs.filter(r => r && r.kind !== 'audio' && r.kind !== 'video');
        if (files && files.length) {
            for (const f of files) {
                if (f.type.startsWith('image/')) {
                    const b64 = await fileToDataUrl(f);
                    images.push(b64);
                }
            }
        }

        const body = {
            message: text || '',
            sessionId: sessionId,
            images: images,
            files: fileAttachments,
            fileRefs: fileRefs,
            mediaRefs: mediaRefs,
            role: selectedRole,
            permissionMode: permissionMode || 'default',
            confirmPolicy: confirmPolicy || 'dangerous'
        };
        const resp = await fetch(
            '/api/conversations/' + encodeURIComponent(sessionId) + '/messages/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });

        if (!isCurrentSend()) {
            if (resp.body) await resp.body.cancel().catch(() => {});
            return;
        }
        if (!resp.ok || !resp.body) {
            const message = await chatHttpErrorMessage(resp);
            if (!isCurrentSend()) return;
            botBody.textContent = message;
            botBody.classList.remove('streaming');
            resetUserQuestionCard(questionCard, '回复未发送，请检查原因后重试。');
            if (isCurrentSend()) {
                setStreaming(false); onInputChange(); scrollToBottom();
            }
            return;
        }

        if (questionCard) markUserQuestionAnswered(text, questionCard);

        // 读 SSE 流
        await consumeStream(resp.body.getReader(), botBody, sessionId);
        if (isStreaming && isCurrentSend()) unlockComposer();
    } catch (err) {
        if (!isCurrentSend()) return;
        botBody.innerHTML = renderMD('❌ ' + err.message);
        botBody.classList.remove('streaming');
        resetUserQuestionCard(questionCard, '回复未发送，请检查网络后重试。');
        if (isCurrentSend()) {
            setStreaming(false); onInputChange(); scrollToBottom();
        }
    }
}

/** 复用的 SSE 渲染：首次发送与刷新重连共用同一套增量渲染逻辑 */
const MAX_STREAM_RECONNECTS = 3;  // 单次发送内流意外中断后最多自动重连次数
async function consumeStream(reader, botBody, sessionId, reconnectAttempt = 0) {
  const loadEpoch = window._msgLoadEpoch;
  const activeEpoch = streamEpoch;
  const isCurrentStream = () => sessionId === currentId
      && loadEpoch === window._msgLoadEpoch && activeEpoch === streamEpoch;
  try {
        const decoder = new TextDecoder('utf-8');
        let buffer = '', answer = '', done = false;
        let thinkText = '', thinkCollapsed = true;
        let waitingOnQuestion = false;
        let waitingOnPermission = false, waitingOnPlanApproval = false;
        // Approval is settled in 07-permission.js, outside this stream closure.
        // pendingAskTool is cleared there on approve/deny/expiry; the local flag alone
        // would stay true and discard every subsequent token and the final answer.
        const waitingForInteraction = () => waitingOnQuestion
            || (waitingOnPermission && !!pendingAskTool) || waitingOnPlanApproval;
        // 本 turn 是否由失败/取消收尾，只影响折叠条的「已取消 / 已完成」用词
        let turnCancelled = false;
        let turnIncomplete = false;
        let turnFailed = false, turnWaiting = false;
        let taskOutcomeStatus = null, taskOutcomeReason = '';
        const MAX_THINK_CHARS = 12000;
        let thinkPaintTimer = null;
        // 增量渲染的 DOM 引用与已渲染游标（避免每个 token 重建整棵子树导致闪烁）
        let cleared = false, panelEl = null, headLabelEl = null, thinkTextEl = null,
            timelineEl = null, answerEl = null, answerTailEl = null;
        let renderedThink = '', renderedAnswer = '', appliedCollapsed = null;
        // 流式正文的两个渲染游标：定稿块（frozen）与末尾未闭合块（tail）
        let renderedFrozen = '', renderedTail = '';
        let answerPaintTimer = null;
        // Only real decomposed task nodes produce a progress card. Model turns and
        // free-form subgoal messages do not imply a measurable task percentage.
        let todoItems = null;
        let todoEl = null;
        function clearOnce() { if (!cleared) { botBody.innerHTML = ''; cleared = true; } }
        function firstInteractionCard() {
            return botBody.querySelector('.user-question-card, .permission-request-card');
        }
        function schedulePaintAnswer() {
            if (answerPaintTimer) return;
            answerPaintTimer = setTimeout(() => {
                answerPaintTimer = null;
                paintAnswerNow();
            }, 50);
        }

        /**
         * 流式正文的增量绘制：**已定稿的块只追加一次，只重画末尾那个还没闭合的块**。
         *
         * <h3>为什么不能每次 `innerHTML = renderMDStreaming(answer)`</h3>
         *
         * 那样每 50ms 重建整棵子树。回答里一旦有 `<img>`，每次重建都会销毁旧节点、造一个新的，
         * 新节点在图片解码完成前没有尺寸、解码后又撑开 —— 正文随之上下来回跳。
         * 2026-10-22 实测症状就是「生成时回复字体乱上下跳动」，且只在回答带图时出现。
         *
         * 现在按空行切：最后一个空行之前的内容是定稿块，追加后不再碰；
         * 只有最后一个未闭合块（通常正在打字的那一段）会重画。整段重画只在
         * 前缀被改写时发生（重连后从头来过），且只发生一次。
         */
        function paintAnswerNow() {
            if (!isCurrentStream()) return;
            removeTyping();
            ensureAnswer();
            answerEl.classList.toggle('has-content', !!answer.trim());
            if (typeof setMarkdownContent === 'function' && answerEl.querySelector('audio[data-media-key],video[data-media-key]')) {
                setMarkdownContent(answerEl, renderMDStreaming(dropLeadBlanks(answer)));
                answerTailEl = null;
                renderedFrozen = '';
                renderedTail = '';
                renderedAnswer = answer;
                scrollToBottom();
                return;
            }
            // 自愈：正文容器被整段替换过（finish / 历史回放）时 .md-tail 已经脱离 DOM，
            // 此时游标全部作废，重建结构后从头画。
            if (!answerTailEl || !answerTailEl.isConnected) {
                answerEl.innerHTML = '';
                answerTailEl = document.createElement('div');
                answerTailEl.className = 'md-tail';
                answerEl.appendChild(answerTailEl);
                renderedFrozen = '';
                renderedTail = '';
            }
            const split = splitStreamingMarkdown(answer);
            if (split.frozen !== renderedFrozen) {
                if (split.frozen.startsWith(renderedFrozen)) {
                    const delta = split.frozen.slice(renderedFrozen.length);
                    if (delta) answerTailEl.insertAdjacentHTML('beforebegin', renderMD(dropLeadBlanks(delta)));
                } else {
                    answerEl.innerHTML = '';
                    answerTailEl = document.createElement('div');
                    answerTailEl.className = 'md-tail';
                    answerEl.appendChild(answerTailEl);
                    if (split.frozen) {
                        answerTailEl.insertAdjacentHTML('beforebegin', renderMD(dropLeadBlanks(split.frozen)));
                    }
                    renderedTail = '';
                }
                renderedFrozen = split.frozen;
            }
            if (split.tail !== renderedTail) {
                answerTailEl.innerHTML = renderMDStreaming(dropLeadBlanks(split.tail));
                renderedTail = split.tail;
            }
            renderedAnswer = answer;
            scrollToBottom();
        }

        function escTodo(s) {
            return String(s == null ? '' : s)
                .replace(/&/g, '&amp;').replace(/</g, '&lt;')
                .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
        }

        // 思考不按 step 归组，也不进「任务计划」卡片 —— 见 .think-panel 的注释。

        function ensureTimeline() {
            if (timelineEl) return;
            clearOnce();
            timelineEl = document.createElement('div');
            timelineEl.className = 'tl-stream';
            // 位次不变式：panelEl 永远是 bot-body 的最后一个子节点，其余元素插到它前面。
            // 顺序：时间线 → 答案 → 思考（思考钉底，对齐 WorkBuddy）。工具过程行已下线。
            // ref 必须落在 botBody 里，否则 insertAt 会静默降级成 append 跑到思考后面去。
            insertAt(botBody, timelineEl, todoEl || answerEl || firstInteractionCard() || panelEl);
        }
        /**
         * 清空正文容器，并**按固定结构重建** .md-tail。
         *
         * <p>不能只写 `answerEl.innerHTML = ''`：那会把 answerTailEl 一起清掉，
         * 而 renderedFrozen / renderedTail 还是旧值。下一段正文要么插进已脱离 DOM 的节点（静默丢内容），
         * 要么因为 `split.tail === renderedTail` 判定「没变化」而完全不画。
         */
        function resetAnswerDom() {
            renderedAnswer = '';
            renderedFrozen = '';
            renderedTail = '';
            if (answerPaintTimer) {
                clearTimeout(answerPaintTimer);
                answerPaintTimer = null;
            }
            if (!answerEl) return;
            answerEl.innerHTML = '';
            answerEl.classList.remove('has-content');
            answerTailEl = document.createElement('div');
            answerTailEl.className = 'md-tail';
            answerEl.appendChild(answerTailEl);
        }
        /** 工具调用或重试会撤销这一轮草稿；只保留任务卡，不把模型的过程文案堆进聊天记录。 */
        function sealAnswerToTimeline() {
            answer = '';
            resetAnswerDom();
            thinkCollapsed = true;
            if (panelEl) panelEl.classList.add('collapsed');
        }
        function finish(html, cancelled, incomplete) {
            if (!isCurrentStream()) return;
            if (done) return; done = true;
            turnWaiting = taskOutcomeStatus === 'WAITING';
            turnCancelled = taskOutcomeStatus === 'CANCELED' || (!taskOutcomeStatus && !!cancelled);
            turnIncomplete = !!incomplete;
            turnFailed = taskOutcomeStatus === 'FAILURE';
            botBody.dataset.taskStatus = taskOutcomeStatus
                || (turnCancelled ? 'CANCELED' : turnIncomplete ? 'INCOMPLETE' : 'SUCCESS');
            if (taskOutcomeReason) botBody.dataset.taskReason = taskOutcomeReason;
            if (taskRunningSessionId === sessionId) {
                taskIsActive = false;
                taskRunningSessionId = null;
                taskPauseRequested = false;
                taskPausedAtCheckpoint = false;
            }
            if (!turnWaiting) {
                waitingOnQuestion = false;
                waitingOnPermission = false;
                waitingOnPlanApproval = false;
            }
            if (!turnWaiting && typeof settlePendingAskOnTerminal === 'function') {
                settlePendingAskOnTerminal('任务已结束，授权请求已失效。');
            }
            turnFinishedAt = Date.now();
            if (thinkTickTimer) {
                clearInterval(thinkTickTimer);
                thinkTickTimer = null;
            }
            if (answerPaintTimer) {
                clearTimeout(answerPaintTimer);
                answerPaintTimer = null;
            }
            botBody.classList.remove('streaming');
            // 保留助手气泡里的执行卡片、思考与时间线，只定稿答案区域
            ensureAnswer();
            if (typeof setMarkdownContent === 'function') setMarkdownContent(answerEl, html);
            else answerEl.innerHTML = html;
            // Errors and stop notices may have no streamed answer, but their
            // terminal content still needs to be visible after removing status cards.
            answerEl.classList.toggle('has-content', !!String(html || '').trim());
            // 定稿后正文结构由 html 决定，不再有 .md-tail；游标一并作废
            answerTailEl = null;
            renderedAnswer = answer;
            renderedFrozen = '';
            renderedTail = '';
            if (panelEl) {
                // 定稿后收起，并重新钉到气泡最底（时间线 → 正文 → 思考）
                insertAt(botBody, panelEl, null);
                panelEl.classList.add('done', 'collapsed');
                thinkCollapsed = true;
                appliedCollapsed = true;
                paintThinkHead();
            }
            setStreaming(false);
            onInputChange();
            if (todoItems && todoItems.length) paintTodo();
            const finalHtml = botBody.innerHTML;
            bindLightbox(botBody.closest('.bot-row'));
            addMsgToConv('assistant', answer, finalHtml, null);
            scrollToBottom();
            stopPolling(sessionId);
            removeTaskRunningHint();
            if (typeof renderSidebar === 'function') renderSidebar();
            if (typeof syncConversationHeader === 'function') syncConversationHeader();
        }

        /**
         * 折叠条表头文案：流式中「深度思考 · 已处理 Ns」，定稿后「深度思考 · 已完成 Ns」。
         * 两个措辞沿用 WorkBuddy 的 streamingDuration.label 与
         * metaFold.turnCompletedWithDuration；失败收尾时用「已取消」。
         */
        function thinkHeadText() {
            if (!turnStartedAt) return '深度思考';
            const span = fmtDuration((turnFinishedAt || Date.now()) - turnStartedAt);
            const state = turnFinishedAt ? (turnWaiting ? '等待你的操作 '
                : turnCancelled ? '已取消 ' : turnIncomplete ? '未完成 '
                : turnFailed ? '失败 ' : '已完成 ') : '已处理 ';
            return '深度思考 · ' + state + span;
        }
        function paintThinkHead() {
            if (!headLabelEl) return;
            const t = thinkHeadText();
            if (headLabelEl.textContent !== t) headLabelEl.textContent = t;
        }

        /**
         * 与 SSE 事件解耦的秒表：每秒重画一次表头，直到定稿。
         * 重连会再进一次 consumeStream，所以先清掉上一轮的定时器，避免堆叠。
         */
        function startThinkTicker() {
            if (thinkTickTimer) {
                clearInterval(thinkTickTimer);
                thinkTickTimer = null;
            }
            thinkTickTimer = setInterval(function () {
                if (turnFinishedAt) {
                    clearInterval(thinkTickTimer);
                    thinkTickTimer = null;
                    return;
                }
                paintThinkHead();
            }, 1000);
        }

        function ensurePanel() {
            if (panelEl) return;
            clearOnce();
            panelEl = document.createElement('div');
            panelEl.className = 'think-panel';
            const head = document.createElement('div');
            head.className = 'think-head';
            head.onclick = function () {
                panelEl.classList.toggle('collapsed');
                thinkCollapsed = panelEl.classList.contains('collapsed');
            };
            headLabelEl = document.createElement('span');
            headLabelEl.className = 'think-label';
            headLabelEl.textContent = '深度思考';
            const caret = document.createElement('span');
            caret.className = 'think-caret'; caret.textContent = '▼';
            head.appendChild(headLabelEl); head.appendChild(caret);
            const body = document.createElement('div');
            body.className = 'think-body';
            thinkTextEl = document.createElement('span');
            body.appendChild(thinkTextEl);
            panelEl.appendChild(head); panelEl.appendChild(body);
            // 钉在 bot-body 最底：工具时间线与正文都在它上面（WorkBuddy 的位次）。
            // 不变式：panelEl 是 botBody 的最后一个子节点，其余元素插到它前面。
            // 别改回插到 timelineEl/answerEl 前面 —— 那样流式期间思考
            // 会落到 .tl-stream 之上（实测 DOM: think-panel, tl-stream, answer-stream）。
            insertAt(botBody, panelEl, null);
            // 有思考面板后不再用底部 liveDock 重复挂「思考中」
            const dock = document.getElementById('liveDock');
            if (dock) dock.hidden = true;
            startThinkTicker();
        }
        function ensureAnswer() {
            if (answerEl) return;
            clearOnce();
            answerEl = document.createElement('div');
            answerEl.className = 'answer-stream md-body';
            /* 结构固定为「定稿块…」+「.md-tail（末尾未闭合块）」：
               定稿块直接挂在 answerEl 下，新增块插到 .md-tail 之前，.md-tail 始终是最后一个子节点。
               这样流式期间只需重画 .md-tail，已画好的图不会被反复销毁重建。 */
            answerTailEl = document.createElement('div');
            answerTailEl.className = 'md-tail';
            answerEl.appendChild(answerTailEl);
            renderedFrozen = '';
            renderedTail = '';
            // 任务节点在前，最终回复在后；与 SSE 事件到达顺序无关。
            insertAt(botBody, answerEl, firstInteractionCard() || panelEl);
        }
        /** The DAG/todo snapshot is the only source of node progress. */
        function paintTodo() {
            if (!todoItems) return;
            clearOnce();
            botBody.querySelectorAll('.task-status-card').forEach(card => card.remove());
            const botRow = botBody.closest('.bot-row');
            const previousRow = botRow && botRow.previousElementSibling;
            if (previousRow && previousRow.classList.contains('plan-row')) previousRow.remove();
            todoEl = renderTaskProgress(botBody, todoItems, {
                live: isStreaming && !done, waiting: turnWaiting, incomplete: turnIncomplete,
                failed: turnFailed, cancelled: turnCancelled, status: taskOutcomeStatus,
                reason: taskOutcomeReason, allowConfirm: true
            });
        }
        function removeTyping() {
            const t = botBody.querySelector('.typing-dots');
            if (t) t.remove();
        }
        function paint() {
            if (!isCurrentStream()) return;
            // 思考只有一条出口：.think-panel，钉在本轮气泡最上方。
            // 与任务节点进度无关，节点卡片只展示结构化的执行步骤。
            if (thinkText) {
                ensurePanel();
                if (thinkText !== renderedThink) {
                    if (thinkText.startsWith(renderedThink)) {
                        thinkTextEl.appendChild(document.createTextNode(
                            thinkText.slice(renderedThink.length)));
                    } else {
                        thinkTextEl.textContent = thinkText;
                    }
                    renderedThink = thinkText;
                    const thinkBody = thinkTextEl.parentElement;
                    if (thinkBody) thinkBody.scrollTop = thinkBody.scrollHeight;
                }
                if (appliedCollapsed !== thinkCollapsed) {
                    panelEl.classList.toggle('collapsed', thinkCollapsed);
                    appliedCollapsed = thinkCollapsed;
                }
                paintThinkHead();
                // 思考面板已表达状态，关掉底部重复的「思考中」
                const dock = document.getElementById('liveDock');
                if (dock) dock.hidden = true;
            } else if (answer) {
                setLiveStatus('生成回复中');
            }
            if (answer) {
                removeTyping();
                if (answer !== renderedAnswer) schedulePaintAnswer();
            }
            if (!thinkPaintTimer) {
                thinkPaintTimer = setTimeout(() => {
                    thinkPaintTimer = null;
                    scrollToBottom();
                }, 120);
            }
        }

        /**
         * 规则计划待确认：把确认条亮在输入框正上方，不再往消息流里插卡片。
         *
         * 为什么不放消息里：审批轮的正文本体就很长（目标 / 步骤 / 回复「开始」），
         * 卡片接在它下面等于把同一件事说两遍，而且它随消息一起滚走，
         * 用户得往上翻才能点。确认是「下一步动作」，所以放在输入框上方，
         * 与「需要用户选择的框」同一位置、同一种形态（标题 + 编号选项）。
         */
        function showPlanApproval(data) {
            let payload = data;
            if (typeof payload === 'string') {
                try { payload = JSON.parse(payload); } catch (_) { payload = {}; }
            }
            if (!payload || payload.required !== true) return;
            const card = document.getElementById('planConfirm');
            const hint = document.getElementById('planConfirmHint');
            if (!card) return;
            // 不重复正文列过的目标与步骤，只说为什么会出现这张卡
            if (hint) {
                hint.textContent = payload.reason === 'PLAN_APPROVAL_REQUIRED'
                    ? '规划模型未返回可用任务图，已改用规则计划'
                    : '本轮计划需要你确认后才能执行';
            }
            card.classList.add('show');
            // 审批轮常常没有任何 token，收尾会清空气泡；这里只是把输入区交还给用户
            removeTyping();
            $inp.focus();
        }

        /** 隐藏确认卡。发任何消息（含批准）都算离开这个待确认状态。 */
        function hidePlanApproval() {
            const card = document.getElementById('planConfirm');
            if (card) card.classList.remove('show');
        }

        function showUserQuestionCard(payload) {
            removeTyping();
            clearOnce();
            const old = botBody.querySelector('.user-question-card:not(.answered)');
            if (old) old.remove();
            const card = document.createElement('section');
            card.className = 'user-question-card';
            card.dataset.sessionId = sessionId;
            card.setAttribute('aria-live', 'polite');
            const title = document.createElement('div');
            title.className = 'user-question-title';
            title.textContent = '需要你补充信息';
            const question = document.createElement('div');
            question.className = 'user-question-text';
            question.textContent = payload && payload.question
                ? String(payload.question) : '需要你补充一点信息才能继续。';
            const optionsBox = document.createElement('div');
            optionsBox.className = 'user-question-options';
            const status = document.createElement('div');
            status.className = 'user-question-status';
            const options = Array.isArray(payload && payload.options)
                ? payload.options.map(option => String(option || '').trim()).filter(Boolean) : [];
            const multiSelect = !!(payload && payload.multiSelect);
            if (options.length) {
                status.textContent = multiSelect
                    ? '可多选，选择后点击提交；也可以直接在输入框回复。'
                    : '点选一个选项，或直接在输入框回复。';
            const selected = new Set();
            let submit = null;
            if (multiSelect) {
                submit = document.createElement('button');
                submit.type = 'button';
                submit.className = 'user-question-submit';
                submit.textContent = '提交选择';
                submit.disabled = true;
                submit.setAttribute('aria-disabled', 'true');
                submit.onclick = () => answerUserQuestion([...selected].join('、'), card);
            }
                options.forEach((label, index) => {
                    const button = document.createElement('button');
                    button.type = 'button';
                    button.className = 'user-question-option';
                    button.textContent = label;
                    button.setAttribute('aria-pressed', 'false');
                    if (multiSelect) {
                        button.onclick = () => {
                            if (selected.has(label)) selected.delete(label);
                            else selected.add(label);
                            button.classList.toggle('selected', selected.has(label));
                            button.setAttribute('aria-pressed', selected.has(label) ? 'true' : 'false');
                            submit.disabled = selected.size === 0;
                            submit.setAttribute('aria-disabled', submit.disabled ? 'true' : 'false');
                        };
                    } else {
                        button.onclick = () => answerUserQuestion(label, card);
                    }
                    optionsBox.appendChild(button);
                });
            if (submit) optionsBox.appendChild(submit);
            } else {
                status.textContent = '请在下方输入框补充信息后发送。';
            }
            card.appendChild(title);
            card.appendChild(question);
            if (options.length) card.appendChild(optionsBox);
            card.appendChild(status);
            insertAt(botBody, card, panelEl);
            registerUserQuestionCard(card);
            scrollToBottom();
        }

        function showPermissionAskCard(payload) {
            removeTyping();
            clearOnce();
            const card = document.createElement('section');
            card.className = 'permission-request-card';
            const title = document.createElement('div');
            title.className = 'permission-request-title';
            const detail = document.createElement('div');
            detail.className = 'permission-request-detail';
            const status = document.createElement('div');
            status.className = 'permission-request-status';
            const actions = document.createElement('div');
            actions.className = 'permission-request-actions';
            const once = document.createElement('button');
            once.type = 'button';
            once.className = 'permission-allow-once';
            once.innerHTML = '<span aria-hidden="true">✓</span> 允许一次';
            once.onclick = () => grantAskTool();
            const always = document.createElement('button');
            always.type = 'button';
            always.className = 'permission-allow-always';
            always.innerHTML = '<span aria-hidden="true">◷</span> 始终允许';
            always.onclick = () => alwaysAllowAskTool();
            const deny = document.createElement('button');
            deny.type = 'button';
            deny.className = 'permission-deny';
            deny.innerHTML = '<span aria-hidden="true">⊘</span> 拒绝';
            deny.onclick = () => denyAskTool();
            actions.append(once, always, deny);
            card.append(title, detail, status, actions);
            insertAt(botBody, card, panelEl);
            registerInlineAskCard(card, payload && (payload.tool || payload.name),
                payload && payload.display, payload && payload.grantToken,
                payload && payload.expiresAt);
            scrollToBottom();
        }

        function onEvent(name, data) {
            if (!isCurrentStream()) return;
            if (name === 'thinking') {
                thinkText += data;
                if (thinkText.length > MAX_THINK_CHARS)
                    thinkText = thinkText.slice(thinkText.length - MAX_THINK_CHARS);
                paint();
            } else if (name === 'todo') {
                try {
                    const payload = typeof data === 'string' ? JSON.parse(data) : data;
                    const items = payload && payload.items ? payload.items : [];
                    todoItems = Array.isArray(items) ? items : [];
                    paintTodo();
                    syncPendingConfirm(todoItems);
                } catch (e) { /* 忽略坏 payload */ }
            } else if (name === 'subgoal') {
                // A loop's subgoal counter is not a decomposed task graph. The
                // dedicated node snapshot above drives the card and its percentage.
            } else if (name === 'seal' || name === 'reset') {
                // 封存过程正文进时间线，再开下一段（兼容旧 reset）
                sealAnswerToTimeline();
                paint();
            } else if (name === 'start') {
                if (!waitingForInteraction()) { answer = data; paint(); }
            } else if (name === 'token') {
                if (!waitingForInteraction()) {
                    if (!answer && !thinkCollapsed) thinkCollapsed = true;
                    answer += data; paint();
                }
            } else if (name === 'context') {
                try {
                    const ctx = typeof data === 'string' ? JSON.parse(data) : data;
                    paintContext(ctx.used || 0, ctx.limit || 0);
                } catch (e) { /* 忽略坏 payload */ }
            } else if (name === 'task_outcome') {
                try {
                    const payload = typeof data === 'string' ? JSON.parse(data) : data;
                    const rawStatus = String(payload && payload.status || '').toUpperCase();
                    const aliases = { COMPLETED: 'SUCCESS', FAILED: 'FAILURE', CANCELLED: 'CANCELED' };
                    const status = aliases[rawStatus] || rawStatus;
                    if (['SUCCESS', 'WAITING', 'CANCELED', 'FAILURE'].includes(status)) {
                        taskOutcomeStatus = status;
                        taskOutcomeReason = String(payload.reason || '');
                        if (payload.executionId) botBody.dataset.executionId = String(payload.executionId);
                    }
                } catch (_) { /* Legacy streams may not carry a structured outcome. */ }
            } else if (name === 'end') {
                // 最终定稿：只覆盖当前答案段，保留时间线
                const missingFinalAnswer = !String(data || '').trim() && !String(answer || '').trim();
                if (taskOutcomeStatus === 'WAITING') {
                    answer = String(data || '').trim() || answer || '等待你的操作后继续。';
                } else if (taskOutcomeStatus === 'CANCELED') {
                    answer = String(data || '').trim() || answer || '任务已取消。';
                } else if (taskOutcomeStatus === 'FAILURE') {
                    answer = compactTaskFailure(String(data || '').trim() || answer || '任务执行失败，请查看执行日志。');
                } else {
                    answer = resolveStreamFinalAnswer(data, answer);
                }
                finish(renderMD(answer), false, missingFinalAnswer && !taskOutcomeStatus);
            } else if (name === 'error') {
                if (!taskOutcomeStatus) {
                    turnCancelled = /取消|停止|cancel(?:led|ed)?/i.test(String(data || ''));
                    if (!turnCancelled) taskOutcomeStatus = 'FAILURE';
                }
                answer = (taskOutcomeStatus === 'WAITING' ? '' : '❌ ')
                    + compactTaskFailure(data || (taskOutcomeStatus === 'WAITING' ? '等待你的操作后继续。' : '服务处理出错'));
                finish(renderMD(answer), turnCancelled);
            } else if (name === 'user') {
                renderUserEchoIfNeeded(botBody, data);
            } else if (name === 'gone') {
                // 通道没了：不在这里定稿，等读流结束后走 tryResumeOrFinish
            } else if (name === 'permission_ask') {
                try {
                    const payload = typeof data === 'string' ? JSON.parse(data) : data;
                    waitingOnPermission = true;
                    answer = '';
                    resetAnswerDom();
                    showPermissionAskCard(payload || {});
                } catch (_) {
                    waitingOnPermission = true;
                    answer = '';
                    resetAnswerDom();
                    showPermissionAskCard({ display: String(data || '') });
                }
            } else if (name === 'permission_ask_released') {
                // 全自动（自动编辑 + 自动确认）在任务运行中生效：后端已经把本会话
                // 所有等待点放行并继续执行。弹窗必须跟着收起 —— 后端在跑、页面还停在
                // 「等一个不会再被消费的点击」，就是用户说的「开了自动还卡在授权上」。
                pendingAskTool = null;
                waitingOnPermission = false;
                pendingAskGrantToken = null;
                clearTimeout(pendingAskExpiryTimer);
                pendingAskExpiryTimer = null;
                completeInlineAskCard('权限策略已更新，Agent 正在继续执行。', 'approved');
                document.getElementById('askModal').classList.remove('show');
                setAskModalText('工具待确认');
                showAskActions();
            } else if (name === 'user_question') {
                try {
                    const payload = typeof data === 'string' ? JSON.parse(data) : data;
                    waitingOnQuestion = true;
                    answer = '';
                    resetAnswerDom();
                    showUserQuestionCard(payload || {});
                } catch (_) {
                    waitingOnQuestion = true;
                    answer = '';
                    resetAnswerDom();
                    showUserQuestionCard({ question: String(data || ''), options: [] });
                }
            } else if (name === 'session') { /* 忽略 */
            } else if (name === 'plan_approval') {
                // 规则计划需要用户确认：由后端在 PLAN_APPROVAL_REQUIRED 时发出。
                // 必须在这里接上 —— showPlanApproval 只定义、不接事件，卡片永远不会出现。
                waitingOnPlanApproval = true;
                answer = '';
                resetAnswerDom();
                showPlanApproval(data);
            } else if (name === 'append_ack') {
                // 入队回执。这里**有意不处理**：同一次追加还有 POST /messages 的 JSON 响应，
                // 那份带得到 success/failure 与失败原因，状态由 appendUserMessage 统一写。
                // 两处都写会互相覆盖 —— ack 只证明「入队成功」，失败路径它根本不发。
            }
        }

        /**
         * 流意外中断时的兜底：若任务仍在运行且未超重连上限，挂到 attach 端点续流；
         * 否则把当前已累积的答案定稿（或显示错误）。
         * 定义在闭包内，可访问 answer/done/finish 等流状态。
         */
        async function tryResumeOrFinish(err) {
            if (!isCurrentStream()) return;
            if (reconnectAttempt >= MAX_STREAM_RECONNECTS) {
                if (answer) finish(renderMD(answer), false, true);
                else finish(renderMD('❌ 连接多次中断，请重试' + (err ? '：' + err.message : '')), false, true);
                return;
            }
            // 确认后台任务还在跑，才值得重连
            let stillRunning = false;
            try {
                const r = await fetch('/api/conversations/' + encodeURIComponent(sessionId) + '/task');
                if (r.ok) stillRunning = (await apiJson(r) || {}).running === true;
            } catch (e) { /* 探测失败按未运行处理 */ }
            if (!isCurrentStream()) return;

            if (!stillRunning) {
                if (answer) finish(renderMD(answer), false, true);
                else if (sessionId === currentId) {
                    done = true;
                    unlockComposer();
                    await switchConv(sessionId, true);
                } else finish(renderMD('❌ 连接中断' + (err ? '：' + err.message : '')), false, true);
                return;
            }

            // 退避后挂到 attach 端点，复用同一 botBody 续流
            await new Promise(res => setTimeout(res, 800 * (reconnectAttempt + 1)));
            if (!isCurrentStream()) return;
            try {
                const resp = await fetch('/api/conversations/' + encodeURIComponent(sessionId) + '/messages/stream');
                if (!isCurrentStream()) {
                    if (resp.body) await resp.body.cancel().catch(() => {});
                    return;
                }
                if (!resp.ok || !resp.body) throw new Error('attach HTTP ' + resp.status);
                done = true; // 交棒给重连流，避免本帧再次走收尾分支
                await consumeStream(resp.body.getReader(), botBody, sessionId, reconnectAttempt + 1);
            } catch (e) {
                if (!isCurrentStream()) return;
                console.warn('resume stream failed:', e);
                if (answer) finish(renderMD(answer), false, true);
                else {
                    unlockComposer();
                    showTaskRunningHint(sessionId);
                    startPolling(sessionId);
                }
            }
        }

        botBody.querySelectorAll('.task-status-card').forEach(card => card.remove());
        try {
            while (true) {
                const { value, done: streamDone } = await reader.read();
                if (!isCurrentStream()) {
                    if (typeof reader.cancel === 'function') await reader.cancel().catch(() => {});
                    return;
                }
                if (streamDone) break;
                buffer += decoder.decode(value, { stream: true });
                const events = buffer.split('\n\n');
                buffer = events.pop();
                for (const block of events) {
                    let evt = 'message';
                    const dataLines = [];
                    for (const line of block.split('\n')) {
                        if (line.startsWith('event:')) evt = line.slice(6).trim();
                        else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
                    }
                    if (dataLines.length) onEvent(evt, dataLines.join('\n'));
                }
            }
            // 流自然结束但没收到 end/gone/error → 多半是连接超时/中断被掐断，
            // 后台任务可能还在跑。尝试重连续流，而不是把残缺答案直接定稿。
            if (!done) await tryResumeOrFinish();
        } catch (readErr) {
            // 读流过程网络异常 → 同样尝试重连
            if (!done) await tryResumeOrFinish(readErr);
        }
    } catch (err) {
        if (!isCurrentStream()) return;
        botBody.innerHTML = renderMD('❌ ' + err.message);
        botBody.classList.remove('streaming');
        setStreaming(false); onInputChange(); scrollToBottom();
    }
}

function fileToDataUrl(file) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = reject;
        reader.readAsDataURL(file);
    });
}

function fileToBase64(file) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => {
            const base64 = reader.result.split(',')[1];
            resolve(base64);
        };
        reader.onerror = reject;
        reader.readAsDataURL(file);
    });
}

/** The server's final answer takes precedence over stale local interaction flags. */
function resolveStreamFinalAnswer(data, streamedAnswer) {
    const finalAnswer = typeof data === 'string' ? data : '';
    if (finalAnswer.trim()) return finalAnswer;
    if (String(streamedAnswer || '').trim()) return streamedAnswer;
    return '任务已结束，但智能体没有生成最终回复。请查看右侧任务详情中的执行记录和已生成文件。';
}

function addMsgToConv(role, text, html, imgSrc) {
    // Messages are persisted by the backend; no local storage needed.
    // Sidebar refresh is handled by switchConv and send completion.
}

/**
 * 把计划卡片里「进行中」的视觉与实际执行状态对齐。
 *
 * <p>{@code in_progress} 在服务端是「当前指向的步骤」，不是「正在执行」：
 * 中断或本轮结束后它还停在那条上，但页面不该继续画成绿色实心点在转。</p>
 *
 * <p>卡片只在收到 {@code todo} 事件时重绘，一轮结束不会自动刷新，
 * 所以由 {@code setStreaming()} 主动调这里。</p>
 */
function refreshTodoIdleState() {
    const idle = !isStreaming;
    document.querySelectorAll('.todo-panel .todo-item.in_progress').forEach(li => {
        li.classList.toggle('idle', idle);
        const existing = li.querySelector('.todo-hint');
        const nodeState = li.querySelector('.todo-node-state');
        if (nodeState) {
            nodeState.textContent = idle ? '待继续' : '进行中';
            if (existing) existing.remove();
            return;
        }
        if (idle && !existing) {
            const hint = document.createElement('span');
            hint.className = 'todo-hint';
            hint.textContent = '待继续';
            li.appendChild(hint);
        } else if (!idle && existing) {
            existing.remove();
        }
    });
}

async function toggleTaskPause() {
    if (!currentId || !taskIsActive || taskRunningSessionId !== currentId) return;
    const sessionId = currentId;
    const action = taskPauseRequested ? 'resume' : 'pause';
    const button = document.getElementById('taskPauseBtn');
    if (button) button.disabled = true;
    try {
        const response = await fetch('/api/conversations/' + encodeURIComponent(sessionId)
            + '/task/' + action, { method: 'PUT' });
        const body = await response.json();
        if (!response.ok || !apiSuccess(body)) {
            throw new Error(apiFailureMessage(body, response));
        }
        if (sessionId !== currentId || !isStreaming) return;
        taskPauseRequested = action === 'pause';
        taskPausedAtCheckpoint = false;
        syncTaskControlButton();
        if (taskPausePollTimer) clearInterval(taskPausePollTimer);
        taskPausePollTimer = action === 'pause'
            ? setInterval(() => refreshTaskPauseState(sessionId), 500) : null;
    } catch (error) {
        console.warn('task pause control failed:', error);
        alert((action === 'pause' ? '暂停' : '继续') + '任务失败：' + error.message);
    } finally {
        if (button) button.disabled = false;
    }
}

async function refreshTaskPauseState(sessionId) {
    if (!sessionId || sessionId !== currentId || !taskIsActive
            || taskRunningSessionId !== sessionId) return;
    try {
        const response = await fetch('/api/conversations/' + encodeURIComponent(sessionId) + '/task');
        if (!response.ok) return;
        const status = await apiJson(response) || {};
        if (sessionId !== currentId || !taskIsActive || taskRunningSessionId !== sessionId) return;
        taskPauseRequested = status.pauseRequested === true;
        taskPausedAtCheckpoint = status.pausedAtCheckpoint === true;
        syncTaskControlButton();
        if (taskPauseRequested && !taskPausePollTimer) {
            taskPausePollTimer = setInterval(() => refreshTaskPauseState(sessionId), 500);
        } else if (!taskPauseRequested && taskPausePollTimer) {
            clearInterval(taskPausePollTimer);
            taskPausePollTimer = null;
        }
    } catch (_) { /* 状态查询失败时保留当前 UI 状态，后续间隔重试 */ }
}
