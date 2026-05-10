# Small-Server Performance Plan

## 1. Baseline Benchmark
- **Goal**: Document current resource usage before optimizations.
- **Validation Criteria**: Reproducible measurements of CPU, memory, disk I/O, and startup times.
- **Homelab Relevance**: Provides a starting point for optimizations targeting small hardware.

## 2. Small-Server Docker Compose
- **Goal**: Create a single-file `docker-compose.yaml` optimized for 8GB RAM.
- **Validation Criteria**: Services start and run without OOM (Out of Memory) errors.
- **Homelab Relevance**: Ensures the system runs within resource limits.

## 3. Hikari Connection Pool Tuning
- **Goal**: Right-size the DB connection pool for low-concurrency environments.
- **Validation Criteria**: Reduced memory footprint with no degradation in query performance.
- **Homelab Relevance**: Prevents excessive database connections and memory usage.

## 4. Cache Request Metrics
- **Goal**: Add counters to track cache hits and misses.
- **Validation Criteria**: Metrics show cache efficiency improvements.
- **Homelab Relevance**: Helps identify ineffective caching strategies.

## 5. Targeted Cache Invalidation
- **Goal**: Replace broad cache invalidations with precise ones.
- **Validation Criteria**: Reduced cache churn and improved hit rates.
- **Homelab Relevance**: Minimizes unnecessary recomputation and memory overhead.

## 6. Frontend Asset Budget
- **Goal**: Compress and defer JS/CSS to reduce page load times.
- **Validation Criteria**: Faster page loads (measured via `curl` or browser dev tools).
- **Homelab Relevance**: Reduces bandwidth and client-side processing on slow networks.

## 7. Database Index Audit
- **Goal**: Add indexes only where EXPLAIN shows performance gains.
- **Validation Criteria**: Improved query performance without unnecessary indexes.
- **Homelab Relevance**: Avoids index bloat and storage overhead.

## 8. Integration Test
- **Goal**: End-to-end smoke test on target hardware.
- **Validation Criteria**: All features work as expected under load.
- **Homelab Relevance**: Ensures the system performs well in production-like conditions.