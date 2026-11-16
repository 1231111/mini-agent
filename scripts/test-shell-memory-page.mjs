import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = fs.readFileSync(path.join(root, 'mini-agent-app/src/main/resources/static/js/shell-pages.js'), 'utf8');
const template = fs.readFileSync(path.join(root, 'mini-agent-app/src/main/resources/templates/memory.html'), 'utf8');

function harness(data = {}) {
    const elements = new Map();
    const context = vm.createContext({
        console, document: {
            readyState: 'loading', addEventListener() {},
            getElementById(id) {
                if (!elements.has(id)) elements.set(id, { innerHTML: '', textContent: '', value: '' });
                return elements.get(id);
            }
        },
        fetch: async url => ({ ok: true, json: async () => ({ success: true,
            data: url === '/api/conversations' ? [] : data }) })
    });
    vm.runInContext(source, context, { filename: 'shell-pages.js' });
    return { context, elements, html: id => elements.get(id)?.innerHTML || '', text: id => elements.get(id)?.textContent || '' };
}
const persisted = content => ({ content, target: 'user', persisted: true });
let passed = 0;
async function test(name, run) { await run(); passed++; console.log('PASS: ' + name); }

await test('the memory page contains a dedicated saved preference region', () => {
    assert.match(template, /id="memPreferences"/);
    assert.match(template, /id="memPreferenceCount"/);
    assert.ok(template.indexOf('id="memPreferences"') < template.indexOf('id="memEpisodes"'));
});

await test('the real page loader displays full persisted preferences in API order', async () => {
    const fullContent = '人物介绍模板\n'.repeat(80) + '最后一项：提供来源';
    const h = harness({ preferencesAvailable: true,
        userPreferences: [persisted('最新的用户偏好'), persisted(fullContent), persisted('更早偏好')],
        related: [{ type: 'fact', predicate: 'learned_from', object: 'unrelated learned content' }],
        stats: { memoryCount: 4, factCount: 1, procedureCount: 0 } });
    await h.context.renderMemoryPage();
    const html = h.html('memPreferences');
    assert.match(html, /最新的用户偏好/);
    assert.ok(html.includes(fullContent));
    assert.ok(html.indexOf('最新的用户偏好') < html.indexOf('人物介绍模板'));
    assert.ok(html.indexOf('人物介绍模板') < html.indexOf('更早偏好'));
    assert.equal(h.text('memPreferenceCount'), '已载入 3 条 · 最新优先');
    assert.doesNotMatch(html, /unrelated learned content/);
    assert.match(h.html('memStats'), /记忆条目/);
});

await test('preference HTML stays escaped and malformed or unconfirmed records stay hidden', () => {
    const h = harness();
    h.context.renderMemoryPreferences({ preferencesAvailable: true, userPreferences: [
        persisted('<img src=x onerror="alert(1)"> & <script>invalid</script>'),
        { content: 'agent notes', target: 'memory', persisted: true },
        { content: 'not persisted', target: 'user', persisted: false },
        { content: 'string bool', target: 'user', persisted: 'true' },
        { content: {}, target: 'user', persisted: true }, null
    ] });
    const html = h.html('memPreferences');
    assert.match(html, /&lt;img src=x onerror=&quot;alert\(1\)&quot;&gt; &amp; &lt;script&gt;/);
    assert.doesNotMatch(html, /<img|<script|agent notes|not persisted|string bool|\[object Object\]/);
    assert.equal(h.text('memPreferenceCount'), '已载入 1 条 · 最新优先');
});

await test('empty persisted preferences are distinct from a failed or unknown read', () => {
    const h = harness();
    h.context.renderMemoryPreferences({ preferencesAvailable: true, userPreferences: [] });
    assert.match(h.html('memPreferences'), /尚未保存用户偏好/);
    assert.equal(h.text('memPreferenceCount'), '已载入 0 条 · 最新优先');
    h.context.renderMemoryPreferences({ preferencesAvailable: false, userPreferences: [persisted('stale content')] });
    assert.match(h.html('memPreferences'), /暂时无法读取/);
    assert.doesNotMatch(h.html('memPreferences'), /尚未保存|stale content/);
    assert.equal(h.text('memPreferenceCount'), '读取失败');
    h.context.renderMemoryPreferences(null);
    assert.match(h.html('memPreferences'), /未提供已保存的偏好数据/);
    assert.equal(h.text('memPreferenceCount'), '状态未知');
});

await test('learned facts and episodes never stand in for user preferences', () => {
    const h = harness();
    h.context.renderMemoryPreferences({ preferencesAvailable: true, related: [
        { type: 'fact', predicate: 'learned_from', object: 'learned content' },
        { type: 'episode', summary: 'episode summary' }
    ] });
    assert.match(h.html('memPreferences'), /尚未保存用户偏好/);
    assert.doesNotMatch(h.html('memPreferences'), /learned content|episode summary/);
});

await test('HTTP failures preserve a visible error instead of replacing it with empty statistics', async () => {
    const h = harness();
    h.context.fetch = async url => url === '/api/conversations'
        ? { ok: true, json: async () => ({ success: true, data: [] }) }
        : { ok: false, status: 503, json: async () => ({}) };
    await h.context.renderMemoryPage();
    assert.match(h.html('memStats'), /加载失败：HTTP 503/);
    assert.match(h.html('memPreferences'), /用户偏好加载失败：HTTP 503/);
    assert.equal(h.text('memPreferenceCount'), '读取失败');
    assert.doesNotMatch(h.html('memPreferences'), /尚未保存用户偏好/);
    assert.match(h.html('memEpisodes'), /选择一个会话/);
});

await test('an empty response clears loading without claiming that preferences are absent', async () => {
    const h = harness(null);
    await h.context.renderMemoryPage();
    assert.match(h.html('memPreferences'), /未提供已保存的偏好数据/);
    assert.equal(h.text('memPreferenceCount'), '状态未知');
});

console.log(`${passed} shell memory page tests passed`);
