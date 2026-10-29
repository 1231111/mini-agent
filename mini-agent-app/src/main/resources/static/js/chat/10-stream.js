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
async function appendUserMessage(text, sessionId) {
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
        } else {
            const why = (data && (data.message || data.error)) || ('HTTP ' + resp.status);
            setInjectStatus(status, 'failed', '未送达：' + why + '。请直接重新发送。');
        }
    } catch (e) {
        setInjectStatus(status, 'failed', '未送达（网络异常）。请直接重新发送。');
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

