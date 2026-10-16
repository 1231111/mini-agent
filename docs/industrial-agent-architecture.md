# Industrial Agent Runtime Contract

This document defines the runtime contract for MiniAgent.  A model response is
an input to the runtime; it is never proof that an operation completed.

## Non-negotiable invariants

1. **Default deny.** Every model-controlled side effect goes through the
   capability registry, tenant/owner check, path and network guards, budget
   gate, approval gate, audit journal, and idempotency check.  A new tool is
   unavailable until it declares those capabilities and has a regression test.
2. **Evidence before success.** A run may enter `COMPLETED` only after the
   independent step/graph evaluator accepts executable evidence.  A deadlock,
   budget stop, lost lease, missing artifact, or human wait has its own terminal
   outcome and reason code.
3. **Durable state is authoritative.** The database/event log owns run, step,
   approval, retry, lease, and artifact state.  in-memory maps are caches and
   must be evictable.  Replays use the same idempotency key and never repeat a
   committed side effect.
4. **Cancellation reaches the work.** HTTP disconnect, explicit cancel,
   deadline, and shutdown propagate to the LLM call, tool future, child
   process, and delegated agent.  A cancelled run releases its lease and quota
   exactly once.
5. **Untrusted data stays data.** Web pages, tool output, uploaded files, and
   memory are delimited as untrusted data.  They cannot add instructions to a
   system prompt or broaden a capability.
6. **Every decision is observable.** A run timeline records owner, outcome,
   reason, tool name, argument digest, retry, token/cost, approval, and lease
   transitions.  Secrets and raw credentials are redacted; retention is
   bounded.

## Runtime boundaries

The production deployment should keep these failure domains separate:

```text
API/session  -> durable workflow + scheduler -> isolated worker/sandbox
       |                 |                         |
       +---------- policy/approval/secret ---------+
       +---------- model gateway / budgets --------+
       +---------- memory/index service -----------+
       +---------- metrics, traces, audit, alerts --+
```

The current Spring application already contains useful implementations of the
policy pipeline, planner CAS/fencing, evidence evaluator, token budgets,
memory isolation, and SSE trace.  The deployment contract above is the target
for keeping those pieces connected when the process is restarted or scaled.

## Release gates

Before a production release, CI must pass all of the following:

- unit and integration tests for every tool guard and state transition;
- behavior evals that assert no unauthorized tool, path, network, secret, or
  prompt disclosure;
- fault injection for LLM timeout/429, worker restart, Redis loss, DB retry,
  duplicate delivery, cancellation, and SSE reconnect;
- load tests for concurrent runs, queue backpressure, token/cost quotas, and
  long-lived streams;
- migration upgrade, rollback, backup, and restore rehearsal;
- container/image scanning and signed release artifacts.

The worktree is not a release artifact until these gates have run from a clean
checkout with a pinned dependency and container digest.
