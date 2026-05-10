# Performance Baseline Documentation

## Test Environment Specification
- **Hardware**: 4-core i5-6500T, 8GB RAM
- **OS**: Ubuntu 22.04 LTS
- **JVM Version**: OpenJDK 17
- **MySQL Version**: 8.0
- **Docker Version**: 20.10.12

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