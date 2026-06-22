# Route Opportunity / Forecast Backend

This document details the design, configuration, and verification of the Route Forecast system.

## Design Overview

The Route Forecast backend provides players with a single unified forecast object that estimates passenger/cargo demand, expected revenue, expected cost, expected profit, confidence level, and competition level. It reuses the game's existing simulation logic (`LinkSimulation`, `DemandGenerator`, `Pricing`) to ensure consistency with actual gameplay.

### Suggested Model Selection & Schedule
1. **Demand Calculations:**
   - Passenger Demand: `DemandGenerator.computeBaseDemandBetweenAirports` combined from both directions.
   - Cargo Demand: `CargoDemandGenerator.computeCargoDemandBetweenAirports` combined from both directions (if cargo is enabled).
2. **Aircraft Recommendation:**
   - Scans all models to find those fitting range and runway length restrictions.
   - Uses `ConsultantAdvisor.suggestModel` to select the primary recommended aircraft.
3. **Weekly Schedule & Profitability simulation:**
   - Determines the recommended frequency based on seats-per-flight and target demand.
   - Constructs a temporary `Link` object representing the prospective route.
   - Simulates operating costs (fuel, crew, maintenance, landing fees, depreciation, lounge charges) via `LinkSimulation.computeFlightLinkConsumptionDetail`.
   - Incorporates belly cargo revenue (if cargo is enabled) using `SoloConfig.cargoCaptureRatio` and `SoloConfig.cargoRevenuePerUnitKm`.
4. **Competition Level:**
   - Analyzes existing direct flights (`LinkSource.loadFlightLinksByAirports`) to classify competition into `NONE`, `LOW`, `MEDIUM`, or `HIGH`.
   - Reduces the player's demand capture share proportionally under high/medium competition.
5. **Confidence Band:**
   - Classified as `HIGH`, `MEDIUM`, or `LOW` depending on projected profit, competition intensity, airport constraints, and range margins.
6. **Reasons & Player Feedback:**
   - Translates findings into list of player-readable explanation strings.

## Configuration & Feature Flags

Like other solo-player QOL enhancements, this backend is gated by a feature flag under the `solo.*` prefix in `application.conf` or JVM system properties:

- `solo.routeForecast.enabled` (Boolean, default: `false`): Enables or disables the Route Forecast API.

Example JVM startup argument:
```bash
SBT_OPTS="-Dsolo.routeForecast.enabled=true -Dsolo.cargo.enabled=true"
```

## API Endpoint

- **Path:** `GET /airlines/:airlineId/route-forecast`
- **Query Parameters:**
  - `originAirportId` (Int): ID of the origin airport.
  - `destinationAirportId` (Int): ID of the destination airport.
- **Access Guard:** Protected by the `AuthenticatedAirline(airlineId)` wrapper.
- **Responses:**
  - `200 OK`: Returns the JSON representation of the forecast.
  - `403 Forbidden`: Gated when `solo.routeForecast.enabled` is `false`.
  - `404 Not Found`: Gated when origin or destination airport ID is invalid.

### Example JSON Output

```json
{
  "originAirportId": 1,
  "destinationAirportId": 2,
  "passengerDemandEstimate": 524,
  "cargoDemandEstimate": 86,
  "expectedRevenue": 156000,
  "expectedCost": 92000,
  "expectedProfit": 64000,
  "confidenceLevel": "HIGH",
  "competitionLevel": "NONE",
  "recommendedAircraftModels": ["Boeing 737 MAX 7"],
  "recommendedFrequency": 4,
  "reasons": [
    "Strong passenger demand on this route.",
    "Light cargo demand (86 units) available.",
    "No direct competition. A perfect opportunity for monopoly.",
    "Aircraft suggestion: Boeing 737 MAX 7 fits this route's distance and runway limits.",
    "Healthy profit margins projected under typical load factors."
  ]
}
```

## Verification

### Unit & Integration Tests
Tests are located in `airline-data/src/test/scala/com/patson/RouteForecastServiceSpec.scala`.
They cover:
1. **Feature disabled behavior:** Ensures the service returns `Left(FEATURE_DISABLED)` when the flag is off.
2. **Unavailable data:** Verifies behavior with invalid airport IDs.
3. **Passenger-only routes:** Verifies expected profit calculations without cargo.
4. **Cargo-supported routes:** Verifies additional revenue and reasoning when cargo is enabled.
5. **High competition:** Validates that competition level and reasons adjust when rival routes exist.
6. **Unsuitable aircraft:** Verifies behavior when runway or range restrictions prevent any database model from operating.

### Environment Limits
The database tests require a running local MySQL instance configured in `application.conf`. In sandbox testing environments where MySQL is not running, the database connection will be refused, causing tests that query the database to fail. However, all components compile successfully.
