# Delta Spec: Compact Breathing Floating Monitor Carousel

> **Status: INACTIVE ALTERNATIVE**
>
> Retained for future reference only. Do not implement, ticket, or treat this document as the active visual contract unless the user explicitly reactivates it.
>
> The active visual contract is `floating-monitor-visual-delta.md`.

## Summary

Replace the current simultaneous two-metric Floating monitor layout with a smaller, larger-type, single-page metric carousel. The visible pages rotate continuously through `req → proc → err → last` every 2.25 seconds. Page order and timing do not jump or reset when inference starts or finishes.

`proc` and `last` intentionally use task-appropriate units. `proc` is a live elapsed timer in whole seconds with a `9999+` cap; `last` preserves the existing status metric in whole milliseconds with a `99999+` cap. Each page shows its unit explicitly.

The hexagon fill breathes continuously in both RUNNING and PROCESSING, varying only fill opacity from 90% to 60% and back over a four-second full cycle. RUNNING and PROCESSING retain their approved state colors; a state transition changes the fill and edge hue immediately while preserving the breathing and carousel phases. The opaque 2dp edge remains, no Logo is added, and the controller's existing one-second metric/elapsed/permission/retry cadence remains unchanged.

## Scope

### In

- Continuous four-page carousel: `req → proc → err → last`.
- Fixed page dwell of 2.25 seconds with a direct cut between pages.
- No page jump on inference start or completion.
- Existing `ServerMetrics.lastLatencyMs` as the `last` data source.
- Existing `ServerMetrics.avgLatencyMs` remains available elsewhere but is not shown in the overlay.
- Explicit page-specific duration formatting: `proc` in seconds and `last` in milliseconds.
- Continuous fill-only breathing in both RUNNING and PROCESSING.
- Fill opacity range 90% ↔ 60%, with a four-second full cycle.
- Immediate RUNNING/PROCESSING state-color changes independent of carousel position.
- RUNNING state color `#4ADE80`.
- PROCESSING state color `#AFC6FF`.
- Existing same-state-color 2dp edge retained at 100% opacity.
- Metric page colors:
  - `req`: orange `#F59E0B`;
  - `proc`: black `#000000`;
  - `err`: red `#EF4444`;
  - `last`: white `#FFFFFF`.
- Single label plus single larger value on each page.
- Compact geometry of 84 × 92dp.
- Fixed label size 12sp and value size 22sp.
- Request/error count formatting without grouping separators.

### Out

- State-triggered page selection or page-order reset.
- Displaying average latency, peak latency, TTFB, token speed, queue depth, or HTTP handler count.
- New latency aggregation or terminal-outcome instrumentation.
- Logo, bitmap, blur, gradient, shadow, glow, scale animation, geometry animation, or scrolling transition.
- Whole-View alpha animation.
- Per-frame `WindowManager.updateViewLayout()` calls.
- Changes to the existing one-second controller cadence.
- RUNNING/PROCESSING state semantics, admission, queueing, cancellation, recovery, Engine/Conversation ownership, model unload, or SSE behavior.
- LiteRT-LM dependency or runtime upgrades; this phase remains on `litertlm-android:0.11.0`.

## Current Behavior

- Observable behavior:
  - The control measures 96 × 108dp.
  - It simultaneously renders `req` in the upper half and one state-dependent secondary metric in the lower half.
  - RUNNING renders cumulative `err`; PROCESSING renders current elapsed `proc` seconds.
  - Values use 18sp bold monospace; labels use 10sp.
  - Request/error counts use ASCII comma grouping such as `12,345` and cap at `99,999+`.
  - The comma is already half-width ASCII, but monospace rendering allocates it a full character cell.
  - No carousel or View-local animation exists.
  - The current source still uses opaque `#55D68B` for RUNNING and `#FFB74D` for PROCESSING.
  - The edge is a same-state-color 2dp stroke and text is opaque black.
- Existing metric sources:
  - `ServerMetrics.requestCount` supplies cumulative requests.
  - `ServerMetrics.errorCount` supplies cumulative errors.
  - `ProcessingElapsedTracker` supplies current active processing seconds.
  - `ServerMetrics.lastLatencyMs` supplies the latest successfully recorded request latency.
  - `ServerMetrics.avgLatencyMs` supplies average recorded latency but is intentionally excluded from the overlay.
- Evidence:
  - `FloatingMonitorView.kt` defines geometry, typography, state colors, stroke, and static simultaneous drawing.
  - `FloatingMonitorFormatter.kt` defines comma grouping and existing count/elapsed caps.
  - `FloatingMonitorController.kt` reads request/error/elapsed values once per second while visible.
  - `ServerMetrics.kt` exposes `lastLatencyMs` and `avgLatencyMs`; `recordLatency()` updates both.
  - `StatusScreen.kt` already displays Last Latency and Avg Latency from those fields.
- Known inconsistencies:
  - The previous visual Delta described static 80% fill, optional PROCESSING-only breathing, and excluded previous latency. This document replaces that contract completely.
  - The current production implementation and canonical monitor documents still describe the older simultaneous layout and colors; they are migration targets, not the proposed behavior.
  - The current branch HEAD contains an obsolete RED requiring removal of idle 1Hz reconciliation. That scheduling requirement remains superseded and is unrelated to this carousel/animation change.

## Behavior Deltas

### D-01 — Replace simultaneous metrics with a direct-page carousel

- Type: MODIFIED
- Before: `req` is always visible together with either `err` or `proc`.
- After: Exactly one metric page is visible at a time. Pages rotate continuously in the fixed order `req → proc → err → last`, with a direct cut every 2.25 seconds.
- Reason: Allow larger text in a smaller control while retaining access to all selected data.
- Affected actors/contracts: Floating monitor users, render model, View rendering, accessibility text, and visual/timing tests.

#### Scenario: Carousel advances continuously

- GIVEN the monitor remains visible
- WHEN each 2.25-second dwell expires
- THEN the next page SHALL be selected in the fixed order `req → proc → err → last → req`
- AND no fade, slide, or scrolling transition SHALL be required.

#### Scenario: Inference state does not redirect the carousel

- GIVEN any carousel page is currently visible
- WHEN inference starts or finishes
- THEN the page SHALL remain unchanged until its normal dwell expires
- AND the page order and carousel phase SHALL not reset.

#### Scenario: Attachment restarts at the first page

- GIVEN the monitor was hidden, detached, or disposed
- WHEN a new visible attachment begins
- THEN the carousel SHALL begin at `req`
- AND detaching or disposing SHALL stop its page timer.

### D-02 — Display existing last latency without adding average latency

- Type: ADDED
- Before: The overlay does not display last or average request latency.
- After: The `last` page displays `ServerMetrics.lastLatencyMs` directly as whole milliseconds from `1` through `99999`, capped as `99999+`, with a separate `ms` suffix. Before any positive latency is available it displays `—`. `ServerMetrics.avgLatencyMs` is not displayed.
- Reason: Surface the existing status-page last-latency metric without adding lifecycle instrumentation or lengthening the carousel with a fifth page.
- Affected actors/contracts: Render model, formatter, controller metric snapshot, accessibility text, and formatter tests.

#### Scenario: Last latency uses existing successful-request semantics

- GIVEN `ServerMetrics.lastLatencyMs` has a positive value
- WHEN the `last` page is visible
- THEN it SHALL show that integer value with a separate `ms` suffix
- AND a value above 99,999 milliseconds SHALL display `99999+`
- AND it SHALL NOT infer duration from request logs, error counts, or current processing elapsed.

#### Scenario: No last latency is available

- GIVEN `ServerMetrics.lastLatencyMs` is zero
- WHEN the `last` page is visible
- THEN it SHALL display `—`
- AND no unit suffix SHALL be displayed.

### D-03 — Use explicit page-specific duration units

- Type: MODIFIED
- Before: Current `proc` elapsed is formatted in seconds, while Last Latency is available in milliseconds only on the status/API surfaces; the overlay has no explicit two-page unit contract.
- After: `proc` floors current elapsed milliseconds to whole seconds, displays `0` through `9999` with a separate `s` suffix, and caps larger values as `9999+`. `last` displays the existing integer `lastLatencyMs` from `1` through `99999` with a separate `ms` suffix and caps larger values as `99999+`. The `proc` page displays `0s` while RUNNING; `last` displays `—` with no suffix before data exists.
- Reason: Seconds are easier to scan for a running timer, while milliseconds preserve useful precision for a completed latency measurement.
- Affected actors/contracts: Processing elapsed formatting, last-latency formatting, render model, carousel page content, and accessibility text.

#### Scenario: Each duration page declares its unit

- GIVEN positive `proc` and `last` durations are available
- WHEN either page is visible
- THEN `proc` SHALL display whole seconds with `s`
- AND `last` SHALL display whole milliseconds with `ms`
- AND neither page SHALL present a unitless positive value.

#### Scenario: Short inference may complete between proc pages

- GIVEN a request begins and ends while another carousel page is visible
- WHEN no `proc` dwell occurs during that interval
- THEN the carousel SHALL not jump to `proc`
- AND RUNNING/PROCESSING fill and edge colors SHALL still follow the actual state immediately.

### D-04 — Add continuous state-following fill breathing

- Type: ADDED
- Before: The fill is static and opaque.
- After: While the View is visible and attached, only the fill alpha continuously varies `90% → 60% → 90%` over a four-second full cycle in both RUNNING and PROCESSING.
- Reason: Keep the compact monitor visibly alive without moving its geometry or fading its data and edge.
- Affected actors/contracts: View-local animation lifecycle, Canvas rendering, visual tests, and small continuous redraw cost.

#### Scenario: State color changes without restarting animation

- GIVEN the fill is at any point in its breathing cycle
- WHEN state changes between RUNNING and PROCESSING
- THEN the fill and edge hue SHALL change immediately to the new state color
- AND the current breathing phase SHALL continue without resetting
- AND the currently visible carousel page SHALL remain unchanged.

#### Scenario: Only the fill breathes

- GIVEN the animation is active
- WHEN a frame is drawn
- THEN only fill-paint alpha SHALL vary
- AND the edge, page label, page value, and seconds suffix SHALL remain at 100% opacity
- AND View alpha, scale, geometry, touch bounds, and WindowManager layout SHALL remain unchanged.

#### Scenario: Animation lifetime follows View lifetime

- GIVEN breathing is active
- WHEN the monitor hides, detaches, deactivates, or disposes
- THEN the animator SHALL stop
- AND no animation-frame invalidation SHALL continue from the inactive View.

### D-05 — Preserve approved state color and edge contract

- Type: MODIFIED
- Before: Production source uses opaque RUNNING `#55D68B` and PROCESSING `#FFB74D`, with a same-color 2dp edge.
- After: RUNNING uses `#4ADE80`; PROCESSING uses `#AFC6FF`. The fill uses the animated alpha from D-04; the same-state-color 2dp edge remains opaque.
- Reason: Preserve the approved visual palette and explicit edge while incorporating breathing.
- Affected actors/contracts: Canvas color constants and visual tests only.

#### Scenario: Color remains independent of metric page

- GIVEN any of the four pages is visible
- WHEN the server is RUNNING or PROCESSING
- THEN fill and edge hue SHALL be determined only by server visual state
- AND metric page selection SHALL not alter the state hue.

### D-06 — Assign a stable color to each metric page

- Type: MODIFIED
- Before: All labels and values use opaque black.
- After: Each page's label, value, and optional unit suffix use one opaque page color: `req #F59E0B`, `proc #000000`, `err #EF4444`, and `last #FFFFFF`.
- Reason: Make page identity recognizable at a glance during continuous rotation.
- Affected actors/contracts: Paint selection, accessibility descriptions, screenshot expectations, and contrast review.

#### Scenario: Page color does not breathe

- GIVEN any page is visible
- WHEN fill opacity changes
- THEN page text SHALL remain fully opaque in its assigned color
- AND only the fill-paint alpha SHALL animate.

### D-07 — Increase typography while shrinking geometry

- Type: MODIFIED
- Before: The control is 96 × 108dp with 10sp labels and 18sp values in a simultaneous four-line layout.
- After: The control is 84 × 92dp with a centered single-page layout, fixed 12sp label, and fixed 22sp value. Values SHALL not dynamically shrink per page.
- Reason: Improve data readability while reducing the overlay footprint; the carousel removes the need for four simultaneous baselines.
- Affected actors/contracts: View measurement, hit rectangle, Canvas baselines, text sizing, drag clamping, placement restoration, and visual tests.

#### Scenario: Long bounded values fit without dynamic scaling

- GIVEN `99999+`, `9999+`, or the accepted last-latency cap is visible
- WHEN the page is drawn at 22sp
- THEN the value SHALL fit inside the 84 × 92dp hexagon without clipping
- AND the renderer SHALL not reduce font size dynamically.

### D-08 — Remove count grouping separators

- Type: MODIFIED
- Before: Request and error counts use comma grouping, including `12,345` and `99,999+`.
- After: Counts use ungrouped ASCII digits, including `12345` and `99999+`; the existing exact-count ceiling remains 99,999.
- Reason: The existing comma is already half-width but consumes a full cell in the monospace value font. Removing it saves width for larger text.
- Affected actors/contracts: Count formatter, formatter tests, visual tests, and accessibility output.

#### Scenario: Count cap remains honest

- GIVEN a request or error count at or below 99,999
- THEN the exact ungrouped count SHALL be displayed
- AND a larger count SHALL display `99999+`.

### D-09 — Keep carousel, breathing, and controller cadence independent

- Type: ADDED
- Before: Only the one-second controller loop updates visible metrics and elapsed time.
- After: State transitions remain event-driven; metric/elapsed/permission/retry reconciliation remains at one second; carousel dwell is 2.25 seconds; breathing uses a four-second View-local cycle. None of these clocks replaces another.
- Reason: Prevent visual animation from altering server observation, permission health, retry behavior, or window placement.
- Affected actors/contracts: Controller/View responsibility boundary and lifecycle tests.

#### Scenario: Animation does not perform window work

- GIVEN the monitor is attached and breathing or rotating pages
- WHEN an animation or carousel frame changes
- THEN the View MAY invalidate its own Canvas
- AND it SHALL NOT call `WindowManager.updateViewLayout()` merely for breathing or page rotation.

## Compatibility Impact

- API/protocol: None. HTTP and SSE contracts do not change.
- Data/schema: None. Existing metrics are read without adding persisted fields or changing their semantics.
- Configuration: None. No new user setting is introduced.
- Client/provider/adapter: None.
- User-visible behavior: Intentionally changed. Simultaneous metrics become a four-page carousel; motion is continuous; typography, dimensions, count formatting, state colors, and text colors change.
- Placement/touch behavior: The overlay's rectangular touch window becomes smaller. Persisted normalized placement remains compatible but must be re-clamped against the new measured dimensions.
- Operations/observability: Continuous fill animation and carousel add View-local redraw/timer work while visible, including idle RUNNING. The one-second controller health/retry cadence remains unchanged.
- Security/privacy: None. Overlay permission and touch model do not change.

## Validation Matrix

| Delta | Verification seam | Planned check | Pass condition |
|---|---|---|---|
| D-01 | Pure carousel state helper | Deterministic clock/page-sequence JVM tests | Page order is `req → proc → err → last`, dwell is 2.25s, and state changes do not redirect it |
| D-01 | View lifecycle seam | Attach/detach tests | New attachment starts at req; detach/dispose stops page rotation |
| D-02 | Render model and metric source | JVM tests with zero, positive, and over-cap `lastLatencyMs` | Zero renders `—`; positive values retain integer milliseconds with `ms`; values over 99,999 render `99999+`; avg is absent |
| D-03 | Page-specific duration formatters plus render model | Boundary tests for both `proc` and `last` | `proc` uses whole seconds/`s`/`9999+`; `last` uses whole milliseconds/`ms`/`99999+`; RUNNING proc is `0s` |
| D-04 | Pure alpha interpolation / animator lifecycle | JVM helper tests plus source review | Alpha stays within 60–90%; full cycle is 4s; inactive View stops frames |
| D-04 | Runtime drawing boundary | Source review | Only fill-paint alpha changes; no whole-View or WindowManager animation exists |
| D-05 | Pure visual constants | JVM visual-contract tests | RUNNING is `#4ADE80`, PROCESSING is `#AFC6FF`, edge is same hue at 2dp/100% |
| D-06 | Page-palette helper | JVM tests | req/proc/err/last map exactly to their approved colors and remain opaque |
| D-07 | Visual constants and geometry | JVM contract tests plus bounded screenshot check | Size is 84 × 92dp, label 12sp, value 22sp, and longest bounded values do not clip |
| D-08 | Count formatter | JVM boundary tests | `12345`, `99999`, and `99999+` render without grouping separators |
| D-09 | Controller/View source boundary | Source review and focused lifecycle tests | Existing 1Hz cadence is unchanged; carousel and breathing are View-local and stop when inactive |
| D-01–D-09 | Real-device visual acceptance | Short recording over RUNNING and bounded PROCESSING | Carousel, state color, breathing, typography, and clipping match the contract without affecting server operation |

## Risks and Mitigations

- Continuous idle breathing adds persistent redraw work.
  - Mitigation: animate only the small Canvas fill; perform no WindowManager layout work; stop immediately when hidden or detached.
- A 2.25-second carousel can hide current elapsed during short requests.
  - Mitigation: this is an explicit product decision; state color still changes immediately and no state-triggered page jump is added.
- White `last` text and orange `req` text may have lower contrast on some breathing/background combinations.
  - Mitigation: validate the user-selected palette over representative light and dark app backgrounds; do not silently replace the selected colors.
- Larger fixed text inside a smaller hexagon can clip at caps.
  - Mitigation: directly measure and screenshot-test `99999+` and `9999+`; adjust internal baselines/spacing rather than adding dynamic font scaling.
- Multiple independent timers can leak after detach.
  - Mitigation: one View-owned lifecycle stops carousel and breathing together on hide, detach, deactivate, and dispose.
- `lastLatencyMs` records the latest successfully recorded latency rather than every failed/cancelled request.
  - Mitigation: preserve and document the existing metric semantics; do not invent terminal outcomes in the visual layer.

## Open Questions

None required before implementation. Exact visual fit remains a validation gate rather than an unresolved product decision.

## Handoff

- Artifact path: `openspec/changes/add-background-floating-monitor/floating-monitor-breathing-carousel-delta.md`
- Delta IDs: D-01 through D-09
- Compatibility-sensitive IDs: none externally; D-01, D-04, D-07, and D-09 require lifecycle/timing review
- Validation matrix status: complete for source/JVM planning; real-device visual checks remain deferred until authorized
- Tickets needed: no; implement as focused RED → GREEN slices for carousel/data, animation, and geometry/palette
- Suggested next skill: `gm-tdd`
- Review depth: moderate because two View-local timers must stop reliably and the smaller geometry must fit fixed-size values
- Implementation approval status: INACTIVE; retained only as an alternative and not approved for current implementation
