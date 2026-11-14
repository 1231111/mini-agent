// Trusted test adapters run in a supervised child process, never inside the gate process.
import { pathToFileURL } from 'node:url';
let harness;
process.on('message', async message => {
    const { id, method, args } = message ?? {};
    try {
        let value;
        if (method === 'initialize') {
            const module = await import(pathToFileURL(args.adapterPath).href);
            if (typeof module.createHarness !== 'function') throw new Error();
            harness = await module.createHarness({ ...args.run, modelIdentity: args.modelIdentity,
                allowPaid: args.allowPaid === true,
                cases: args.cases,
                signal: new AbortController().signal });
            if (!harness || typeof harness.execute !== 'function' || typeof harness.observe !== 'function'
                || typeof harness.close !== 'function') throw new Error();
            value = { agentHome: harness.agentHome, mode: harness.mode,
                modelIdentitySha256: harness.modelIdentitySha256, targetArtifact: harness.targetArtifact };
        } else if (['execute', 'observe', 'close'].includes(method) && harness) {
            value = await harness[method]({ ...args, signal: new AbortController().signal });
        } else throw new Error();
        process.send?.({ id, ok: true, value });
    } catch { process.send?.({ id, ok: false }); }
});
