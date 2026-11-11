/* =========================================================================
   INPUT HANDLING
   ========================================================================= */
$inp.addEventListener('input', function () {
    this.style.height = 'auto';
    this.style.height = Math.min(this.scrollHeight, 200) + 'px';
    onInputChange();
});

// Support pasting screenshots/images directly into the input box (multi-image).
$inp.addEventListener('paste', e => {
    const items = e.clipboardData?.items;
    if (!items) return;
    let added = false;
    for (const item of items) {
        if (item.kind === 'file' && item.type.startsWith('image/')) {
            const file = item.getAsFile();
            if (file) {
                const ext = item.type.split('/')[1] || 'png';
                const named = new File([file], `pasted-image-${Date.now()}-${selectedFiles.length}.${ext}`, { type: item.type });
                addFile(named);
                added = true;
            }
        }
    }
    if (added) e.preventDefault();
});

function onInputChange() {
    const hasText = $inp.value.trim().length > 0;
    const hasContent = hasText || selectedFiles.length > 0;
    $send.classList.toggle('ready', hasContent && !isStreaming);
    $send.classList.toggle('streaming-mode', isStreaming && hasText);
}

function handleKey(e) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
}

function fillInput(t) {
    $inp.value = t;
    $inp.dispatchEvent(new Event('input'));
    $inp.focus();
}

/* =========================================================================
   SIDEBAR TOGGLE
   ========================================================================= */
function toggleSidebar() {
    const sidebar = document.getElementById('sidebar');
    if (window.matchMedia('(max-width: 700px)').matches) {
        const open = !sidebar.classList.contains('mobile-open');
        sidebar.classList.remove('collapsed');
        sidebar.classList.toggle('mobile-open', open);
        sidebarOpen = open;
        const backdrop = document.getElementById('sidebarBackdrop');
        if (backdrop) backdrop.hidden = !open;
        const toggle = document.getElementById('sidebarToggle');
        if (toggle) toggle.title = open ? '关闭导航' : '打开导航';
        return;
    }
    sidebarOpen = !sidebarOpen;
    sidebar.classList.toggle('collapsed', !sidebarOpen);
    const btn = document.getElementById('sidebarToggle');
    if (btn) {
        btn.title = sidebarOpen ? '收起侧栏' : '展开侧栏';
    }
}

