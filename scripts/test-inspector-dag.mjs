import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

// Exercise the production DAG renderer and snapshot refresh behavior without a browser or model.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const chat = path.join(root, 'mini-agent-app/src/main/resources/static/js/chat');
const files = ['13-markdown.js', '14a-inspector.js'].map(file => [
    file, fs.readFileSync(path.join(chat, file), 'utf8')
]);

function harness(initialSnapshot) {
    const elements = new Map();
    const element = () => ({
        innerHTML: '', hidden: false, title: '', style: {},
        classList: { add() {}, remove() {}, contains: () => false },
        querySelectorAll: () => []
    });
    for (const id of ['inspSteps', 'inspTools', 'inspEvents', 'inspTaskDetails',
        'inspProgress', 'inspProgressFill', 'inspStateDot', 'inspStateText']) {
        elements.set(id, element());
    }
    let snapshot = initialSnapshot;
    const context = vm.createContext({
        console: { warn() {} }, URL, Date, Intl, AbortController,
        currentId: 'session-a',
        document: {
            readyState: 'loading', addEventListener() {},
            getElementById: id => elements.get(id) || null
        },
        window: {}, localStorage: { getItem: () => null },
        setTimeout, clearTimeout,
        fetch: async url => ({ ok: true, data: url.endsWith('/inspector-snapshot')
            ? snapshot
            : url === '/api/trace-nodes' ? [] : { builtin: [], mcp: [] } }),
        apiJson: async response => response.data
    });
    for (const [filename, source] of files)
        vm.runInContext(source, context, { filename });
    return {
        context, elements,
        setSnapshot(next) { snapshot = next; }
    };
}

function todo(id, content, status = 'pending', extra = {}) {
    return { id, content, status, note: '', dependsOn: [], retryCount: 0, replanGeneration: 0, ...extra };
}

let passed = 0;
function test(name, run) {
    run();
    passed++;
    console.log('PASS: ' + name);
}

test('renders every DAG node and resolves dependencies from the todo projection', () => {
    const todos = Array.from({ length: 9 }, (_, index) => todo(index + 1, '阶段 ' + (index + 1)));
    todos[8].dependsOn = [1, 7, 999];
    const html = harness({ todos }).context.buildInspectorTodoRows(todos, []);
    assert.equal((html.match(/role="listitem"/g) || []).length, 9);
    assert.match(html, /aria-label="完整任务 DAG"/);
    assert.match(html, /依赖：阶段 1、阶段 7、节点 #999/);
    assert.doesNotMatch(html, /另有 \d+ 个步骤/);
});

test('shows rerun and replan markers with a complete escaped blocking note', () => {
    const note = '执行受阻：需要确认前置条件。'.repeat(14) + '<script>alert(1)</script>';
    const todos = [todo(1, '核验输入', 'blocked', {
        note, retryCount: 2, replanGeneration: 3
    })];
    const html = harness({ todos }).context.buildInspectorTodoRows(todos, []);
    assert.match(html, /重跑 ×2/);
    assert.match(html, /重规划 R3/);
    assert.ok(html.includes(note.slice(0, note.indexOf('<script>'))));
    assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
    assert.doesNotMatch(html, /<script>/);
});

test('re-renders when dependency or blocking-note details change without a status change', async () => {
    const first = {
        execution: { executionId: 'execution-a', status: 'RUNNING' },
        running: true,
        todos: [todo(1, '收集输入'), todo(2, '生成报告', 'blocked', {
            note: '等待输入', dependsOn: [1]
        })],
        steps: []
    };
    const h = harness(first);
    await h.context.renderInspectorSteps('session-a');
    let html = h.elements.get('inspSteps').innerHTML;
    assert.match(html, /等待输入/);
    assert.match(html, /依赖：收集输入/);

    h.setSnapshot({ ...first, todos: [
        first.todos[0], { ...first.todos[1], note: '已更新阻塞原因', dependsOn: [] }
    ] });
    await h.context.renderInspectorSteps('session-a');
    html = h.elements.get('inspSteps').innerHTML;
    assert.match(html, /已更新阻塞原因/);
    assert.doesNotMatch(html, /依赖：收集输入/);
});

test('timeline CSS lets dependencies and blocking reasons expand beyond one line', () => {
    const css = fs.readFileSync(path.join(root, 'mini-agent-app/src/main/resources/static/css/shell.css'), 'utf8');
    assert.match(css, /\.insp-section--timeline \.insp-step-meta\.insp-dependencies\s*\{[^}]*display:\s*block;[^}]*overflow:\s*auto;/s);
    assert.match(css, /\.insp-section--timeline \.insp-step-meta\.insp-block-reason\s*\{[^}]*display:\s*block;[^}]*max-height:\s*120px;[^}]*overflow:\s*auto;/s);
});

console.log(`${passed} inspector DAG tests passed`);
