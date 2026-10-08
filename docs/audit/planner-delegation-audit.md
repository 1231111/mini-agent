# Planner / Task-Graph / Recovery / Delegation Audit
**Target:** `D:\AI\miniagent` — Java 21 / Spring Boot / LangChain4j agent runtime
**Benchmark:** production-grade agent runtimes (Claude Code, Devin, Codex, Manus)
**Mode:** READ ONLY. No files were modified, created, or deleted. No builds were run.
**Scope:** `mini-agent-planner` (planner package), `mini-agent-loop` (`delegate`, `scope`), plus the cross-cutting state/lock implementations those subsystems delegate to.

Every finding below cites absolute path + line numbers + a verbatim excerpt. Claims I could not verify from source are explicitly labelled **UNVERIFIED**.

---

## Executive summary

| Severity | Count | Headline issues |
|---|---|---|
| **P0** | 4 | Completion is reported as success when the task is not done (outer-timeout, deadlock); identical retry with no strategy change; idempotency key omits arguments so corrected retries are silently skipped; subagent inherits `planApproved=true` and can escape the parent's `ASK` gate |
| **P1** | 9 | Livelock on cancellation deadlock; no independent verifier at graph acceptance; lock renewal window; no hard recursion-depth counter; no concurrency bound across subagents; lost update on CAS conflict |
| **P2** | 8 | Dead code (`ToolSuccessStats`), no backoff enforcement, `appendEvent` silent drops, unordered/priority-tie scheduling, capability fabricated by regex |
| **P3** | 3 | Unused `bounded` knobs, missing max-depth cap, replace-vs-merge `init` |

**The single most important structural finding:** this planner cannot tell "done" from "gave up". `PlanningLoop.run` falls out of its outer loop in at least four distinct unsatisfied states and returns a *normal, success-shaped answer string* (`PlanningLoop.java:527-531`). The caller (`AgentChatApplicationService.java:235`) then calls `taskRunService.markCompleted(run)`. An unfinished, deadlocked, or recovery-exhausted run is recorded as `COMPLETED`.

---

## 1. Plan quality & validation

### How GoalCompiler decomposes

`GoalCompiler.compile` (`GoalCompiler.java:247-271`) tries a **template first**, then the LLM:

1. `fallback()` → `templateGraph()` — rule-based templates keyed off `DecompositionPolicy` signals (diagram / fetch+write / read+analyze / research+file), else one node per `TaskPlan.step`, else a single node (`GoalCompiler.java:419-475`).
2. `structureDeterminate()` — if the template already produced a determinate graph, **return it without ever calling the LLM** (`GoalCompiler.java:250-254`, `612-633`).
3. Otherwise ask the LLM (`compileWithLlm`, `GoalCompiler.java:300-322`), retrying `compilerRetry` times (default 1, so 2 attempts).
4. `parseGraph` → `DataflowNormalizer.normalize` then `markPending`.

### What validation exists (`PlanValidator.validate`, `PlanValidator.java:41-186`)

Genuinely present and good: EMPTY_GRAPH, `hasCycle()`, BLANK_NODE_ID, BLANK_NODE_NAME, DUPLICATE_IDS, `doneWhen().valid()`, MISSING_DEPENDENCY, UNPRODUCED_INPUTS, capability allow-list, file-acceptance-on-non-writer, acquire→persist edge, goal-vs-graph file criteria reconciliation.

### P0-1 — Identical retry on garbled/invalid LLM plans (no strategy change, no prompt repair)

**Evidence** — `D:\AI\miniagent\mini-agent-planner\src\main\java\com\miniagent\agent\planner\GoalCompiler.java:255-270`
```java
ChatModel planner = resolvePlannerChat(chat);
if (planner != null && (plan == null || plan.requiresStructuredPlan())) {
    int retries = Math.max(0, properties.getCompilerRetry());
    for (int i = 0; i <= retries; i++) {
        try {
            ParsedCompilation parsed = compileWithLlm(planner, userMessage, plan);
            if (parsed != null && parsed.graph() != null && !parsed.graph().isEmpty()) {
                return new CompileResult(parsed.goal(), markPending(parsed.graph()), false);
            }
        } catch (Exception e) {
            log.warn("GoalCompiler LLM 拆解失败 retry={}: {}", i, e.getMessage());
        }
    }
}
return structured;
```
`compileWithLlm` builds a **fixed** user prompt with no failure feedback (`GoalCompiler.java:304-312`). The retry re-sends a byte-identical request. Per the report's own criterion — *a production agent must not retry the same failing action unchanged* — this is a blind retry. Note the sibling method `compileWithLlmAndCorrection` (`GoalCompiler.java:224-245`) *does* thread a correction prompt, so the machinery exists and is simply not used on the parse-failure path.

**Production impact:** A model that emits fenced/garbled JSON, or JSON with a schema it will deterministically reproduce, burns the retry budget and falls to the template. Median token cost per compile is doubled for zero expected gain. Worse, when `planner == null` or `requiresStructuredPlan()` is false, a garbled plan degrades straight to a template/single-node graph with `DoneWhen.note()` (`GoalCompiler.java:468-469`) — i.e. **the plan silently loses its acceptance criteria**.

**Remediation:** On parse failure, retry with a *changed* prompt: append the parser exception plus the offending text extract, and switch to a strict-JSON mode (response format / tool-call schema). Feed `PlanValidationReport.toCorrectionPrompt()` into the *first* retry, not only into the post-validation loop. Track `compileAttempts` + `lastParseError` in `Goal` for observability.

### P0-2 — No max-depth / max-node cap on the compiled graph

**Evidence** — `GoalCompiler.java:324-362` (no node-count or depth guard anywhere in the parse path); the only size-related code is a *warning*:
```java
// PlanValidator.java:177-183
if (g.nodes().size() > 10) {
    warnings.add(new PlanValidationReport.ValidationWarning(
        PlanValidationReport.ValidationWarning.Type.PERFORMANCE,
        null,
        "任务图节点较多(" + g.nodes().size() + ")，可能影响执行效率"
    ));
}
```
Confirmed by exhaustive search: `PlannerProperties` (`PlannerProperties.java:1-124`) exposes `maxRecoveries`, `maxOuterRounds`, `proposal*`, `maxLocalRepair/ReplaceTool/RewriteGraph/ReviseGoal`, `maxReplanRetries` — but **no `maxNodes` and no `maxDepth`**. `TaskGraph.hasCycle()` is recursive DFS (`TaskGraph.java:146-162`) with no depth bound.

**Production impact:** A model that returns 500 nodes, or 5 000 chained nodes, is accepted. Cost is bounded only indirectly by `maxOuterRounds=24` — but with `proposalBatchSize=1` a 500-node plan produces a plan that can never finish inside 24 rounds, so the run reliably ends in the *silently-successful* state of P0-3. A deep chain additionally risks `StackOverflowError` in `hasCycle`/`cycleDfs`, which would be thrown out of `compileAndValidate` — **UNVERIFIED** whether a `StackOverflowError` here is caught anywhere up-stack (`PlanningLoop.run` has no try/catch around `compileAndValidate`).

**Remediation:** Add `PlannerProperties.maxNodes` (e.g. 25) and `maxDepth` (e.g. 8), validate in `PlanValidator` as fatal `ValidationError`s, convert `hasCycle` to iterative DFS with an explicit stack.

### P2-1 — Node `capability` is frequently invented by regex, and unknown capabilities are not fatal

**Evidence** — `DataflowNormalizer.java:126-156`
```java
static String inferCapability(TaskNode n) {
    ...
    if (dw != null && dw.isFile()) { return "file_write"; }
    if (dw != null && dw.isMedia()) { return "image"; }
    if (dw != null && dw.isCommand()) { return "shell"; }
    if (dw != null && dw.isValidation()) { return "code"; }
    if (!pathFromName(n.name()).isBlank()) { return "file_write"; }
```
and the capability error is only raised when `structured` is true (`PlanValidator.java:121`):
```java
if (structured && !schedulableCapability(cap)) {
```
`structured = plan != null && plan.requiresStructuredPlan()` (`PlanValidator.java:67`).

**Impact:** When `requiresStructuredPlan()` is false the planner still runs (it can be entered via `DecompositionPolicy.hasGraphSignal`, `PlanningLoop.java:134-136`) but **capability validation is disabled**. A node with `capability="general"` then passes validation, `ToolRouter.allowedFor` resolves `capabilityRegistry.toolsFor("general")`, and the step executes with a generic tool surface — the "node is executable with a registered tool" guarantee is void on this path.

**Remediation:** Run capability validation unconditionally whenever the planner owns the turn; make the `structured` flag affect only the decomposition-quality checks.

### P2-2 — `compileAndValidate` returns a graph it just declared invalid

**Evidence** — `PlanningLoop.java:1117-1130`
```java
log.warn("Replan 全部失败，改用 fallback 模板，最终错误数={}",
        report.errors().size());
GoalCompiler.CompileResult fb = goalCompiler.fallback(
        compiled.goal(), userMessage, taskPlan);
PlanValidationReport fbReport = planValidator.validate(
        fb.graph(), taskPlan, fb.goal());
if (fbReport.valid()) {
    return fb;
}
log.warn("fallback 仍无法验收，拒绝调度 code={} errors={}",
        ErrorCode.AGENT_PLANNER_GRAPH_INVALID.getCode(),
        fbReport.errors().size());
return fb;
```
The last statement returns `fb` even though it failed validation. The safety property survives only because *every* caller re-validates (`PlanningLoop.java:216`, `252`, `937`). That is a fragile invariant — one future caller that trusts `compileAndValidate`'s contract schedules an invalid graph. Also note validation runs twice per compile (in `compileAndValidate` and again at the call site), doubling cost.

**Impact:** Latent correctness hazard; duplicated validation work.

**Remediation:** Return a `CompileResult` carrying `PlanValidationReport`, or throw/return `Optional.empty()`; drop the redundant second validation at call sites.

---

## 2. Scheduling correctness

### What is correct

* Dependencies are respected: `TaskGraph.normalizeForScheduling()` phase 2 promotes to `READY` only when **every** `dependsOn` is `SUCCESS` (`TaskGraph.java:95-118`).
* `ReadyTaskSelector` refuses to schedule anything while any node is `RUNNING` — hard serialization (`ReadyTaskSelector.java:14-17`).
* `PlanningLoop` re-checks `n.status() != READY` before accepting a proposal (`PlanningLoop.java:358-366`), and `ownsRunningProposal` fences every dispatch (`PlanningLoop.java:804-811`).
* Concurrency is effectively bounded at 1 by the above, so "can a node run before its dependency's output is bound?" → **no**, on the normal path. `predecessorOutputs` injects only `SUCCESS` predecessors' output (`PlanningLoop.java:626-668`).

### P0-3 — Unsatisfiable/deadlocked graph is never detected; the run reports success

Two distinct paths, same terminal behaviour.

**Path A — no-ready-node loop.** `PlanningLoop.java:321-350`
```java
List<TaskNode> ready = graphScheduler.select(snap.graph());
if (ready.isEmpty()) {
    ...
    log.warn("PlanningLoop 无 ready 节点且未全部成功，尝试 REWRITE session={}", sessionId);
    TaskNode stuck = firstNonSuccess(snap.graph());
    if (stuck == null) { break; }
    FailureDiagnosis dx = recoveryEngine.diagnose(
            stuck, diagnoseTool(stuck, ""), "no ready nodes");
    TaskGraph before = snap.graph();
    snap = applyRecoveryOrCancel(sessionId, snap, stuck, dx, stuck.id(), ...);
    if (sameNodeStatuses(before, snap.graph())) { break; }
```

**Path B — cancelled dependency permanently wedges its dependents.** `RecoveryEngine.recover` `REVISE_GOAL`/`REWRITE_GRAPH`/`LOCAL_REPAIR` all reset the node to `PENDING` (`RecoveryEngine.java:199-214`); exhaustion sets `CANCELLED` (`PlanningLoop.java:879-892`):
```java
TaskGraph g = snap.graph().replace(
        latest.withStatus(TaskNodeStatus.CANCELLED).withError("recovery_exhausted"));
```
But `normalizeForScheduling` only ever promotes nodes whose deps are `SUCCESS` (`TaskGraph.java:98-108`), and `CANCELLED` is not `SUCCESS` and is not in the retry set. So **every transitive dependent of a CANCELLED node is permanently unreachable**. `firstNonSuccess` skips `CANCELLED` but returns the wedged `PENDING` dependent (`PlanningLoop.java:1050-1057`), whose recovery classes are `LOCAL_REPAIR`/`REPLACE_TOOL`/`REWRITE_GRAPH` — `REWRITE_GRAPH` routes to `tryLlmReplan`, which calls `mergeKeepSuccess` and **discards the old cancelled node**, re-mapping new roots to `successIds` only (`PlanningLoop.java:966-1010`). The dependency-on-cancelled-work fact is destroyed.

**Terminal behaviour** — `PlanningLoop.java:524-531`
```java
if (rounds > properties.getMaxOuterRounds())
    metrics.outerTimeout();

if (StringUtils.isBlank(lastAnswer))
    lastAnswer = "已按规划图推进任务（version="
            + stateStore.get(sessionId).map(StateSnapshot::version).orElse(0L)
            + "，metrics=" + metrics.snapshot() + "）。";
return lastAnswer;
```
`metrics.outerTimeout()` only increments a counter. No exception, no error return, no `RunStatus.FAILURE`. The caller treats it as success — `D:\AI\miniagent\mini-agent-app\src\main\java\com\miniagent\application\AgentChatApplicationService.java:235`
```java
taskRunService.markCompleted(run);
```

**Production impact:** P0. A deadlocked or budget-exhausted plan is durably recorded as a completed task, with an answer string that reads like a progress report ("已按规划图推进任务…"). Users get no signal that work is outstanding; the todo projection was last written with `FAILED`/`PENDING` items (`PlanningLoop.java:501-502`) so the UI may still show incomplete steps while the run is `COMPLETED`. This is the classic premature-completion / reward-hacking path, and it also destroys the metrics (`outerTimeouts` is never surfaced as a failure).

**Remediation:**
1. Add an explicit unsatisfiable-graph check before "no ready nodes": if any non-terminal node has a `CANCELLED`/`FAILED`-terminal ancestor, mark it `CANCELLED` with `blocked_by_dependency`.
2. Return a typed outcome (`RunOutcome.unfinished(reason)`) from `PlanningLoop.run`; map `outerTimeout` / `recovery_exhausted` / `no_ready` to a non-success `RunStatus` so `markCompleted` is not called.
3. Add `PlannerMetrics` exposure for `outerTimeouts` via `HealthController` and alert on it.

### P1-1 — Livelock: `LOCAL_REPAIR` on a cyclic no-progress condition burns the whole round budget

**Evidence** — `PlanningLoop.java:344-349`
```java
if (sameNodeStatuses(before, snap.graph())) {
    break;
}
```
This guards *only* the exact case where the graph is byte-identical after recovery. `RecoveryEngine.recover` always increments `retryCount` and writes `execution` counters, and `revisePlan` bumps `planVersion` (`RecoveryEngine.java:226-233`) — so `sameNodeStatuses` compares only `(id, status)` pairs (`PlanningLoop.java:1033-1048`) and will see `PENDING → PENDING` as "changed" while the *semantic* state is identical. Combined with `normalizeForScheduling` turning that `PENDING` back into `READY` next round, the loop spends rounds until `maxRecoveries`/class limits trip. It terminates only via `properties.getMaxOuterRounds()`.

**Impact:** Up to 24 wasted outer rounds plus up to `3 + 2 + 2 + 1 = 8` recoveries, each potentially a full LLM re-plan, before the P0-3 silent-success exit. Cost amplification with no progress.

**Remediation:** Track a per-node `progressFingerprint` (status + capability + toolHint + argumentsHash). If the fingerprint is unchanged after recovery, treat as no-progress and escalate immediately instead of `continue`.

### P1-2 — Recovery-exhausted nodes are `CANCELLED`, but the graph still "completes"

`allTerminalSuccess()` requires **every** node `SUCCESS` (`TaskGraph.java:120-129`), so a graph containing `CANCELLED` never enters the acceptance branch. But `tryLlmReplan`'s `mergeKeepSuccess` drops cancelled nodes and rebuilds only from the new graph, and `resolveAcceptFailNode` can then return a totally different node (`PlanningLoop.java:1059-1075`). The net effect: a task whose work was cancelled can, after re-planning, be reported as done (see P0-3 for the exit path).

**Remediation:** Persist an `unfinishedNodes` count in `StateSnapshot.execution` and refuse the success exit while it is non-zero; surface it in the final answer ("N steps were cancelled and not delivered").

### P3-1 — Priority ties are broken by declaration order; no fairness across rounds

**Evidence** — `TaskGraph.java:50-58`
```java
public List<TaskNode> readyNodes() {
    List<TaskNode> ready = new ArrayList<>();
    for (TaskNode n : nodes) {
        if (n.status() == TaskNodeStatus.READY) ready.add(n);
    }
    ready.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
    return ready;
}
```
`List.sort` is stable, so ties resolve to insertion order — deterministic *given the same node order*, which for LLM-compiled graphs is model output order. `GraphScheduler.propose` then truncates to `batchSize` (`GraphScheduler.java:33-55`).

**Impact:** Determinism holds (good, contra the audit question), but with `proposalBatchSize=1` a high-priority node that repeatedly fails can be re-proposed ahead of an independent healthy branch every round. Not starvation in the formal sense (the failing node eventually gets `CANCELLED`), but it does serialize the graph on one branch.

**Remediation:** Secondary sort key `(priority desc, retryCount asc, id)` — deprioritize nodes that already failed once so independent work proceeds.

### P3-2 — No bound on graph *width* × rounds feasibility

`maxOuterRounds=24` with `proposalBatchSize=1` means a graph with more than ~24 nodes (minus recoveries) can never reach `allTerminalSuccess()`. `Validate` only warns above 10 nodes. Feasibility is never checked at compile time. **Remediation:** validate `nodes.size() <= maxOuterRounds * proposalBatchSize` at compile time, or raise `proposalBatchSize` when the graph is wide.

---

## 3. Failure classification & recovery

### Enumeration

`FailureKind` (`FailureKind.java:6-22`) — 15 values: `PARAM_ERROR, UNKNOWN_TOOL, TOOL_ERROR, EVAL_FAILED, HARD_GATE, DRIFT, NO_READY, GOAL_BLOCKED, ORPHAN_RUNNING, GENERIC, TIMEOUT, RESOURCE_EXHAUSTED, PERMISSION_DENIED, CONFLICT, DEPENDENCY_FAILED`.

`FailureClass` (`FailureClass.java:4-9`) — 4 values: `LOCAL_REPAIR, REPLACE_TOOL, REWRITE_GRAPH, REVISE_GOAL`.

Mapping is `RecoveryEngine.mapClass` (`RecoveryEngine.java:119-146`) with escalation by `retryCount`. Budgets: `classLimit` (`RecoveryEngine.java:148-155`) → `maxLocalRepair=3, maxReplaceTool=2, maxRewriteGraph=2, maxReviseGoal=1`, global `maxRecoveries=3` (`PlannerProperties.java:24,37-40`). **A global cap does exist** — `RecoveryEngine.java:180-183` and `PlanningLoop.java:868-870`:
```java
if (cur.recoveryCount() >= properties.getMaxRecoveries()) {
    log.warn("Recovery 总上限 session={} count={}", sessionId, cur.recoveryCount());
    return Optional.empty();
}
```
So infinite replanning is prevented. Good.

### P0-4 — `LOCAL_REPAIR` and `REWRITE_GRAPH` produce a byte-identical next attempt

**Evidence** — `RecoveryEngine.java:199-214`
```java
TaskGraph nextGraph = switch (dx.failureClass()) {
    case LOCAL_REPAIR -> working.replace(
            recovering.withStatus(TaskNodeStatus.PENDING).withRetryInc());
    case REPLACE_TOOL -> {
        TaskNode next = recovering.withStatus(TaskNodeStatus.PENDING)
                .withRetryInc();
        if (capabilityRegistry.containsTool(dx.tool())) {
            next = next.withBlockedTool(dx.tool());
        }
        yield working.replace(next);
    }
    case REWRITE_GRAPH -> working.replace(
            recovering.withStatus(TaskNodeStatus.PENDING).withRetryInc());
    case REVISE_GOAL -> working.replace(
            recovering.withStatus(TaskNodeStatus.PENDING).withRetryInc());
};
```
`LOCAL_REPAIR` changes **only** `status PENDING` + `retryCount+1`. The prescribed action text is "修正参数/纠偏后重试同一工具" (`RecoveryEngine.java:59`), but nothing in the code path modifies `toolArguments`, `doneWhen`, `toolHint` or the focus prompt. `REWRITE_GRAPH` in this branch is *identical to* `LOCAL_REPAIR` — the intended subgraph rewrite lives only in `PlanningLoop.tryLlmReplan`, and only when `dx.failureClass() ∈ {REWRITE_GRAPH, REVISE_GOAL}` carries a `chat` model (`PlanningLoop.java:902-905`). Note the subtle trap: `applyRecoveryOrCancel` tries `tryLlmReplan` **first** and falls through to `recoveryEngine.recover` only if it returns empty (`PlanningLoop.java:862-878`).

Then the identical action is re-proposed next round: `GraphScheduler.propose` rebuilds `ActionSpec` from the same `node.capability()`, same `ActionBinder.bind(node, graph)` (`GraphScheduler.java:46`), same `focusBlock` (`PlanningLoop.java:813-846`). Only `retryCount` and `lastError` differ, and `retryCount` is not injected into the prompt.

**Production impact:** P0. For deterministic failures (bad arguments, wrong path, capability misfit — the majority of real agent failures) `LOCAL_REPAIR` is a pure no-op retry at full token cost. It also consumes the recovery budget that `REPLACE_TOOL`/`REWRITE_GRAPH` would need, so genuinely different strategies are starved.

**Remediation:** Make each class mutate state observably:
- `LOCAL_REPAIR`: emit a `correctionHint` into `TaskNode.lastError`/a new `repairDirective` field and inject it into `focusBlock`; for `PARAM_ERROR` specifically, force the step through a re-bind that re-derives `toolArguments` (drop the frozen args and let the model re-select).
- `REWRITE_GRAPH`: require `tryLlmReplan` (do not fall through to a status reset) or, if it fails, escalate to `REVISE_GOAL` rather than silently degrading.
- Add a guard: if the next `ActionSpec` is `equals()` to the failed one (including arguments hash), refuse to dispatch and escalate one class.

### P2-3 — `ActionRetryPolicy` (maxAttempts / backoff) is never enforced

**Evidence** — `ActionRetryPolicy` defines 3 attempts, 500 ms initial backoff, ×2 multiplier (`ActionRetryPolicy.java:21-24`):
```java
public static ActionRetryPolicy transientFailures() {
    return new ActionRetryPolicy(3, 500, 2.0,
            Set.of("TIMEOUT", "RATE_LIMITED", "DEPENDENCY_UNAVAILABLE"));
}
```
It is consumed in exactly one place — a `switch` expression (`RecoveryEngine.java:121`):
```java
if (policy != null && policy.retryable(code) && policy.withinLocalAttempts(retryCount)) {
    return FailureClass.LOCAL_REPAIR;
}
```
and it is *printed to the model* (`PlanningLoop.java:834-835`):
```java
.append(" retryMax=").append(a.retryPolicy().maxAttempts())
```
Repo-wide grep for `initialBackoffMillis|multiplier` finds no scheduling use. There is **no sleep, no backoff, no attempt loop** anywhere in the planner. `ToolPipeline` has its own action-journal attempt counter (`ToolPipeline.java:245-247`) driven by `ActionExecutionStatus`, independent of this policy.

**Impact:** Transient failures (rate limits, timeouts, dependency unavailability) are retried immediately, hot. The declared backoff is a prompt decoration. Under provider rate limiting this converts a recoverable condition into a failure storm.

**Remediation:** Either enforce the policy (sleep `initialBackoffMillis * multiplier^attempt` before re-dispatch, in `executeProposal` or the dispatch loop) or delete the fields so the model is not told about a guarantee the runtime does not provide.

### P2-4 — `DEPENDENCY_FAILED` kind is unreachable; `HARD_GATE` classification is Chinese-string matching

`FailureKind.DEPENDENCY_FAILED` (`FailureKind.java:21`) appears in `mapClass` (`RecoveryEngine.java:139`) but `classify`/`classifyKind` never produce it — grep confirms no producer. Dead branch.

`classifyKind` relies on substring matching against Chinese and English prose (`RecoveryEngine.java:85-113`), e.g.
```java
if (err.contains("硬闸门") || err.contains("hard_gate") || err.contains("hard gate"))
    return FailureKind.HARD_GATE;
...
if (err.contains("目标") || err.contains("goal") || err.contains("无法完成")
        || err.contains("contradict"))
    return FailureKind.GOAL_BLOCKED;
```
Note `err.contains("goal")` — an error string containing the word "goal" (extremely common in an agent runtime) is classified `GOAL_BLOCKED`, which maps to `REVISE_GOAL`, the **most expensive and most destructive** class (it mutates `Goal.constraints`, `RecoveryEngine.java:219-224`).

**Impact:** Misclassification routes cheap failures into goal revision. `GOAL_BLOCKED` has `maxReviseGoal=1`, so a single false positive permanently alters the objective.

**Remediation:** Classify primarily from the typed `ToolErrorCode` (already passed in) and structured fields; treat prose regex as a low-confidence fallback that can only select `GENERIC`/`LOCAL_REPAIR`. Remove the bare `"goal"` substring test.

### P2-5 — Human escalation is not a recovery action

`FailureClass` has no `ESCALATE`/`ASK_HUMAN` member. `PERMISSION_DENIED` maps to `REVISE_GOAL` (`RecoveryEngine.java:138`) — i.e. a permission problem is answered by *rewriting the goal* rather than asking the user. `AWAITING_CONFIRM` is reachable only via the todo projection (`TodoStateProjector.isTodoAwaiting`, `PlanningLoop.java:416-421`), not from the recovery path.

**Impact:** Authorization failures produce silent goal mutation instead of a user-visible confirmation request. Combined with P0-4 this can loop until `CANCELLED`, then exit "successfully".

**Remediation:** Add `FailureClass.ESCALATE_HUMAN`; map `PERMISSION_DENIED`, `GOAL_BLOCKED`, and `RESOURCE_EXHAUSTED`-after-one-retry to it; implement as `AWAITING_CONFIRM` + return to the user.

---

## 4. Verification of completion

### What is genuinely verified

`StepEvaluator.evaluate` (`StepEvaluator.java:58-108`) is **not** pure LLM self-assessment for the strongest acceptance type. `DoneWhen.FILE`/`MEDIA` route to `TodoSemanticValidator.validate` which does real filesystem work: existence (`resolveExisting`), size thresholds, `.md` ≥ 40 bytes and non-blank, `.sql` must contain `CREATE/INSERT/ALTER`, images ≥ 100 bytes, other files ≥ 8 bytes (`TodoSemanticValidator.java:72-119`, `166-187`). `command_success` parses a real `exit_code=0` (`StepEvaluator.java:240-252`). Hollow evidence is actively rejected (`StepEvaluator.java:294-302`):
```java
static boolean isHollowEvidence(String s) {
    if (StringUtils.isBlank(s)) return true;
    String t = s.trim();
    return t.equals(AgentLoop.STEP_SEGMENT_DONE)
            || t.equals("本步已完成")
            || t.startsWith("已按规划图推进任务");
}
```
That is a real anti-reward-hacking control and better than most agent runtimes.

### P1-3 — Graph acceptance is a re-evaluation of the same stored string, not an independent check

**Evidence** — `StepEvaluator.java:147-162`
```java
for (TaskNode n : graph.nodes()) {
    if (n.status() != TaskNodeStatus.SUCCESS) {
        return GraphEval.fail(n.id(), "eval: unfinished node " + n.id());
    }
    if (!NodeOutputBinder.ofNode(n).complete(n)) {
        return GraphEval.fail(n.id(), "eval: outputs 未绑定");
    }
    EvalResult ev = evaluate(n, n.output(), n.output());
    if (!ev.ok()) return GraphEval.fail(n.id(), ev.reason());
}
```
The graph-level check feeds `n.output()` back through the *same* evaluator. For `note_required` nodes, `n.output()` is a string the model produced, and `evaluate` accepts any non-hollow non-blank string (`StepEvaluator.java:91-96`):
```java
if (dw.isNote()) {
    if (StringUtils.isBlank(ev) || isHollowEvidence(ev)) {
        return reject("缺少 evidence");
    }
    return EvalResult.pass();
}
```
**Nothing is re-stat'ed at graph acceptance time.** For file nodes the path is re-validated only because `evaluateFile` re-runs `TodoSemanticValidator` — that part is genuinely independent and would catch a deleted artifact. But a graph made of `note_required` nodes (which the templates emit liberally: `GoalCompiler.java:449`, `523`, `542`, `568`) passes acceptance on model prose alone.

**Impact:** For note-only plans, "done" = "the model wrote a plausible sentence". The `planner` system prompt even warns about this (`GoalCompiler.java:154`: *"note_required 只靠一段文字就算通过，用错会让'什么都没交付'被判成功"*) but the validator only blocks the pattern where `needsFileAcceptance(n)` is true (`PlanValidator.java:108-117`) — i.e. only for capabilities in `CapabilityRegistry.persistsArtifacts`. A `shell`/`code` node that was *supposed* to produce a file but has `note_required` is not blocked unless the normalizer upgraded it.

**Remediation:**
1. At graph acceptance, re-run every `file_exists`/`media_delivered`/`command_success` check against the live world (partially done) and mark `note_required` nodes as *unverifiable*; require an `llm_judge` with `criteria` for any note node whose capability could persist artifacts.
2. Track an artifact manifest (`path → sha256`, already computed by `TodoSemanticValidator.validateFile`) in `StateSnapshot`, and require the graph-level check to confirm the *same hash* is still present.

### P1-4 — No independent verifier for `llm_judge`; the judge is optional and soft-fails closed only by accident

**Evidence** — `StepEvaluator.java:228-238`
```java
private EvalResult judge(TaskNode node, String criteria, String evidence) {
    if (llmJudge == null) {
        return reject("llm_judge 未启用");
    }
    String spec = DoneWhen.JUDGE + ":" + criteria;
    TaskTodoStore.TodoItem item = new TaskTodoStore.TodoItem(
            0, node.name(), TaskTodoStore.Status.in_progress, "", spec, evidence, "", List.of());
    String err = llmJudge.validate(item, evidence);
    return err == null ? EvalResult.pass() : reject(err);
}
```
`llmJudge` is injected `@Autowired(required = false)` (`StepEvaluator.java:52`). When absent, **every `llm_judge` node fails unconditionally** → `REWRITE_GRAPH` → `tryLlmReplan` → likely re-emits a judge node → fail again → `CANCELLED` → P0-3 silent success. Also note the judge receives `node.name()` and the model-supplied `evidence` — i.e. **the model grades its own work**. `PlannerProperties.judgeModelName` (`PlannerProperties.java:46`) exists, and the dedicated judge model is presumably used inside `LlmJudgeTodoValidator` — **UNVERIFIED** (that class was outside the stated scope; I did not read it).

**Remediation:** Fail fast at startup if `strictEval=true` and no judge model is configured. Make the judge a *different* model instance from the executor by construction, and include the artifact bytes/hash rather than the model's own summary as the judged evidence.

### P2-6 — `custom approval` path can flip a failed step back to `READY` without re-verification

`PlanningLoop.java:412-415` and `464-465`:
```java
if (humanWait) {
    g = g.replace(node.withStatus(TaskNodeStatus.READY).withError(""));
    continue;
}
...
} else if (next == TaskNodeStatus.READY) {
    g = g.replace(node.withStatus(TaskNodeStatus.READY).withError(""));
}
```
The `quotaAbort`/`humanWait` path rewinds to `READY` unconditionally — correct for a genuine mid-step interruption, but it also discards `lastError` (`withError("")`), so the failure context that P0-4 needs for a strategy change is erased. **Remediation:** preserve `lastError` when rewinding for quota/human-wait, and distinguish "not yet attempted" from "attempted and interrupted".

---

## 5. Resume / crash recovery

### P1-5 — Idempotency key omits the arguments hash: corrected retries are silently skipped

**Evidence** — `GraphScheduler.java:52`
```java
"idem-" + snap.planVersion() + "-" + node.id(), timeout,
```
consumed by `ToolPipeline.journalKey` (`D:\AI\miniagent\mini-agent-loop\src\main\java\com\miniagent\agent\execution\ToolPipeline.java:290-304`):
```java
ExecutionTurnContext.ActionBinding binding = fence.resolve(
        name + "@" + turn + "@" + sha256(arguments), name);
return new ActionJournalKey(
        fence.sessionId(), fence.planVersion(),
        binding.nodeId(), binding.idempotencyKey());
```
and `ActionJournalKey` is `(sessionId, planVersion, nodeId, idempotencyKey)` (`ActionJournalKey.java:6-7`). Note the arguments hash **is** used as the *resolution* key but is **not part of the journal key** — that is precisely the bug. The replay decision is `ToolPipeline.java:237-239`:
```java
if (entry.status() == ActionExecutionStatus.SUCCEEDED) {
    return ToolResult.success("{\"success\":true,\"deduplicated\":true}");
}
```

**Failure sequence:** run 1 with planVersion P, node `n1`, args A → SUCCEEDED recorded under key `(s, P, n1, idem-P-n1)`. A later attempt in the *same* execution retries `n1` with **corrected** args B (this is exactly what a good `LOCAL_REPAIR` should do) → same key → returns a **synthetic success without executing**. `PlanningLoop.executeBoundAction` treats it as a pass (`PlanningLoop.java:790-791`):
```java
boolean ok = result != null && result.isSuccess()
        && !StepEvaluator.looksLikeToolError(text);
```
and the node goes `SUCCESS` with `bound.evidence` = the string `{"success":true,"deduplicated":true}` (`PlanningLoop.java:454-457`).

**Impact:** P0-class silent wrong-success on the resume/retry path: the corrected side effect never happens, and the step is marked done. This is the exact hazard the ordering question asks about ("retry uses a modified prompt/strategy" — when it finally does, dedup swallows it).

Separately on resume: `planVersion` is deliberately bumped on every recovery (`RecoveryEngine.java:231-233`, `revisePlan`), so a resumed plan gets a **new** journal namespace and thus does **not** dedupe against pre-crash successes — the safe direction for side effects, but it means a resumed SUCCESS node can be re-executed if its status was lost. That is why the requirement "a resumed run must not duplicate side effects" is only half-satisfied.

**Remediation:** Include `argumentsHash` in `ActionJournalKey`. Keep the status-based dedup, but make it apply only when the incoming arguments hash matches the recorded one; otherwise treat as a distinct action. Also record the journal key inside `TaskNode` so the planner can prove which digest produced a `SUCCESS`.

### P1-6 — `ExecutionTurnContext` session identity may disagree with the planner's storage key

**Evidence** — `PlanningLoop.java:732-733`
```java
ExecutionTurnContext.open(sessionId, proposal.basedOnPlanVersion(), bindings,
        () -> dispatchFenceValid(sessionId, proposal));
```
uses the raw `sessionId`, while every state read/write goes through `PlannerStateStore.key(sessionId)` → `TaskScopeRegistry.scopeKey(sessionId)`, which appends `#<taskId>` when `taskId != 0` (`TaskScope.java:61-63`, `PlannerStateStore.java:82-84`). So the action journal for task 1 is stored under session `s` while the graph lives under `s#1`.

**Impact:** The journal and the plan it fences are keyed by different namespaces. Two different tasks on the same session share one journal namespace for `planVersion` collisions (both start at 1), so a node id `n1` in task 1 and task 2 at the same `planVersion` collide on `(sessionId, planVersion, nodeId, idempotencyKey)` and one task's `SUCCEEDED` dedupes the other's action. **This is the second, independent silent-wrong-success path.** Severity P1 (needs two tasks in one session).

**Remediation:** Open the turn context with the scope key (`stateStore.key(sessionId)`), or add `taskId` to `ActionJournalKey`.

### P1-7 — Lost update: CAS conflicts are swallowed and the mutation is not reapplied

**Evidence** — `PlanningLoop.java:491-500`
```java
if (!anyFail) {
    try {
        snap = stateStore.commit(sessionId, snap.version(), snap.withGraph(g));
        trace(sessionId, AgentStepNode.STATE_COMMIT, "{\"version\":" + snap.version() + "}");
    } catch (PlannerStateStore.VersionConflictException e) {
        metrics.casConflict();
        log.warn("成功提交冲突，replan: {}", e.getMessage());
    }
}
```
The catch does **not** `continue` and does not retry — execution falls through to line 501 with the in-memory `g` discarded and `snap` still holding the pre-mutation snapshot. The node's `SUCCESS` transition is lost**.** The subsequent `TODO` projection reads from the store (`PlanningLoop.java:501-502`), so it also shows the stale status. Next outer round, `normalizeForScheduling` sees the node as `RUNNING` and converts it to `FAILED` with `orphan_running` (`TaskGraph.java:85-87`), which classifies as `ORPHAN_RUNNING` → `LOCAL_REPAIR` (`RecoveryEngine.java:128`) → re-execute.

Same pattern at `PlanningLoop.java:481-485` (the `break` abandons the remaining actions in the proposal) and `PlanningLoop.java:307-309`.

**Impact:** A transient version conflict converts a completed step into a re-execution, wasting budget and (for non-idempotent tools) risking duplicate side effects. Combined with P1-5, the retry may instead be deduped into a fake success.

**Remediation:** Re-read, re-apply the delta against the fresh snapshot, and retry with a bounded attempt count (the merge is a pure `graph.replace` so it is trivially replayable). Only give up after N attempts, and when giving up, return a typed `unfinished` outcome rather than falling through.

### P2-7 — `appendEvent` drops audit events silently after 3 CAS attempts

**Evidence** — `PlannerStateStore.java:242-254`
```java
if (persistence != null) {
    for (int i = 0; i < 3; i++) {
        Optional<PlannerStatePersistence.Bundle> loaded = persistence.load(k);
        if (loaded.isEmpty()) { return; }
        long ver = loaded.get().snapshot().version();
        List<DomainEvent> events = appendLocal(loaded.get().events(), event);
        if (persistence.updateEvents(k, ver, events)) { return; }
    }
    return;
}
```
Three failures → `return` with no log, no metric, no exception. `NODE_SUCCESS`/`NODE_FAILED`/`RECOVERY_APPLIED` events (`PlanningLoop.java:460-463`, `471-474`; `RecoveryEngine.java:236-241`) can vanish silently. Also `appendEvent` is a full read-modify-write of the whole event list on every call — O(n) per event with `MAX_EVENTS=500` (`PlannerStateStore.java:49-50`).

**Remediation:** Log + counter on exhaustion; make the journal append-only at the storage layer (separate INSERT) instead of read-modify-write of a JSON blob.

### P3-3 — `PlannerStateStore.init` uses force-overwrite `replace`

**Evidence** — `PlannerStateStore.java:96-113` writes via `persistence.replace(k, snap, events)` — a `DELETE`/`INSERT`-style overwrite (`DbPlannerStatePersistence.java:36-44`), not a CAS. Two concurrent planning turns on the same scope key both `init` and the second silently destroys the first's graph. `TaskRunService.tryStart` normally serializes per session (`TaskRunService.java:88-94`), so this is defensive-only. **Remediation:** make `init` conditional (`insert if absent`) and fall back to resume when a row already exists.

---

## 6. Delegation / subagents

### What is correct

* **Context isolation is real.** Fresh session id with a sub-scope (`ClientMultiAgent.java:126-129`):
```java
String subSid = StringUtils.isNotBlank(parentSid)
        ? parentSid + ":sub:" + Long.toHexString(System.nanoTime())
        : "sub_" + Long.toHexString(System.nanoTime());
```
  empty history (`ClientMultiAgent.java:138`: `List.of()`), separate workspace (`SubagentScope.java:30` → `BuiltinTools.prepareSubagentWorkspace`), separate role, and the subagent only sees `goal` + `context` (`ClientMultiAgent.java:118-124`).
* **Budget/cost accounting to the parent is real.** `ExecutionControl.attach` maps child→parent (`ExecutionControl.java:83-89`), and `attachWorker` is called before the child runs (`ClientMultiAgent.java:132-134`). `lease()`/`resolve()` route tool calls, tokens, deadline and cancellation to the parent's lease (`ExecutionControl.java:181-188`). The class doc is explicit: *"子 agent 与父会话共用取消、墙钟、工具次数和 token 预算"*.
* **Infinite recursion is prevented — by construction, not by a depth counter.** Three layers: tool surface excludes it (`ClientMultiAgent.java:182`):
```java
if (t == null || t.isBlank() || NESTED_DELEGATE.equals(t)) { continue; }
```
  a hook denies it (`DenyNestedDelegateToolHook.java:23-29`), and the tool is not in the general capability surface. I traced the recursive path (`PlanningLoop → AgentLoop → ToolPipeline → DelegateTaskTool → ClientMultiAgent.runWorker → AgentLoop`) and no call exposes `delegate_task` to a child.
* **Allow-list intersection, not union** (`ClientMultiAgent.java:174-178`):
```java
Set<String> ceiling = new LinkedHashSet<>(configured);
Set<String> parent = ToolCallContext.allowedTools();
if (parent != null) { ceiling.retainAll(parent); }
```
  plus `capabilityRegistry.containsTool(t)` re-validation at line 185. This is the right shape.

### P0 — Privilege escalation: subagent forces `planApproved=true` and drops the parent's `ASK` gate

**Evidence** — `SubagentScope.java:21-34`
```java
public static SubagentScope enter(String subSessionId, String roleId, boolean inheritAsk) {
    RunScope parent = RunScope.capture();
    PermissionMode parentMode = parent.permissionMode();
    PermissionMode childMode = PermissionMode.DEFAULT;
    if (inheritAsk && parentMode == PermissionMode.ASK) {
        childMode = PermissionMode.ASK;
    } else if (parentMode == PermissionMode.ACCEPT_EDITS) {
        childMode = PermissionMode.ACCEPT_EDITS;
    }
    Path subWs = BuiltinTools.prepareSubagentWorkspace(subSessionId);
    String role = StringUtils.isBlank(roleId) ? "" : roleId;
    RunScope child = parent.asSubagent(subSessionId, role, childMode, subWs);
    return new SubagentScope(child.bind());
}
```
and the only production caller passes `false` — `ClientMultiAgent.java:137`:
```java
try (SubagentScope scope = SubagentScope.enter(subSid, role, false)) {
```
Two distinct escalations:

1. **`ASK` → `DEFAULT`.** `PermissionMode.ASK` is documented as *"危险工具须前端确认一次后才放行"* (`PermissionMode.java:13`). With `inheritAsk=false` the `if` branch is skipped entirely, so a child spawned from an `ASK` session runs as `DEFAULT` — no confirmation gate. The parent is gated; the child is not, and the parent can reach the child via one tool call. **A user who selected "危险操作询问" has that control bypassed by delegation.**
2. **`PLAN` → `planApproved=true`.** `RunScope.asSubagent` hardcodes it (`RunScope.java:79-88`):
```java
public RunScope asSubagent(
        String subSessionId, String roleId, PermissionMode childMode, Path subWorkspace) {
    String parent = sessionId == null ? "" : sessionId;
    return new RunScope(
            subSessionId, owner, LoopTurnPolicy.NONE,
            childMode == null ? PermissionMode.DEFAULT : childMode,
            true, true, true, parent, ...);
```
   The 5th positional field is `planApproved` (record order at `RunScope.java:21-37`: `permissionMode, planApproved, permissionForced, subagent`). `PermissionContext.planApproved()` then returns the forced `true` (`PermissionContext.java:56-66`), and `PlanUnapprovedStopHook` is neutralised. A `PLAN`-mode parent that has **not** had its plan approved can spawn a child that immediately writes files.

Also note `LoopTurnPolicy.NONE` is installed for the child (`RunScope.java:83`), which means **the planner's hard gate (`ProposalTurnPolicy`) is absent inside subagents** — by design, but it means `hardProposal=true` does not constrain delegated work. And `childMode` for a `PLAN` parent becomes `DEFAULT` (the `else if` only matches `ACCEPT_EDITS`), so the child loses the parent's read-only restriction too.

**Production impact:** P0. Permission controls are not monotonically decreasing across the delegation boundary — the child can be strictly more privileged than the parent. In a multi-tenant web deployment this is an authorization bypass reachable by prompt content alone.

**Remediation:**
- Compute `childMode` as the *minimum* capability of parent mode and role requirement: never upgrade. Explicitly: `PLAN → PLAN`, `ASK → ASK`, `DEFAULT → DEFAULT`, `ACCEPT_EDITS → ACCEPT_EDITS` unless the role is read-only.
- Remove the hardcoded `planApproved=true`; pass the parent's value through (or `false`).
- Add an assertion/test: for every `PermissionMode`, `childMode ⊆ parentMode`.

### P1-8 — No concurrency bound on live subagents; no cancellation that reaches the child

`ClientMultiAgent.runWorker` has no semaphore, queue, or pool — every `delegate_task` invocation runs the child to completion on the caller thread, capped only by `subagentMaxIterations=25` (`ExecutionProperties.java:18`) and the parent's shared lease. `sessions` is just a membership set (`ClientMultiAgent.java:50`).

**Impact:** A model that emits N parallel `delegate_task` calls (or the planner doing so via `proposalBatchSize>1`) spawns N concurrent subagents, each with its own LLM loop, all sharing one `maxToolCalls=120`/`maxEstimatedTokens=2e6` lease (`ExecutionProperties.java:13-14`). Budget is shared, so the *parent* loop starves — and there is no per-subagent wall clock beyond the parent's lease deadline.

**Remediation:** Add `agent.execution.max-concurrent-subagents` enforced by a `Semaphore` with a bounded wait, plus a per-subagent deadline.

### P1-9 — Subagent result is JSON-wrapped but never validated

**Evidence** — `ClientMultiAgent.java:148-157`
```java
try {
    return MAPPER.writeValueAsString(Map.of(
            "success", true,
            "role", roleLabel,
            "goal", goal,
            "summary", clamp(answer, SUMMARY_MAX_CHARS)));
} catch (Exception e) {
    return error(e.getMessage());
}
```
`"success": true` is unconditional once `agentLoop.run` returns without throwing — including when the child's loop ended on `MAX_ITERATIONS` (partial work) or when `answer` is empty/hollow. Only exceptions produce `success:false` (`ClientMultiAgent.java:143-144`). There is no schema validation, no artifact verification (the summary is trusted text), and `clamp` truncates at 2000 chars (`ClientMultiAgent.java:211-216`) with no structured field for produced file paths that the parent could verify.

**Impact:** The parent consumes `{"success": true, "summary": "…"}` at face value. `DelegateTaskTool.handle` returns this string straight into the parent's tool result with no parsing (`DelegateTaskTool.java:120`). A child that did nothing but describe intent is indistinguishable from one that delivered.

**Remediation:** Validate the child outcome before returning: require non-empty `answer`, map `endReason` (`MAX_ITERATIONS`/`RESOURCE_QUOTA_EXCEEDED`) to `success:false` with reason, and require a machine-checkable `artifacts: [{path, sha256, bytes}]` array that the parent verifies with `TodoSemanticValidator`-style checks. Reject `success:true` with zero artifacts when the role is a producer role.

### P2-8 — Role prompt is appended, not scoped; role tool list silently ignored when empty

`RoleLoader.load` swallows all failures and leaves `roles` empty (`RoleLoader.java:62-64`):
```java
} catch (Exception e) {
    log.error("加载 roles.yml 失败", e);
}
```
`resolveTools` then falls back to the *general* tool surface (`ClientMultiAgent.java:171-173`):
```java
} else {
    configured = capabilityRegistry.toolsFor(CapabilityRegistry.GENERAL);
}
```
So a YAML/schema error **silently widens** the tool surface from a role-restricted list to the general list, instead of failing closed. A role whose `allowed_tools` is absent has the same effect.

**Remediation:** Fail startup (or fail the delegation) when a role is requested but no role config loaded; treat an empty `allowed_tools` as "deny all" for producer roles, or require an explicit `*`.

---

## 7. State store, locking, multi-instance

### What is correct

* **CAS optimistic locking is real and DB-enforced.** `DbPlannerStatePersistence.compareAndSet` issues a conditional SQL update and checks the row count (`DbPlannerStatePersistence.java:48-65`):
```java
if (next.version() != expectedVersion + 1)
    throw new IllegalArgumentException(
            "next.version must be expected+1, got " + next.version()
                    + " expectedBase=" + expectedVersion);
...
int updated = repo.casUpdate(sessionId, expectedVersion, next.version(), ..., LocalDateTime.now());
return updated == 1;
```
* **`buildCommitted` enforces plan-version discipline** (`PlannerStateStore.java:164-195`): monotonic-by-at-most-one, semantic fields may not change without a `planVersion` bump, and parent linkage is checked. Genuinely strong invariant.
* **Multi-instance locking exists and is Redis-backed when configured.** `RedisTaskConcurrency.tryLockSession` uses `SET NX` with TTL (`RedisTaskConcurrency.java:122-125`); renewal and release are Lua scripts guarded by token ownership (`RedisTaskConcurrency.java:54-68`, `157-161`); `RedisMysqlPlannerStateStore` deliberately keeps MySQL as the CAS authority with Redis as a read cache, and evicts on conflict (`RedisMysqlPlannerStateStore.java:74-85`). That is the correct architecture for multi-replica.
* **Fencing tokens** are threaded from `TaskRunService` into the planner and checked every outer round (`PlanningLoop.java:270-276`).

### P1-10 — Lock renewal only happens once per outer round; lock can expire mid-round on a live instance

**Evidence** — `PlanningLoop.java:269-276` (top of the outer `while`):
```java
while (rounds++ < properties.getMaxOuterRounds()) {
    if (!sessionLock.renewSessionLock(sessionId, fencingToken)) {
```
Nothing renews inside `executeProposal`, which can run `maxIter = proposalMaxIterations(8)` iterations **plus** up to `proposalMaxChunks(6)` continuation chunks (`PlanningLoop.java:752-771`), each with real LLM latency. `runLockTtlSeconds` defaults to **7200 s** (`ReplicaProperties.java:15`).

**Impact:** A single long step (browser work: `proposalBrowserMaxIterations=16` × 6 chunks) can plausibly exceed 2 h on a stalled provider. When the Redis lock expires during that window, another replica's `tryStart` succeeds (`TaskRunService.java:98-99`), `interruptPreviousRun` marks the original `INTERRUPTED` (`TaskRunService.java:180-189`), and both instances now write to the same `planner:session:*` / DB row. The CAS prevents a torn graph, but both loops execute side-effecting tools. **The fencing token is checked only at round boundaries, so the violating instance keeps working for up to a full round after losing the lease.**

**Remediation:** Renew from a heartbeat (scheduled task, or inside the tool-dispatch path — `ToolPipeline` already has a `RunScope`). Add a `dispatchFence` check that also verifies the lock token, so tool calls are refused the moment the lease is lost. Reduce `runLockTtlSeconds` and renew at ~⅓ TTL.

### P1-11 — Crash leaves the session locked until TTL, and the orphan reaper explicitly skips it

**Evidence** — `TaskRunService.java:73-76`
```java
if (taskConcurrency != null
        && taskConcurrency.isSessionLocked(run.getSessionId())) {
    continue;
}
```
At startup, a `RUNNING` row is only reaped to `INTERRUPTED` when the Redis lock is **gone**. If the process died but Redis is alive, the lock key persists for the remaining TTL (up to 7200 s), so:
- the row stays `RUNNING` forever (until TTL),
- `isRunning()` returns true (`TaskRunService.java:254-259`) so `tryStart` rejects new work with *"该会话已有任务在运行"* (`TaskRunService.java:92-94`),
- the user must wait up to two hours with no way to unblock.

Release *is* in a `finally` (`AgentChatApplicationService.java:256-259` → `TaskRunService.finish` → `releaseResources`), so graceful shutdown is fine. `SIGKILL`/OOM/power loss is not.

**Impact:** P1 — a crash makes a session unusable for up to 2 h, and the reaper's own logic is what prevents the fix. In local mode (`taskConcurrency == null`) the lock is a plain `ConcurrentHashMap` entry (`TaskRunService.java:36`, `124`, `169`) with no TTL at all; a process killed after `putIfAbsent` and before `releaseResources` leaves the session **permanently** locked (the JVM map dies with the process, so the *next* process is fine — but a `RUNNING` DB row remains, and `isRunning` checks the DB in local mode at `TaskRunService.java:258`, so `tryStart` rejects forever).

**Remediation:** Reap `RUNNING` rows on startup regardless of lock presence when the row's `startedAt` is older than `runLockTtlSeconds`, and delete the stale Redis key (`unlockSession` with the token recorded in the row). Store the fencing token in `AgentTaskRun` so the reaper can release precisely. Add a "force release session" admin path.

### P2-9 — Planner state falls back to a process-local map when no persistence bean is present

**Evidence** — `PlannerStateStore.java:69-74`
```java
@Autowired
public PlannerStateStore(@Autowired(required = false) PlannerStatePersistence persistence,
                         @Autowired(required = false) TaskScopeRegistry scopeRegistry) {
    this.persistence = persistence;
    this.scopeRegistry = scopeRegistry;
}
```
`DbPlannerStatePersistence` is conditional on `agent.planner.storage=db` with `matchIfMissing = true` (`DbPlannerStatePersistence.java:19`), and `application.yml:310` sets `storage: db`. So production is fine **only because of config**. If the property is moved to `file`, `FilePlannerStatePersistence` becomes active — but that writes with a plain `Files.writeString` (`FilePlannerStatePersistence.java:104-105`), **not** temp-file + atomic rename, so a crash mid-write truncates the snapshot and `load` then swallows the parse error and returns `Optional.empty()` (`FilePlannerStatePersistence.java:51-54`) — silently losing the plan. If neither bean is present, everything is a `ConcurrentHashMap` (`PlannerStateStore.java:52`) and resume is impossible.

**Impact:** P2 — configuration-dependent silent state loss. `FilePlannerStatePersistence`'s own javadoc says "非多副本" but says nothing about durability.

**Remediation:** Fail startup when `planner.enabled=true` and no persistence bean is present. Make the file backend atomic (`write temp → fsync → ATOMIC_MOVE`) and distinguish "absent" from "corrupt" on load (a corrupt file must raise, not return empty).

### P2-10 — `SessionLock.renewSessionLock` is called with a possibly-blank fencing token, aborting the run

**Evidence** — `TaskRunService.java:159-170`
```java
public boolean renewSessionLock(String sessionId, String fencingToken) {
    if (sessionId == null) { return true; }
    if (fencingToken == null || fencingToken.isBlank()) { return false; }
```
and `PlanningLoop.run` accepts a null/blank token from its caller (`PlanningLoop.java:183`, passed through `AgentChatApplicationService.java:420`). A `false` return is interpreted as "lock lost" and aborts with `AGENT_PLANNER_LOCK_LOST` (`PlanningLoop.java:270-276`).

**Impact:** Any caller that does not supply a fencing token gets an immediate planner abort on the first round. Currently `AgentChatApplicationService` always supplies `run.fencingToken()`, so this is latent — **UNVERIFIED** whether any other caller (desktop entry point, tests, MCP path) invokes `PlanningLoop.run` without a token; I only confirmed the one call site via grep.

**Remediation:** Distinguish "no token supplied → single-instance mode, allow" from "token supplied but lost → abort". Or make `PlanningLoop` fail fast at construction when `fencingToken` is blank and multi-replica mode is on.

---

## Already solid

These are genuine strengths, several of them better than typical production agent runtimes:

1. **Dispatch fence is a real, non-bypassable gate.** `NodeExecutor.requireFence()` throws when no valid fence is installed (`NodeExecutor.java:74-79`), `ExecutionTurnContext` is an explicit, copied-into-virtual-threads scope (not a loose ThreadLocal), and `ownsRunningProposal` re-verifies `(planVersion, node.status==RUNNING)` at both dispatch and result-commit time (`PlanningLoop.java:804-811`, `372-377`, `399-404`). Stale proposals are provably discarded.
2. **Filesystem-grounded acceptance for artifact nodes.** `TodoSemanticValidator` actually stats files, enforces size floors, and pattern-checks `SQL`/markdown content (`TodoSemanticValidator.java:72-119`). Hollow-evidence rejection is explicit and tested (`StepEvaluator.isHollowEvidence`, `StepEvaluator.java:294-302`).
3. **Cycle detection and dependency existence checks** exist and are wired into validation (`TaskGraph.hasCycle` `TaskGraph.java:146-162`; `MISSING_DEPENDENCY` `PlanValidator.java:149-158`; `UNPRODUCED_INPUTS` `PlanValidator.java:161-168`).
4. **The planner never runs a node with unsatisfied dependencies** — `normalizeForScheduling` phase 2 is the single promotion point (`TaskGraph.java:95-118`) and `PlanningLoop` double-checks `READY` before proposing (`PlanningLoop.java:358-366`).
5. **Bounded recovery.** A global `maxRecoveries` plus four per-class circuit breakers (`RecoveryEngine.java:180-190`, `148-155`) means unbounded replanning is genuinely impossible — a real maturity signal.
6. **Plan versioning is disciplined.** `buildCommitted` refuses plan-semantic changes without a `planVersion` bump and validates parent linkage (`PlannerStateStore.java:164-195`); `planVersion` is separated from `state.version` (`PlanRevision.java:7`).
7. **Task-scope keying fixes "previous task bleeds into this task".** The `sessionId#taskId` scheme (`TaskScope.java:61-63`) plus a monotonic sequence allocator that survives `RESUME` (`TaskScopeRegistry.java:64-69`, `175-179`) is a genuinely good design, and the rationale in the javadoc is accurate about the failure it removes.
8. **`mergeKeepSuccess` preserves completed work across re-planning** and cycle-checks the merged graph before accepting it (`PlanningLoop.java:966-1010`).
9. **Multi-instance architecture is correct in shape:** MySQL is the CAS authority, Redis is a hot cache that is evicted on conflict, locks are token-guarded Lua scripts, and the code explicitly refuses to let Redis be the source of truth (`RedisMysqlPlannerStateStore.java:22-25, 74-85`).
10. **Subagent isolation and budget inheritance are real:** fresh session, empty history, separate workspace, distinct role; and `ExecutionControl.childToParent` genuinely charges a child's tool calls, tokens, deadline and cancellation to the parent's lease (`ExecutionControl.java:82-89`, `181-188`).
11. **Nested delegation is blocked in depth** (tool-surface exclusion + `DenyNestedDelegateToolHook` + hardcoded name filter), and tool allow-lists are **intersected** with the parent's, never unioned (`ClientMultiAgent.java:174-188`).
12. **`DbPlannerStatePersistence.toBundle` reconciles the version column against the JSON blob** (`DbPlannerStatePersistence.java:88-97`), defending against exactly the drift that breaks schema-less state stores.

---

## Cross-cutting recommendation

Four of the four P0s share one root cause: **the runtime has no first-class notion of "the run ended without satisfying the goal."** `PlanningLoop.run` returns `String`. Every terminal condition — outer timeout, recovery exhaustion, unsatisfiable graph, human-wait — is flattened into the same channel as success, and `AgentChatApplicationService` maps that channel onto `markCompleted`.

Introducing a `RunOutcome { status: COMPLETED | UNFINISHED | BLOCKED_ON_HUMAN | FAILED, answer, unfinishedNodes[], reason }` returned by `PlanningLoop.run`, and refusing `markCompleted` for anything but `COMPLETED`, would close P0-3, make P1-1/P1-2/P1-7 observable instead of silent, and give the UI the information it needs to show "3 steps outstanding". I'd do that before any of the individual remediations above.
