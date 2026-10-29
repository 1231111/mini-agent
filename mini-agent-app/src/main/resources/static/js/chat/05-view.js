/* =========================================================================
   SCROLL-TO-BOTTOM BUTTON
   ========================================================================= */
const $scrollBtn = document.getElementById('scrollBtn');
const $main = document.querySelector('.main');

function innerCanScroll(el, deltaY) {
    if (!el || el.scrollHeight <= el.clientHeight + 1) {
        return false;
    }
    if (deltaY < 0) {
        return el.scrollTop > 0;
    }
    return el.scrollTop + el.clientHeight < el.scrollHeight - 1;
}

$msg.addEventListener('scroll', () => {
    stickToBottom = $msg.scrollHeight - $msg.scrollTop - $msg.clientHeight < SCROLL_BOTTOM_PX;
    $scrollBtn.classList.toggle('visible', !stickToBottom);
    scheduleTaskPinUpdate();

    // Infinite scroll: load older messages when scrolling to top
    if ($msg.scrollTop < 100 && window._msgHasMore && !window._msgLoading && currentId) {
        loadMoreMessages(currentId, true);
    }
});

// 任务条 / 输入框不在 #messages 里，滚轮落在上面时原先滚不动对话。
// 往上滚立刻松吸底，避免流式 scrollToBottom 把人拽回去。
$main.addEventListener('wheel', (e) => {
    if (e.deltaY < 0) {
        stickToBottom = false;
    }
    if (e.target.closest('textarea, input, select')) {
        return;
    }
    const inner = e.target.closest('.think-body, .plan-row .todo-panel');
    if (innerCanScroll(inner, e.deltaY)) {
        return;
    }
    if ($msg.contains(e.target)) {
        return;
    }
    $msg.scrollTop += e.deltaY;
}, { passive: true });
$msg.addEventListener('touchmove', () => { stickToBottom = false; }, { passive: true });

/* =========================================================================
   顶部任务条（task pin）
   -------------------------------------------------------------------------
   命中规则：取「顶边已经滚过消息区上沿」的最后一条 .user-row。
   之所以用「最后一条滚过上沿的」而不是「离上沿最近的」：
   后者会在两条用户消息的交界处来回跳，前者只会单向替换，滚动时稳定。

   重算时机：滚动 / $msg 内容变化（流式追加、历史 prepend、会话切换）/
             窗口缩放。全部经 rAF 合并，一次滚动只算一帧。
   ========================================================================= */
const $taskPin     = document.getElementById('taskPin');
const $taskPinIdx  = document.getElementById('taskPinIdx');
const $taskPinText = document.getElementById('taskPinText');

let _pinRows      = null;  // .user-row 缓存
let _pinRowsStamp = -1;    // 缓存对应的 $msg.childElementCount
let _pinCurrent   = null;  // 当前命中的 row
let _pinCount     = -1;    // 当前命中时的任务总数
let _pinRaf       = 0;

/** 取 .user-row 列表；子节点数变了才重新 query，避免每次滚动都扫 DOM */
function pinRows() {
    const stamp = $msg.childElementCount;
    if (!_pinRows || stamp !== _pinRowsStamp) {
        _pinRows = Array.from($msg.querySelectorAll('.user-row'));
        _pinRowsStamp = stamp;
        _pinCurrent = null;
        _pinCount = -1;
    }
    return _pinRows;
}

function scheduleTaskPinUpdate() {
    if (_pinRaf) return;
    _pinRaf = requestAnimationFrame(() => { _pinRaf = 0; updateTaskPin(); });
}

function updateTaskPin() {
    const rows = pinRows();
    if (!rows.length) {
        _pinCurrent = null; _pinCount = -1;
        $taskPin.classList.remove('show');
        return;
    }

    // 只读一次布局：首次 getBoundingClientRect 触发布局，后续读都是命中缓存的
    const top = $msg.getBoundingClientRect().top;
    let hit = null, hitIdx = -1;
    for (let i = 0; i < rows.length; i++) {
        if (rows[i].getBoundingClientRect().top <= top + 8) { hit = rows[i]; hitIdx = i; }
        else break;   // 块级布局里 top 单调递增，可提前退出
    }
    // 首条用户消息都还没滚过（如刚 prepend 完老消息），退化为第一条
    if (!hit) { hit = rows[0]; hitIdx = 0; }

    if (hit === _pinCurrent && rows.length === _pinCount) return;

    _pinCurrent = hit;
    _pinCount = rows.length;
    const label = hit.dataset.task || '';
    $taskPinIdx.textContent = (hitIdx + 1) + '/' + rows.length;
    $taskPinText.textContent = label || '（本条无文本，仅附件）';
    $taskPin.title = (label ? '任务：' + label + '\n' : '') + '点击定位到该任务';
    $taskPin.classList.add('show');
}

/** 点击任务条 → 把该条用户消息滚到消息区顶部 */
function taskPinJump() {
    if (!_pinCurrent) return;
    _pinCurrent.scrollIntoView({ block: 'start', behavior: 'smooth' });
}

// 内容变化即重算（流式增量写的是 bot-body 的文本节点，所以必须监听 characterData）
new MutationObserver(scheduleTaskPinUpdate)
    .observe($msg, { childList: true, subtree: true, characterData: true });
window.addEventListener('resize', scheduleTaskPinUpdate);

// ---- 角色选择器 ----
function toggleRoleMenu() {
    const menu = document.getElementById('roleMenu');
    menu.classList.toggle('show');
    document.getElementById('permMenu')?.classList.remove('show');
    document.getElementById('confirmMenu')?.classList.remove('show');
    document.getElementById('execMenu')?.classList.remove('show');
    // 点击外部关闭
    if (menu.classList.contains('show')) {
        setTimeout(() => {
            document.addEventListener('click', closeRoleMenu, { once: true });
        }, 0);
    }
}

function closeRoleMenu() {
    document.getElementById('roleMenu').classList.remove('show');
}

function selectRole(role) {
    selectedRole = role;
    const btn = document.getElementById('roleBtn');
    const label = document.getElementById('roleLabel');
    const menu = document.getElementById('roleMenu');

    // 更新按钮状态
    if (role) {
        btn.classList.add('active');
        const roleNames = {
            'tester': '测试工程师',
            'developer': '开发工程师',
            'pm': '产品经理',
            'designer': 'UI设计师',
            'security': '安全工程师',
            'ops': '运维工程师',
            'dba': '数据库管理员',
            'architect': '架构师',
            'tech_writer': '技术文档工程师'
        };
        label.textContent = roleNames[role] || role;
    } else {
        btn.classList.remove('active');
        label.textContent = '角色';
    }

    // 更新菜单项状态
    menu.querySelectorAll('.role-item').forEach(item => {
        item.classList.toggle('active', item.dataset.role === role);
    });

    // 关闭菜单
    menu.classList.remove('show');

    // 保存到本地存储
    localStorage.setItem('mini_agent_role', role);
}

// 页面加载时恢复角色选择
(function() {
    const saved = localStorage.getItem('mini_agent_role');
    if (saved) {
        selectRole(saved);
    }
})();

/* =========================================================================
   MODEL CONFIG（预设 + 自定义，服务端按用户持久化）
   ========================================================================= */
let modelConfigState = {
    presetId: 'default',
    presets: [],
    customBaseUrl: '',
    customModelName: '',
    hasCustomApiKey: false
};

function selectModelPreset(presetId) {
    modelConfigState.presetId = presetId || 'default';
    const preset = (modelConfigState.presets || []).find(p => p.id === presetId);
    document.querySelectorAll('.model-preset-item').forEach(el => {
        el.classList.toggle('active', el.dataset.id === modelConfigState.presetId);
    });
    if (preset) {
        // 仅当自定义框为空时用预设值预填展示（不强制覆盖用户已填）
        const baseEl = document.getElementById('modelBaseUrl');
        const nameEl = document.getElementById('modelNameInput');
        if (!baseEl.value) baseEl.placeholder = preset.baseUrl || '留空则用预设/全局默认';
        if (!nameEl.value) nameEl.placeholder = preset.modelName || '如 gpt-4o-mini';
    }
}

function applyModelConfigView(data) {
    if (!data || !data.success) return;
    const cur = data.current || {};
    modelConfigState.presetId = cur.presetId || data.defaultPreset || 'default';
    modelConfigState.presets = data.presets || [];
    modelConfigState.customBaseUrl = cur.customBaseUrl || '';
    modelConfigState.customModelName = cur.customModelName || '';
    modelConfigState.hasCustomApiKey = !!cur.hasCustomApiKey;

    const label = cur.label || cur.modelName || '模型';
    document.getElementById('modelBadgeLabel').textContent = label;
    try { localStorage.setItem('mini_agent_model_label', label); } catch (_) {}

    const list = document.getElementById('modelPresetList');
    list.innerHTML = '';
    (modelConfigState.presets || []).forEach(p => {
        const div = document.createElement('div');
        div.className = 'model-preset-item' + (p.id === modelConfigState.presetId ? ' active' : '');
        div.dataset.id = p.id;
        div.onclick = () => selectModelPreset(p.id);
        div.innerHTML = '<span class="mp-label"></span><span class="mp-meta"></span>';
        div.querySelector('.mp-label').textContent = p.label || p.id;
        div.querySelector('.mp-meta').textContent = (p.modelName || '') + (p.baseUrl ? ' · ' + p.baseUrl : '');
        list.appendChild(div);
    });

    document.getElementById('modelBaseUrl').value = modelConfigState.customBaseUrl;
    document.getElementById('modelNameInput').value = modelConfigState.customModelName;
    document.getElementById('modelApiKey').value = '';
    document.getElementById('modelApiKey').placeholder = modelConfigState.hasCustomApiKey
        ? '已配置自定义 Key，留空不改'
        : (cur.apiKeyMasked ? ('全局/预设 ' + cur.apiKeyMasked + '，填新值可覆盖') : '已配置则留空不改');
    document.getElementById('modelMenuHint').textContent =
        '当前生效：' + (cur.modelName || '-') + ' @ ' + (cur.baseUrl || '-')
        + (cur.apiKeyMasked ? ' · key ' + cur.apiKeyMasked : '');
}

async function loadModelConfig() {
    try {
        const resp = await fetch('/api/model');
        if (!resp.ok) return;
        const data = await resp.json();
        applyModelConfigView(data);
    } catch (e) {
        console.warn('loadModelConfig failed', e);
        const cached = localStorage.getItem('mini_agent_model_label');
        if (cached) document.getElementById('modelBadgeLabel').textContent = cached;
    }
}

async function saveModelConfig() {
    const body = {
        presetId: modelConfigState.presetId,
        baseUrl: document.getElementById('modelBaseUrl').value.trim(),
        modelName: document.getElementById('modelNameInput').value.trim(),
        apiKey: document.getElementById('modelApiKey').value
    };
    try {
        const resp = await fetch('/api/model', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        const data = await resp.json();
        if (!data.success) {
            document.getElementById('modelMenuHint').textContent = data.message || '保存失败';
            return;
        }
        applyModelConfigView(data);
    } catch (e) {
        document.getElementById('modelMenuHint').textContent = '保存失败: ' + e.message;
    }
}

async function resetModelConfig() {
    try {
        const resp = await fetch('/api/model', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ reset: true })
        });
        const data = await resp.json();
        applyModelConfigView(data);
    } catch (e) {
        document.getElementById('modelMenuHint').textContent = '恢复失败: ' + e.message;
    }
}

loadModelConfig();

