# Handoff: Floating Monitor and Minimal Inference Lifecycle

**Updated:** 2026-08-02

**Working branch:** `fix/minimal-inference-lifecycle`

**Fixed baseline:** `8ab20cb0ac43a825465752694b59f0e3907ac3e3`

**Latest code HEAD before this handoff update:** `93486e9c1cfecd5971fb7865be9da90a9e49662d`

**Latest code CI evidence:** GitHub Actions `30478480764` passed stableDebug compilation, JVM tests, and Android lint at code commit `93486e9c`.

This document is the cold-session entry point. Stable user preferences and project guardrails live in [`openspec/USER_PREFERENCES.md`](USER_PREFERENCES.md). Stable behavior requirements live in the two OpenSpec change specs; executable sequencing and checkboxes live in their `tasks.md`. Do not treat this handoff as proof of runtime acceptance.

## 1. Branch topology

```text
main (f4f7bf9)
  └─ refactor/remove-endpoint-selector (8ab20cb)
       ├─ completed floating-monitor implementation
       └─ fix/minimal-inference-lifecycle
            ├─ lifecycle code through 0dc2996c
            ├─ floating-monitor saved-intent, restart-copy, and bounded tap-recovery cleanup through 93486e9c
            ├─ tracked lifecycle documents through 79a84d53
            ├─ active/inactive Floating monitor visual Deltas through d3b835f9
            ├─ obsolete event-driven-idle RED 783aac1f reverted by 46322dfa
            └─ approved static Floating monitor implementation through 502d01a0
```

The endpoint-selector removal is deliberate. It is not a stray revert. The monitor and lifecycle work currently inherit that simplified baseline.

A separate review-only branch, `claude/widget-feature-review-mfao9u` at `5eb1b747`, produced the source review that informed this handoff. It changed no production code and performed no CI, APK, or device verification.

## 2. Why a request owner is required

LiteRT-LM exposes a handle-less asynchronous Conversation API:

- `sendMessageAsync()` returns no request handle or future;
- `cancelProcess()` is Conversation-scoped, not request-scoped;
- cancellation may arrive through the same callback shape as normal completion;
- Conversation state may require recovery after error or cancellation.

Therefore the server must retain:

- serialized generation per Conversation;
- a callback-to-wait notification bridge;
- explicit request identity and terminal outcome;
- one owner for dispatch, cancellation, recovery, and finish.

Do not rewrite this onto coroutines merely to replace a latch. That does not add a native request handle or request-scoped cancellation.

## 3. Implemented lifecycle behavior

The current branch has deterministic JVM coverage and CI GREEN for:

- Gateway as per-request native execution owner;
- queued/preparing cancellation without shared native cancel or ghost dispatch;
- preparation exactly once for blocking and streaming;
- caller cancellation wake followed by recovery/finish;
- blocking and streaming external cancellation routed through the owner;
- SSE disconnect and stop-sequence owner routing;
- native cancellation exactly once for an owned dispatched request;
- recovery before operation finish and metrics completion;
- incremental cache metadata only for completed, non-stop-sequence results;
- keep-alive generation invalidation for stale timeout callbacks.

Protected boundaries remain unchanged: LiteRT Engine/Conversation wrappers, `ServerLlmModelHelper.runInference()`, normal prompt/sampler/token/thinking/tool-call behavior, payloads, SSE wire format, notification behavior, and floating-monitor visuals.

## 4. Remaining lifecycle findings and decisions

### Terminal state authority

Execution phase plus a phase-owned outcome must become the sole authority for success, timeout, cancellation, disconnect, stop sequence, and error. Native-completion and lifecycle-finished signals may remain only as owner-driven notifications required by the SDK callback bridge. Do not add another flag, atomic reference, or latch.

### Exception reachability before unbounded settlement

Preparation, synchronous dispatch, cancellation, recovery, and finish failures must each reach exactly one terminal state. This evidence must land before removing the current `timeoutSeconds + 5` lifecycle backstop; otherwise an exception bug can become a permanent caller hang. If recovery or finish itself fails, the lane must fail closed: later requests receive deterministic unavailable/error behavior and cannot prepare against the uncertain Engine/Conversation until safe recovery or replacement/quarantine completes.

### Model request admission

Generation invalidation is necessary but insufficient. `ModelLifecycle` needs a small active-admission lease acquired/try-acquired under `keepAliveLock` before model selection. The lease remains held through native owner settlement, request/native restoration, success-qualified cache publication, and every remaining path that can touch model/native state. It releases exactly once after leaving inner lifecycle locks and before ordinary response encoding, SSE channel close/drain, or slow network delivery.

`rejectWhenBusy` uses atomic admission semantics:

- enabled: while a barrier-controlled earlier admission remains held, a second try-acquire is rejected;
- disabled: acquire admission and permit serialized queueing.

Metrics remain observability and never own model lifetime.

Lock order:

```text
keepAliveLock
  → whole-generation inferenceLock
    → InferenceExecution state monitor
```

Admission acquire/try-acquire and unload's zero-admission check share `keepAliveLock`. Admission-first aborts unload; unload-first detaches the model before the request can select it. Lease release occurs after inner locks are left, then takes `keepAliveLock` to decrement ownership and begin a fresh idle period. The admission count is not an additional lock.

### SSE caller cancellation

The full SSE writer must not be wrapped in `NonCancellable`. Parent Ktor cancellation must reach the execution owner. Non-cancellable context is permitted only for bounded cleanup that signals cancellation and awaits owner settlement.

The separate audio-transcription `NonCancellable` path is explicitly excluded. Revisit it only after a focused audio cancellation RED.

## 5. Safe continuation order

Follow `openspec/changes/fix-minimal-inference-lifecycle/tasks.md` and `serialized-inference-lane-delta.md`. The current source/JVM/CI-first order is:

```text
phase-owned terminal outcome
→ deterministic exception paths
→ remove timeout + 5 early return
→ complete ModelRequestLease and rejectWhenBusy admission
→ SSE parent cancellation
→ final dual-axis review and CI
→ deferred signed APK and device gates only when separately authorized
```

The device gates remain diagnostic and are not waived as final acceptance. They are deferred by the current instruction to avoid model loading, inference requests, phone stress, and timeout tests. Commit `168c293` may already have removed the primary stuck `requests_processing` symptom by separating preparation from recovery; that remains unverified at runtime.

## 6. Device acceptance contract

Final acceptance requires an installed, persistently signed APK and Gemma-4-E4B-it evidence:

```text
long request timeout
→ native generation stops
→ request config and Conversation recover
→ requests_processing = 0
→ monitor returns to RUNNING
→ subsequent short request returns HTTP 200 without force-stop
```

Also smoke queued cancellation, streaming disconnect, stop sequence, cache fallback, and idle unload against an admitted request.

Compilation, tests, lint, and an installable APK are necessary but are not runtime acceptance. Flyme may reject ADB installation, so the user may need to install through the phone installer.

## 7. Floating monitor status

The monitor baseline implementation was CI-green through production code commit `93486e9c`. The approved next visual contract is `openspec/changes/add-background-floating-monitor/floating-monitor-visual-delta.md`:

- point-top hexagon at 88 × 100dp;
- RUNNING fill `#4ADE80` and PROCESSING fill `#AFC6FF`, each at 80% fill alpha with an opaque same-hue 2dp edge;
- grouped request/error counts retained, with narrower proportional comma punctuation;
- RUNNING information layout unchanged (`req` above centered `err`);
- PROCESSING lower area split into current `proc` on the left and previous successful `last` on the right;
- fixed 20sp primary values, 16sp processing/last values, and 10sp labels/units;
- `last` at 85% black, exact milliseconds below 10 seconds, then deterministic one-decimal seconds and a bounded cap;
- no breathing, carousel, average latency, Logo, or new timer;
- existing visible one-second metric/elapsed/permission/retry/WindowManager reconciliation cadence retained.

The alternative breathing/carousel design is preserved in `floating-monitor-breathing-carousel-delta.md` and explicitly marked INACTIVE.

The saved-intent cleanup is complete: an unsaved Floating monitor draft no longer exposes the permission-required row or Grant action. RED commit `9290150b` failed in JVM-test compilation on the missing gate, and GREEN commit `9372ebc1` passed compile, JVM tests, and lint in Actions run `30472036614`.

The restart-required affordance is complete: the Floating monitor description now says to restart the server after saving. RED commit `92e8629e` failed only the new resource-contract test, and GREEN commit `007ecf90` passed compile, 1,355 JVM tests, and lint in Actions run `30475002423`. Hot application to an already-running `ServerService` remains deliberately out of scope.

Bounded tap recovery is complete: a reported-successful Activity launch suppresses the detached monitor for at most three seconds; total launch failure and confirmed foreground transition release suppression immediately; disposal cancels pending recovery. Detach-before-launch and the PendingIntent/direct fallback are preserved. Integration RED `75e69f15` failed on the missing coordinator in Actions `30477061732`; GREEN `93486e9c` passed compile/JVM/lint in Actions `30478480764`. Focused follow-up review closed the earlier StateFlow-conflation and insufficient-integration-evidence findings with no new blocker.

Device evidence on 2026-07-30: persistently signed `0.9.6-dev.106` (`versionCode=18`) was installed in place over dev.105 with matching v2 signer certificate and preserved overlay permission. The user observed a real Gemma-4-E4B-it request complete normally: PROCESSING showed the orange timer, completion returned to green RUNNING, and a subsequent request switched back to PROCESSING. This is a valid request/monitor smoke, not the still-paused lifecycle timeout Gate 0.

The repeated `Sampler params may be ignored on GPU backend` warning is not new to dev.106: both dev.105's baseline and dev.106 contain commit `aeb33721`. It is emitted by OlliteRT's OpenAI-compatible endpoint layer per request, while an upstream native direct-inference UI does not traverse that logging path. Its trigger also counts `max_tokens`, so common clients can produce an over-broad warning on every request. Any correction should be a separate focused change (exclude non-sampler length limits and deduplicate), not part of floating-monitor cleanup.

The obsolete event-driven-idle RED was reverted after the cadence decision changed. The approved static visual delta is now implemented through `502d01a0`; its formatter/latch, controller/render-model, and Canvas/geometry slices each completed RED → GREEN, exact-HEAD compile/JVM/lint, and focused source review with no blocking findings. Existing one-second reconciliation remains unchanged and no second service was introduced. The complete device acceptance checklist remains deferred rather than waived.

## 8. Working rules

- One remaining task group per focused RED → minimal GREEN cycle.
- A RED must fail on the current code for the stated reason before implementation.
- Stop if a change adds another request-state flag/atomic/latch or pushes callbacks through three or more layers.
- Keep blocking and streaming on one native ownership core.
- Do not parallelize Conversation inference.
- Update the relevant `tasks.md` immediately after each verified group.
- Preserve production failure evidence; retry production tasks by cloning rather than rewriting history.
- Do not modify the frozen experimental worktree.

## 9. Current blockers and next action

- Local Gradle remains unavailable because the host has no configured Java/JDK; code validation uses GitHub Actions.
- The active visual delta is implemented and source/JVM/CI reviewed; its separately authorized real-device acceptance checklist remains deferred.
- Five lifecycle source-hardening groups remain: terminal authority, deterministic exception recovery, grace removal, atomic model admission, and SSE parent cancellation.
- Device Gate 0/Gate 5 and final inference smoke remain deferred and must not be inferred from compile/JVM/lint evidence.

**Next action:** begin lifecycle Group 1, `Make execution state the single terminal authority`, as its own focused RED → minimal GREEN slice. Do not mix deterministic exception recovery, timeout-grace removal, model admission, or SSE parent cancellation into that group. Preserve `litertlm-android:0.11.0` and the protected `ServerLlmModelHelper.kt` boundary.
