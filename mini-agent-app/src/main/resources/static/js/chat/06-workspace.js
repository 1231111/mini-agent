/* =========================================================================
   WORKSPACE（会话级工作目录）
   ========================================================================= */

function workspaceUrl() {
    return '/api/conversations/' + encodeURIComponent(currentId) + '/workspace';
}

function toggleWsMenu() {
    const menu = document.getElementById('wsMenu');
    if (!menu) return;
    const willShow = !menu.classList.contains('show');
    ['permMenu', 'confirmMenu', 'execMenu', 'roleMenu'].forEach(id =>
        document.getElementById(id)?.classList.remove('show'));
    menu.classList.toggle('show', willShow);
    if (willShow) {
        // 原生目录选择只在桌面壳里有；网页版没有这个能力，按钮直接隐藏
        const pick = document.getElementById('wsPickBtn');
        if (pick) pick.style.display = window.electronAPI?.pickDirectory ? '' : 'none';
        const unavailable = !currentId;
        ['wsInput', 'wsPickBtn'].forEach(id => {
            const control = document.getElementById(id);
            if (control) control.disabled = unavailable;
        });
        document.querySelectorAll('#wsMenu .ws-act').forEach(control => {
            control.disabled = unavailable;
        });
        const hint = document.getElementById('wsHint');
        loadWorkspace().then(() => {
            if (hint && unavailable && menu.classList.contains('show')) {
                hint.textContent = '工作区按会话保存，请先打开一个会话';
            }
        });
        if (!unavailable) setTimeout(() => document.getElementById('wsInput')?.focus(), 0);
        setTimeout(() => document.addEventListener('click', closeWsMenuOutside), 0);
    }
}

function closeWsMenuOutside(ev) {
    const wrap = document.getElementById('wsSelector');
    if (wrap && wrap.contains(ev.target)) return;
    document.getElementById('wsMenu')?.classList.remove('show');
    document.removeEventListener('click', closeWsMenuOutside);
}

function closeWsMenu() {
    document.getElementById('wsMenu')?.classList.remove('show');
    document.removeEventListener('click', closeWsMenuOutside);
}

function shortWorkspaceLabel(path) {
    if (!path) return '默认工作区';
    const parts = String(path).split(/[\\/]+/).filter(Boolean);
    return parts.length ? parts[parts.length - 1] : path;
}

function paintWorkspaceView(view) {
    const current = document.getElementById('wsCurrent');
    const label = document.getElementById('wsLabel');
    const hint = document.getElementById('wsHint');
    const effective = view?.effectivePath || view?.path || null;
    const bound = !!view?.path;
    if (current) current.textContent = effective || '默认工作区';
    if (label) {
        // 侧栏里的这一项显示**工作区名**：未绑定时明确写"默认工作区"，
        // 而不是一个含糊的"工作区"——左侧栏的位置比原来输入框上的小标签更正式。
        label.textContent = bound ? shortWorkspaceLabel(effective) : '默认工作区';
        label.title = effective || '默认工作区';
    }
    if (hint) {
        hint.classList.toggle('is-error', !!view?.missing);
        hint.textContent = view?.missing
            ? '绑定目录已不存在，当前回退到默认工作区'
            : (bound ? '' : '未指定时用默认工作区，文件写在数据目录下');
    }
    const btn = document.getElementById('wsBtn');
    if (btn) btn.classList.toggle('active', bound);
}

async function loadWorkspace() {
    if (!currentId) { paintWorkspaceView(null); return; }
    try {
        const resp = await fetch(workspaceUrl());
        const body = await readApiBody(resp);
        if (!apiSuccess(body)) { paintWorkspaceView(null); return; }
        const view = body.data || {};
        paintWorkspaceView(view);
        const input = document.getElementById('wsInput');
        if (input && document.activeElement !== input) input.value = view.path || '';
    } catch (_) {
        paintWorkspaceView(null);
    }
}

async function saveWorkspace() {
    const input = document.getElementById('wsInput');
    const hint = document.getElementById('wsHint');
    const path = (input?.value || '').trim();
    if (!currentId) { if (hint) hint.textContent = '请先打开一个会话'; return; }
    if (!path) { if (hint) { hint.textContent = '请填写绝对路径'; hint.classList.add('is-error'); } return; }
    if (hint) { hint.classList.remove('is-error'); hint.textContent = '正在校验…'; }
    try {
        const resp = await fetch(workspaceUrl(), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path })
        });
        const body = await readApiBody(resp);
        if (!apiSuccess(body)) {
            if (hint) { hint.textContent = apiFailureMessage(body, resp); hint.classList.add('is-error'); }
            return;
        }
        paintWorkspaceView(body.data || {});
        if (hint) hint.textContent = '已切换，下一次发送消息生效';
        closeWsMenu();
    } catch (e) {
        if (hint) { hint.textContent = '保存失败: ' + e.message; hint.classList.add('is-error'); }
    }
}

async function resetWorkspace() {
    const hint = document.getElementById('wsHint');
    if (!currentId) return;
    try {
        const resp = await fetch(workspaceUrl(), { method: 'DELETE' });
        const body = await readApiBody(resp);
        if (!apiSuccess(body)) {
            if (hint) { hint.textContent = apiFailureMessage(body, resp); hint.classList.add('is-error'); }
            return;
        }
        const input = document.getElementById('wsInput');
        if (input) input.value = '';
        paintWorkspaceView(body.data || null);
        if (hint) { hint.classList.remove('is-error'); hint.textContent = '已恢复默认工作区'; }
        closeWsMenu();
    } catch (e) {
        if (hint) { hint.textContent = '恢复失败: ' + e.message; hint.classList.add('is-error'); }
    }
}

async function pickWorkspaceDirectory() {
    const pick = window.electronAPI?.pickDirectory;
    if (!pick) return;
    try {
        const dir = await pick();
        if (!dir) return;
        const input = document.getElementById('wsInput');
        if (input) input.value = dir;
        await saveWorkspace();
    } catch (e) {
        const hint = document.getElementById('wsHint');
        if (hint) { hint.textContent = '无法打开目录选择: ' + e.message; hint.classList.add('is-error'); }
    }
}

