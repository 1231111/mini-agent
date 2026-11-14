import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const all = fs.readFileSync(new URL('../mini-agent-app/src/main/resources/static/js/chat/05-view.js', import.meta.url), 'utf8');
const source = all.slice(all.indexOf('let modelConfigState ='), all.indexOf('\nloadModelConfig();'));
const template = fs.readFileSync(new URL('../mini-agent-app/src/main/resources/templates/chat.html', import.meta.url), 'utf8');
for (const id of ['modelBaseUrlPolicy', 'modelClearApiKey', 'modelSaveButton', 'modelResetButton', 'modelContextWindowTokens', 'modelContextWindowHint']) assert.match(template, new RegExp('id="' + id + '"'));
const view = (overrides = {}) => ({ success: true, customBaseUrlAllowed: true, defaultPreset: 'default',
    presets: [{ id: 'default', label: 'Default', baseUrl: 'https://platform.test/v1', modelName: 'platform' }],
    current: { presetId: 'default', label: 'Default', baseUrl: 'https://own.test/v1', modelName: 'own-model',
        customBaseUrl: 'https://own.test/v1', customModelName: 'own-model', configurationValid: true,
        hasCustomApiKey: true, hasApiKey: true, apiKeyMasked: '***test' }, ...overrides });
function harness() {
    const nodes = new Map(), requests = [];
    const element = () => ({ value: '', textContent: '', placeholder: '', checked: false, disabled: false, dataset: {},
        classList: { toggle() {} }, appendChild() {}, querySelector() { return element(); } });
    const get = id => { if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id); };
    const ctx = vm.createContext({ console: { warn() {} },
        document: { getElementById: get, querySelectorAll: () => [], createElement: element },
        localStorage: { setItem() {}, getItem() {} },
        fetch: async (url, options) => { requests.push({ url, ...options }); return { ok: true, json: async () => view() }; } });
    vm.runInContext(source, ctx);
    return { ctx, get, requests };
}

{
    const h = harness(); h.ctx.applyModelConfigView(view({ customBaseUrlAllowed: false }));
    assert.equal(h.get('modelBaseUrl').disabled, true);
    assert.match(h.get('modelBaseUrlPolicy').textContent, /禁止自定义/);
    h.ctx.fetch = async (_, options) => { const body = JSON.parse(options.body); assert.equal(body.baseUrl, '');
        return { ok: true, json: async () => ({ success: false, message: '请重新输入对应 Key' }) }; };
    await h.ctx.saveModelConfig();
    assert.equal(h.get('modelBaseUrl').value, 'https://own.test/v1');
    assert.equal(h.get('modelMenuHint').textContent, '请重新输入对应 Key');
    console.log('PASS: disabled endpoint is not submitted and failure preserves the repair form');
}
{
    const h = harness(); h.ctx.applyModelConfigView(view()); h.get('modelApiKey').value = 'sk-new';
    h.ctx.fetch = async (_, options) => { assert.equal(JSON.parse(options.body).apiKey, 'sk-new');
        return { ok: true, json: async () => ({ success: false, customBaseUrlRejected: true, message: '本次均未保存' }) }; };
    await h.ctx.saveModelConfig();
    assert.equal(h.get('modelApiKey').value, 'sk-new');
    assert.equal(h.get('modelMenuHint').textContent, '本次均未保存');
    assert.equal(h.get('modelSaveButton').disabled, false);
    console.log('PASS: rejected saves keep user input and never display false success');
}
{
    const h = harness(); h.ctx.applyModelConfigView(view());
    h.get('modelClearApiKey').checked = true;
    h.ctx.fetch = async (_, options) => { assert.equal(JSON.parse(options.body).apiKey, '__CLEAR__');
        return { ok: true, json: async () => view({ current: { modelName: 'saved-model', baseUrl: 'http://localhost:11880/v1',
            configurationValid: true, hasCustomApiKey: false } }) }; };
    await h.ctx.saveModelConfig();
    assert.equal(h.get('modelMenuHint').textContent, '当前生效：saved-model @ http://localhost:11880/v1');
    assert.equal(h.get('modelApiKey').value, '');
    assert.equal(h.get('modelClearApiKey').checked, false);
    console.log('PASS: explicit key clearing and saved effective endpoint are rendered accurately');
}
{
    const h = harness(); h.ctx.applyModelConfigView(view());
    h.get('modelClearApiKey').checked = true; h.get('modelApiKey').value = 'new-key';
    await h.ctx.saveModelConfig();
    assert.equal(h.requests.length, 0);
    assert.match(h.get('modelMenuHint').textContent, /只能选择一种/);
    console.log('PASS: contradictory clear and replace key choices are blocked before saving');
}
{
    const h = harness(); h.ctx.applyModelConfigView(view({ configurationError: '旧端点已禁用', current: {
        configurationValid: false, configurationError: '旧端点已禁用', customBaseUrl: 'https://old.test/v1', hasCustomApiKey: true } }));
    assert.equal(h.get('modelBadgeLabel').textContent, '模型配置待修复');
    assert.equal(h.get('modelMenuHint').textContent, '配置未生效：旧端点已禁用');
    assert.equal(h.get('modelBaseUrl').value, 'https://old.test/v1');
    assert.match(h.get('modelApiKey').placeholder, /请重新输入/);
    assert.equal(h.get('modelSaveButton').disabled, false);
    console.log('PASS: invalid stored configuration remains repairable without claiming a fallback is active');
}
{
    const h = harness();
    h.ctx.fetch = async () => ({ ok: false, status: 503 });
    await h.ctx.loadModelConfig(); await h.ctx.saveModelConfig();
    assert.match(h.get('modelMenuHint').textContent, /尚未加载成功/);
    console.log('PASS: load failure cannot save an uninitialized configuration');
}
{
    const h = harness(); h.ctx.applyModelConfigView(view());
    h.ctx.fetch = async () => ({ ok: false, status: 500, json: async () => ({ success: true }) });
    await h.ctx.saveModelConfig();
    assert.match(h.get('modelMenuHint').textContent, /保存失败/);
    await h.ctx.resetModelConfig();
    assert.match(h.get('modelMenuHint').textContent, /恢复失败/);
    console.log('PASS: HTTP failures are handled for save and reset');
}
{
    const h = harness(); h.ctx.applyModelConfigView(view({ presets: [
        { id: 'default', baseUrl: 'https://platform.test/v1', modelName: 'platform' },
        { id: 'other', baseUrl: 'https://other.test/v1', modelName: 'other-model' }] }));
    assert.equal(h.get('modelBaseUrl').value, 'https://own.test/v1');
    assert.equal(h.get('modelNameInput').value, 'own-model');
    h.get('modelApiKey').value = 'unsaved-key'; h.get('modelClearApiKey').checked = true;
    h.ctx.selectModelPreset('other');
    assert.equal(h.get('modelBaseUrl').value, '');
    assert.equal(h.get('modelNameInput').value, '');
    assert.equal(h.get('modelContextWindowTokens').value, '0');
    assert.equal(h.get('modelApiKey').value, '');
    assert.equal(h.get('modelClearApiKey').checked, false);
    assert.equal(h.get('modelBaseUrl').placeholder, 'https://other.test/v1');
    assert.equal(h.get('modelNameInput').placeholder, 'other-model');
    assert.match(h.get('modelMenuHint').textContent, /需重新输入/);
    console.log('PASS: deliberate preset changes clear unsaved overrides while normal rendering preserves saved fields');
}
for (const first of ['saveModelConfig', 'resetModelConfig']) {
    const h = harness(); h.ctx.applyModelConfigView(view());
    let complete, calls = 0;
    h.ctx.fetch = async () => { calls++; return await new Promise(resolve => { complete = resolve; }); };
    const pending = h.ctx[first]();
    assert.equal(h.get('modelSaveButton').disabled, true);
    assert.equal(h.get('modelResetButton').disabled, true);
    await h.ctx.saveModelConfig(); await h.ctx.resetModelConfig();
    assert.equal(calls, 1);
    complete({ ok: true, json: async () => view() }); await pending;
    assert.equal(h.get('modelSaveButton').disabled, false);
    assert.equal(h.get('modelResetButton').disabled, false);
    console.log('PASS: ' + first + ' serializes save/reset and prevents out-of-order effective settings');
}
{
    const h = harness(); h.ctx.applyModelConfigView(view({ current: { modelName: 'small-model', baseUrl: 'https://own.test/v1',
        customContextWindowTokens: 32000, contextWindowTokens: 32000, configurationValid: true } }));
    assert.equal(h.get('modelContextWindowTokens').value, '32000');
    assert.match(h.get('modelContextWindowHint').textContent, /32000/);
    h.ctx.fetch = async (_, options) => { assert.equal(JSON.parse(options.body).contextWindowTokens, 64000);
        return { ok: true, json: async () => view() }; };
    h.get('modelContextWindowTokens').value = '64000'; await h.ctx.saveModelConfig();
    console.log('PASS: configured model input window is displayed and saved as an integer');
}
{
    const h = harness(); h.ctx.applyModelConfigView(view({ current: { customContextWindowTokens: 0,
        contextWindowTokens: 8192, contextWindowWarning: '自定义模型窗口未确认，暂按8192 token预算', configurationValid: true } }));
    assert.equal(h.get('modelContextWindowTokens').value, '0');
    assert.match(h.get('modelContextWindowHint').textContent, /未确认/);
    for (const invalid of ['-1', '1', '1023', '32000.5', '10000001', 'not-a-number']) {
        h.get('modelContextWindowTokens').value = invalid; await h.ctx.saveModelConfig();
        assert.match(h.get('modelMenuHint').textContent, /上下文窗口/);
    }
    assert.equal(h.requests.length, 0);
    h.get('modelContextWindowTokens').value = ''; await h.ctx.saveModelConfig();
    assert.equal(JSON.parse(h.requests[0].body).contextWindowTokens, 0);
    console.log('PASS: unknown model window has an explicit warning and invalid values never reach the server');
}
