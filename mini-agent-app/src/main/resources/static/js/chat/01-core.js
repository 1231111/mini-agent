async function openAccountPortal() {
    const api = window.electronAPI;
    if (api && api.getPortalUrl && api.openExternal) {
        try {
            const url = await api.getPortalUrl();
            if (url) {
                const opened = await api.openExternal(
                    url.replace(/\/+$/, '') + '/account');
                if (!opened || !opened.ok) {
                    alert('打不开账号页：'
                        + ((opened && opened.error) || '未知原因'));
                }
                return;
            }
        } catch (e) {
            alert(e.message || e);
            return;
        }
    }
    window.location.href = '/membership';
}

/* =========================================================================
   DATA LAYER — conversations persisted to localStorage
   Structure:
     mini_agent_convs : [{id, title, updatedAt, messages:[{role,text,html,imgSrc}]}]
     mini_agent_cur   : current conversation id
   ========================================================================= */
const LS_CONVS = 'mini_agent_convs';
const LS_CUR   = 'mini_agent_cur';
const LS_THEME = 'mini_agent_theme';
const MAX_CONVS     = 60;
const MAX_MSG_CHARS = 8000;

/**
 * 系统控制语前缀。**必须与后端 MessageConstants.SYSTEM_MESSAGE_PREFIX 逐字一致。**
 *
 * 后端 isSystemControlMessage 只认前缀、不看语义地决定「这一轮要不要提任务信号」，
 * 所以两者一旦不一致就会静默失效 —— 控制语被当成用户任务照常走规划器。
 * 2026-10-15 实测：「确认并继续」按钮拼的那条漏了前缀，后端收到
 * 「继续执行已确认的步骤 #1，请继续。」后给出
 * TASK_SIGNALS={"signals":"complex,action,continue"} 并真拉出 {"nodes":4} 任务图
 * （「执行」命中 task-action-signals、「步骤」命中 complex-signals、「继续」命中 continue-signals）。
 * 前端无法 import Java 常量，所以这里靠这一行注释 + 拼装点全部走本常量来维持一致：
 * **新增任何前端拼的控制语，一律 SYS_PREFIX + 正文，不要写字面量。**
 */
const SYS_PREFIX = '【系统】';

/**
 * **显示层**判据：凡以「【系统」开头的都是系统拼进会话的消息，不装成用户气泡。
 *
 * 为什么比 SYS_PREFIX 宽：后端另有一类 `【系统通知】…`
 * （`AGENT_LLM_NETWORK_RECONNECT`、`AGENT_LLM_REFUSE_RETRY` 等，网络重连与安全网关拒答后的一轮恢复），
 * 它们同样是系统拼的、同样不该显示成用户输入。但两个前缀第 4 个字符就分岔了
 * （`【系统】` 是 `】`，`【系统通知】` 是 `通`），所以后端的
 * `isSystemControlMessage` **不认**它们。
 *
 * **显示判据与语义判据在这里刻意不同，两边各有明确职责**：
 * 语义层（后端 `isSystemControlMessage`）决定「这一轮要不要提任务信号」，必须与后端逐字一致，
 * 多认一个词就可能让真实用户消息漏掉任务信号；
 * 显示层只回答「这条是不是用户打的字」，宽一点更对 —— 漏判的后果是把系统消息画成用户气泡。
 */
const SYS_TEXT_PREFIX = '【系统';

/** 是否系统拼进会话的消息（显示层判据，见 SYS_TEXT_PREFIX 的说明）。 */
function isSystemAuthoredText(text) {
    return typeof text === 'string' && text.trimStart().startsWith(SYS_TEXT_PREFIX);
}

/** 解包统一 ApiResponse：有 success+data 时取 data，否则原样返回。 */
async function apiJson(resp) {
    const body = await resp.json();
    if (body && typeof body === 'object'
            && Object.prototype.hasOwnProperty.call(body, 'success')
            && Object.prototype.hasOwnProperty.call(body, 'data'))
        return body.data;
    return body;
}
function unwrapApiBody(body) {
    if (body && typeof body === 'object'
            && Object.prototype.hasOwnProperty.call(body, 'success')
            && Object.prototype.hasOwnProperty.call(body, 'data'))
        return body.data;
    return body;
}

let currentId    = null;
let selectedFiles = [];
let uploadedFileRefs = [];
let uploadingCount = 0;  // 正在上传的文件数
let previewSrcs   = [];
let sidebarOpen  = true;
let selectedRole = '';  // 当前选择的角色
let permissionMode = localStorage.getItem('mini_agent_perm_mode') || 'default';
let confirmPolicy = localStorage.getItem('mini_agent_confirm_policy') || 'dangerous';
let pendingTodoConfirm = null;
let pendingAskTool = null;
let pendingAskGrantToken = null;
/** 审批弹窗的过期定时器：服务端 token 过期后再点「批准」只会拿到 403，所以到点就自动收起。 */
let pendingAskExpiryTimer = null;
let isStreaming  = false;
let streamEpoch  = 0;
// 本 turn 的起止时间戳，只给思考折叠条的表头文案用（「已处理 12s」/「已完成 12s」）。
// 口径对齐 WorkBuddy 的 turnDurationMs：那次是「本 turn 最后一条 assistant 的
// finishTime 减去最后一条 user 的 createTime」，这里退化成「进入流式态 → 定稿」，
// 因为我们没有逐条消息的 createTime/finishTime 字段。
let turnStartedAt  = 0;
let turnFinishedAt = 0;
/**
 * 表头耗时的刷新定时器。
 *
 * <p>文案里的秒数是 `Date.now() - turnStartedAt`，算得是对的，但它只在
 * {@code paint()} 里被重画，而 `paint()` 由 SSE 增量驱动。模型停住等工具、
 * 或在等首包时**没有任何增量**，秒数就冻在上一个事件那一刻（看到的「已处理 26s」不动）。
 * 因此必须有一个与事件无关的时钟。</p>
 */
let thinkTickTimer = null;
const SCROLL_BOTTOM_PX = 80;
let stickToBottom = true;

const $msg  = document.getElementById('messages');
const $inp  = document.getElementById('msgInput');
const $send = document.getElementById('sendBtn');

// ---- localStorage helpers ----
function getAllConvs() { try { return JSON.parse(localStorage.getItem(LS_CONVS) || '[]'); } catch { return []; } }
function saveAllConvs(a) { try { localStorage.setItem(LS_CONVS, JSON.stringify(a)); } catch {} }
function getConv(id) { return getAllConvs().find(c => c.id === id) || null; }
function saveConv(c) {
    let a = getAllConvs().filter(x => x.id !== c.id);
    a.unshift(c);
    if (a.length > MAX_CONVS) a = a.slice(0, MAX_CONVS);
    saveAllConvs(a);
}
function delConv(id) { saveAllConvs(getAllConvs().filter(c => c.id !== id)); }

