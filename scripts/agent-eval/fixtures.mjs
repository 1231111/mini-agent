import fs from 'node:fs';
import path from 'node:path';
import { digest } from './quality-gate.mjs';

// Synthetic oracle/collector data for testing validators. These are never real-agent success evidence.
export function makeFixtures(artifactRoot, runId = 'fixture-run') {
    const manifest = { schemaVersion: 1, cases: [
        { id: 'daily-news', category: 'news', tier: 'smoke', prompt: '查询指定日期的AI新闻，引用原始来源。',
            sloMs: 1000, timeoutMs: 2000, checks: [{ type: 'news', date: '2026-11-13', timeZone: 'Asia/Shanghai',
                minItems: 2, minSources: 2, allowedHosts: ['one.example', 'two.example'], requiredSourceIds: ['article-one', 'article-two'] }] },
        { id: 'responsive-ui', category: 'ui', tier: 'smoke', prompt: '生成包含导航和可用提交按钮的响应式网页。',
            sloMs: 1000, timeoutMs: 2000, checks: [{ type: 'ui-artifact',
                viewports: [{ width: 1280, height: 800 }, { width: 390, height: 844 }],
                requiredSelectors: ['nav', 'button[type=submit]'], requiredActions: [{ id: 'submit', expectedState: 'confirmation-visible' }] }] },
        { id: 'form-submit', category: 'form', tier: 'smoke', prompt: '在本地测试表单填入指定字段，上传测试文件并提交，返回回执号。',
            sloMs: 1000, timeoutMs: 2000, checks: [{ type: 'form-receipt', route: '/fixture/orders',
                expectedFields: { name: '测试用户', city: '上海', agree: true }, expectedFiles: [
                    { field: 'attachment', name: 'fixture.txt', bytes: 12, sha256: digest('fixture-file') }
                ] }] }
    ] };
    const nonces = Object.fromEntries(manifest.cases.map(c => [c.id, `nonce-${c.id}`]));
    for (const definition of manifest.cases) fs.mkdirSync(path.join(artifactRoot, definition.id), { recursive: true });
    const artifact = (relative, content) => {
        fs.writeFileSync(path.join(artifactRoot, 'responsive-ui', relative), content);
        return { path: relative, sha256: digest(content) };
    };
    const html = artifact('index.html', '<!doctype html><html><head><title>测试</title></head><body><nav>导航</nav><button type="submit">提交</button></body></html>');
    const viewports = manifest.cases[1].checks[0].viewports.map(({ width, height }) => {
        // Minimal PNG header fixture for dimension validation; production evidence must come from a browser capture.
        const png = Buffer.alloc(33);
        Buffer.from('89504e470d0a1a0a', 'hex').copy(png);
        png.writeUInt32BE(13, 8); png.write('IHDR', 12); png.writeUInt32BE(width, 16); png.writeUInt32BE(height, 20);
        return { width, height, scrollWidth: width, screenshot: artifact(`${width}.png`, png),
            elements: { nav: { visible: true, count: 1 }, 'button[type=submit]': { visible: true, count: 1 } } };
    });
    const sources = [
        { id: 'article-one', url: 'https://one.example/ai', title: 'AI技术一', publisher: 'One', publishedAt: '2026-11-12T16:30:00Z' },
        { id: 'article-two', url: 'https://two.example/ai', title: 'AI技术二', publisher: 'Two', publishedAt: '2026-11-13T12:00:00+08:00' }
    ].map(source => ({ ...source, httpStatus: 200, bodySha256: digest(source.id), dateEvidence: 'fixture-oracle' }));
    const capture = (id, delivery, observation) => ({ runId, caseId: id, status: 'DONE', terminal: true, elapsedMs: 200,
        delivery, observation: { caseId: id, nonce: nonces[id], ...observation } });
    const captures = [
        capture('daily-news', { news: sources.map(source => ({ sourceId: source.id, title: source.title, url: source.url,
            publishedAt: source.publishedAt, summary: '这是已核对来源的测试摘要。' })) }, { newsSources: sources }),
        capture('responsive-ui', { artifact: html }, { ui: { artifactSha256: html.sha256, loaded: true,
            consoleErrors: [], failedRequests: [], viewports, actions: [{ id: 'submit', observedState: 'confirmation-visible' }] } }),
        capture('form-submit', { receiptId: 'receipt-one' }, { fieldReadback: { name: '测试用户', city: '上海', agree: true },
            formReceipt: { origin: 'fixture-server', nonce: nonces['form-submit'], accepted: true, receiptId: 'receipt-one',
                method: 'POST', route: '/fixture/orders', fields: { name: '测试用户', city: '上海', agree: true },
                files: structuredClone(manifest.cases[2].checks[0].expectedFiles) } })
    ];
    const context = { runId, artifactRoot, nonces, mode: 'fixture', provenance: {
        git: { commit: 'a'.repeat(40), dirty: false, workingTreeSha256: digest('tree') },
        modelIdentitySha256: digest('fixture-model'), adapterSha256: digest('fixture-adapter') } };
    return { manifest, captures, context };
}
