# Design: Minimal Background Floating Monitor

## Design Constraints

The LLM/Ktor Service is the product-critical component. The monitor is disposable observation UI and must not enter the same failure/cancellation domain. Minimal Phase 1 implements only Running, Processing, and Hidden. It does not prebuild future states or service handoff machinery.

## Architecture

```text
ServerMetrics status/isInferring/requestCount/errorCount
OlliteRTLifecycleProvider foreground StateFlow
permission-flow suppression StateFlow
saved setting/position snapshot
        |
        v
FloatingMonitorController
  independent Main/Supervisor scope
  pure state + visibility reducer
  WindowManager lifecycle + 1s metric throttle
        |
        v
FloatingMonitorView
  static state-specific four-line renderer
  tap + drag input
```

Controller is created best-effort by the existing `ServerService`, through an application-level Hilt EntryPoint if dependencies are required. It does not own model, Ktor, locks, notifications, or Service restart behavior.

## State and Display Model

```text
Hidden
Running
Processing
```

```text
STOPPED/LOADING/ERROR       -> Hidden
RUNNING && !isInferring     -> Running
RUNNING && isInferring      -> Processing
```

```text
Running    -> req / exact requestCount / exact errorCount / err
Processing -> req / exact requestCount / current elapsed / proc
```

The two visible states use identical four-line geometry, not a carousel. Background/border color communicates state.

## Lifecycle and Visibility

MainActivity writes application visibility from ON_START/ON_STOP to a singleton `StateFlow<Boolean>`. Existing indirect NavGraph/ViewModel ON_PAUSE/ON_RESUME writers are removed, and existing synchronous consumers migrate to `.value`.

The controller combines observable inputs:

```text
setting snapshot
permission
permission-flow suppression
app foreground
service alive
ServerMetrics.status
ServerMetrics.isInferring
```

It attaches only when the visibility predicate is true. The setting is captured for the current Service instance; Settings saves do not require hot controller creation/destruction. Permission and app visibility remain live inputs.

## Permission Flow

Saved intent is independent from permission. Settings shows On + Permission required and an explicit Grant action. Before launching system overlay settings, shared suppression becomes true. The result/resume path rechecks permission before clearing suppression so Activity ON_STOP cannot reveal the overlay above system settings.

Permission revocation has no reliable callback. While visible, the 1s metric tick also rechecks permission; state events do the same. Any add/update failure also forces reconciliation.

## Stability and Coroutine Isolation

Controller owns `SupervisorJob + Dispatchers.Main.immediate`. It never uses or cancels `ServerService.serviceScope`. Construction is outside the Service's large critical initialization try/catch whose catch can call `stopSelf()`. All overlay entry points catch locally and disable only the UI.

At the beginning of `onDestroy()`, Service calls best-effort `dispose()` before existing potentially long inference executor/model cleanup. Dispose is idempotent and never changes the relative order of existing LLM cleanup operations.

All WindowManager add/update/remove operations execute on Main. Internal attachment state is updated only after successful operations or reconciled conservatively after exceptions.

## Metrics and Time

`requestCount` and `errorCount` are existing StateFlows and are never re-counted. Request count means request IDs assigned, not successful completions.

Count formatter:

```text
0..99,999 -> exact grouped integer
>=100,000 -> 99,999+
```

Stable-width numerals avoid horizontal jitter. No K/M/Million format exists.

Controller observes `isInferring` from Service start. A false→true transition records injected monotonic time locally; true→false/dispose clears it. Elapsed formatter:

```text
0..59 seconds    -> Ns
60s..99:59       -> M:SS
>=100 minutes    -> 99m+
```

No historical TTFB, latency, token callback, or progress sampling is used.

Status/visibility updates render immediately. Metric values are snapshotted and coalesced by a visible one-second tick; hidden/disposed state has no tick. A draw is skipped if formatted visible output did not change.

## Renderer and Input

A native Canvas View draws the point-top hexagon. Running and Processing use distinct dark semantic palettes with readable border and white values; labels are subdued gray. The actual overlay window is a tight rectangular hit target at least 48dp, because transparent hex corners cannot reliably pass input through.

Tap is distinguished from drag by movement threshold. Tap best-effort detaches before sending a PendingIntent to MainActivity; fallback Intent uses NEW_TASK|SINGLE_TOP. Send failure runs visibility reconciliation. Drag only moves and saves position; there is no long press or swipe.

## Position Model

Persist normalized X/Y only after clamping to current safe bounds. On attach or display/configuration changes, derive pixels from current bounds/insets, then clamp. Reset deletes saved coordinates and causes default position on the next attach without restarting Service.

## Settings Wiring

The setting is added to the existing server behavior/Auto-Launch card path and must participate in metadata registration, search, typed ViewModel state, save, reset, and change detection. Permission status/action is adjacent to the setting rather than conflated with its value.

## Test Seams

- Pure state/display mapper.
- Pure visibility reducer.
- Injected monotonic clock and elapsed/count formatters.
- Fake WindowManager adapter and attached-state reducer.
- Pure gesture and normalized-position geometry.
- Settings preference and Activity-result suppression seams.
- Service construction/disposal seam proving overlay exceptions never call/trigger `stopSelf()`.

Tests assert behavior through these seams rather than private implementation details.

## Rejected Alternatives

- **Second foreground Service/notification:** unnecessary lifecycle and notification ownership complexity.
- **LOADING/ERROR/STOP + long-press start:** scope and service ownership expansion rejected for Phase 1.
- **Carousel of req/proc/err:** users can miss a metric for several seconds and animation adds lifecycle/power complexity.
- **Last TTFB in Processing:** current metric is historical and written after request completion, so it can misrepresent a stuck current request.
- **Token stall instrumentation:** more accurate for stalls but touches inference callback paths and does not generalize cleanly to every request type.
