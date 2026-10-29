/* =========================================================================
   THEME TOGGLE — 明暗切换，持久化到 localStorage
   ========================================================================= */
function paintThemeUi() {
    const light = document.documentElement.getAttribute('data-theme') === 'light';
    const el = document.getElementById('themeLabel');
    if (el) {
        el.textContent = light ? '浅色' : '深色';
    }
}

function toggleTheme() {
    const cur = document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
    const next = cur === 'light' ? 'dark' : 'light';
    document.documentElement.setAttribute('data-theme', next);
    try { localStorage.setItem(LS_THEME, next); } catch (e) {}
    paintThemeUi();
}

function openSettings() {
    document.getElementById('settingsModal')?.classList.add('show');
    paintThemeUi();
    loadProcedureProposals();
}

/** 会员页：整页跳转（与 membership.html 里"← 返回对话"配对） */
function openMembership() {
    window.location.href = '/membership';
}

/** 轨迹页：整页跳转。不用 window.open —— Electron 里会另开窗口，
 *  而 sessionStorage 里的令牌不一定跟过去，结果是一个打不开的空壳页面。 */
function openTraces() {
    window.location.href = '/trace';
}

/**
 * 侧栏「工作区」分区右上角的 ＋：
 *   桌面壳有原生目录选择 → 直接选目录并应用（保存成功会关菜单）；
 *   网页版没有这个能力 → 打开菜单并聚焦输入框，让用户手填路径。
 * 两种情况都不会出现"点了没反应"。
 */
function openWorkspacePicker() {
    const menu = document.getElementById('wsMenu');
    const canPick = !!window.electronAPI?.pickDirectory;
    if (!menu?.classList.contains('show')) toggleWsMenu();
    if (canPick) {
        pickWorkspaceDirectory();
    } else {
        setTimeout(() => document.getElementById('wsInput')?.focus(), 30);
    }
}

async function loadProcedureProposals() {
    const list = document.getElementById('procedureProposalList');
    if (!list) return;
    list.textContent = '正在加载…';
    try {
        const token = window.MiniAuth?.token();
        if (!token) throw new Error('登录状态已失效');
        const response = await fetch('/v1/memory/procedure-proposals', {
            headers: { Authorization: 'Bearer ' + token }
        });
        const body = await response.json();
        if (!response.ok || !apiSuccess(body)) throw new Error(apiFailureMessage(body, response));
        const proposals = apiJsonData(body);
        list.replaceChildren();
        if (!Array.isArray(proposals) || proposals.length === 0) {
            list.textContent = '暂无改写提案';
            return;
        }
        proposals.forEach(proposal => {
            const section = document.createElement('section');
            section.className = 'settings-section';
            const title = document.createElement('strong');
            title.textContent = `${proposal.procedureName || '未命名 SOP'} · v${proposal.baseVersion}`;
            const status = document.createElement('div');
            status.className = 'settings-row-meta';
            status.textContent = `状态：${proposal.status}`;
            section.append(title, status);
            const candidate = parseProposalJson(proposal.proposedContentJson);
            const steps = document.createElement('pre');
            steps.style.cssText = 'white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;font-size:12px;max-height:180px;overflow:auto';
            steps.textContent = `候选步骤：\n${Array.isArray(candidate.steps) ? candidate.steps.join('\n') : '无'}`;
            section.appendChild(steps);
            const evidence = document.createElement('details');
            const summary = document.createElement('summary'); summary.textContent = '查看失败证据';
            const evidenceText = document.createElement('pre');
            evidenceText.style.cssText = 'white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;font-size:12px';
            const parsedEvidence = parseProposalJson(proposal.evidenceJson);
            evidenceText.textContent = String(parsedEvidence.resolution || '无可展示证据');
            evidence.append(summary, evidenceText); section.appendChild(evidence);
            if (proposal.status === 'PENDING') {
                const actions = document.createElement('div');
                actions.className = 'model-menu-actions';
                ['拒绝', '批准'].forEach((label, index) => {
                    const button = document.createElement('button');
                    button.type = 'button'; button.textContent = label;
                    button.className = index ? 'model-btn-primary' : 'model-btn-secondary';
                    button.onclick = () => reviewProcedureProposal(proposal.id, index === 1, button);
                    actions.appendChild(button);
                });
                section.appendChild(actions);
            }
            list.appendChild(section);
        });
    } catch (error) {
        list.textContent = '提案加载失败：' + (error.message || '请求失败');
    }
}

function parseProposalJson(value) {
    try { return JSON.parse(value || '{}'); } catch (_) { return {}; }
}

function apiJsonData(body) {
    return body && Object.prototype.hasOwnProperty.call(body, 'data') ? body.data : body;
}

async function reviewProcedureProposal(id, approved, button) {
    button.disabled = true;
    try {
        const token = window.MiniAuth?.token();
        const response = await fetch(`/v1/memory/procedure-proposals/${encodeURIComponent(id)}/review`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
            body: JSON.stringify({ approved })
        });
        const body = await response.json();
        if (!response.ok || !apiSuccess(body)) throw new Error(apiFailureMessage(body, response));
        await loadProcedureProposals();
    } catch (error) {
        alert('审核失败：' + (error.message || '请求失败'));
        button.disabled = false;
    }
}

function closeSettings() {
    document.getElementById('settingsModal')?.classList.remove('show');
}

/* =========================================================================
   LOGOUT — DELETE /api/tokens（会摘掉服务端 SSE 流），
   点完不管后端成功与否都清本地 token 并回登录页：留着 token 也不能用，
   Redis 里的 jti 已经没了，硬留在页面上只会在下一个请求吃 401。
   ========================================================================= */
async function doLogout() {
    const btn = document.getElementById('logoutBtn');
    if (btn) btn.disabled = true;
    try {
        await fetch('/api/tokens', { method: 'DELETE' });
    } catch (err) {
        console.warn('[Logout] request failed, clearing local token anyway', err);
    }
    try { window.MiniAuth.clear(); } catch (_) {}
    window.location.replace('/login');
}

document.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
        closeSettings();
    }
});
paintThemeUi();

