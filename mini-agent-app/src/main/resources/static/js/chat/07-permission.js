/* =========================================================================
   PERMISSION MODE（Plan / Ask / Accept edits）
   ========================================================================= */
const PERM_LABELS = {
    default: '默认',
    plan: 'Plan',
    accept_edits: '自动编辑',
    ask: '询问'
};

function togglePermMenu() {
    const menu = document.getElementById('permMenu');
    menu.classList.toggle('show');
    document.getElementById('roleMenu')?.classList.remove('show');
    document.getElementById('confirmMenu')?.classList.remove('show');
    document.getElementById('execMenu')?.classList.remove('show');
    if (menu.classList.contains('show')) {
        setTimeout(() => document.addEventListener('click', closePermMenuOutside, { once: true }), 0);
    }
}
function closePermMenuOutside(ev) {
    const wrap = document.getElementById('permSelector');
    if (wrap && wrap.contains(ev.target)) return;
    document.getElementById('permMenu')?.classList.remove('show');
}
function permissionUrl() {
    return '/api/conversations/' + encodeURIComponent(currentId) + '/permission';
}

/* 统一响应体的成功判定。
   服务端 com.miniagent.common.ApiResponse 的契约只有一条：
     成功 { "success": true, "data": ... }   —— code / message 因 @JsonInclude(NON_NULL) 被整体省略
     失败 { "success": false, "code": "AUTH.01.01", "message": "..." }
   code 是**错误码字符串**，不是数字；成功时根本没有这个键。
   所以不能写 code === 0：浏览器里取到的是 undefined，任何成功响应都会被判成失败。
   实测故障：grantAskTool 批准已生效、工具已继续跑，页面却弹「批准失败: HTTP 200」。 */
function apiSuccess(body) {
    return !!body && body.success === true;
}

/** 失败原因：优先用服务端 message，其次错误码，最后退回 HTTP 状态。 */
function apiFailureMessage(body, resp) {
    if (body && body.message) return String(body.message);
    if (body && body.code) return String(body.code);
    return resp ? 'HTTP ' + resp.status : '未知错误';
}

/** 统一取 data；空响应体不抛异常，交给调用方按失败处理。 */
async function readApiBody(resp) {
    try {
        return await resp.json();
    } catch (e) {
        return null;
    }
}

async function selectPermissionMode(mode) {
    permissionMode = mode || 'default';
    localStorage.setItem('mini_agent_perm_mode', permissionMode);
    document.getElementById('permLabel').textContent = PERM_LABELS[permissionMode] || permissionMode;
    document.getElementById('permBtn').classList.toggle('active', permissionMode !== 'default');
    document.querySelectorAll('#permMenu .perm-item').forEach(el => {
        el.classList.toggle('active', el.dataset.mode === permissionMode);
    });
    document.getElementById('permMenu')?.classList.remove('show');
    refreshPlanBanner();
    if (currentId) {
        try {
            await fetch(permissionUrl(), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: currentId, mode: permissionMode })
            });
        } catch (e) { console.warn('set permission mode failed', e); }
    }
}
function refreshPlanBanner() {
    const banner = document.getElementById('planBanner');
    if (!banner) return;
    const show = permissionMode === 'plan';
    banner.classList.toggle('show', show);
}
async function approvePlan() {
    if (!currentId) { alert('请先开始对话'); return; }
    try {
        const resp = await fetch(permissionUrl(), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: currentId, action: 'approve_plan' })
        });
        const body = await readApiBody(resp);
        if (!resp.ok || !apiSuccess(body)) {
            // 原来失败分支什么都不做：按钮按下去没反应，用户只能反复点。
            throw new Error(apiFailureMessage(body, resp));
        }
        const data = unwrapApiBody(body) || {};
        document.getElementById('planBannerText').textContent =
            data.planApproved ? 'Plan 已批准，写工具已解锁' : 'Plan 模式';
    } catch (e) {
        alert('批准失败: ' + e.message);
    }
}
function showAskModal(tool, display, grantToken, expiresAt) {
    const labels = {
        exec_command: '终端命令执行',
        http_post: '发送 HTTP POST 请求'
    };
    const label = labels[tool] || tool;
    // 只说「终端命令执行」，用户不知道这条命令要干什么，只能盲批。
    // 有 display 就把它摊开说清楚。
    const detail = display ? '本次调用：' + display + '。' : '';
    setAskModalText('Agent 需要「' + label + '」才能继续当前步骤。' + detail
        + '批准只对这一次完全相同的调用有效，过期后需要重新触发。'
        + '「确认并继续」是待办步骤的确认，不能代替这里的批准。');
    // 上一次失败留下的「知道了」按钮必须换回可操作的按钮组，
    // 否则新的审批弹出来却点不到「批准并继续」。
    showAskActions();

    // 先判断是否已经过期：服务端 token 过期后批准一定失败，
    // 与其弹一个点不动的框，不如直接告诉用户重来一次。
    const remaining = expiresAt ? Number(expiresAt) - Date.now() : 0;
    if (grantToken && remaining <= 0) {
        pendingAskTool = null;
        pendingAskGrantToken = null;
        setAskModalText('这次「' + label + '」的批准已经过期，Agent 会按未批准继续。'
            + '需要执行时请在下一轮重新触发。');
        document.getElementById('askModal').classList.add('show');
        showAskNotice();
        clearTimeout(pendingAskExpiryTimer);
        pendingAskExpiryTimer = setTimeout(dismissAskNotice, 4000);
        return;
    }

    pendingAskTool = tool;
    pendingAskGrantToken = grantToken || null;
    document.getElementById('askModal').classList.add('show');

    clearTimeout(pendingAskExpiryTimer);
    pendingAskExpiryTimer = null;
    if (grantToken && remaining > 0) {
        pendingAskExpiryTimer = setTimeout(() => {
            pendingAskTool = null;
            pendingAskGrantToken = null;
            document.getElementById('askModal').classList.remove('show');
            setAskModalText('工具待确认');
            showAskActions();
        }, remaining);
    }
}

/** 新审批卡附着在当前助手消息中，审批仍然使用服务端一次性 token。 */
function registerInlineAskCard(card, tool, display, grantToken, expiresAt) {
    if (pendingAskCard && pendingAskCard !== card) {
        updateInlineAskCard(pendingAskCard, '此授权请求已被新的请求替代。', 'closed');
    }
    clearTimeout(pendingAskExpiryTimer);
    pendingAskExpiryTimer = null;
    pendingAskCard = card || null;
    pendingAskTool = tool || null;
    pendingAskGrantToken = grantToken || null;

    const labels = { exec_command: '终端命令执行', http_post: '发送 HTTP POST 请求' };
    const label = labels[tool] || tool || '工具调用';
    const title = card && card.querySelector('.permission-request-title');
    const description = card && card.querySelector('.permission-request-detail');
    const status = card && card.querySelector('.permission-request-status');
    if (title) title.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2.8 20 6v5.5c0 5.1-3.4 8.5-8 10.1-4.6-1.6-8-5-8-10.1V6l8-3.2Zm0 3.1L7 7.8v3.7c0 3.4 1.9 5.8 5 7.2 3.1-1.4 5-3.8 5-7.2V7.8l-5-1.9Zm-1 8.8-2.2-2.2 1.4-1.4 1.1 1.1 2.7-2.8 1.4 1.4-4.4 4.5Z"/></svg><span>需要你的授权</span>';
    if (description) description.textContent = display
        ? label + '：' + display
        : 'Agent 请求「' + label + '」权限以继续当前步骤。';
    if (status) status.textContent = '允许一次仅限本次调用；始终允许仅对本会话生效。';

    const remaining = expiresAt ? Number(expiresAt) - Date.now() : 0;
    if (!tool || !grantToken) {
        pendingAskTool = null;
        pendingAskGrantToken = null;
        updateInlineAskCard(card,
            '授权信息不完整，无法安全批准。请在下一轮重新触发该操作。', 'failed');
        pendingAskCard = null;
        return;
    }
    if (grantToken && remaining <= 0) {
        pendingAskTool = null;
        pendingAskGrantToken = null;
        updateInlineAskCard(card, '这次授权已过期，Agent 会按未批准继续。需要执行时请在下一轮重新触发。', 'expired');
        pendingAskCard = null;
        return;
    }
    if (grantToken && remaining > 0) {
        pendingAskExpiryTimer = setTimeout(() => {
            if (pendingAskCard !== card) return;
            pendingAskTool = null;
            pendingAskGrantToken = null;
            pendingAskExpiryTimer = null;
            updateInlineAskCard(card, '授权已过期，Agent 会按未批准继续。需要执行时请在下一轮重新触发。', 'expired');
            pendingAskCard = null;
        }, remaining);
    }
}

function updateInlineAskCard(card, message, state) {
    if (!card) return;
    card.classList.remove('approved', 'denied', 'expired', 'failed', 'closed');
    if (state) card.classList.add(state);
    const status = card.querySelector('.permission-request-status');
    if (status) status.textContent = message || '';
    const actions = card.querySelector('.permission-request-actions');
    if (actions) actions.hidden = true;
}

function completeInlineAskCard(message, state) {
    const card = pendingAskCard;
    if (!card) return false;
    updateInlineAskCard(card, message, state);
    pendingAskCard = null;
    return true;
}

/** 任务进入终态后，一次性授权就失效，清掉计时器、token 和仍可点击的授权卡。 */
function settlePendingAskOnTerminal(message) {
    clearTimeout(pendingAskExpiryTimer);
    pendingAskExpiryTimer = null;
    pendingAskTool = null;
    pendingAskGrantToken = null;
    if (pendingAskCard) {
        updateInlineAskCard(pendingAskCard, message || '任务已结束，授权请求已失效。', 'closed');
        pendingAskCard = null;
    }
    const modal = document.getElementById('askModal');
    if (modal) modal.classList.remove('show');
    if (typeof setAskModalText === 'function') setAskModalText('工具待确认');
    if (typeof showAskActions === 'function') showAskActions();
}

function setAskModalText(text) {
    document.getElementById('askModalText').textContent = text;
}

/** 正常的审批状态：两个按钮都能用。 */
function showAskActions() {
    const actions = document.getElementById('askActions');
    const notice = document.getElementById('askNoticeActions');
    if (actions) actions.style.display = '';
    if (notice) notice.style.display = 'none';
}

/** 只给结论、不给动作：那次调用的 token 已经作废，按钮点不动比不给按钮更糟。 */
function showAskNotice() {
    const actions = document.getElementById('askActions');
    const notice = document.getElementById('askNoticeActions');
    if (actions) actions.style.display = 'none';
    if (notice) notice.style.display = '';
}

function dismissAskNotice() {
    clearTimeout(pendingAskExpiryTimer);
    pendingAskExpiryTimer = null;
    if (pendingAskCard) {
        updateInlineAskCard(pendingAskCard, '授权请求已关闭。', 'closed');
        pendingAskCard = null;
        return;
    }
    document.getElementById('askModal').classList.remove('show');
    setAskModalText('工具待确认');
    showAskActions();
}
function denyAskTool() {
    const tool = pendingAskTool;
    const grantToken = pendingAskGrantToken;
    const card = pendingAskCard;
    pendingAskTool = null;
    pendingAskGrantToken = null;
    clearTimeout(pendingAskExpiryTimer);
    pendingAskExpiryTimer = null;
    pendingAskCard = null;
    if (card) updateInlineAskCard(card, '已拒绝，Agent 会按未批准处理。', 'denied');
    document.getElementById('askModal').classList.remove('show');
    if (tool && grantToken && currentId) {
        fetch(permissionUrl(), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: currentId, action: 'deny_ask', tool, grantToken })
        }).catch(e => console.warn('deny approval failed', e));
    }
}
async function grantAskTool() {
    const tool = pendingAskTool;
    const grantToken = pendingAskGrantToken;
    const card = pendingAskCard;
    if (!tool || !grantToken || !currentId) return;
    try {
        const resp = await fetch(permissionUrl(), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: currentId, action: 'grant_ask', tool,
                grantToken })
        });
        const body = await readApiBody(resp);
        if (!resp.ok || !apiSuccess(body)) {
            throw new Error(apiFailureMessage(body, resp));
        }
        // 只有服务端确认放行之后才收起弹窗、清掉一次性 token。
        // 顺序反过来的话，请求真失败时用户既看不到原因、也拿不回 token，只能整轮重来。
        pendingAskTool = null;
        pendingAskGrantToken = null;
        clearTimeout(pendingAskExpiryTimer);
        pendingAskExpiryTimer = null;
        pendingAskCard = null;
        if (card) updateInlineAskCard(card, '已批准，Agent 正在继续执行。', 'approved');
        document.getElementById('askModal').classList.remove('show');
        setAskModalText('工具待确认');
        showAskActions();
    } catch (e) {
        console.warn('grant ask failed', e);
        // 失败只有三种可能：token 过期、等待点已结束（工具已按未批准返回）、
        // 或该 token 不属于本会话 —— 都意味着这个 token 不能再用了。
        // 所以清掉 token，但把原因留在原位，并且只给「知道了」。
        pendingAskTool = null;
        pendingAskGrantToken = null;
        clearTimeout(pendingAskExpiryTimer);
        pendingAskExpiryTimer = null;
        pendingAskCard = null;
        if (card) {
            updateInlineAskCard(card, '这次批准没有生效：' + e.message
                + '。Agent 会按未批准继续；需要执行时请在下一轮重新触发。', 'failed');
            return;
        }
        setAskModalText('这次批准没有生效：' + e.message
            + '。Agent 会按未批准继续；需要执行时请在下一轮重新触发。');
        document.getElementById('askModal').classList.add('show');
        showAskNotice();
        pendingAskExpiryTimer = setTimeout(dismissAskNotice, 8000);
    }
}

/**
 * 「本会话始终允许」：把当前工具写进会话级白名单，再放行这一次待批的调用。
 *
 * 顺序不能反：白名单只是让「之后的同名调用」不再询问，当前这一次仍然持有一次性
 * grantToken，必须由 grantAskTool() 走 PUT /api/permission 才能继续跑。
 * 白名单请求失败时不放行 —— 否则用户以为已经记住，实际下一轮还会再问一次。
 */
async function alwaysAllowAskTool() {
    const tool = pendingAskTool;
    if (!tool || !currentId) return;
    try {
        const resp = await fetch('/api/shell/permission/always', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: currentId, tool })
        });
        const body = await readApiBody(resp);
        if (!resp.ok || !apiSuccess(body)) {
            throw new Error(apiFailureMessage(body, resp));
        }
        await grantAskTool();
    } catch (e) {
        console.warn('always allow failed', e);
        if (pendingAskCard) {
            const status = pendingAskCard.querySelector('.permission-request-status');
            if (status) status.textContent = '没能记住这个工具：' + e.message
                + '。仍可批准并继续这一次调用。';
            return;
        }
        setAskModalText('没能记住这个工具：' + e.message + '。可以改用「批准并继续」只放行这一次。');
        document.getElementById('askModal').classList.add('show');
        showAskActions();
    }
}

function registerUserQuestionCard(card) {
    activeUserQuestionCard = card || null;
}

function beginUserQuestionAnswer(card) {
    if (!card || !card.isConnected || card.classList.contains('answered')) return;
    card.classList.add('submitting');
    card.querySelectorAll('button').forEach(button => { button.disabled = true; });
    const status = card.querySelector('.user-question-status');
    if (status) status.textContent = '正在提交你的回复…';
}

function markUserQuestionAnswered(text, card) {
    const target = card || activeUserQuestionCard;
    if (!target || !target.isConnected) {
        if (target === activeUserQuestionCard) activeUserQuestionCard = null;
        return;
    }
    target.classList.add('answered');
    target.classList.remove('submitting');
    target.querySelectorAll('button').forEach(button => { button.disabled = true; });
    const status = target.querySelector('.user-question-status');
    if (status) status.textContent = '已回复：' + String(text || '');
    if (target === activeUserQuestionCard) activeUserQuestionCard = null;
}

function resetUserQuestionCard(card, message) {
    if (!card || !card.isConnected || card.classList.contains('answered')) return;
    card.classList.remove('submitting');
    const hasSelection = !!card.querySelector('.user-question-option.selected');
    card.querySelectorAll('button').forEach(button => {
        button.disabled = button.classList.contains('user-question-submit') && !hasSelection;
        if (button.classList.contains('user-question-submit'))
            button.setAttribute('aria-disabled', button.disabled ? 'true' : 'false');
    });
    const status = card.querySelector('.user-question-status');
    if (status) status.textContent = message || '回复未发送，请重试。';
    registerUserQuestionCard(card);
}

async function answerUserQuestion(text, card) {
    if (!text || !currentId) return;
    const target = card || activeUserQuestionCard;
    const sessionId = currentId;
    if (target && target.dataset.sessionId && target.dataset.sessionId !== sessionId) return;
    beginUserQuestionAnswer(target);
    const deadline = Date.now() + 90000;
    while (isStreaming && Date.now() < deadline) {
        await new Promise(r => setTimeout(r, 150));
    }
    if (isStreaming) {
        if (target) resetUserQuestionCard(target, 'Agent 仍在收尾，请稍后再提交。');
        return;
    }
    await new Promise(r => setTimeout(r, 200));
    if (currentId !== sessionId || (target && !target.isConnected)) {
        resetUserQuestionCard(target, '会话已切换，请回到原会话后再回复。');
        return;
    }
    if (isStreaming) {
        resetUserQuestionCard(target, 'Agent 仍在收尾，请稍后再提交。');
        return;
    }
    document.getElementById('welcomeScreen')?.remove();
    if ($inp && $inp.value.trim() === text) {
        $inp.value = '';
        $inp.style.height = 'auto';
        onInputChange();
    }
    renderUserMsg(text, null, 0, []);
    addMsgToConv('user', text, null, null);
    const botBody = renderBotMsg(
        '<div class="typing-dots"><span></span><span></span><span></span></div>', true);
    setStreaming(true);
    sendChatPost(text, sessionId, [], [], botBody, target);
}

const CONFIRM_LABELS = { dangerous: '需确认', auto: '自动确认' };

function toggleConfirmMenu() {
    const menu = document.getElementById('confirmMenu');
    menu.classList.toggle('show');
    document.getElementById('permMenu')?.classList.remove('show');
    document.getElementById('roleMenu')?.classList.remove('show');
    document.getElementById('execMenu')?.classList.remove('show');
    if (menu.classList.contains('show')) {
        setTimeout(() => document.addEventListener('click', closeConfirmMenuOutside, { once: true }), 0);
    }
}
function closeConfirmMenuOutside(ev) {
    const wrap = document.getElementById('confirmSelector');
    if (wrap && wrap.contains(ev.target)) return;
    document.getElementById('confirmMenu')?.classList.remove('show');
}
function paintConfirmPolicyUi() {
    const label = CONFIRM_LABELS[confirmPolicy] || confirmPolicy;
    const btn = document.getElementById('confirmPolicyBtn');
    const labelEl = document.getElementById('confirmPolicyLabel');
    if (labelEl) labelEl.textContent = label;
    if (btn) {
        btn.title = confirmPolicy === 'auto' ? '自动确认' : '危险操作确认';
        btn.classList.toggle('active', confirmPolicy !== 'dangerous');
    }
    document.querySelectorAll('#confirmMenu .perm-item').forEach(el => {
        el.classList.toggle('active', el.dataset.policy === confirmPolicy);
    });
}
async function selectConfirmPolicy(policy) {
    confirmPolicy = policy === 'auto' ? 'auto' : 'dangerous';
    localStorage.setItem('mini_agent_confirm_policy', confirmPolicy);
    paintConfirmPolicyUi();
    document.getElementById('confirmMenu')?.classList.remove('show');
    if (currentId) {
        try {
            await fetch(permissionUrl(), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: currentId, confirmPolicy: confirmPolicy })
            });
        } catch (e) { console.warn('set confirm policy failed', e); }
    }
}

/* =========================================================================
   EXEC POLICY（终端命令执行策略：block / ask / allow）
   与后端 ExecPolicy 三档一一对应。'default' 表示清除会话覆盖、跟随全局默认。
   ------------------------------------------------------------------------
   和权限模式/确认策略不同，这一项**不能**只靠 localStorage：
   生效值 = 会话覆盖 ?? 全局默认，而全局默认只有后端知道。
   前端自己推会导致"全局配了 block，界面却显示放行"这种误导。
   所以这里一律以 GET /permission 返回的视图为准。
   ========================================================================= */
const EXEC_LABELS = { block: '禁止', ask: '需批准', allow: '放行' };
let execPolicyOverride = '';   // '' = 跟随全局
let execPolicyEffective = '';

function toggleExecMenu() {
    const menu = document.getElementById('execMenu');
    menu.classList.toggle('show');
    document.getElementById('permMenu')?.classList.remove('show');
    document.getElementById('confirmMenu')?.classList.remove('show');
    document.getElementById('roleMenu')?.classList.remove('show');
    if (menu.classList.contains('show')) {
        setTimeout(() => document.addEventListener('click', closeExecMenuOutside, { once: true }), 0);
    }
}
function closeExecMenuOutside(ev) {
    const wrap = document.getElementById('execSelector');
    if (wrap && wrap.contains(ev.target)) return;
    document.getElementById('execMenu')?.classList.remove('show');
}

/** 用后端返回的 permission 视图刷新 UI。 */
function applyExecPolicyView(v) {
    if (!v) return;
    execPolicyOverride = v.execPolicyOverride || '';
    execPolicyEffective = v.execPolicyEffective || '';
    const label = document.getElementById('execPolicyLabel');
    if (label) {
        const eff = EXEC_LABELS[execPolicyEffective] || execPolicyEffective || '执行';
        // 跟随全局时加前缀，否则用户会以为"需批准"是自己在这个会话里设的
        label.textContent = (execPolicyOverride ? eff : '跟随·' + eff);
    }
    const btn = document.getElementById('execPolicyBtn');
    // 只有"禁止"才高亮：放行/需批准都算正常态，不必让按钮一直亮着
    if (btn) btn.classList.toggle('active', execPolicyEffective === 'block');
    const hint = document.getElementById('execGlobalHint');
    if (hint) hint.textContent = '全局默认：' + (EXEC_LABELS[v.execPolicyGlobal] || v.execPolicyGlobal || '-');
    document.querySelectorAll('#execMenu .perm-item').forEach(el => {
        el.classList.toggle('active', el.dataset.exec === (execPolicyOverride || 'default'));
    });
}

async function refreshExecPolicyView() {
    if (!currentId) return;
    try {
        const resp = await fetch(permissionUrl());
        const data = await readApiBody(resp);
        if (apiSuccess(data) && data.data) applyExecPolicyView(data.data);
    } catch (e) { console.warn('load exec policy failed', e); }
}

async function selectExecPolicy(value) {
    document.getElementById('execMenu')?.classList.remove('show');
    if (!currentId) {
        alert('请先开始对话 —— 执行策略是按会话生效的（全局默认在配置文件里）。');
        return;
    }
    try {
        const resp = await fetch(permissionUrl(), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            // 'default' 直接透传给后端翻译成"清除覆盖"。前端不要自己翻成某一档，
            // 否则全局默认一改，这里就会发错档位。
            body: JSON.stringify({ sessionId: currentId, execPolicy: value })
        });
        const data = await readApiBody(resp);
        if (apiSuccess(data) && data.data) {
            applyExecPolicyView(data.data);
        } else {
            alert('切换失败：' + apiFailureMessage(data, resp));
        }
    } catch (e) {
        console.warn('set exec policy failed', e);
        alert('切换失败，请检查网络或后端日志。');
    }
}

/**
 * 回到"没有会话"的状态时复位执行策略按钮。
 * 不复位的话，新开会话会顶着上一个会话的"跟随·禁止"这类标签，
 * 而实际上新会话是全新的、还没有任何会话级覆盖。
 */
function resetExecPolicyView() {
    execPolicyOverride = '';
    execPolicyEffective = '';
    const label = document.getElementById('execPolicyLabel');
    if (label) label.textContent = '执行';
    document.getElementById('execPolicyBtn')?.classList.remove('active');
    const hint = document.getElementById('execGlobalHint');
    if (hint) hint.textContent = '';
    document.querySelectorAll('#execMenu .perm-item').forEach(el => {
        el.classList.toggle('active', el.dataset.exec === 'default');
    });
}

function syncPendingConfirm(items) {
    const waiting = (items || []).find(it =>
        String(it.status || '').toLowerCase() === 'awaiting_confirm');
    pendingTodoConfirm = waiting
        ? { id: waiting.id, content: waiting.content || '' }
        : null;
    refreshConfirmBanner();
}
function refreshConfirmBanner() {
    const banner = document.getElementById('confirmBanner');
    if (!banner) return;
    const show = pendingTodoConfirm && !isStreaming;
    banner.classList.toggle('show', !!show);
    if (show) {
        const text = document.getElementById('confirmBannerText');
        if (text) {
            text.textContent = '步骤 #' + pendingTodoConfirm.id
                + ' 需要确认：' + pendingTodoConfirm.content;
        }
    }
    document.querySelectorAll('.todo-confirm-btn').forEach(btn => {
        btn.disabled = !!isStreaming;
    });
}
function confirmPendingTodo() {
    if (!pendingTodoConfirm) return;
    confirmTodoAndContinue(pendingTodoConfirm.id);
}

/**
 * 批准规则计划：走和手动输入「开始」**完全相同**的路径 ——
 * 消息进入正常聊天接口，后端的审批判定照旧生效，不开旁路。
 * 草稿先挪开，批准后还回输入框，避免用户已经打了一半的话被吞掉。
 */
function confirmPlanApproval() {
    const banner = document.getElementById('planConfirm');
    if (banner) banner.classList.remove('show');
    const draft = $inp.value;
    fillInput('开始');
    send();
    if (draft.trim()) {
        setTimeout(() => {
            if (!$inp.value.trim()) {
                $inp.value = draft;
                onInputChange();
            }
        }, 0);
    }
}

/** 调整计划：收起确认条，把光标交回输入框，让用户直接说改什么。 */
function adjustPlanApproval() {
    const banner = document.getElementById('planConfirm');
    if (banner) banner.classList.remove('show');
    $inp.focus();
}
async function confirmTodoAndContinue(id) {
    if (!currentId || isStreaming) return;
    try {
        const resp = await fetch(
            '/api/conversations/' + encodeURIComponent(currentId) + '/todos/' + encodeURIComponent(id),
            { method: 'PATCH' });
        const body = await resp.json();
        if (!body.success) {
            alert(body.message || '确认失败');
            return;
        }
        pendingTodoConfirm = null;
        refreshConfirmBanner();
        const text = SYS_PREFIX + '继续执行已确认的步骤 #' + id + '，请继续。';
        document.getElementById('welcomeScreen')?.remove();
        renderUserMsg(text, null, 0, []);
        addMsgToConv('user', text, null, null);
        const botBody = renderBotMsg(
            '<div class="typing-dots"><span></span><span></span><span></span></div>', true);
        setStreaming(true);
        sendChatPost(text, currentId, [], [], botBody);
    } catch (e) {
        alert('确认失败: ' + e.message);
    }
}

(function initPermUi() {
    // 仅同步本地 UI，避免首屏无 session 时误打 API
    document.getElementById('permLabel').textContent = PERM_LABELS[permissionMode] || permissionMode;
    document.getElementById('permBtn')?.classList.toggle('active', permissionMode !== 'default');
    document.querySelectorAll('#permMenu .perm-item').forEach(el => {
        el.classList.toggle('active', el.dataset.mode === permissionMode);
    });
    refreshPlanBanner();
    paintConfirmPolicyUi();
})();

function scrollToBottom(smooth = false, force = false) {
    if (!force && !stickToBottom) return;
    if (force) {
        stickToBottom = true;
    }
    $msg.scrollTo({ top: $msg.scrollHeight, behavior: smooth ? 'smooth' : 'instant' });
}

