import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { discoverBrowserContracts, browserContractArguments, mavenInvocation, validateBrowserReports } from './lib/browser-contracts.mjs';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'miniagent-browser-gate-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    return root;
}
function java(root, module, className, annotations) {
    const target = path.join(root, module, 'src/test/java/fixture', className + '.java');
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, `package fixture;\n${annotations}\nclass ${className} {}`);
}
const browserCondition = '@EnabledIfSystemProperty(named = "miniagent.browser.integration", matches = "true")';
function report(root, testCase, counts = {}) {
    const reports = path.join(root, 'reports');
    fs.mkdirSync(reports, { recursive: true });
    const totals = { tests: 2, skipped: 0, failures: 0, errors: 0, ...counts };
    fs.writeFileSync(path.join(reports, 'TEST-' + testCase.className + '.xml'),
        '<testsuite ' + Object.entries(totals).map(([key, value]) => `${key}="${value}"`).join(' ') + '></testsuite>');
}

test('new opt-in browser classes automatically join the gate without editing CI', t => {
    const root = fixture(t);
    java(root, 'mini-agent-tools', 'ExistingBrowserTest', browserCondition);
    const before = discoverBrowserContracts(root);
    java(root, 'mini-agent-app', 'NewMediaPlaybackTest',
        '@EnabledIfSystemProperty(\n matches = "true",\n named = "miniagent.browser.integration"\n)');
    const after = discoverBrowserContracts(root);
    assert.equal(before.length, 1);
    assert.deepEqual(after.map(value => value.className), ['fixture.ExistingBrowserTest', 'fixture.NewMediaPlaybackTest']);
    assert.match(browserContractArguments(after, path.join(root, 'evidence')).join('\n'), /fixture\.NewMediaPlaybackTest/);
});

test('ordinary unit, paid model and native desktop tests do not join the local Chromium gate', t => {
    const root = fixture(t);
    java(root, 'mini-agent-tools', 'BrowserFixtureTest', browserCondition);
    java(root, 'mini-agent-app', 'PaidModelTest', '@EnabledIfSystemProperty(named="miniagent.live.control", matches="true")');
    java(root, 'mini-agent-tools', 'DesktopInputTest', '@EnabledIfSystemProperty(named="miniagent.desktop.integration", matches="true")');
    java(root, 'mini-agent-planner', 'OrdinaryUnitTest', '@Test');
    assert.deepEqual(discoverBrowserContracts(root).map(value => value.className), ['fixture.BrowserFixtureTest']);
    java(root, 'mini-agent-app', 'MixedUnsafeTest', browserCondition
        + '\n@EnabledIfSystemProperty(named="miniagent.live.control", matches="true")');
    assert.throws(() => discoverBrowserContracts(root), /UNSAFE_OPT_IN/);
});

test('empty or ambiguous discovery fails before Maven can produce a false green', t => {
    const root = fixture(t);
    assert.throws(() => discoverBrowserContracts(root), /NO_BROWSER_CONTRACTS_FOUND/);
    java(root, 'mini-agent-app', 'DuplicateTest', browserCondition);
    java(root, 'mini-agent-tools', 'DuplicateTest', browserCondition);
    assert.throws(() => discoverBrowserContracts(root), /DUPLICATE_BROWSER_CONTRACT_CLASS/);
});

test('Maven receives the browser opt-in, discovered classes and one fresh evidence directory', t => {
    const root = fixture(t);
    java(root, 'mini-agent-app', 'MediaTest', browserCondition);
    const args = browserContractArguments(discoverBrowserContracts(root), path.join(root, 'evidence with spaces'));
    assert.ok(args.includes('-Dminiagent.browser.integration=true'));
    assert.ok(args.includes('-Dtest=fixture.MediaTest'));
    assert.ok(args.includes('-Dminiagent.test.reports-directory=' + path.join(root, 'evidence with spaces/reports')));
    assert.equal(args.some(value => value.startsWith('-Dsurefire.reportsDirectory=')), false);
    assert.equal(args.some(value => /miniagent\.(?:live|desktop)\./.test(value)), false);
});

test('the parent Surefire plugin consumes the report property passed by the browser gate', () => {
    const pom = fs.readFileSync(path.join(repository, 'pom.xml'), 'utf8');
    assert.match(pom, /<miniagent\.test\.reports-directory>\$\{project\.build\.directory\}\/surefire-reports<\/miniagent\.test\.reports-directory>/);
    const surefire = [...pom.matchAll(/<plugin>([\s\S]*?)<\/plugin>/g)]
        .map(match => match[1]).find(plugin => /<artifactId>maven-surefire-plugin<\/artifactId>/.test(plugin));
    assert.ok(surefire, 'the inherited Surefire plugin must configure reportsDirectory');
    assert.match(surefire, /<reportsDirectory>\$\{miniagent\.test\.reports-directory\}<\/reportsDirectory>/);
});

test('Windows wrapper preserves paths and arguments as literals without cmd string interpolation', () => {
    const root = "C:/path with spaces/owner's repo";
    const args = ["-Dprobe=$(throw 'must not execute')", '-Dsample=`literal`', '-Dvalue=one two'];
    const invocation = mavenInvocation(root, args, 'win32', 'C:/Windows');
    assert.match(invocation.command.replaceAll('\\', '/'), /WindowsPowerShell\/v1\.0\/powershell\.exe$/);
    assert.ok(invocation.args.includes('-EncodedCommand'));
    const script = Buffer.from(invocation.args.at(-1), 'base64').toString('utf16le');
    assert.ok(script.includes("owner''s repo"));
    assert.ok(script.includes("'-Dprobe=$(throw ''must not execute'')'"));
    assert.ok(script.includes("'-Dvalue=one two'"));
    assert.ok(script.endsWith('exit $LASTEXITCODE'));
    for (const unsafe of ['-Dvalue=one&two', '-Dvalue=%PATH%', '-Dvalue=x|y', '-Dvalue=x\nwhoami'])
        assert.throws(() => mavenInvocation(root, [unsafe], 'win32'), /UNSAFE_WINDOWS_MAVEN_ARGUMENT/);
    const unix = mavenInvocation('/tmp/repository', args, 'linux');
    assert.equal(unix.command.replaceAll('\\', '/'), '/tmp/repository/mvnw');
    assert.deepEqual(unix.args, args);
});

test('successful Maven reports must cover every selected class with actual unskipped executions', t => {
    const root = fixture(t);
    const first = { className: 'fixture.FirstTest' }, second = { className: 'fixture.SecondTest' };
    report(root, first);
    assert.throws(() => validateBrowserReports([first, second], root), /MISSING_BROWSER_CONTRACT_REPORT/);
    report(root, second, { tests: 3 });
    assert.deepEqual(validateBrowserReports([first, second], root), { classes: 2, tests: 5 });
    for (const counts of [{ tests: 0 }, { skipped: 1 }, { failures: 1 }, { errors: 1 }]) {
        report(root, second, counts);
        assert.throws(() => validateBrowserReports([first, second], root), /BROWSER_CONTRACT_NOT_PASSED/);
    }
    fs.writeFileSync(path.join(root, 'reports/TEST-fixture.SecondTest.xml'), '<testsuite tests="2"></testsuite>');
    assert.throws(() => validateBrowserReports([first, second], root), /INVALID_BROWSER_CONTRACT_REPORT/);
});

test('the read-only CLI includes the shipped media tests without enabling desktop or paid tests', () => {
    const cli = spawnSync(process.execPath, ['scripts/run-browser-contracts.mjs', '--list'], { cwd: repository, encoding: 'utf8' });
    assert.equal(cli.status, 0, cli.stderr);
    const selected = JSON.parse(cli.stdout);
    assert.ok(selected.some(value => value.className === 'com.miniagent.web.ClientMediaPresentationIntegrationTest'));
    assert.ok(selected.some(value => value.className === 'com.miniagent.agent.browser.BrowserControlIntegrationTest'));
    assert.equal(selected.some(value => /LiveComputer|WindowsDesktop/.test(value.className)), false);
});
