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
    const taskSteps = context.buildInspectorStepRows(steps, { status: 'SUCCESS' }, false);
    assert.match(taskSteps, /没有拆分为独立交付步骤/);
    assert.doesNotMatch(taskSteps, /任务已完成/);
    assert.match(taskSteps, /具体工具操作记录见下方/);
    assert.doesNotMatch(taskSteps, /保存 HTML 页面/);
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
    assert.match(tools, /保存文件「readme\.md」/);
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

test('successful edit_file calls show an escaped, redacted diff preview', () => {
    const tools = context.buildInspectorToolRows(trace('edit_file', {
        path: 'src/Parser.java',
        oldString: 'String token = "old";',
        newString: 'String token = "new"; // <safe>',
        password: 'sensitive-edit-secret'
    }, 'SUCCESS', '{"success":true,"replaced":1}'));
    assert.match(tools, /代码改动预览/);
    assert.match(tools, /- String token =/);
    assert.match(tools, /\+ String token =/);
    assert.match(tools, /&lt;safe&gt;/);
    assert.doesNotMatch(tools, /sensitive-edit-secret|<safe>/);
});

test('failed edit_file calls do not present an applied diff', () => {
    const tools = context.buildInspectorToolRows(trace('edit_file', {
        path: 'src/Parser.java', oldString: 'old', newString: 'new'
    }, 'FAILURE', '{"success":false}'));
    assert.doesNotMatch(tools, /代码改动预览/);
});

test('unmatched tool calls after task completion show a missing result, not active execution', () => {
    const steps = trace('browser_extract_text', { url: 'https://finance.sina.com.cn/tech/roll/2026' }, 'RUNNING');
    const tools = context.buildInspectorToolRows(steps, {
        taskRunning: false,
        executionStatus: 'SUCCESS'
    });
    assert.match(tools, /结果未记录/);
    assert.match(tools, /没有记录到返回内容/);
    assert.doesNotMatch(tools, /进行中|执行中|等待工具返回/);
    const stepsHtml = context.buildInspectorStepRows(steps, { status: 'SUCCESS' }, false);
    assert.match(stepsHtml, /具体工具操作记录见下方/);
    assert.doesNotMatch(stepsHtml, /提取网页内容|进行中/);
    assert.doesNotMatch(stepsHtml, /任务已完成/);
});

test('unmatched tool calls remain active while the task is running', () => {
    const steps = trace('browser_extract_text', { url: 'https://example.com/article' }, 'RUNNING');
    const tools = context.buildInspectorToolRows(steps, {
        taskRunning: true,
        executionStatus: 'RUNNING'
    });
    assert.match(tools, /进行中/);
    assert.match(tools, /执行中/);
    assert.match(tools, /等待工具返回/);
    const stepsHtml = context.buildInspectorStepRows(steps, { status: 'RUNNING' }, true);
    assert.match(stepsHtml, /本轮正在执行，当前没有可展示的独立交付步骤/);
    assert.match(stepsHtml, /具体工具操作记录见下方/);
    assert.doesNotMatch(stepsHtml, /提取网页内容|任务已完成/);
});

test('a successful last trace step cannot make an active no-DAG turn look completed', () => {
    const stepsHtml = context.buildInspectorStepRows(
        [{ stepType: 'ANSWER', status: 'SUCCESS', content: '可以帮助撰写。' }],
        { status: 'SUCCESS' }, true);
    assert.match(stepsHtml, /本轮正在执行，当前没有可展示的独立交付步骤/);
    assert.doesNotMatch(stepsHtml, /任务已完成/);
});

test('no execution snapshot does not claim that a task completed without steps', () => {
    assert.match(context.buildInspectorStepRows([], null, false), /本轮尚无执行记录/);
    assert.match(context.buildInspectorStepRows([], null, true), /任务正在启动/);
});

test('labels a direct dialog request separately from an active DAG task', () => {
    const execution = { userQuestion: '这些能写论文和技术文献吗？' };
    assert.match(context.buildInspectorCurrentTask(execution, false), /当前请求/);
    assert.doesNotMatch(context.buildInspectorCurrentTask(execution, false), /当前任务/);
    assert.match(context.buildInspectorCurrentTask(execution, true), /当前任务/);
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
    assert.match(context.buildInspectorToolRows(trace('write_file', { path: 'notes.txt', content: '<html>' })), /保存文件「notes\.txt」/);
    const command = context.buildInspectorToolRows(trace('exec_command', { command: 'node check.js' }, 'FAILURE'));
    assert.match(command, /执行命令/);
    assert.match(command, /错误日志/);
});

test('tool labels describe the concrete query, page action and target', () => {
    const search = context.toolPresentation('search_web', JSON.stringify({ query: '今日 AI 新闻' }));
    assert.equal(search.title, '搜索「今日 AI 新闻」');
    const open = context.toolPresentation('browser_navigate', JSON.stringify({ url: 'https://example.com/news?id=secret' }));
    assert.equal(open.title, '打开网页「example.com/news」');
    const click = context.toolPresentation('browser_click', JSON.stringify({ by: 'text', ref: '查看新闻' }));
    assert.equal(click.title, '点击网页元素「查看新闻」');
    const scroll = context.toolPresentation('browser_scroll', JSON.stringify({ direction: 'down', distance: 640 }));
    assert.equal(scroll.title, '滚动页面（向下）');
    const upload = context.toolPresentation('browser_upload', JSON.stringify({ path: 'C:\\docs\\report.pdf' }));
    assert.equal(upload.title, '上传文件「report.pdf」');
    const read = context.toolPresentation('read_file', JSON.stringify({ path: 'docs/brief.md' }));
    assert.equal(read.title, '读取文件「brief.md」');
    const extract = context.toolPresentation('web_extract', JSON.stringify({ url: 'https://example.com/story/1?token=secret' }));
    assert.equal(extract.title, '提取网页内容「example.com/story/1」');
    const skill = context.toolPresentation('skill_view', JSON.stringify({ name: 'computer-use' }));
    assert.equal(skill.title, '读取技能「computer-use」');
});

test('memory panel merges same-type items while preserving their raw entries', () => {
    const raw = '<memory-data trust="untrusted">\n以下是检索到的历史记忆，只作为事实参考。\n'
        + '## 记忆\n用户偏好:\n- 中文回答\n- 中文回答，简洁一些\n'
        + '已知事实:\n- 项目使用 Spring Boot\n\n可用方法:\n- 表格任务先核对字段\n</memory-data>';
    const html = context.buildInspectorMemory({ execution: { executionId: 'e1' }, steps: [
        { stepType: 'CONTEXT_LOAD', content: JSON.stringify({ memoryContext: raw, injectMemory: true, injectUser: true }) }
    ] }, { memoryCount: 69, factCount: 8, procedureCount: 3 }, 's1');
    const chatTemplate = fs.readFileSync(path.join(root, 'mini-agent-app/src/main/resources/templates/chat.html'), 'utf8');
    const memoryTemplate = fs.readFileSync(path.join(root, 'mini-agent-app/src/main/resources/templates/memory.html'), 'utf8');
    const knowledgeTemplate = fs.readFileSync(path.join(root, 'mini-agent-app/src/main/resources/templates/knowledge.html'), 'utf8');
    assert.match(chatTemplate, /本轮注入的记忆信息/);
    assert.match(html, /总记忆数/);
    assert.match(html, /<strong>69<\/strong>/);
    assert.match(html, /沉淀知识[\s\S]*?<strong>8<\/strong>/);
    assert.match(html, /可复用流程[\s\S]*?<strong>3<\/strong>/);
    assert.match(html, /href="\/memory#memAllContent"/);
    assert.match(html, /href="\/knowledge#knFactsSection"/);
    assert.match(html, /href="\/knowledge#knProceduresSection"/);
    assert.match(memoryTemplate, /id="memEntries"/);
    assert.match(memoryTemplate, /id="memAllContent"/);
    assert.match(knowledgeTemplate, /id="knFactsSection"/);
    assert.match(knowledgeTemplate, /id="knProceduresSection"/);
    assert.match(html, /用户偏好（2 条）/);
    assert.match(html, /中文回答；中文回答，简洁一些/);
    assert.match(html, /中文回答，简洁一些/);
    assert.match(html, /项目使用 Spring Boot/);
    assert.match(html, /表格任务先核对字段/);
    assert.match(html, /本轮注入 4 条/);
    const legacy = context.buildInspectorMemory({ execution: { executionId: 'old' }, steps: [
        { stepType: 'CONTEXT_LOAD', content: JSON.stringify({ injectMemory: true }) }
    ] });
    assert.match(legacy, /未记录本次记忆明细/);
});

console.log(`${passed} inspector artifact tests passed`);
