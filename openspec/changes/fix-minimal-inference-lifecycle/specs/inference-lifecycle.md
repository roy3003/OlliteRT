# Minimal Inference Lifecycle Specification

## Purpose

This change fixes request timeout and cancellation without rewriting normal inference. The HTTP request, native LiteRT Conversation, recovery, metrics, and incremental cache metadata SHALL have one coherent lifecycle. A request SHALL NOT appear finished while its native work or recovery is still active.

### Requirement: Model admission protects selection from idle unload

`ModelLifecycle` SHALL own an explicit request-admission lease independent of `ServerMetrics`. The common inference POST path SHALL acquire the lease before model selection and release it only after the complete blocking response or SSE writer lifecycle has settled.

Keep-alive timeout callbacks SHALL NOT unload or clean up a model while any request admission is active. Cancelling a Handler callback SHALL also invalidate a timeout already dispatched to background execution. Metrics MAY report processing state but SHALL NOT act as the model admission lock.

#### Scenario: Idle timeout races with an admitted request

- GIVEN an idle-unload timeout is pending or already dispatched
- WHEN an inference POST acquires admission and selects the active model
- THEN that timeout SHALL NOT clear or clean up the selected model
- AND unload MAY be reconsidered only after all admitted requests release and a new idle period expires

### Requirement: Gateway is the single native execution owner

Each request SHALL create one `InferenceGateway` execution owner. The owner SHALL serialize request phases and the synchronous native dispatch/cancel commit boundary. Runner, endpoint, Logs UI, SSE writer, metrics, and cache code MAY provide operations or cancellation signals but SHALL NOT independently own native lifecycle state.

The execution SHALL progress through equivalent states for queued, preparing, dispatching/running, cancelling/settling, and finished behavior. The state monitor SHALL be held only around short phase transitions and native dispatch/cancel commit boundaries, not for the full generation duration.

#### Scenario: A request dispatches normally

- GIVEN an admitted request owns a queued execution
- WHEN preparation succeeds and cancellation has not claimed the request
- THEN preparation SHALL run once
- AND native inference SHALL be dispatched once
- AND the request SHALL remain owned until completion or settlement finishes

### Requirement: Pre-dispatch cancellation is request-scoped

Cancellation while a request is queued or preparing SHALL abandon only that request. It SHALL NOT call the shared Conversation-level `cancelProcess()`, SHALL NOT dispatch native inference later, and SHALL NOT disturb another request that currently owns native execution.

Cancellation racing with synchronous dispatch SHALL be linearized by the same execution monitor so the outcome is either cancellation before dispatch or cancellation of the dispatched request, never an unowned intermediate state.

#### Scenario: Queued request is cancelled

- GIVEN request A owns native inference
- AND request B is queued behind it
- WHEN request B is cancelled
- THEN request B SHALL finish without native dispatch
- AND request B SHALL NOT call shared native cancellation
- AND request A SHALL remain unaffected

### Requirement: Running cancellation is exactly once

Timeout, caller cancellation, Logs/UI stop, SSE disconnect or writer failure, and stop-sequence termination SHALL all signal the current request owner. Only an execution that committed native dispatch MAY call the shared native cancellation operation.

A running request SHALL deliver native cancellation at most once. Duplicate or late cancellation after settling or finish SHALL be a no-op and SHALL NOT affect a later request.

#### Scenario: Multiple cancellation sources race

- GIVEN one request has dispatched native inference
- WHEN timeout, client disconnect, and an external stop signal race
- THEN exactly one source SHALL claim native cancellation
- AND native cancellation SHALL target only the currently owned execution
- AND later signals SHALL have no native side effect

### Requirement: Recovery completes before lifecycle release

On timeout, cancellation, native error, or a dispatch failure that may have committed native work, the owner SHALL request native cancellation when applicable and perform request recovery. Recovery SHALL restore per-request configuration and reset or recover Conversation state before lifecycle ownership is released.

The caller SHALL await lifecycle settlement without a secondary `timeout + grace` early-return path. Metrics SHALL remain processing until recovery and operation finish complete. Preparation, dispatch, cancellation, recovery, and finish failures SHALL still leave the execution in one terminal state and SHALL not skip required recovery.

#### Scenario: Blocking request times out

- GIVEN a blocking native inference exceeds its request timeout
- WHEN the Gateway claims timeout cancellation
- THEN native cancellation SHALL be delivered once
- AND request configuration and Conversation recovery SHALL complete
- AND metrics SHALL return to non-processing only after recovery
- AND only then SHALL the caller receive the timeout result

### Requirement: Blocking and streaming share ownership semantics

Blocking and streaming inference SHALL use the same request execution owner and native operation boundaries for prepare, dispatch, cancel, recover, and finish. They SHALL retain their existing output-specific behavior, including token/thinking collection, tool handling, stop-sequence processing, response shaping, and SSE wire format.

The SSE body SHALL remain cancellable by its parent Ktor request. `NonCancellable` MAY be used only for bounded cleanup that signals cancellation and awaits owner settlement; it SHALL NOT wrap the full streaming writer lifecycle.

#### Scenario: Streaming client disconnects

- GIVEN streaming native inference is running
- WHEN the Ktor caller is cancelled or the SSE writer fails
- THEN the request owner SHALL receive the cancellation signal
- AND native cancellation and recovery SHALL follow the same exactly-once ordering as blocking inference
- AND channel/writer cleanup SHALL NOT mark metrics complete before owner settlement

### Requirement: Incremental cache metadata is success-qualified

Incremental Conversation cache metadata SHALL be committed only for an explicitly completed result that did not terminate on a stop sequence. Timeout, cancellation, disconnect, native error, incomplete streaming, and stop-sequence termination SHALL NOT publish new cache metadata.

A queued cancellation that never touched Conversation MAY preserve previously valid metadata. A failed dispatched execution SHALL rely on Conversation recovery and SHALL NOT claim the failed request as a reusable cache state.

#### Scenario: Completion eligibility is evaluated

- GIVEN an inference result is being finalized
- WHEN `completed == true` and `stopSequenceTriggered == false`
- THEN its incremental metadata MAY be committed
- OTHERWISE no new incremental metadata SHALL be committed

### Requirement: Compatibility boundary remains narrow

This change SHALL preserve existing OpenAI/Anthropic payloads, response and SSE formats, prompt construction, sampler behavior, token/thinking/tool-call parsing, notification behavior, floating-monitor visuals, and normal successful inference semantics.

The change SHOULD remain within `InferenceGateway`, `InferenceRunner`, `EndpointHandlers`, the common Ktor/model-admission boundary, and focused tests. It SHALL NOT modify LiteRT Engine/Conversation wrappers or the internal implementation of `ServerLlmModelHelper.runInference()` unless new deterministic evidence proves the existing operation boundary insufficient.

### Requirement: Delivery requires CI and real-device closure

Compilation, JVM tests, and Android lint SHALL pass on the exact branch HEAD. An installable persistently signed APK is necessary but SHALL NOT be reported as runtime acceptance.

#### Scenario: Gemma timeout recovery closes the change

- GIVEN the APK is installed and Gemma-4-E4B-it is loaded
- WHEN a long request times out
- THEN native generation SHALL stop
- AND request configuration and Conversation recovery SHALL finish
- AND `requests_processing` SHALL become `0`
- AND the floating monitor SHALL return to `RUNNING`
- AND a subsequent short request SHALL return HTTP 200 without force-stopping the app
