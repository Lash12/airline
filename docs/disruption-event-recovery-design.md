# Disruption and Event Recovery Design

Status: design only. No disruption code is implemented in this pass.

## Why Per-Flight Delays Are Out Of Scope

The simulation currently models weekly aggregate links, not individual scheduled flights. A per-flight
delay/cancellation system would require flight instances, departure times, recovery aircraft, crew
availability, missed connections, passenger reaccommodation, and compensation state. Adding those concepts
would be a major simulation rewrite and would risk turning a route-planning game into manual operations
micromanagement.

For the current architecture, disruption effects should be aggregate, bounded, and self-recovering.

## Recommended First Slice

Implement aggregate airport or route reliability penalties:

- Airport disruption: temporarily reduces demand, reputation, or airport throughput for links touching
  one airport.
- Route disruption: temporarily applies a cost, load-factor, or reputation penalty to one route.
- Region disruption: optional later extension that applies smaller penalties across multiple airports.

The first implementation should be read-only from the player's perspective: it informs the player and
temporarily changes economics, then expires automatically.

## World News Surface

Disruptions should surface through the existing World News system:

- New event published when a disruption starts.
- Optional reminder/update if a long disruption is still active.
- Recovery news item when the penalty expires.

Recommended copy should be operational but not alarmist:

- "Weather disruption at JFK is reducing route reliability this week."
- "JFK operations have recovered; temporary reliability penalties have ended."

## Bounds And Recovery

Disruptions must be bounded so they create texture without punishing players arbitrarily.

Recommended guardrails:

- Max active disruptions at once.
- Max affected routes per airline.
- Max penalty percentage by severity.
- Short default duration measured in simulation cycles.
- Automatic expiry with no manual recovery action.
- Cooldown before the same airport or route can be affected again.
- No bankruptcy-triggering disruption size; penalties should be noticeable, not catastrophic.

## Economic Interaction

Route economics can absorb aggregate disruption penalties cleanly:

- Demand modifier: reduce effective passenger/cargo demand by a bounded percentage.
- Cost modifier: add a temporary operating cost surcharge.
- Reputation modifier: apply a small temporary satisfaction/reputation hit.
- Asset interaction: airport assets may reduce severity later, but should not be required.

Avoid penalties that force the player to close routes or manually reschedule aircraft. The player can choose
to adjust frequency or prices, but the system should recover on its own if they do nothing.

## Required Future Config Flags

Suggested flags:

```hocon
solo.disruption.enabled = false
solo.disruption.maxActive = 2
solo.disruption.frequencyCycles = 24
solo.disruption.minDurationCycles = 2
solo.disruption.maxDurationCycles = 6
solo.disruption.severity.min = 0.05
solo.disruption.severity.max = 0.20
solo.disruption.cooldownCycles = 48
```

Leave the feature disabled by default until it has UI, economic tests, and deploy validation.

## Data Model Options

Preferred first option: reuse existing event/news infrastructure for visibility and add only the smallest
runtime state needed to track active penalties.

Possible persistence options:

- In-memory only for a first prototype if disruptions are allowed to disappear on restart.
- Small side table if active disruptions must survive restarts:

```sql
active_disruption(
  id,
  disruption_type,
  airport_id,
  from_airport_id,
  to_airport_id,
  severity,
  started_cycle,
  expires_cycle
)
```

Only add a side table if restart persistence is necessary. If added, use the repo's safe startup migration
pattern.

## Test Plan For Future Implementation

- Unit tests for severity bounds, duration bounds, cooldowns, and max-active limits.
- Unit tests proving penalties expire automatically.
- Unit tests proving route economics receive temporary modifiers and return to baseline.
- World News tests for start and recovery messages.
- Playwright tests for visible news/recovery messaging and any route-detail penalty labels.
- Regression tests proving disruptions are off when `solo.disruption.enabled=false`.

## Reasons This Is Deferred

- The current weekly aggregate model is not ready for per-flight operations.
- A disruption economy needs careful balance to avoid random-feeling punishment.
- The route forecast, cargo, freighter, and advisor surfaces needed clarity first.
- No manual recovery micromanagement should be required, and that design needs deliberate UI review.
