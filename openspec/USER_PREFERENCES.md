# OlliteRT User Preferences and Project Guardrails

**Owner:** Alex

**Scope:** durable product preferences and architectural guardrails for `roy3003/OlliteRT`.

This document records stable project preferences. It is not a progress log, implementation plan, or proof of validation.

- Current branch and execution state: [`openspec/HANDOFF.md`](HANDOFF.md)
- Stable behavior contracts: `openspec/changes/*/specs/`
- Short executable plans and evidence checkboxes: `openspec/changes/*/tasks.md`

## 1. Product direction

OlliteRT is evaluated primarily as a phone-hosted LAN inference node that exposes an OpenAI-compatible API. The important distinction is between:

- an Android app that connects to a remote server; and
- an Android app that loads a local model and exposes that model as a network server.

Only the second behavior satisfies the core OlliteRT use case.

Before treating a local/mobile model integration as usable, prefer real checks of:

- `/v1/models`;
- `/v1/chat/completions`;
- strict structured JSON behavior;
- tool/function-call behavior where supported;
- generation speed;
- timeout and cancellation behavior;
- screen-off and background service stability;
- a real Hermes/Telegram path after direct API diagnostics pass.

## 2. Network and endpoint semantics

Endpoint presentation and actual server reachability are separate concerns.

- Ktor already listens on `0.0.0.0` in the relevant OlliteRT baseline.
- An endpoint selector changes which address is displayed; it does not by itself change bind behavior, Android routing, VPN policy, firewall behavior, or Tailscale reachability.
- Prior Tailscale TCP timeout evidence belonged to the Android/system network path, not to an app bind-address defect.
- Network conclusions require direct tests from the specified client host to the Android target. Do not infer reachability from UI text or from another machine's proxy/VPN state.

## 3. Floating monitor architecture

The main `ServerService` should remain focused on LiteRT-LM, Ktor, and native inference stability.

For the current observational floating monitor:

- keep it subordinate to the existing `ServerService`;
- keep overlay failures and coroutine scope isolated from model/Ktor lifecycle;
- do not add another foreground service or notification merely to render RUNNING/PROCESSING state;
- keep the original foreground-service notification unchanged.

If a future monitor must persistently show STOP/ERROR and provide a durable control entry point while `ServerService` is absent, use one lightweight same-process `ServerMonitorService` as the sole control host. It should read status through an independent lifecycle event/metrics layer rather than transferring one overlay window between two services.

## 4. Floating monitor visual contract

The accepted visual contract is:

- RUNNING: full fill `#55D68B`;
- PROCESSING: full fill `#FFB74D`;
- labels and values: App UI pure black `#000000`;
- processing time: current request's plain integer seconds;
- seconds suffix: a smaller `s` placed to the right without shifting the numeric value itself away from center;
- elapsed time resets for every request/inference sequence;
- the original foreground-service notification does not change with floating-monitor work.

The current bounded formatter contract is recorded in the floating-monitor spec; this preference document owns the visual intent, not implementation progress.

## 5. Native inference lifecycle architecture

When lifecycle work begins spreading across Endpoint, Runner, Gateway, SSE, metrics, cache, and model lifecycle, freeze broad implementation and re-evaluate against the official LiteRT-LM API and a clean baseline.

The preferred ownership split is:

- `ModelRequestLease`: protects model selection, admission, queueing, idle-unload exclusion, and release of model-level resource ownership;
- per-request `InferenceExecution` owned by `InferenceGateway`: owns preparation, native dispatch, cancellation classification, recovery, finish, and terminal outcome.

These responsibilities must remain separate:

- admission/resource ownership is not metrics;
- metrics observe lifecycle but do not lock it;
- native cancellation remains request-owned even though LiteRT-LM exposes only Conversation-scoped `cancelProcess()`;
- queued/preparing cancellation abandons only that request and must not cancel a different running request;
- a dispatched request may issue native cancellation at most once;
- recovery and configuration/Conversation restoration complete before lifecycle ownership and processing metrics are released;
- blocking and streaming share one native ownership core while preserving their output-specific shaping and SSE wire format.

## 6. Change-scope guardrails

Prefer the smallest evidence-backed change to upstream code.

- Preserve LiteRT Engine/Conversation wrappers and `ServerLlmModelHelper.runInference()` unless deterministic evidence proves the existing operation boundary insufficient.
- Preserve normal prompt construction, sampler/config behavior, token/thinking/tool-call parsing, OpenAI/Anthropic payloads, response/SSE formats, notifications, and accepted floating-monitor visuals.
- Do not split blocking and streaming into independent lifecycle state machines.
- Do not replace explicit external-side-effect serialization with a lock-free CAS design.
- Do not add multiple uncoordinated `Boolean`, `AtomicBoolean`, `AtomicReference`, or latch representations of request state.
- Stop and reassess if callbacks must cross three or more layers or if native ownership can no longer remain in the Gateway.

## 7. Evidence and acceptance boundary

Do not conflate these milestones:

1. source compiles;
2. JVM tests and Android lint pass;
3. an APK artifact exists;
4. the APK is persistently signed and can update the installed dev chain;
5. the APK is installed on the target device;
6. the real model/runtime behavior passes.

For timeout/cancellation work, final device evidence should establish:

```text
long request timeout
→ native generation stops
→ request configuration and Conversation recover
→ requests_processing = 0
→ floating monitor returns to RUNNING
→ a subsequent short request returns HTTP 200 without force-stop
```

Production failures remain evidence. A retry should use a fresh/cloned test task rather than rewriting the historical failed attempt.

## 8. Document maintenance boundary

Update this file only when a durable OlliteRT preference or architectural guardrail changes.

Do not place the following here:

- current task progress;
- temporary blockers;
- CI run IDs as ongoing status;
- APK hashes for a particular test build;
- commit-specific review findings;
- short-lived device/network state.

Those belong in `openspec/HANDOFF.md`, the relevant `tasks.md`, or the session evidence trail.
