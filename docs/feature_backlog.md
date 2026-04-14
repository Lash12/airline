# Phase 1: Feature Ideation & Backlog

## Upstream-Friendly Shortlist
1. **Route Profitability Quick View**:
   - *Why first*: Strong player value, low conceptual risk, and reuses existing route planning/search logic.
   - *Likely implementation*: add a lightweight planning endpoint that exposes route profitability estimates without creating the route.
2. **Hub-and-Spoke Wave Scheduler**:
   - *Why*: Builds directly on existing schedule and route-planning mechanics and feels like a natural upstream strategy feature.
   - *Likely implementation*: scheduling UI + backend validation helpers around coordinated bank arrivals/departures.
3. **Fleet Age / Maintenance Visualization**:
   - *Why*: Smaller UI-oriented change with clear gameplay value and minimal model churn.
   - *Likely implementation*: expose fleet age/maintenance aggregates to the frontend and add map/fleet coloring.
4. **Airport / World Events Surface**:
   - *Why*: Adds flavor with relatively small simulation changes and clear user-visible payoff.
   - *Likely implementation*: small event feed/ticker powered by `EventSimulation` outputs.

## Quick Wins (1-3 Days)
1.  **Airport "Events" Ticker**:
    -   *Description*: Random small events (weather, strikes) shown in a ticker, affecting demand slightly.
    -   *Impact*: Adds life to the world without deep mechanics.
    -   *Code*: Frontend ticker + `EventSimulation` tweaks.
2.  **Route Profitability "Quick View"**:
    -   *Description*: Tooltip on map showing estimated profit/loss for a potential route before creating it.
    -   *Impact*: High player value, reduces "spreadsheeting".
    -   *Code*: Expose `LinkSimulation` logic via API.
3.  **Visual "Fleet Age" Heatmap**:
    -   *Description*: Color code routes/planes by age to prompt replacement.
    -   *Impact*: Visualizes maintenance needs.

## Mid-Sized (1-2 Weeks)
1.  **Hub-and-Spoke "Wave" Scheduler**:
    -   *Description*: UI to drag-and-drop flight times to align connections at hubs.
    -   *Impact*: Strategic depth. Encourages realistic scheduling.
    -   *Risk*: UI complexity.
2.  **Used Aircraft Leasing**:
    -   *Description*: Lease used planes instead of buying. Lower upfront, higher daily cost.
    -   *Impact*: Helps early-game growth.
    -   *Code*: New table `leases`, `AirlineSimulation` logic updates.
3.  **Dynamic Fuel Hedging**:
    -   *Description*: More advanced oil contracts (futures options, not just bulk buy).
    -   *Impact*: Financial strategy.

## Big Bets (3-6+ Weeks)
1.  **Global Cargo Network**:
    -   *Description*: Dedicated cargo planes, routes, and contracts. Separate demand simulation.
    -   *Impact*: Massive content expansion. New gameplay loop.
    -   *Code*: New `CargoLinkSimulation`, DB tables, UI overhaul.
2.  **Alliances 2.0 (Joint Ventures)**:
    -   *Description*: Revenue sharing on specific routes between alliance members.
    -   *Impact*: Cooperative multiplayer depth.
    -   *Risk*: DB calculation complexity (splitting revenue).
3.  **Real-Time "Crisis" Mode**:
    -   *Description*: Active responding to crises (volcano ash, pandemic) with rerouting tools.
    -   *Impact*: High engagement urgency.

## Implementation Priorities
1.  **Route Profitability Advisor** (Quick Win) - best ROI.
2.  **Wave Scheduler** (Mid) - core for "Airline" strategy.
3.  **Cargo** (Big) - long term goal.
