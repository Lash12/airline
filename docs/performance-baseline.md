# Performance Baseline Documentation

## Test Environment Specification
- **Hardware**: 4-core i5-6500T, 8GB RAM
- **OS**: Ubuntu 22.04 LTS
- **JVM Version**: OpenJDK 17
- **MySQL Version**: 8.0
- **Docker Version**: 20.10.12

## Current Baseline: OptiPlex `airline-dev` (2026-06-18)

Measured after adding the small-server supervisor entrypoint, automatic
`airline-data` `publishLocal`, Docker DB host defaults, and a 3 GB simulation heap.

- **Host**: `airline-dev` at `192.168.1.52`
- **Host memory**: 15,860 MB total, 10,357 MB available, 0 MB swap used
- **Container state**: `airline-app` healthy, `airline-db` running
- **Web health**: `/` returned HTTP 200 in 0.034 s; `/login/` returned HTTP 200 in 0.038 s
- **Current cycle endpoint**: `{"cycle":432}` during measurement
- **Active-cycle memory**: `airline-app` 4.424 GiB / 7 GiB, `airline-db` 446.3 MiB / 1.465 GiB
- **Active-cycle CPU**: `airline-app` 138.20%, `airline-db` 0.73%
- **Cycle 432 wall time**: 239 s
- **Cycle 432 phase timings**: caches 11.859 s, link 218.873 s, airport 5.135 s,
  airplane 1.222 s, airline 2.223 s, all other phases under 0.1 s except post-cycle work

The first attempted 1.5 GB simulation heap spent 95-99% of wall time in GC. The 3 GB heap
completed the cycle without that GC-thrashing pattern. LinkSimulation remains the clear
optimization target.

## LinkSimulation Follow-up

Cycle 432 spent 218.873 s in LinkSimulation, mostly inside passenger route finding. The
first optimization removes the unsafe mutable route cache from the parallel demand-chunk
loop and precomputes one route map per passenger group before chunk consumption. To split
the next measured run between route calculation and seat consumption, enable:

```bash
SIM_EXTRA_OPTS="-Dsolo.link.profile=true"
```

Look for `[link-profile]` log lines with `routePrecomputeMs`, `consumeMs`,
`passengerGroups`, `chunks`, and `availableLinks` per consumption pass.

## Current Measurement: Fresh DB / Lash Air JFK Scenario (2026-06-19)

Measured during a live fast-forward run after recreating Lash Air, adding the LAX-JFK
A350-900 route, and leaving a pending A350-900 delivery in progress.

- **Current cycle endpoint**: `{"cycle":1}` at the start of observation.
- **Active-cycle memory**: `airline-app` 5.15 GiB / 7 GiB, `airline-db` 597 MiB / 1.465 GiB.
- **Active-cycle CPU**: `airline-app` 378.91%, `airline-db` 0.42%.
- **Cycle 3 wall time**: 221 s; phase timings: caches 8.525 s, link 205.003 s,
  airport 5.285 s, airplane 1.141 s, airline 1.403 s.
- **Cycle 4 wall time**: 217 s; phase timings: caches 8.407 s, link 201.186 s,
  airport 5.194 s, airplane 1.129 s, airline 1.306 s.
- **Cycle 4 demand/link profile**: 1,970,629 generated demand chunks, pruned to
  1,347,701; 5,593 available links at loop 0; 128,730 consumption entries saved.
- **Cycle 4 route finding**: route precompute totaled 110.521 s across loops 0-9;
  seat consumption totaled 1.682 s. Route finding remains the dominant measured
  inner-loop cost, with additional LinkSimulation time in demand generation,
  tallying, DB writes, and table rotation.

## OptiPlex Deploy Check: Indexed Route Search WIP (2026-06-20)

Deployed the indexed route-search/concurrent route-cache WIP to `airline-dev` and
measured cycle 136. The stack was healthy and Playwright passed, but this first
cycle is not a confirmed performance win versus the prior 201 s LinkSimulation
sample.

- **Current cycle endpoint**: `{"cycle":136}` during observation.
- **Container memory/CPU during active sim**: `airline-app` 4.977 GiB / 7 GiB at
  339% CPU; `airline-db` 490.8 MiB / 1.465 GiB at 6% CPU.
- **Cycle 136 wall time**: 257 s; phase timings: caches 12.413 s, link 235.141 s,
  airport 5.556 s, airplane 1.211 s, airline 2.056 s.
- **Route-search profile**: per-pass index build was negligible (2-19 ms). The
  largest pass handled 1,015,614 chunks and 24,821 passenger groups with aggregate
  route compute of 155.332 s and pass wall time of 40.944 s.
- **Interpretation**: the index itself is cheap and the route work is parallelized,
  but the full LinkSimulation phase remains too slow. Treat this as a functional
  profiling checkpoint, not a completed optimization.

## OptiPlex Tuning Pass: Lazy Origin-Indexed Route Solve (2026-06-20)

After the 257 s checkpoint, route solving was changed to use the route-search index
by active origin airport. Instead of materializing passenger-specific
`LinkConsideration` objects for nearly every candidate link before shortest-path
search, the solver now creates them lazily only for outgoing edges from vertices it
actually reaches.

- **Current cycle endpoint**: `{"cycle":139}` during observation.
- **Cycle 139 wall time**: 176 s; phase timings: caches 12.187 s, link 153.546 s,
  airport 5.399 s, airplane 1.206 s, airline 2.193 s.
- **Route-search profile**: loop 0 route compute dropped to 37.051 s with pass
  wall time 10.693 s for 1,022,125 chunks and 24,542 passenger groups. Later
  loops stayed below 23.454 s aggregate route compute.
- **Result**: keep this tuning pass. It improves the deployed checkpoint from
  257 s to 176 s total cycle time, and LinkSimulation from 235.141 s to 153.546 s.

## Startup Measurement
- **Goal**: Measure time to first HTTP 200 response.
- **Command**:
  ```bash
  time curl -I http://localhost:9000
  ```

## Idle Memory Footprint
- **JVM Heap (Play Framework)**
  ```bash
  docker stats
  jstat -gc <container_id> 1000 10
  ```
- **MySQL Memory Usage**
  ```bash
  docker stats
  SHOW ENGINE INNODB STATUS;
  ```
- **Total Memory Footprint**
  ```bash
  free -m
  ```

## Load Test Procedure
- **Goal**: Simulate user traffic and measure response times.
- **Tool**: `wrk` (HTTP benchmarking tool)
- **Command**:
  ```bash
  wrk -t4 -c100 -d30s http://localhost:9000
  ```

## Key Metrics Table
| Metric           | Tool/Command                         | Target Value |
|------------------|--------------------------------------|--------------|
| Startup Time     | `time curl -I http://localhost:9000` | < 5s         |
| JVM Heap (Idle)  | `jstat -gc`                          | < 512MB      |
| MySQL Memory     | `SHOW ENGINE INNODB STATUS`          | < 256MB      |
| Response Time    | `wrk`                                | < 100ms avg  |
| Error Rate       | `wrk`                                | < 1%         |

## How to Reproduce
1. Start services:
   ```bash
   docker-compose up -d
   ```
2. Measure startup time.
3. Capture idle memory footprint.
4. Run load test and record metrics.
5. Repeat for consistency.
