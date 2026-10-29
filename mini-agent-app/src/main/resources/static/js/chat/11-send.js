/* =========================================================================
   SEND MESSAGE
   ========================================================================= */
async function send() {
    const text = $inp.value.trim();
    if (!text && selectedFiles.length === 0) return;
    if (uploadingCount > 0) { alert('文件上传中，请等待'); return; }
    // 发出任何消息（包括批准）都算离开「待确认」状态：确认条只是当轮的动作入口。
    const planCard = document.getElementById('planConfirm');
    if (planCard) planCard.classList.remove('show');

    // 执行中追加模式：agent 正在运行时，发送追加消息
    if (isStreaming && currentId && text) {
        appendUserMessage(text, currentId);
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
    const fileNames = selectedFiles.filter(f => !f.type.startsWith('image/')).map(f => f.name);
    renderUserMsg(text, imgSrcs[0] || null, imgSrcs.length, fileNames);
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
    sendChatPost(text, sid, filesCopy, refsCopy, botBody);
}

/** POST /api/conversations/{id}/messages/stream，读 SSE 流式响应 */
async function sendChatPost(text, sessionId, files, refs, botBody) {
    const epoch = ++streamEpoch;
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

        if (!resp.ok || !resp.body) {
            botBody.innerHTML = renderMD('❌ HTTP ' + resp.status);
            botBody.classList.remove('streaming');
            if (epoch === streamEpoch) {
                setStreaming(false); onInputChange(); scrollToBottom();
            }
            return;
        }

        // 读 SSE 流
        await consumeStream(resp.body.getReader(), botBody, sessionId);
        if (isStreaming && epoch === streamEpoch) unlockComposer();
    } catch (err) {
        botBody.innerHTML = renderMD('❌ ' + err.message);
        botBody.classList.remove('streaming');
        if (epoch === streamEpoch) {
            setStreaming(false); onInputChange(); scrollToBottom();
        }
    }
}

/** 复用的 SSE 渲染：首次发送与刷新重连共用同一套增量渲染逻辑 */
const MAX_STREAM_RECONNECTS = 3;  // 单次发送内流意外中断后最多自动重连次数
async function consumeStream(reader, botBody, sessionId, reconnectAttempt = 0) {
  try {
        const decoder = new TextDecoder('utf-8');
        let buffer = '', answer = '', done = false;
        let thinkText = '', thinkCollapsed = false;
        // 本 turn 是否由失败/取消收尾，只影响折叠条的「已取消 / 已完成」用词
        let turnCancelled = false;
        const MAX_THINK_CHARS = 12000;
        let thinkPaintTimer = null;
        // 增量渲染的 DOM 引用与已渲染游标（避免每个 token 重建整棵子树导致闪烁）
        let cleared = false, panelEl = null, headLabelEl = null, thinkTextEl = null,
            timelineEl = null, answerEl = null, answerTailEl = null;
        let renderedThink = '', renderedAnswer = '', appliedCollapsed = null;
        // 流式正文的两个渲染游标：定稿块（frozen）与末尾未闭合块（tail）
        let renderedFrozen = '', renderedTail = '';
        let answerPaintTimer = null;
        // 任务计划钉在用户提问下方；sub-goal 仍可回退到 bot 内
        let todoItems = null;
        let todoEl = null, todoListEl = null, todoMetaEl = null, planRowEl = null;
        let subGoalText = '', subGoalDone = 0, subGoalTotal = 0;
        let progressEl = null, progressLabelEl = null, progressFillEl = null;
        function clearOnce() { if (!cleared) { botBody.innerHTML = ''; cleared = true; } }
        function findUserRowForBot() {
            const botRow = botBody.closest('.bot-row');
            let el = botRow ? botRow.previousElementSibling : null;
            while (el) {
                if (el.classList.contains('user-row')) return el;
                if (el.classList.contains('plan-row')) {
                    el = el.previousElementSibling;
                    continue;
                }
                break;
            }
            return null;
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
            removeTyping();
            ensureAnswer();
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
            insertAt(botBody, timelineEl, answerEl || panelEl);
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
            answerTailEl = document.createElement('div');
            answerTailEl.className = 'md-tail';
            answerEl.appendChild(answerTailEl);
        }
        /** 把当前流式正文封存为过程说明，再开新一段（不抹掉可见历史） */
        function sealAnswerToTimeline() {
            const text = (answer || '').trim();
            if (text) {
                ensureTimeline();
                const note = document.createElement('div');
                note.className = 'tl-note';
                const lab = document.createElement('div');
                lab.className = 'tl-note-label';
                lab.textContent = '过程说明';
                const body = document.createElement('div');
                body.className = 'tl-note-body';
                body.innerHTML = renderMDStreaming(text);
                note.appendChild(lab);
                note.appendChild(body);
                timelineEl.appendChild(note);
            }
            answer = '';
            resetAnswerDom();
            thinkCollapsed = false;
            if (panelEl) {
                panelEl.classList.remove('collapsed', 'done');
                appliedCollapsed = false;
            }
        }
        function finish(html, cancelled) {
            if (done) return; done = true;
            turnFinishedAt = Date.now();
            turnCancelled = !!cancelled;
            if (thinkTickTimer) {
                clearInterval(thinkTickTimer);
                thinkTickTimer = null;
            }
            if (answerPaintTimer) {
                clearTimeout(answerPaintTimer);
                answerPaintTimer = null;
            }
            botBody.classList.remove('streaming');
            // 保留思考 / 时间线；计划已在用户提问下，不塞进 bot
            ensureAnswer();
            answerEl.innerHTML = html;
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
            const finalHtml = botBody.innerHTML;
            bindLightbox(botBody.closest('.bot-row'));
            addMsgToConv('assistant', answer, finalHtml, null);
            setStreaming(false); onInputChange(); scrollToBottom();
            stopPolling(sessionId);
            removeTaskRunningHint();
        }

        /**
         * 折叠条表头文案：流式中「深度思考 · 已处理 Ns」，定稿后「深度思考 · 已完成 Ns」。
         * 两个措辞沿用 WorkBuddy 的 streamingDuration.label 与
         * metaFold.turnCompletedWithDuration；失败收尾时用「已取消」。
         */
        function thinkHeadText() {
            if (!turnStartedAt) return '深度思考';
            const span = fmtDuration((turnFinishedAt || Date.now()) - turnStartedAt);
            const state = turnFinishedAt ? (turnCancelled ? '已取消 ' : '已完成 ') : '已处理 ';
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
            head.onclick = function () { panelEl.classList.toggle('collapsed'); };
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
            // 别改回插到 progressEl/timelineEl/answerEl 前面 —— 那样流式期间思考
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
            // 答案插到思考条前面：思考恒在链尾，正文在它之上。
            insertAt(botBody, answerEl, panelEl);
        }
        /** 任务计划钉在用户提问与 bot 之间 */
        function paintTodo() {
            if (!todoItems || !todoItems.length) return;
            const userRow = findUserRowForBot();
            planRowEl = null;
            todoEl = null;
            todoListEl = null;
            todoMetaEl = null;
            if (userRow) {
                const next = userRow.nextElementSibling;
                if (next && next.classList.contains('plan-row'))
                    planRowEl = next;
            }
            if (planRowEl) {
                todoEl = planRowEl.querySelector('.todo-panel');
                todoListEl = planRowEl.querySelector('.todo-list');
                todoMetaEl = planRowEl.querySelector('.todo-head-meta');
            }
            if (!todoEl || !todoListEl || !todoMetaEl) {
                if (!planRowEl) {
                    planRowEl = document.createElement('div');
                    planRowEl.className = 'msg-row plan-row';
                    if (userRow) userRow.after(planRowEl);
                    else {
                        clearOnce();
                        // 无用户行时退到 bot 体内，同样走收口、锚链上下一个元素
                        insertAt(botBody, planRowEl, progressEl || timelineEl || answerEl || panelEl);
                    }
                }
                planRowEl.innerHTML = '';
                const inner = document.createElement('div');
                inner.className = 'msg-inner';
                todoEl = document.createElement('div');
                todoEl.className = 'todo-panel';
                const head = document.createElement('div');
                head.className = 'todo-head';
                const title = document.createElement('span');
                title.textContent = '任务计划';
                todoMetaEl = document.createElement('span');
                todoMetaEl.className = 'todo-head-meta';
                head.appendChild(title);
                head.appendChild(todoMetaEl);
                todoListEl = document.createElement('ol');
                todoListEl.className = 'todo-list';
                todoEl.appendChild(head);
                todoEl.appendChild(todoListEl);
                inner.appendChild(todoEl);
                planRowEl.appendChild(inner);
            }
            let doneN = 0;
            for (const it of todoItems)
                if (it.status === 'completed' || it.status === 'cancelled') doneN++;
            todoMetaEl.textContent = doneN + '/' + todoItems.length;
            todoListEl.innerHTML = '';
            for (const it of todoItems) {
                const li = document.createElement('li');
                li.className = 'todo-item ' + (it.status || 'pending').toLowerCase();
                const mark = document.createElement('span');
                mark.className = 'todo-mark';
                const text = document.createElement('span');
                text.className = 'todo-text';
                text.textContent = (it.id != null ? it.id + '. ' : '') + (it.content || '');
                li.appendChild(mark);
                li.appendChild(text);
                if (String(it.status || '').toLowerCase() === 'awaiting_confirm') {
                    const btn = document.createElement('button');
                    btn.type = 'button';
                    btn.className = 'todo-confirm-btn';
                    btn.textContent = '确认并继续';
                    btn.disabled = isStreaming;
                    const todoId = it.id;
                    btn.onclick = function (ev) {
                        ev.preventDefault();
                        ev.stopPropagation();
                        confirmTodoAndContinue(todoId);
                    };
                    li.appendChild(btn);
                }
                todoListEl.appendChild(li);
            }
            if (progressEl) { progressEl.remove(); progressEl = null; }
        }
        function ensureProgress() {
            if (todoItems && todoItems.length) return; // 有计划列表时不再画单行进度条
            if (progressEl) return;
            clearOnce();
            progressEl = document.createElement('div');
            progressEl.className = 'subgoal-bar';
            progressLabelEl = document.createElement('div');
            progressLabelEl.className = 'subgoal-label';
            const track = document.createElement('div');
            track.className = 'subgoal-track';
            progressFillEl = document.createElement('div');
            progressFillEl.className = 'subgoal-fill';
            track.appendChild(progressFillEl);
            progressEl.appendChild(progressLabelEl);
            progressEl.appendChild(track);
            // 进度条在时间线/答案/思考之前
            insertAt(botBody, progressEl, timelineEl || answerEl || panelEl);
        }
        function paintProgress() {
            if (todoItems && todoItems.length) return;
            if (!subGoalTotal) return;
            ensureProgress();
            const stepNo = Math.min(subGoalDone + 1, subGoalTotal);
            const label = '步骤 ' + stepNo + '/' + subGoalTotal + '：' + subGoalText;
            if (progressLabelEl.textContent !== label) progressLabelEl.textContent = label;
            const pct = Math.round((subGoalDone / subGoalTotal) * 100);
            const w = pct + '%';
            if (progressFillEl.style.width !== w) progressFillEl.style.width = w;
        }
        function removeTyping() {
            const t = botBody.querySelector('.typing-dots');
            if (t) t.remove();
        }
        function paint() {
            // 思考只有一条出口：.think-panel，钉在本轮气泡最上方。
            // 与「任务计划」卡片、sub-goal 进度条无关，那两处只放计划本身。
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

        function onEvent(name, data) {
            if (name === 'thinking') {
                if (thinkCollapsed && !answer) thinkCollapsed = false;
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
                try {
                    const sg = typeof data === 'string' ? JSON.parse(data) : data;
                    subGoalText = sg.text || '';
                    subGoalDone = sg.done || 0;
                    subGoalTotal = sg.total || 0;
                    paintProgress();
                } catch (e) { /* 容错 */ }
            } else if (name === 'seal' || name === 'reset') {
                // 封存过程正文进时间线，再开下一段（兼容旧 reset）
                sealAnswerToTimeline();
                paint();
            } else if (name === 'start') {
                answer = data; paint();
            } else if (name === 'token') {
                if (!answer && !thinkCollapsed) thinkCollapsed = true;
                answer += data; paint();
            } else if (name === 'context') {
                try {
                    const ctx = typeof data === 'string' ? JSON.parse(data) : data;
                    paintContext(ctx.used || 0, ctx.limit || 0);
                } catch (e) { /* 忽略坏 payload */ }
            } else if (name === 'end') {
                // 最终定稿：只覆盖当前答案段，保留时间线
                if (data) answer = data;
                finish(renderMD(answer));
            } else if (name === 'error') {
                // 失败/取消收尾：折叠条表头用「已取消 Ns」而不是「已完成 Ns」
                finish(renderMD('❌ ' + (data || '服务处理出错')), true);
            } else if (name === 'user') {
                renderUserEchoIfNeeded(botBody, data);
            } else if (name === 'gone') {
                // 通道没了：不在这里定稿，等读流结束后走 tryResumeOrFinish
            } else if (name === 'permission_ask') {
                try {
                    const payload = typeof data === 'string' ? JSON.parse(data) : data;
                    const tool = payload && (payload.tool || payload.name);
                    if (tool) showAskModal(tool, payload.display || '',
                        payload.grantToken || '', payload.expiresAt || 0);
                } catch (_) {
                    if (data) showAskModal(String(data));
                }
            } else if (name === 'permission_ask_released') {
                // 全自动（自动编辑 + 自动确认）在任务运行中生效：后端已经把本会话
                // 所有等待点放行并继续执行。弹窗必须跟着收起 —— 后端在跑、页面还停在
                // 「等一个不会再被消费的点击」，就是用户说的「开了自动还卡在授权上」。
                pendingAskTool = null;
                pendingAskGrantToken = null;
                clearTimeout(pendingAskExpiryTimer);
                pendingAskExpiryTimer = null;
                document.getElementById('askModal').classList.remove('show');
                setAskModalText('工具待确认');
                showAskActions();
            } else if (name === 'user_question') {
                try {
                    const payload = typeof data === 'string' ? JSON.parse(data) : data;
                    showUserQuestionModal(payload);
                } catch (_) {
                    showUserQuestionModal({ question: String(data || ''), options: [] });
                }
            } else if (name === 'session') { /* 忽略 */
            } else if (name === 'plan_approval') {
                // 规则计划需要用户确认：由后端在 PLAN_APPROVAL_REQUIRED 时发出。
                // 必须在这里接上 —— showPlanApproval 只定义、不接事件，卡片永远不会出现。
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
            if (reconnectAttempt >= MAX_STREAM_RECONNECTS) {
                if (answer) finish(renderMD(answer));
                else finish(renderMD('❌ 连接多次中断，请重试' + (err ? '：' + err.message : '')));
                return;
            }
            // 确认后台任务还在跑，才值得重连
            let stillRunning = false;
            try {
                const r = await fetch('/api/conversations/' + encodeURIComponent(sessionId) + '/task');
                if (r.ok) stillRunning = (await apiJson(r) || {}).running === true;
            } catch (e) { /* 探测失败按未运行处理 */ }

            if (!stillRunning) {
                if (answer) finish(renderMD(answer));
                else if (sessionId === currentId) {
                    done = true;
                    unlockComposer();
                    await switchConv(sessionId, true);
                } else finish(renderMD('❌ 连接中断' + (err ? '：' + err.message : '')));
                return;
            }

            // 退避后挂到 attach 端点，复用同一 botBody 续流
            await new Promise(res => setTimeout(res, 800 * (reconnectAttempt + 1)));
            try {
                const resp = await fetch('/api/conversations/' + encodeURIComponent(sessionId) + '/messages/stream');
                if (!resp.ok || !resp.body) throw new Error('attach HTTP ' + resp.status);
                done = true; // 交棒给重连流，避免本帧再次走收尾分支
                await consumeStream(resp.body.getReader(), botBody, sessionId, reconnectAttempt + 1);
            } catch (e) {
                console.warn('resume stream failed:', e);
                if (answer) finish(renderMD(answer));
                else {
                    unlockComposer();
                    showTaskRunningHint(sessionId);
                    startPolling(sessionId);
                }
            }
        }

        try {
            while (true) {
                const { value, done: streamDone } = await reader.read();
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

function addMsgToConv(role, text, html, imgSrc) {
    // Messages are persisted by the backend; no local storage needed.
    // Sidebar refresh is handled by switchConv and send completion.
}

