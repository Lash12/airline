# HikariCP Pool Tuning Guide

The simulation/data process (`airline-data`) uses a single HikariCP connection pool,
configured in `airline-data/src/main/scala/com/patson/data/Meta.scala`. All settings are
read from the `hikari.*` config namespace via Typesafe Config
(`airline-data/src/main/resources/application.conf`), so they can be overridden in that
file or with JVM system properties (e.g. `-Dhikari.maxPoolSize=10`).

## Available settings

| Config key | Default | Hikari setting |
|---|---|---|
| `hikari.poolName` | `airline-data-pool` | Pool name for logging/monitoring |
| `hikari.maxPoolSize` | 20 (15 in bundled application.conf) | `maximumPoolSize` |
| `hikari.minimumIdle` | = maxPoolSize (Hikari default) | `minimumIdle` |
| `hikari.idleTimeout` | 300000 (5 min) | `idleTimeout` (ms) |
| `hikari.maxLifetime` | 3600000 (1 h) | `maxLifetime` (ms) |
| `hikari.connectionTimeout` | 10000 (90000 in bundled application.conf) | `connectionTimeout` (ms) |
| `hikari.leakDetectionThreshold` | 30000 | `leakDetectionThreshold` (ms) |

## Recommended profiles

### Local development
```hocon
hikari.maxPoolSize = 5
hikari.minimumIdle = 1
```

### Small server (see SMALL_SERVER.md / docker-compose.small.yaml)
```hocon
hikari.maxPoolSize = 10
hikari.minimumIdle = 2
hikari.connectionTimeout = 30000
```
Keeping `minimumIdle` low lets the pool shrink between simulation cycles, freeing
MySQL connections and memory on resource-constrained hosts. MySQL's default
`max_connections` is 151 — leave headroom for the web app's connections.

### Larger deployments
```hocon
hikari.maxPoolSize = 20
hikari.minimumIdle = 5
hikari.leakDetectionThreshold = 15000
```

Overrides can also be passed without editing files, e.g.:
```bash
sbt -Dhikari.maxPoolSize=10 -Dhikari.minimumIdle=2 "runMain com.patson.MainSimulation"
```

## Monitoring recommendations
1. Track pool metrics under the configured `hikari.poolName`
2. Alert on connection wait times exceeding 200 ms
3. Review leak-detection warnings in the logs (`leakDetectionThreshold`)
