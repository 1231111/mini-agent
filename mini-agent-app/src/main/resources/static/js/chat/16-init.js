/* =========================================================================
   INIT
   ========================================================================= */
function getCurrentUser() {
    const match = document.cookie.match(/(?:^|;\s*)uname=([^;]*)/);
    return match ? decodeURIComponent(match[1]) : null;
}

(async function () {
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
