import fs from 'node:fs';
import path from 'node:path';

// These are the modules built by -pl mini-agent-app -am. Select tests by their
// opt-in condition, never by a second hand-maintained list of class names.
const APP_MODULES = ['mini-agent-common', 'mini-agent-memory', 'mini-agent-tools', 'mini-agent-planner', 'mini-agent-app'];
const BROWSER_CONDITION = 'miniagent.browser.integration';

export function discoverBrowserContracts(repository) {
    const tests = [];
    const visit = (directory, module) => {
        if (!fs.existsSync(directory)) return;
        for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
            const filename = path.join(directory, entry.name);
            if (entry.isDirectory()) visit(filename, module);
            else if (entry.isFile() && entry.name.endsWith('.java')) {
                const source = fs.readFileSync(filename, 'utf8');
                const conditions = [...source.matchAll(/@(?:org\.junit\.jupiter\.api\.condition\.)?EnabledIfSystemProperty\s*\(([\s\S]*?)\)/g)];
                const optedIn = conditions.some(condition =>
                    /\bnamed\s*=\s*"miniagent\.browser\.integration"/.test(condition[1])
                    && /\bmatches\s*=\s*"true"/.test(condition[1]));
                if (!optedIn) continue;
                // Keep paid-model and native-desktop tests out even if someone
                // accidentally adds a browser condition to a mixed fixture.
                if (conditions.some(condition => /\bnamed\s*=\s*"miniagent\.(?:live|desktop)\./.test(condition[1])))
                    throw new Error('BROWSER_CONTRACT_HAS_UNSAFE_OPT_IN: ' + filename);
                const packageName = source.match(/^\s*package\s+([\w.]+)\s*;/m)?.[1];
                const className = path.basename(filename, '.java');
                if (!packageName || !/^[A-Za-z_$][\w$]*Test$/.test(className))
                    throw new Error('INVALID_BROWSER_CONTRACT_CLASS: ' + filename);
                tests.push({ module, className: packageName + '.' + className, source: path.relative(repository, filename).replaceAll('\\', '/') });
            }
        }
    };
    for (const module of APP_MODULES) visit(path.join(repository, module, 'src/test/java'), module);
    tests.sort((a, b) => a.className.localeCompare(b.className));
    if (!tests.length) throw new Error('NO_BROWSER_CONTRACTS_FOUND');
    if (new Set(tests.map(test => test.className)).size !== tests.length) throw new Error('DUPLICATE_BROWSER_CONTRACT_CLASS');
    return tests;
}

export function browserContractArguments(tests, evidenceDir) {
    return ['-B', '-ntp', '-pl', 'mini-agent-app', '-am', 'test',
        '-Dtest=' + tests.map(test => test.className).join(','),
        '-Dsurefire.failIfNoSpecifiedTests=false',
        '-D' + BROWSER_CONDITION + '=true',
        '-Dminiagent.test.reports-directory=' + path.join(evidenceDir, 'reports'),
        '-Dminiagent.ui.evidence=' + path.join(evidenceDir, 'ui')];
}

export function mavenInvocation(repository, args, platform = process.platform, systemRoot = process.env.SystemRoot || 'C:\\Windows') {
    if (platform !== 'win32') return { command: path.join(repository, 'mvnw'), args };
    // PowerShell ultimately invokes a batch file through cmd.exe. Refuse its
    // metacharacters even inside arguments; legacy native argument forwarding
    // must not turn an evidence path into a second command or expand %VAR%.
    if ([repository, ...args].some(value => /[\r\n"&|<>^%]/.test(value)))
        throw new Error('UNSAFE_WINDOWS_MAVEN_ARGUMENT');
    // .cmd cannot be spawned directly with shell:false. Pass literal PowerShell
    // arguments through an encoded script instead of a cmd.exe command string.
    const literal = value => "'" + String(value).replaceAll("'", "''") + "'";
    const script = "$ErrorActionPreference='Stop'; & " + literal(path.join(repository, 'mvnw.cmd'))
        + ' @(' + args.map(literal).join(',') + '); exit $LASTEXITCODE';
    return { command: path.join(systemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'),
        args: ['-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')] };
}

/** A zero Maven exit alone is insufficient: every selected class must really run. */
export function validateBrowserReports(tests, evidenceDir) {
    let executed = 0;
    for (const test of tests) {
        const filename = path.join(evidenceDir, 'reports', 'TEST-' + test.className + '.xml');
        if (!fs.existsSync(filename)) throw new Error('MISSING_BROWSER_CONTRACT_REPORT: ' + test.className);
        const xml = fs.readFileSync(filename, 'utf8');
        const attributes = xml.match(/<testsuite\b([^>]*)>/)?.[1] || '';
        const count = name => {
            const raw = attributes.match(new RegExp('\\b' + name + '="(\\d+)"'))?.[1];
            if (raw === undefined) throw new Error('INVALID_BROWSER_CONTRACT_REPORT: ' + test.className);
            return Number(raw);
        };
        const total = count('tests');
        if (total < 1 || count('skipped') !== 0 || count('failures') !== 0 || count('errors') !== 0)
            throw new Error('BROWSER_CONTRACT_NOT_PASSED: ' + test.className);
        executed += total;
    }
    return { classes: tests.length, tests: executed };
}
