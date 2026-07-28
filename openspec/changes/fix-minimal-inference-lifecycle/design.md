# Design: Where the Inference Lifecycle Complexity Comes From

## Question this document answers

The inference lifecycle work grew complex enough to be abandoned once and restarted.
Before continuing, two questions need a recorded answer:

1. Is the complexity caused by the LiteRT-LM SDK, or by the existing server-side design?
2. Should the change follow the existing shape, or replace it?

The answer is split: the *shape* is forced by the SDK and must be kept. The *state
representation* is the existing design's own accidental complexity and must be replaced.
The current branch already does the second thing, but has not finished it.

## Part 1: What the SDK forces (not a design choice)

Four properties of `ServerLlmModelHelper` / the LiteRT-LM `Conversation` API are fixed
constraints. No server-side architecture removes them.

**No per-request handle.** `conversation.sendMessageAsync(...)`
(`ServerLlmModelHelper.kt:664`, `:730`) returns nothing. There is no future, no token, no
request id. Completion is observable only through `MessageCallback.onDone()`, invoked on an
SDK-owned thread.

**Cancellation is Conversation-scoped, not request-scoped.**
`conversation.cancelProcess()` (`ServerLlmModelHelper.kt:627`) cancels whatever that
Conversation is currently doing. There is no "cancel request X" primitive.

**A cancelled inference reports as a successful completion.** In both callback paths,
`onError(CancellationException)` is translated to `resultListener("", true, null)`
(`ServerLlmModelHelper.kt:679-681`, `:745-747`) — the same signal as a normal `onDone()`.
Native callbacks therefore *cannot* tell the server that a request was cancelled.

**Conversation state is undefined after an error.** Recorded in the SDK adapter itself
(`ServerLlmModelHelper.kt:675-678`): the cached-turn state is dropped on error because the
session may be corrupt.

Three consequences follow that are not open to redesign:

- Generations must be serialized per Conversation. A single-thread executor plus a lock held
  for the whole generation is *forced*, not a stylistic preference.
- A callback-to-blocking bridge (a latch or equivalent) is *forced* by the handle-less async
  API.
- Because native cancel is Conversation-scoped **and** cancellation is indistinguishable from
  completion at the callback boundary, the server must own request identity and terminal
  outcome itself. There is no design in which this ownership layer disappears.

Conclusion for Part 1: the existing shape — serialize, bridge, own identity server-side — is
correct and must be preserved.

## Part 2: What is the existing design's own accidental complexity

One request's progress is currently represented in eight parallel places, with no owner and
no invariant tying them together:

| # | Representation | Location |
|---|---|---|
| 1 | `errorOccurred` local var (streaming) | `InferenceGateway.kt:78` |
| 2 | `error: AtomicReference<String?>` | `InferenceGateway.kt:145` |
| 3 | `inferenceLatch` | `InferenceGateway.kt:142` |
| 4 | `lifecycleLatch` | `InferenceGateway.kt:143` |
| 5 | `earlyUnblock` / `lifecycleLatchRef` back door | `InferenceGateway.kt:138`, `InferenceRunner.kt:185`, `:256` |
| 6 | `inferenceActuallyStarted: AtomicBoolean` | `InferenceRunner.kt` |
| 7 | `userCancelFlag: AtomicBoolean` | `InferenceRunner.kt` |
| 8 | `ServerMetrics._inferringCount` | `ServerMetrics.kt:480` |

Five cancellation sources — request timeout, caller/Ktor cancellation, Logs/UI stop, SSE
disconnect, native error — each touch a *different subset* of those eight. That is the actual
source of the complexity, and it is entirely server-side.

Three shipped defects follow directly from it:

**`resetConversation` means both "prepare" and "recover".** It is invoked as preparation
(`InferenceGateway.kt:102`) and again as timeout recovery (`:175`), but the lambda behind it
(`InferenceRunner.kt:206-230`) is a full preparation step. On timeout the whole preparation
re-runs:

- `ServerMetrics.onInferenceStarted()` (`InferenceRunner.kt:212`) fires twice against a single
  `onInferenceCompleted()` (`:252`). `_inferringCount` goes `+2 / -1` and sticks at 1 forever.
  Downstream: keep-alive never unloads, the floating monitor stays on Processing, and
  `rejectWhenBusy` rejects every subsequent request.
- `originalConfig = model.configValues` (`:215`) is re-captured *after* the request's snapshot
  was applied, so the request's sampler config becomes the permanent baseline for all later
  requests.

**A timeout on a timeout.** `lifecycleLatch.await(timeoutSeconds + 5)`
(`InferenceGateway.kt:196`) exists only because there is no "settled" state to wait on. The
`+ 5` is a guess standing in for an invariant.

**A back door around the caller.** `earlyUnblock` hands the lifecycle latch to
`RequestLogStore.registerCancellation`, which counts it down directly (`InferenceRunner.kt:185`),
letting the caller return while the executor is still holding the lock and touching native
state.

Conclusion for Part 2: none of the shipped defects are SDK limitations. All of them are
"no explicit request state".

## Decision

Keep the shape the SDK forces. Replace the state representation with one owner.

This is what the branch's `NativeOperation` + `InferenceExecution` already does, and it is the
right response — the correct answer to "N uncoordinated flags touched by 5 cancellation
sources" is exactly one explicit state machine. Replacing `earlyUnblock` with
`onCancellationReady` routed through `execution.cancel()` was part of the same correction.

### Rejected: rewrite onto coroutines / `suspendCancellableCoroutine`

Looks cleaner, removes none of the three real constraints. Callbacks still arrive on SDK
threads; `cancelProcess()` is still Conversation-scoped; cancellation still masquerades as
completion. A continuation replaces the latch and nothing else, while introducing a second
cancellation semantics that must be reconciled with the whole-generation lock. Negative net
value, and precisely the kind of rewrite that caused the first attempt to spiral.

### Rejected: multi-threaded executor or concurrent requests

`Conversation` holds mutable history. Concurrency requires one Conversation per in-flight
request, which means duplicated model memory and a full re-prefill per request. That is a
different project, not a lifecycle fix.

### Rejected: fixing this inside `ServerLlmModelHelper.runInference()`

It is a stateless adapter over `sendMessageAsync` with no lifecycle state of its own. Moving
request identity into it would push ownership into the SDK adapter layer, which is the wrong
place — and would make the "cancellation is invisible to native callbacks" constraint harder
to work around, not easier. The existing guardrail against modifying it is correct.

## Remaining leak: the state machine is not yet the single source of truth

This is the one place where the flag soup still survives inside the new design, and it should
be closed before task group 4.

- `ExecutionPhase` and `error: AtomicReference<String?>` are two parallel representations of
  the terminal outcome. `error.compareAndSet` is called from four sites; whichever writer wins
  names the outcome, independently of the phase the machine is actually in.
- `inferenceLatch` and `lifecycleLatch` are a third and fourth representation of progress.
  Given a real phase machine, `lifecycleLatch` is precisely "phase == FINISHED".

**Requirement to add:** the execution phase SHALL be the sole authority for a request's
terminal outcome. The error string and the lifecycle latch SHALL be derived from, or owned by,
the phase transition — not set independently by whoever reaches the code first.

Sequencing note: land this *before* task group 4. Group 4 asserts that every exception path
reaches exactly one terminal state, which is not provable while four independent
representations of "terminal" exist.

## Lock ordering (currently unrecorded)

`ModelLifecycle.kt:107` documents two levels. The branch adds a third and task group 1 adds a
fourth. Record the full order and update that comment:

1. `keepAliveLock` — model lifecycle transitions (outermost)
2. `inferenceLock` — whole-generation serialization
3. `InferenceExecution.stateLock` — phase transitions and the native dispatch/cancel commit
   boundary (innermost)

The admission lease is a counter, not a lock, and adds no level. It SHALL be read inside
`keepAliveLock`, in the same critical section as the `defaultModel = null` write
(`ModelLifecycle.kt:129-146`) — checking it anywhere else moves the race instead of closing it.

Invariant to record: `InferenceExecution.cancel()` invokes `cancelNative()` while holding
`stateLock`. This is acyclic only while `ServerLlmModelHelper.stopResponse()` acquires no lock
at level 1 or 2. Any future change to `stopResponse()` must preserve that.

## Invariants to record alongside the guardrails

- Native ownership depends on the single-thread executor plus the whole-generation
  `inferenceLock`. Do not parallelize inference without redesigning ownership first.
- Native callbacks cannot report cancellation (`ServerLlmModelHelper.kt:679`, `:745`). Only the
  execution phase may classify a request as cancelled.
- `rejectWhenBusy` currently reads `ServerMetrics.isInferring.value` (`KtorServer.kt:525`, and
  the audio path). Once admission is separated from `isInferring`, "busy" changes meaning: an
  admitted request that has not yet reached `prepare()` no longer counts. The signal
  `rejectWhenBusy` reads after the split must be named explicitly, or this lifecycle fix ships
  a user-visible behavior regression while claiming a narrow compatibility boundary.

## Sequencing adjustments

- Task groups 1 and 3 together fix the shipped symptom (stuck `requests_processing`). Cut a
  real-device closure there, while the branch is still small, instead of gating the only device
  check behind all four groups.
- Task group 2 must account for two `NonCancellable` wrappers, not one: `KtorServer.kt:297`
  (SSE, wrapping `withTimeout(resp.outerTimeoutMs)`) and `KtorServer.kt:535` (audio
  transcription, same "shield from cancelCallOnClose" rationale). Removing the SSE wrapper also
  changes how the outer timeout races the inner streaming timeout — the existing comment's
  guarantee ("only fires if inner cleanup hangs") inverts once cancellation propagates. Either
  bring the audio path into scope or state that it is deliberately excluded.
- Task group 4 last, after the single-source-of-truth requirement above.
