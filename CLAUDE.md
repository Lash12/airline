# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an open-source airline management simulation game forked from airline-club.com, running live at myfly.club. The game features real-time multiplayer simulation with persistent state, financial modeling, route planning, and complex passenger demand systems.

**Tech Stack**: Scala 2.13.11, Play Framework 2.x, MySQL 8, Elasticsearch 7.x, Apache Pekko (formerly Akka), Docker

## Architecture

### Three-Process Architecture

The application runs as three independent processes that communicate via database and Pekko remote actors:

1. **Simulation Engine** (`airline-data` module)
   - Standalone Scala application that runs game cycle simulations (~29 minutes per week)
   - Entry: `sbt run` → select `MainSimulation`
   - Publishes cycle events via Pekko remote actors (`localhost:2552`)

2. **Web Server** (`airline-web` module)
   - Play Framework REST API + WebSocket server
   - Entry: `sbt run` (runs on `localhost:9000`)
   - Subscribes to simulation events via Pekko (`localhost:2553`)

3. **MySQL Database**
   - Shared persistent storage between simulation and web
   - Connection pooling via C3P0 (100 max connections)

### Simulation Cycle Flow

Each cycle in `MainSimulation.scala` runs these simulations sequentially:

```
UserSimulation → EventSimulation → LinkSimulation → AirportSimulation
→ AirportAssetSimulation → AirplaneSimulation → AirlineSimulation
→ CountrySimulation → AllianceSimulation → AirplaneModelSimulation
```

**Key simulations:**
- `DemandGenerator.computeDemand()` - Generates passenger demand between airport pairs based on distance, population, income, country relationships
- `PassengerSimulation.passengerConsume()` - Routes passengers through multi-leg journeys using Dijkstra-style route finding with 9 consumption iterations
- `LinkSimulation.computeLinkProfits()` - Calculates link P&L including fuel (distance^1.4), crew, airport fees, maintenance
- `AirlineSimulation.airlineSimulation()` - Updates airline finances, reputation, service quality, handles bankruptcies

### Real-Time Communication

Simulation publishes events to web via Pekko remote actors:

```
MainSimulation → SimulationEventStream.publish(CycleCompleted)
  → BridgeActor (remote actor)
    → ActorCenter (web side)
      → MyWebSocketActor (per-client)
        → Browser WebSocket
```

## Development Commands

### Local Setup (Non-Docker)

```bash
# Set SBT JVM options (required)
export SBT_OPTS="-Xms2g -Xmx8g"

# Initialize database (first time only)
cd airline-data
sbt publishLocal
sbt run  # Select MainInit option, may need to run multiple times if migration fails

# Run simulation engine
cd airline-data
sbt run  # Select MainSimulation option

# Run web server (in separate terminal)
cd airline-web
sbt run  # Access at http://localhost:9000
```

### Docker Setup

```bash
# Start services
docker compose up -d

# Enter container
docker compose exec airline-app bash

# Initialize (run multiple times until succeeds)
sh init-data.sh

# Start backend simulation (in one session)
sh start-data.sh

# Start web frontend (in separate session)
sh start-web.sh

# Access at http://<host-ip>:9000
```

### Testing

```bash
# Run Scala unit tests
cd airline-data
sbt test

# Run specific test
sbt "testOnly com.patson.PassengerSimulationSpec"

# E2E tests (Playwright)
cd e2e
npm install  # First time only
npx playwright test
```

### Database

**Connection details** are in `airline-data/src/main/scala/com/patson/data/Constants.scala` and `airline-web/conf/application.conf`

Default credentials:
- Host: `localhost:3306` (or `airline-db` in Docker)
- Database: `airline`
- User: `mfc` / `mfc01` (Docker)
- Schema defined in `Constants.scala` (180+ tables)

**Important:** Add `character-set-server=utf8mb4` to `/etc/my.cnf` if you encounter encoding errors.

## Code Structure

### airline-data Module (Simulation Engine)

```
airline-data/src/main/scala/com/patson/
├── MainSimulation.scala           # Orchestrates all simulations per cycle
├── MainInit.scala                 # Database initialization entry point
├── *Simulation.scala              # Individual simulation modules
├── DemandGenerator.scala          # Passenger demand computation
├── PassengerSimulation.scala      # Route finding and seat consumption
├── RouteFinder.scala             # Multi-leg route pathfinding
├── Authentication.scala          # Login/auth logic
├── data/
│   ├── Constants.scala           # Database schema definitions (all tables)
│   ├── Meta.scala               # Connection pool management
│   └── *Source.scala            # Data access objects (Airport, Airline, Link, etc.)
├── model/                        # Domain models
│   ├── Airport.scala
│   ├── Airline.scala
│   ├── Link.scala
│   ├── airplane/
│   │   ├── Airplane.scala
│   │   └── Model.scala
│   └── PassengerGroup.scala
├── init/                         # Initialization utilities
│   ├── GeoDataGenerator.scala   # Load airports, cities, countries
│   └── AirlineGenerator.scala   # Generate NPC airlines
├── patch/                        # Data patches and migrations
└── stream/                       # Event streaming (Pekko actors)
    ├── SimulationEventStream.scala
    └── BridgeActor.scala
```

### airline-web Module (Play Framework)

```
airline-web/
├── app/
│   ├── controllers/              # REST API endpoints
│   │   ├── Application.scala    # Core game state, airports, cycle info
│   │   ├── AirlineApplication.scala  # Airline management, finances
│   │   ├── LinkApplication.scala     # Route planning, operations
│   │   ├── AirplaneApplication.scala # Fleet management
│   │   └── WebsocketApplication.scala # WebSocket setup
│   ├── models/                   # Web-specific models (minimal)
│   ├── views/                    # Twirl templates
│   └── websocket/
│       ├── MyWebSocketActor.scala    # Per-client WebSocket handler
│       └── ActorCenter.scala         # Central actor system coordinator
├── conf/
│   ├── routes                    # URL routing configuration
│   └── application.conf          # Play config, DB settings, Pekko config
└── public/                       # Static assets (JS, CSS, images)
    └── javascripts/              # Frontend JavaScript
```

## Key Data Models

Located in `airline-data/src/main/scala/com/patson/model/`

### Airport
- Properties: IATA code, location (lat/lon), size, population, income levels, slots
- Relationships: Cities served, airline bases, lounges, loyalty/appeal bonuses
- Cached in `AirportCache`

### Airline
- Properties: Balance, reputation, service quality, maintenance quality, airline type (PAX/LCC/REGIONAL)
- Income tracking: Three categories (links, transactions, others) with weekly/quarterly/yearly P&L
- Relationships: Bases, delegates, alliances, owned airports
- Cached in `AirlineCache`

### Link (Flight Route)
- Properties: From/to airports, airline, capacity by class, price by class, frequency, duration
- Airplane assignment: Links to assigned aircraft via `LinkAssignment`
- Quality calculation: Based on aircraft condition, model, airline service quality
- Costs: Fuel (exponential: distance^1.4), crew, airport fees, maintenance, depreciation

### PassengerGroup
- Types: BUSINESS, TOURIST, OLYMPICS, ELITE, TRAVELER
- Preferences: Price sensitivity, quality expectations, loyalty considerations
- Route finding: Multi-leg journeys with transit time considerations

### Airplane
- Properties: Model, condition (0-100), configuration (seat counts by class)
- Assignment: Linked to routes, can be on standby
- Maintenance: Condition degrades per cycle, requires periodic maintenance

## Data Access Patterns

All data sources in `airline-data/src/main/scala/com/patson/data/`

### Connection Management
```scala
val connection = Meta.getConnection()  // Get pooled connection
// Use prepared statements
connection.close()  // Return to pool
```

### Load Patterns
Many `*Source` objects support different load depths:
```scala
LinkSource.FULL_LOAD    // Load complete objects with all relationships
LinkSource.SIMPLE_LOAD  // Load basic data only for performance
LinkSource.ID_LOAD      // Load IDs only for references
```

### Caching Strategy
Heavily cached with TTL-based invalidation:
```scala
AirlineCache.getAirline(id)      // ~5 min TTL
AirportCache.getAirport(id)       // ~5 min TTL
AirplaneOwnershipCache           // Flight assignments
CountryCache, AllianceCache, UserCache
```

All caches invalidated at cycle start via `MainSimulation.invalidateCaches()`

## Configuration

### Database Configuration
- `airline-web/conf/application.conf` - Web app DB config
- `airline-data/src/main/scala/com/patson/data/Constants.scala` - Connection constants
- Docker: Environment variables in `docker-compose.yaml`

### Google APIs (Optional)
Set in `airline-web/conf/application.conf`:
- `google.mapKey` - Google Maps API key (required for maps)
- `google.apiKey` - Custom Search API (optional, for airport images)

For Gmail (password reset emails) and Google Photos (banners), see `airline-web/README`

### Pekko Remote Actors
Configured in `application.conf`:
- Simulation: `localhost:2552` (configured in `AirlineSimulation.scala`)
- Web: `localhost:2553` (configured in `application.conf`)

## Important Concepts

### Link Economics
Links calculate profitability each cycle:
- **Revenue**: Ticket sales by class (First/Business/Economy/Discount)
- **Fixed Costs**: Airport fees, crew salaries, depreciation
- **Variable Costs**: Fuel (distance^1.4 exponential), maintenance (condition-based)
- **Modifiers**: Lounge costs, alliance bonuses, negotiation discounts

### Passenger Demand
`DemandGenerator` computes demand between all airport pairs considering:
- Distance between airports (optimal range ~500-8000km)
- Population and income levels at both ends
- Country relationships (open skies, treaties, tensions)
- Affinity zones (geographic/cultural connections)
- Special events (Olympics, disasters)

### Route Finding Algorithm
`PassengerSimulation` uses multi-iteration consumption (9 cycles) with:
- Dijkstra-style pathfinding for multi-leg routes
- Cost function: Price + quality penalty + connection penalty
- Alliance code-sharing support
- Loyalty bonuses for airport champions
- Capacity constraints (seats fill progressively)

### Financial System
Airlines track three income categories:
1. **Links Income**: Flight operations P&L
2. **Transactions Income**: Capital gains, asset sales
3. **Others Income**: Loans, base upkeep, advertisements

Separate cash flow tracking for capital expenditures (airplanes, bases, facilities)

## Common Development Workflows

### Adding a New Simulation Feature
1. Add simulation logic to relevant `*Simulation.scala` file
2. Update database schema in `Constants.scala` if needed
3. Add/modify data source in `com.patson.data.*Source.scala`
4. Run migration via `MainInit` if schema changed
5. Add tests in `airline-data/src/test/scala`

### Adding a New API Endpoint
1. Add route to `airline-web/conf/routes`
2. Implement controller method in `airline-web/app/controllers/`
3. Define JSON serialization using Play JSON `Writes`
4. Query data via `*Source` objects from `airline-data`
5. Test via browser or curl

### Modifying Domain Models
1. Update model in `airline-data/src/main/scala/com/patson/model/`
2. Update corresponding `*Source` load/save methods
3. Update database schema in `Constants.scala` if needed
4. Clear affected caches or restart simulation
5. Update JSON `Writes` in web controllers if exposed via API

### Debugging Simulation Issues
1. Check logs from `MainSimulation` output
2. Query database directly for affected cycles
3. Review `link_consumption` and `link_statistics` tables
4. Check passenger flow in `passenger_history` table
5. Use `AirlineSimulation` financial logs for airline-specific issues

## Data Sources

- **Cities**: http://download.geonames.org/export/dump/cities500.zip
- **Airports/Runways**: https://ourairports.com/data/
- **GDP Data**: World Bank (http://databank.worldbank.org/)
- CSV files in `airline-data/`: airports.csv, additional-cities.csv, runways.csv, etc.

## Notes

- Simulation is single-threaded per cycle but uses parallel collections for performance within simulations
- Web server and simulation must both be running for real-time updates
- Elasticsearch required for flight search functionality (install 7.x)
- Character encoding must be UTF-8/UTF8MB4 for MySQL to handle unicode airport names
- SBT requires significant memory: Set `SBT_OPTS="-Xms2g -Xmx8g"` minimum
