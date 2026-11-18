/* =========================================================================
   INIT
   ========================================================================= */
function getCurrentUser() {
    const match = document.cookie.match(/(?:^|;\s*)uname=([^;]*)/);
    return match ? decodeURIComponent(match[1]) : null;
}

function formatQuotaTokens(value) {
    const n = Number(value || 0);
    if (n >= 100000000) return (n / 100000000).toFixed(1).replace(/\.0$/, '') + '亿';
    if (n >= 10000) return (n / 10000).toFixed(1).replace(/\.0$/, '') + '万';
    return String(Math.max(0, Math.floor(n)));
}

function loadDailyTokenQuota() {
    const badge = document.getElementById('railUserQuota');
    if (!badge) return;
    // Refresh the local shadow user's membership allowance when an account-side
    // subscription changed in the account portal since the previous chat session.
    fetch('/api/membership/current').catch(function () { return null; })
        .then(function () { return fetch('/api/me/token-quota'); })
        .then(function (response) { return response.json(); })
        .then(function (result) {
            if (!result.success || !result.data) {
                badge.textContent = '额度不可用';
                return;
            }
            const quota = result.data;
            const remaining = quota.availableTokens == null ? '不限' : formatQuotaTokens(quota.availableTokens);
            badge.textContent = '余 ' + remaining;
            badge.title = quota.userUnlimited
                ? '今日已用 ' + formatQuotaTokens(quota.userUsedTokens) + ' Token · 当前可用不限'
                : '今日已用 ' + formatQuotaTokens(quota.userUsedTokens) + ' / '
                    + formatQuotaTokens(quota.userDailyLimit) + ' Token';
        }).catch(function () {
            badge.textContent = '额度不可用';
        });
}

(async function () {
    loadDailyTokenQuota();
    const userName = getCurrentUser();
    const nameEl = document.getElementById('sbUserName');
    if (nameEl && userName) {
        nameEl.textContent = userName;
    }

    const savedId = localStorage.getItem(LS_CUR);
    if (savedId) {
        await switchConv(savedId);
    } else {
        $msg.innerHTML = WELCOME;
        renderSidebar();
    }
    $inp.focus();
})();
