# Observability: Caffeine Cache Metrics

## Overview
These changes enable monitoring of Caffeine caches using Micrometer metrics. All caches will expose:
- Load operations
- Eviction counts
- Hit rates
- Performance stats

## Setup Instructions

### 1. Add configuration
Add to your `application.conf`:
```hocon
include "caffeine-metrics.conf"
```

### 2. Enable module
Add to your `play.modules.enabled`:
```scala
com.airline.smallserver.commons.CacheMetricsModule
```

### 3. Verify metrics
Metrics will appear under:
```
http://localhost:9000/admin/cache-stats
```

```json
{
  "caches": {
    "session-cache": {
      "stats": {
        "hitCount": 1234,
        "missCount": 56,
        "loadSuccessCount": 28,
        "loadFailureCount": 0,
        "evictionCount": 5
      }
    }
  }
}
```

### 4. Prometheus Metrics
These metrics will automatically appear in Prometheus:
```
caffeine_cache_eviction_total{cache="session-cache"} 5
caffeine_cache_hit_ratio{cache="session-cache"} 0.95
caffeine_cache_loads_total{cache="session-cache"} 28
```

## Technical Details
- **.recordStats()**: Enables internal tracking in Caffeine cache
- **CaffeineStatsCounter**: Micrometer-implemented stats counter
- **auto-register**: True binds cache metrics to global registry

## Validation Steps
1. Access /admin/cache-stats endpoint
2. Check stats appear for all caches
3. Verify metrics appear in Prometheus
4. Monitor eviction counts during load testing
