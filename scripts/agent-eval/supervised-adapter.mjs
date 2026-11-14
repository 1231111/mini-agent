import { fork, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

/** Hard bounds cover synchronous JS hangs, ignored AbortSignals and non-closing child handles. */
export function supervisedAdapter(run) {
    const child = fork(fileURLToPath(new URL('./adapter-worker.mjs', import.meta.url)), [], {
        cwd: run.directory, env: { ...process.env, MINI_AGENT_HOME: run.agentHome,
            MINI_AGENT_EVAL_RUN_ID: run.runId },
        detached: process.platform !== 'win32', windowsHide: true, execArgv: [],
        // Adapter/provider stdout can contain raw credentials; only typed IPC reaches the report.
        stdio: ['ignore', 'ignore', 'ignore', 'ipc'], serialization: 'advanced'
    });
    let sequence = 0, stopped = false, exited = false;
    const pending = new Map();
    const failPending = () => {
        for (const { reject, detach } of pending.values()) { detach(); reject(new Error('HARNESS_PROCESS_STOPPED')); }
        pending.clear();
    };
    child.on('message', message => {
        const request = pending.get(message?.id);
        if (!request) return;
        pending.delete(message.id); request.detach();
        if (message.ok === true) request.resolve(message.value);
        else request.reject(new Error('HARNESS_OPERATION_FAILED'));
    });
    child.on('error', failPending);
    child.on('exit', () => { exited = true; failPending(); });
    function stop() {
        if (stopped) return;
        stopped = true;
        if (process.platform === 'win32') {
            // No shell composition: terminate this known child's entire process tree.
            spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], {
                windowsHide: true, stdio: 'ignore', timeout: 5000
            });
            try { child.kill('SIGKILL'); } catch { /* already gone */ }
        } else {
            try { process.kill(-child.pid, 'SIGKILL'); } catch { try { child.kill('SIGKILL'); } catch { /* gone */ } }
        }
        try { if (child.connected) child.disconnect(); } catch { /* closed concurrently */ }
        child.unref(); failPending();
    }
    function call(method, args, signal) {
        return new Promise((resolve, reject) => {
            if (stopped || exited || signal?.aborted) { reject(new Error('HARNESS_PROCESS_STOPPED')); return; }
            const id = ++sequence;
            const abort = () => stop();
            pending.set(id, { resolve, reject, detach: () => signal?.removeEventListener('abort', abort) });
            signal?.addEventListener('abort', abort, { once: true });
            child.send({ id, method, args }, error => {
                if (error) { pending.get(id)?.detach(); pending.delete(id); reject(new Error('HARNESS_PROCESS_STOPPED')); }
            });
        });
    }
    return { call, stop, isStopped: () => stopped || exited };
}
