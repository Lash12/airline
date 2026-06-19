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
