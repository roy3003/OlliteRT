# Floating Monitor Specification

### Requirement: Explicit opt-in configuration

The application SHALL provide a `Floating monitor` setting in the server behavior/auto-launch settings area. It SHALL default to Off. The saved value represents user intent and SHALL apply to the next `ServerService` instance; changing an unsaved draft or saving a new value SHALL NOT require hot enable/disable of the current Service.

The application SHALL persist normalized X/Y position values separately and SHALL provide `Reset position`, which clears those values for the current controller's next attach.

#### Scenario: Default, save, and reset semantics

- GIVEN no floating-monitor preference exists
- WHEN Settings is opened
- THEN Floating monitor SHALL be Off
- WHEN the user saves On
- THEN the intent SHALL persist for the next `ServerService` instance
- WHEN the user resets position
- THEN normalized X/Y SHALL be cleared without restarting the Service

### Requirement: Explicit overlay permission flow

The application SHALL request `SYSTEM_ALERT_WINDOW` only through an explicit user action. Saved intent and current permission SHALL remain separate. When intent is On but permission is absent, Settings SHALL show `Permission required` and a `Grant permission` action rather than claiming the monitor is active.

A shared permission-flow suppression StateFlow SHALL be true before opening system overlay settings and SHALL remain true until the Activity result/resume path has rechecked permission.

#### Scenario: Intent On without permission

- GIVEN Floating monitor intent is On
- AND overlay permission is absent
- WHEN Settings is rendered
- THEN it SHALL display `Permission required`
- AND it SHALL offer `Grant permission`
- AND it SHALL NOT silently grant, launch, or revert the saved intent

#### Scenario: Permission screen does not reveal the monitor

- GIVEN the app is foregrounded and the monitor intent is On
- WHEN the user explicitly opens system overlay settings
- THEN permission-flow suppression SHALL become true before launch
- AND the monitor SHALL remain hidden while system settings is visible
- WHEN control returns
- THEN permission SHALL be rechecked before suppression is cleared

### Requirement: Minimal visibility and state mapping

The monitor SHALL be visible only when all of the following are true:

```text
savedSettingSnapshotEnabled
&& overlayPermissionGranted
&& appIsBackground
&& serviceIsAlive
&& serverStatus == RUNNING
&& !overlayPermissionFlowInProgress
```

Visual state SHALL be derived as follows:

```text
RUNNING && !isInferring -> Running
RUNNING && isInferring  -> Processing
all other states        -> Hidden
```

App foreground SHALL be observable through shared StateFlow written from MainActivity ON_START/ON_STOP, not ON_PAUSE/ON_RESUME or an indirect NavGraph/ViewModel writer.

#### Scenario: Running and Processing mapping

- GIVEN all visibility prerequisites are true
- WHEN server status is RUNNING and `isInferring` is false
- THEN the visual state SHALL be Running
- WHEN `isInferring` becomes true
- THEN the visual state SHALL immediately become Processing

#### Scenario: Foreground or non-running state hides the monitor

- GIVEN the monitor is attached
- WHEN MainActivity reaches ON_START, the Service dies, permission/suppression becomes invalid, or server state becomes STOPPED, LOADING, or ERROR
- THEN the monitor SHALL be removed idempotently
- AND no hidden-state ticker SHALL remain active

### Requirement: Controller isolation from LLM serving

The controller SHALL use an independent `SupervisorJob + Dispatchers.Main.immediate` scope and SHALL NOT reuse, cancel, or block the Service LLM scope. Controller initialization SHALL occur outside the Service's critical model/Ktor initialization try/catch. Failures SHALL disable only the monitor and SHALL NOT call or cause `stopSelf()`.

The Service SHALL best-effort dispose the controller at the beginning of `onDestroy()` before existing model/executor cleanup. WindowManager calls SHALL be serialized on Main and attach/remove SHALL be idempotent.

#### Scenario: Overlay initialization or WindowManager failure

- GIVEN model/Ktor serving is otherwise available
- WHEN controller construction, permission inspection, View creation, add/update/remove, or Activity launch throws
- THEN the failure SHALL be contained in the overlay boundary
- AND the LLM Service SHALL continue running
- AND no overlay path SHALL invoke `stopSelf()`

#### Scenario: Service destruction removes stale state first

- GIVEN a Running or Processing overlay is attached
- WHEN `ServerService.onDestroy()` begins
- THEN the controller SHALL best-effort detach/dispose before long inference/executor/model cleanup
- AND subsequent disposal or remove attempts SHALL remain safe and idempotent

### Requirement: Static metrics and bounded refresh

RUNNING SHALL use this static four-line centered layout:

```text
       req
       <exact request count>
       <exact error count>
       err
```

PROCESSING SHALL use this static four-line centered layout:

```text
       req
       <exact request count>
       <current processing elapsed>
       proc
```

Labels SHALL be subdued gray and values high-contrast white. `req` SHALL read `ServerMetrics.requestCount`, meaning requests for which a request ID was assigned; it SHALL NOT be described as successful completions. RUNNING `err` SHALL read `ServerMetrics.errorCount`. PROCESSING SHALL NOT show historical TTFB or latency.

Counts from `0..99,999` SHALL use exact integers with grouping separators (`999`, `1,000`, `12,345`, `99,999`). Counts from `100,000` onward SHALL show `99,999+`. Digits SHALL use stable width.

The controller SHALL record a local monotonic start when observed `isInferring` changes false→true and clear it on true→false or dispose. Elapsed SHALL format as `0s..59s`, `M:SS` through `99:59`, and `99m+` from 100 minutes onward. This SHALL NOT modify inference/token callbacks.

#### Scenario: Static metrics use truthful bounded formats

- GIVEN Running is rendered
- THEN it SHALL show req/exact-request/exact-error/err
- GIVEN Processing is rendered
- THEN it SHALL show req/exact-request/current-elapsed/proc
- AND `99,999` SHALL remain exact
- AND `100,000` and larger SHALL show `99,999+`
- AND historical TTFB SHALL not be substituted for current elapsed

#### Scenario: Visible metric updates are coalesced without polling

- GIVEN Running or Processing is visible
- WHEN current metric inputs change multiple times in a one-second window
- THEN the monitor SHALL invalidate at most once for those metrics in that window
- AND SHALL draw the latest snapshot
- WHEN the monitor becomes Hidden
- THEN its metric ticker SHALL stop
- AND no HTTP, network, `/proc`, token-progress, or model query SHALL be started

### Requirement: Hex renderer and touch bounds

The View SHALL draw a point-top hexagon inspired by `ic_brand`, with distinct readable dark semantic backgrounds/borders for Running and Processing. Both states SHALL share the same four-line geometry and font budget while rendering their state-specific bottom metric.

The Android overlay input window SHALL be a tight rectangle around the hexagon and at least 48dp. Transparent corners SHALL NOT promise touch pass-through.

#### Scenario: Visual states remain distinct and readable

- GIVEN Running and Processing are rendered
- THEN their backgrounds/borders SHALL be visually distinguishable
- AND labels and stable-width values SHALL remain readable without a three-metric carousel or compressed three-column layout

### Requirement: Safe tap, drag, and placement

A tap SHALL best-effort detach before opening MainActivity using `PendingIntent.getActivity().send()` where practical, or a NEW_TASK|SINGLE_TOP fallback. A movement beyond the drag threshold SHALL move the window and SHALL NOT open the Activity.

Drag completion SHALL clamp the window to current safe display bounds and persist normalized X/Y. Attach and configuration/display changes SHALL reconstruct and re-clamp position. Illegal WindowManager operations SHALL be contained and internal attached state reconciled.

#### Scenario: Tap, drag, and display changes remain safe

- GIVEN the monitor is attached
- WHEN the gesture stays below the drag threshold
- THEN it SHALL detach and open MainActivity safely
- WHEN movement exceeds the threshold
- THEN it SHALL only drag and save a clamped normalized position
- WHEN rotation, insets, cutout, resolution, split-screen, or reset changes placement inputs
- THEN the next layout/attach SHALL reconstruct and clamp the position
- AND no long-press or swipe/carousel action SHALL exist
