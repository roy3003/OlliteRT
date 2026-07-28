# Handoff: Floating Monitor + Inference Lifecycle

**Written:** 2026-07-28
**Audience:** the next session picking up this work.
**Status of this document:** review findings and recommendations. Nothing here has been
implemented. No code was changed in producing it.

Every claim below cites a `file:line` on `main` or on `origin/fix/minimal-inference-lifecycle`.
Verify rather than trust — line numbers drift.

---

## 0. Branch topology

```
main (f4f7bf9)
  │   has: multi-interface endpoint discovery + selector (882b8e0, 0986787, f4f7bf9)
  │
  └─ refactor/remove-endpoint-selector (8ab20cb)
     │   - b06c601 deliberately removes the endpoint selector (-356 lines)
     │   - floating monitor feature: COMPLETE (11 impl files ~1150 lines, 11 test files)
     │
     └─ fix/minimal-inference-lifecycle (69f68be)
           - inference lifecycle rework, ~40 commits
           - CI green at 0dc2996 (stableDebug compile, JVM tests, Android lint)
           - 4 task groups remain
```

The endpoint-selector removal is intentional, not an accident — it is part of the decision to
keep the floating monitor and re-do the rest in a fresh worktree. It is **not** a stray revert.

Consequence to be aware of: the completed floating monitor sits on a baseline that also carries
that removal, and both sit under 7 remaining steps of lifecycle work. See §7.

---

## 1. Floating monitor — status and findings

The feature is **functionally complete and CI-green**. Findings are cleanup, not redesign.

### 1.1 Spec has drifted from the implementation

`openspec/changes/add-background-floating-monitor/specs/floating-monitor.md` and `proposal.md`
no longer describe the code. The spec is currently misleading — worse than absent.

| Spec says | Code actually does |
|---|---|
| Elapsed formats `0s..59s`, `M:SS` to `99:59`, `99m+` | Plain seconds with a separately-drawn `s` suffix, capped `9999+` (`FloatingMonitorFormatter.kt`) |
| "dark semantic backgrounds", "values high-contrast white" | Bright green `#55D68B` / orange `#FFB74D` fill, **black** text (`FloatingMonitorView.kt`) |

Commit `8ab20cb fix: keep processing time in plain seconds` changed the behavior without
updating the spec. Backfill the spec to match the code (the code is the intended behavior).

Count formatting **is** correct: exact through `99,999`, `99,999+` from `100,000`.

### 1.2 tasks.md bookkeeping is stale

All of groups 1–7 in `add-background-floating-monitor/tasks.md` are unchecked while the code is
fully implemented. A cold session reading it will conclude nothing has been done. Check them off.

### 1.3 Four implementation issues

**a. Draft state can launch the permission flow.** `AutoLaunchCard.kt` gates the
"Permission required" row and the Grant button on `vm.floatingMonitorEntry.current`, which is the
*unsaved draft* value. `tasks.md` explicitly says "Do not launch permission settings for an
unsaved draft toggle." Gate on the saved value instead.

**b. Enabling the setting silently requires a server restart.** `settingEnabled` is a snapshot
taken in `ServerService.onCreate()` (`initializeFloatingMonitorBestEffort`). Hot-apply is a
declared non-goal and that trade-off is fine — but the UI says nothing, so a user who enables and
saves sees no overlay and concludes it is broken. Add a hint string.

**c. `tapSuppressed` can latch permanently.** `FloatingMonitorController.kt:127` fakes
foreground-ness (`appIsForeground = input.appIsForeground || tapSuppressed`) to hide the overlay
after a tap, and clears it only when a later render observes `appIsForeground == true`
(`:111`). If `openMainActivity()` returns true but the Activity never actually starts (background
launch blocked by the system), the monitor stays hidden until the user opens the app manually.
Also, encoding "was just tapped" as "app is in foreground" makes the visibility predicate hard to
read. Prefer an explicit suppression input.

**d. 1 Hz wakeup while idle.** The render loop is `while (isActive) { delay(1000); render() }`,
so a backgrounded, *idle* `Running` overlay wakes the main thread and calls
`Settings.canDrawOverlays` every second forever. `FloatingMonitorRenderGate` suppresses the actual
redraw, so cost is low but not zero. The spec asked to *coalesce* redraws to at most 1/s; the
implementation *polls* at 1/s. Event-driven with a 1 s debounce matches the spec and the battery
profile better.

The hidden-state ticker **does** stop correctly
(`shouldContinueFloatingMonitorReconciliation` returns false) — that part is right.

---

## 2. Inference lifecycle — where the complexity actually comes from

This section answers: *is this the SDK's fault or the server design's fault, and should the fix
follow the existing shape or replace it?*

The answer splits. The **shape** is forced by the SDK and must be kept. The **state
representation** is the server's own accidental complexity and must be replaced.

### 2.1 What the LiteRT-LM SDK forces (not open to redesign)

**No per-request handle.** `conversation.sendMessageAsync(...)`
(`ServerLlmModelHelper.kt:664`, `:730`) returns nothing — no future, no token, no request id.
Completion is observable only via `MessageCallback.onDone()` on an SDK-owned thread.

**Cancellation is Conversation-scoped.** `conversation.cancelProcess()`
(`ServerLlmModelHelper.kt:627`) cancels whatever that Conversation is doing. There is no
"cancel request X" primitive.

**A cancelled inference reports as a successful completion.** In both callback paths,
`onError(CancellationException)` is translated into `resultListener("", true, null)`
(`ServerLlmModelHelper.kt:679-681`, `:745-747`) — byte-identical to a normal `onDone()`. Native
callbacks therefore **cannot** tell the server that a request was cancelled.

**Conversation state is undefined after an error.** Recorded in the adapter itself
(`ServerLlmModelHelper.kt:675-678`), which drops the cached-turn state because the session may
be corrupt.

Three consequences that are **not** choices:

- Generations must be serialized per Conversation. A single-thread executor
  (`ServerService.kt:419`) plus a lock held for the whole generation is *forced*, not a style.
- A callback-to-blocking bridge (latch or equivalent) is *forced* by the handle-less async API.
- Because native cancel is Conversation-scoped **and** cancellation is invisible at the callback
  boundary, the server must own request identity and terminal outcome itself. **There is no
  design in which this ownership layer disappears.**

### 2.2 What is the server's own accidental complexity

One request's progress is represented in **eight** parallel places with no owner and no
invariant tying them together:

| # | Representation | Location |
|---|---|---|
| 1 | `errorOccurred` local var (streaming) | `InferenceGateway.kt:78` |
| 2 | `error: AtomicReference<String?>` | `InferenceGateway.kt:145` |
| 3 | `inferenceLatch` | `InferenceGateway.kt:142` |
| 4 | `lifecycleLatch` | `InferenceGateway.kt:143` |
| 5 | `earlyUnblock` / `lifecycleLatchRef` back door | `InferenceGateway.kt:138`, `InferenceRunner.kt:185`, `:256` |
| 6 | `inferenceActuallyStarted: AtomicBoolean` | `InferenceRunner.kt:178` |
| 7 | `userCancelFlag: AtomicBoolean` | `InferenceRunner.kt:177` |
| 8 | `ServerMetrics._inferringCount` | `ServerMetrics.kt:480` |

Five cancellation sources — request timeout, caller/Ktor cancellation, Logs/UI stop, SSE
disconnect, native error — each touch a **different subset** of those eight.

Three shipped defects follow directly:

**`resetConversation` means both "prepare" and "recover".** It is invoked as preparation
(`InferenceGateway.kt:102`) and again as timeout recovery (`:175`), but the lambda behind it
(`InferenceRunner.kt:206-230`) is a full preparation step. On timeout the whole preparation
re-runs:

- `ServerMetrics.onInferenceStarted()` (`InferenceRunner.kt:212`) fires twice against a single
  `onInferenceCompleted()` (`:252`). `_inferringCount` goes `+2 / −1` and sticks at 1 forever.
  Downstream: keep-alive never unloads, the floating monitor stays on Processing, and
  `rejectWhenBusy` rejects every subsequent request.
- `originalConfig = model.configValues` (`:215`) is re-captured *after* the request's snapshot was
  applied, so that request's sampler config becomes the permanent baseline for all later requests.

**A timeout on a timeout.** `lifecycleLatch.await(timeoutSeconds + 5)`
(`InferenceGateway.kt:196`) exists only because there is no "settled" state to wait on. The `+ 5`
is a guess standing in for an invariant.

**A back door around the caller.** `earlyUnblock` hands the lifecycle latch to
`RequestLogStore.registerCancellation`, which counts it down directly (`InferenceRunner.kt:185`),
letting the caller return while the executor still holds the lock and touches native state.

Two more real defects, both server-side:

**Queued cancellation kills someone else's inference.** `InferenceGateway.kt:205-207` catches
`CancellationException` on the *caller* coroutine and calls `cancelInference()` =
`ServerLlmModelHelper.stopResponse(model)` — a Conversation-level cancel. With a single-thread
executor, a queued request B whose client disconnects will cancel running request A.
`RequestLogStore.registerCancellation` guards this with `inferenceActuallyStarted`
(`InferenceRunner.kt:184`); **the caller path has no such guard.**

**Idle unload can cross an admitted request.** `ModelLifecycle.kt:130` guards only on
`ServerMetrics.isInferring.value`, but `isInferring` is not set until `prepare()` — i.e. after the
request already holds `inferenceLock`. Across the whole window from HTTP POST → `selectModel()` →
enqueue → executor start, `isInferring` is false, so a keep-alive timeout will null `defaultModel`
(`:142`) and `safeCleanup()` an Engine that a live request already selected. Additionally
`cancelKeepAliveTimer()` uses `handler.removeCallbacks`, which cannot stop a callback already
dispatched into `lifecycleScope.launch` — the `keepAliveGeneration` counter on the branch covers
that half only.

**Conclusion: none of the shipped defects are SDK limitations. All are "no explicit request
state."**

---

## 3. Decision: keep the forced shape, replace the state representation

This is what the branch's `NativeOperation` + `InferenceExecution` already does, and it is the
right response. The correct answer to "N uncoordinated flags touched by 5 cancellation sources"
is exactly one explicit state machine. Replacing `earlyUnblock` with `onCancellationReady`
routed through `execution.cancel()` was part of the same correction.

The branch **preserves** exactly the parts the SDK forces (single-thread executor,
whole-generation `inferenceLock`, latch bridge, `runInference()` untouched) and **replaces**
exactly the accidental part. That boundary is correct — do not move it.

### Rejected: rewrite onto coroutines / `suspendCancellableCoroutine`

Removes none of the three real constraints. Callbacks still arrive on SDK threads;
`cancelProcess()` is still Conversation-scoped; cancellation still masquerades as completion. A
continuation replaces the latch and nothing else, while introducing a second cancellation
semantics that must be reconciled with the whole-generation lock. Negative net value — and
precisely the class of rewrite that caused the first attempt to spiral.

### Rejected: multi-threaded executor or concurrent requests

`Conversation` holds mutable history. Concurrency requires one Conversation per in-flight
request: duplicated model memory plus a full re-prefill per request. Different project.

### Rejected: fixing this inside `ServerLlmModelHelper.runInference()`

It is a stateless adapter over `sendMessageAsync` with no lifecycle state of its own. Moving
request identity into it would push ownership into the SDK adapter layer — the wrong place. The
existing guardrail against modifying it is correct and should stay.

---

## 4. Gaps not yet captured in the spec or tasks

### 4.1 The state machine is not yet the single source of truth (highest leverage)

The flag soup still survives *inside* the new design:

- `ExecutionPhase` and `error: AtomicReference<String?>` are two parallel representations of the
  terminal outcome. `error.compareAndSet` is called from four sites; whichever writer wins names
  the outcome, independently of the phase the machine is actually in.
- `inferenceLatch` and `lifecycleLatch` are a third and fourth representation of progress. Given
  a real phase machine, `lifecycleLatch` is precisely "phase == FINISHED".

**Requirement to add:** the execution phase SHALL be the sole authority for a request's terminal
outcome. The error string and the lifecycle latch SHALL be derived from, or owned by, phase
transitions — not set independently by whoever reaches the code first.

### 4.2 Lock ordering is unrecorded

`ModelLifecycle.kt:107` documents two levels. The branch adds a third; task group 1 adds a
fourth participant. Record the full order and update that comment:

1. `keepAliveLock` — model lifecycle transitions (outermost)
2. `inferenceLock` — whole-generation serialization
3. `InferenceExecution.stateLock` — phase transitions and the native dispatch/cancel commit
   boundary (innermost)

The admission lease is a **counter, not a lock**, and adds no level. It SHALL be read inside
`keepAliveLock`, in the same critical section as the `defaultModel = null` write
(`ModelLifecycle.kt:129-142`). Checking it anywhere else moves the race instead of closing it.

Invariant to record: `InferenceExecution.cancel()` invokes `cancelNative()` **while holding**
`stateLock`. This is acyclic only while `ServerLlmModelHelper.stopResponse()` acquires no lock at
level 1 or 2. Any future change to `stopResponse()` must preserve that.

### 4.3 `rejectWhenBusy` has no owner after the split

Two sites read `ServerMetrics.isInferring.value`: `KtorServer.kt:521` and `:680`. Once admission
is separated from `isInferring`, "busy" changes meaning — an admitted request that has not yet
reached `prepare()` no longer counts. The spec correctly says metrics must not be the admission
lock, but never says which signal `rejectWhenBusy` reads afterwards. Unresolved, this ships a
user-visible behavior change inside a fix that claims a narrow compatibility boundary.

### 4.4 Task group 2 targets one `NonCancellable`; there are two

- `KtorServer.kt:297` — SSE, wrapping `withTimeout(resp.outerTimeoutMs)`.
- `KtorServer.kt:535` — audio transcription, same "shield from cancelCallOnClose" rationale,
  **not mentioned in the plan at all**.

Removing the SSE wrapper also changes how the outer timeout races the inner streaming timeout:
the existing comment's guarantee ("only fires if inner cleanup hangs") inverts once cancellation
propagates. Either bring the audio path into scope or state that it is deliberately excluded.

### 4.5 Invariants worth writing next to the guardrails

- Native ownership depends on the single-thread executor plus whole-generation `inferenceLock`.
  **Do not parallelize inference** without redesigning ownership first — per-request
  `InferenceExecution` alone does not provide that protection.
- Native callbacks cannot report cancellation (`ServerLlmModelHelper.kt:679`, `:745`). Only the
  execution phase may classify a request as cancelled.

---

## 5. Blocking decisions needed from the maintainer

Neither is a technical question; both block a task group.

1. **After admission is separated, which signal does `rejectWhenBusy` read** — the admission
   count ("accepted"), or `isInferring` ("actually generating")? Admission is stricter than
   today's behavior; `isInferring` preserves today's behavior but leaves the admission window
   uncovered. *Blocks task group 1.*
2. **Is the audio-transcription `NonCancellable` (`KtorServer.kt:535`) in scope?**
   *Blocks task group 2.*

A third, product-level: **does the endpoint-selector removal ship together with the floating
monitor, or separately?** This determines whether the monitor can be unblocked now (§7).

---

## 6. Continuation order — differs from the current tasks.md

### Step 0 (do first): run a real-device check on the current HEAD

`168c293 fix: separate request preparation from timeout recovery` has already landed. That commit
removes the double `onInferenceStarted()`, which means **the primary shipped symptom (stuck
`requests_processing`) may already be fixed at `69f68be`.**

Device verification is a human gate anyway (Flyme rejects ADB install). Running it now costs the
same as running it after four more groups, but the information is completely different:

- If Gemma-4-E4B-it times out, `requests_processing` returns to 0, monitor returns to `RUNNING`,
  and a subsequent short request returns 200 — **the direction is empirically confirmed** and the
  remaining four groups drop from "fixing a bug" to "hardening".
- If it does not — the diagnosis is incomplete and everything downstream is built on sand.
  **That must be known now, not four groups later.**

CI is already green at `0dc2996`; the signed-APK path works. No code is written in this step.

### ⚠️ The current tasks.md orders groups 3 and 4 dangerously

Group 3 removes the upper bound on `lifecycleLatch.await(timeoutSeconds + 5)`. Group 4 is what
proves every exception path reaches exactly one terminal state. **Removing the bound before
proving reachability converts a 5-second overshoot into a permanent hang.** The ugly `+ 5` is
currently the only backstop. Group 4 must land first.

### Recommended order

| Step | Work | Why here |
|---|---|---|
| 1 | **Single-source-of-truth refactor** (§4.1): fold `error` + both latches into `ExecutionPhase` | Pure refactor, no behavior change, with the existing 286-line `InferenceGatewayTest` as the net. Groups 3 and 4 are not provable until this lands |
| 2 | **Group 4** — deterministic exception paths | Only provable after step 1. **Must precede group 3** |
| 3 | **Group 3** — remove the `+ 5` early return | Safe only once group 4 guarantees FINISHED is always reached |
| 4 | **Group 1** — model admission lease | Independent. Record the lock ordering (§4.2) *before* writing code |
| 5 | **Real-device closure #2** | Both shipped symptoms (stuck counter, unload race) are now testable |
| 6 | **Group 2** — SSE `NonCancellable` | Highest risk, deepest coupling to the outer timeout. Last, so it can be reverted alone |
| 7 | Final verification per existing tasks.md | `gm-code-review` dual-axis, full CI, APK, smoke tests |

Two device gates total (steps 0 and 5) — the minimum human-in-the-loop count.

---

## 7. Unblocking the floating monitor in parallel

The monitor is complete and green but currently sits beneath seven remaining steps of lifecycle
work. It should not wait.

It shares a baseline with the endpoint-selector removal, so decision 3 in §5 gates this. If they
ship together, `refactor/remove-endpoint-selector` can be finished now — backfill the spec (§1.1),
check off tasks (§1.2), fix the four implementation issues (§1.3) — and merged. The lifecycle
branch then rebases onto the new `main` and continues.

---

## 8. Working rules to avoid a second spiral

The previous spiral had a specific mechanism: no explicit state → each fix adds a flag → each
flag adds a race → more fixes. §2.2 names the root cause; these rules keep it from recurring.

- **One task group per worktree, per fresh session.** Start by reading this document plus the one
  relevant spec requirement. Do not have a model read all 1815 lines of `InferenceRunner.kt` and
  improvise.
- **The RED test must actually fail on current HEAD, for the stated reason.** If you cannot write
  such a test, the group is misspecified — **stop and fix the description, do not start
  implementing.** This is the single most effective gate.
- **Spiral tripwire: the group adds a new `AtomicBoolean` / `AtomicReference` / latch to the
  request path.** Stop. After step 1, all request state belongs to the phase; a new parallel flag
  is the relapse signature.
- **Update `tasks.md` checkboxes at the end of every group.** The floating-monitor change has all
  seven groups unchecked with the code fully written — the next cold session cannot tell what is
  done. That failure is more expensive here.
- **Treat the existing guardrail as a real gate, not decoration:** stop and reassess if lifecycle
  callbacks must cross three or more layers, or if native ownership can no longer live in the
  Gateway.

---

## 9. What this session did not do

- No code was changed. No test was run. No APK was built.
- Nothing was verified on a real device — every runtime claim above is read from source.
- The floating-monitor spec was **not** backfilled and its tasks were **not** checked off.
- Nothing was merged, rebased, or cherry-picked between branches.
