# Handoff: Floating Monitor and Minimal Inference Lifecycle

**Updated:** 2026-07-30

**Working branch:** `fix/minimal-inference-lifecycle`

**Fixed baseline:** `8ab20cb0ac43a825465752694b59f0e3907ac3e3`

**Latest code HEAD before this handoff update:** `007ecf90051259e3d57ec0542aa2f501bb500aa9`

**Latest code CI evidence:** GitHub Actions `30475002423` passed stableDebug compilation, JVM tests, and Android lint at code commit `007ecf90`.

This document is the cold-session entry point. Stable user preferences and project guardrails live in [`openspec/USER_PREFERENCES.md`](USER_PREFERENCES.md). Stable behavior requirements live in the two OpenSpec change specs; executable sequencing and checkboxes live in their `tasks.md`. Do not treat this handoff as proof of runtime acceptance.

## 1. Branch topology

```text
main (f4f7bf9)
  └─ refactor/remove-endpoint-selector (8ab20cb)
       ├─ completed floating-monitor implementation
       └─ fix/minimal-inference-lifecycle
            ├─ lifecycle code through 0dc2996c
            ├─ floating-monitor saved-intent and restart-copy cleanup through 007ecf90
            └─ tracked lifecycle documents through 79a84d53
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

Preparation, synchronous dispatch, cancellation, recovery, and finish failures must each reach exactly one terminal state. This evidence must land before removing the current `timeoutSeconds + 5` lifecycle backstop; otherwise an exception bug can become a permanent caller hang.

### Model request admission

Generation invalidation is necessary but insufficient. `ModelLifecycle` needs a small active-admission counter acquired before model selection and released after the full blocking response or SSE writer settles.

`rejectWhenBusy` uses atomic admission semantics:

- enabled: accept only when no earlier request owns admission;
- disabled: acquire admission and permit serialized queueing.

Metrics remain observability and never own model lifetime.

Lock order:

```text
keepAliveLock
  → whole-generation inferenceLock
    → InferenceExecution state monitor
```

The admission counter is not a lock. Keep-alive checks it under `keepAliveLock` in the same critical section that clears and cleans up the model.

### SSE caller cancellation

The full SSE writer must not be wrapped in `NonCancellable`. Parent Ktor cancellation must reach the execution owner. Non-cancellable context is permitted only for bounded cleanup that signals cancellation and awaits owner settlement.

The separate audio-transcription `NonCancellable` path is explicitly excluded. Revisit it only after a focused audio cancellation RED.

## 5. Safe continuation order

Follow `openspec/changes/fix-minimal-inference-lifecycle/tasks.md`. The required order is:

```text
Gate 0: signed APK and current-HEAD Gemma timeout check
→ phase-owned terminal outcome
→ deterministic exception paths
→ remove timeout + 5 early return
→ complete ModelRequestLease and rejectWhenBusy admission
→ Gate 5: second Gemma/admission device check
→ SSE parent cancellation
→ final dual-axis review, CI, signed APK, and smoke
```

The first device gate is diagnostic, not final acceptance. Commit `168c293` may already have removed the primary stuck `requests_processing` symptom by separating preparation from recovery. If current HEAD still fails the timeout → zero → RUNNING → next HTTP 200 chain, stop and revise the diagnosis before more hardening.

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

The monitor implementation is functionally complete and was CI-green at baseline `8ab20cb`. Its intended visual contract is now:

- RUNNING full fill `#55D68B`;
- PROCESSING full fill `#FFB74D`;
- pure black `#000000` labels and values;
- exact request/error counts through `99,999`, then `99,999+`;
- current processing elapsed as centered plain seconds `0..9999`, then `9999+`;
- a smaller separate `s` suffix that does not shift the numeric value;
- elapsed resets for every inference sequence;
- original foreground-service notification remains unchanged.

Two bounded review findings remain:

1. tap suppression may remain latched if Android reports launch success but no Activity reaches foreground;
2. idle visible RUNNING currently polls at 1 Hz instead of remaining event-driven.

The saved-intent cleanup is complete: an unsaved Floating monitor draft no longer exposes the permission-required row or Grant action. RED commit `9290150b` failed in JVM-test compilation on the missing gate, and GREEN commit `9372ebc1` passed compile, JVM tests, and lint in Actions run `30472036614`.

The restart-required affordance is complete: the Floating monitor description now says to restart the server after saving. RED commit `92e8629e` failed only the new resource-contract test, and GREEN commit `007ecf90` passed compile, 1,355 JVM tests, and lint in Actions run `30475002423`. Hot application to an already-running `ServerService` remains deliberately out of scope.

These are cleanup tasks in `openspec/changes/add-background-floating-monitor/tasks.md`; they do not justify a redesign or a second service.

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
- No APK containing the lifecycle fixes or latest monitor cleanup has been installed on the device.
- Device currently runs `0.9.6-dev.105` until a later signed APK is explicitly approved and manually installed.

**Next action:** continue the floating-monitor task list with bounded tap-suppression recovery. Lifecycle Gate 0 remains paused until the user returns to inference lifecycle work.
