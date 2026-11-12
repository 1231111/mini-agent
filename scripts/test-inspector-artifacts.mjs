import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

// Exercise the production inspector renderers; no model, browser or user data is needed.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const chat = path.join(root, 'mini-agent-app/src/main/resources/static/js/chat');
const context = vm.createContext({
    console, URL, Date, Intl,
    document: { readyState: 'loading', addEventListener() {} },
    window: {}, localStorage: { getItem: () => null },
    setTimeout, clearTimeout, AbortController
});
for (const file of ['13-markdown.js', '14a-inspector.js'])
    vm.runInContext(fs.readFileSync(path.join(chat, file), 'utf8'), context, { filename: file });

function trace(toolName, args, state = 'SUCCESS', output) {
    const records = [{ stepType: 'TOOL_CALL', toolName, status: 'RUNNING', toolArgs: JSON.stringify(args) }];
    if (state !== 'RUNNING') records.push({ stepType: state === 'FAILURE' ? 'TOOL_ERROR' : 'TOOL_RESULT', toolName,
        status: state, content: JSON.stringify(output || (state === 'FAILURE' ? { error: '磁盘不可写' }
            : { success: true, path: args.path, bytes_written: 23095 })) });
    return records;
}

let passed = 0;
function test(name, run) { run(); passed++; console.log('PASS: ' + name); }

test('HTML writes expose the saved page and original tool identity', () => {
    const steps = trace('write_file', { path: 'workspace/仿真大屏.HTML', content: '<!DOCTYPE html><html></html>' });
    const tools = context.buildInspectorToolRows(steps);
    assert.match(tools, /保存 HTML 页面/);
    assert.match(tools, /write_file/);
    assert.match(tools, /仿真大屏\.HTML/);
    assert.match(tools, /已保存/);
    assert.match(tools, /kind-code/);
    assert.doesNotMatch(tools, /验证通过|浏览器验收通过/);
    assert.match(context.buildInspectorStepRows(steps, { status: 'SUCCESS' }), /保存 HTML 页面/);
});

test('HTM edits and reads use the actual file type', () => {
    assert.match(context.buildInspectorToolRows(trace('edit_file', { path: 'C:\\workspace\\index.htm' })), /编辑 HTML 页面/);
    assert.match(context.buildInspectorToolRows(trace('read_file', { path: 'workspace/index.html' })), /读取 HTML 页面/);
});

test('HTML and ordinary files stay in distinct action groups', () => {
    const tools = context.buildInspectorToolRows([
        ...trace('write_file', { path: 'workspace/index.html' }),
        ...trace('write_file', { path: 'workspace/readme.md' })
    ]);
    assert.equal((tools.match(/class="insp-tool-card /g) || []).length, 2);
    assert.match(tools, /保存 HTML 页面/);
    assert.match(tools, /写入文件/);
    const summary = context.buildInspectorTaskDetails({ status: 'SUCCESS' }, [], trace('write_file', { path: 'index.html' }), false);
    assert.match(summary, /代码与命令/);
    assert.match(summary, /保存 HTML 页面/);
});

test('failed and pending page writes never claim the page was saved', () => {
    const failed = context.buildInspectorToolRows(trace('write_file', { path: 'index.html' }, 'FAILURE'));
    assert.match(failed, /磁盘不可写/);
    assert.match(failed, /错误日志/);
    assert.doesNotMatch(failed, /已保存|验证通过/);
    const pending = context.buildInspectorToolRows(trace('write_file', { path: 'index.html' }, 'RUNNING'));
    assert.match(pending, /进行中/);
    assert.doesNotMatch(pending, /已保存|验证通过/);
});

test('page source is folded and stays escaped with secrets redacted', () => {
    const source = '<script>window.sourceMustNotExecute = true;</script><img src=x onerror="alert(1)">' + 'x'.repeat(600);
    const tools = context.buildInspectorToolRows(trace('write_file', {
        path: 'workspace/大屏.html', content: source, api_key: 'sensitive-key-test'
    }));
    assert.match(tools, /<details[^>]*>[\s\S]*查看[^<]*[源参]/);
    assert.doesNotMatch(tools, /<details[^>]*\bopen\b/);
    assert.doesNotMatch(tools, /<script>|<img src=x|sensitive-key-test/);
    assert.match(tools, /&lt;script/);
    assert.match(tools, /已隐藏/);
    assert.match(tools, /大屏\.html/);
});

test('malformed or unrelated args preserve generic tool names', () => {
    const steps = [{ stepType: 'TOOL_CALL', toolName: 'write_file', toolArgs: '{broken', status: 'RUNNING' }];
    assert.match(context.buildInspectorToolRows(steps), /写入文件/);
    assert.doesNotMatch(context.buildInspectorToolRows(steps), /保存 HTML 页面/);
    assert.match(context.buildInspectorToolRows(trace('write_file', { path: 'notes.txt', content: '<html>' })), /写入文件/);
    const command = context.buildInspectorToolRows(trace('exec_command', { command: 'node check.js' }, 'FAILURE'));
    assert.match(command, /执行命令/);
    assert.match(command, /错误日志/);
});

console.log(`${passed} inspector artifact tests passed`);
