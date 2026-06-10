# OptiPlex CI/CD Plan — Automated Deploy + Test on Proxmox

Handoff plan for implementing upstream-style automated deployment with verification.
Upstream (myflyclub/airline) deploys by SCP/SSH from GitHub-hosted runners to their
servers on every master push, with no post-deploy testing. We invert the connection
(self-hosted runner on the OptiPlex polls GitHub outbound — no exposed ports) and add
verification: every deploy must boot the game and pass the Playwright suite.

## What already exists (do not rebuild)

| Piece | Where |
|---|---|
| Single-player compose profile (mem limits, tuned MySQL, no ES) | `docker-compose.small.yaml`, `.docker/db/small.cnf` |
| Deploy/run scripts (init, sim, web) | `.docker/data/init.sh`, `.docker/data/start.sh`, `.docker/web/start.sh` |
| Setup guide | `SMALL_SERVER.md` |
| Playwright e2e scaffold (homepage smoke test, baseURL :9000) | `e2e/` |
| Compile CI on GitHub-hosted runners | `.github/workflows/ci.yml` |
| Pause-when-idle flag (off by default) | `simulation.pauseWhenIdle` in `airline-data/src/main/resources/application.conf` |
| Per-cycle phase timing logs | `MainSimulation.startCycle` (`>>>>> cycle N phase timings:`) |
| Disabled legacy deploy (manual-only) | `.github/workflows/production-deploy.yml` |

## Part 1 — Proxmox guest (human / Cowork with Proxmox access)

The OptiPlex 3050 Micro runs Proxmox. Provision ONE dedicated guest that hosts both
the GitHub Actions runner and the Docker stack.

**LXC vs VM decision:**
- **Stock 8 GB host RAM → LXC (recommended).** A VM reserves RAM and burns ~0.5–1 GB
  on a guest kernel that an 8 GB box cannot spare. Container config: Ubuntu 24.04
  template, unprivileged, `features: nesting=1,keyctl=1`, rootfs on ext4-backed
  storage (Docker overlay2 on ZFS-backed LXC rootfs is troublesome), 4 vCPU,
  6144 MB RAM, 1024 MB swap, 64 GB disk.
- **If host RAM is upgraded to 16 GB → VM.** Cleaner Docker support and kernel-level
  isolation for the runner. Ubuntu 24.04 cloud image, 4 vCPU, 8192 MB RAM (no
  ballooning), 64 GB virtio disk, qemu-guest-agent.

Either way: DHCP reservation or static IP; `apt install docker.io docker-compose-v2 git curl`
(or Docker's official repo); add the runner user to the `docker` group.

**Memory budget inside a 6 GB guest** (8 GB host case): MySQL container limit 1.5 G,
app container limit 3.5 G → reduce to **3 G** by trimming `.docker/data/start.sh`
to `-Xmx1280M` if OOM appears; runner service ~150 MB; OS the rest. With
`simulation.pauseWhenIdle=true` the steady-state idle load is near zero.

## Part 2 — Runner registration (human, ~15 min)

1. GitHub repo → Settings → Actions → Runners → **New self-hosted runner** → Linux x64.
   Run the three printed commands inside the guest as a non-root user.
   During `./config.sh`, add label **`optiplex`**.
2. Install as a service: `sudo ./svc.sh install && sudo ./svc.sh start`.
3. **Security hardening (required — public repo + self-hosted runner):**
   - Settings → Actions → General → Fork pull request workflows: **require approval
     for ALL outside collaborators** (or make the repo private).
   - The deploy workflow below triggers only on `workflow_dispatch` / `push: master`
     — NEVER add `pull_request` to a self-hosted job.
4. Playwright needs browser deps once: `npx playwright install --with-deps chromium`
   inside the guest (or let the workflow's install step handle it on first run).

## Part 3 — Repo changes (Claude Cowork implements)

### 3.1 `scripts/optiplex-deploy.sh` (new, idempotent)
```
#!/bin/sh -e
# 1. docker compose -f docker-compose.small.yaml up -d --build
# 2. Wait for MySQL healthy (mysqladmin ping loop, ~60s timeout)
# 3. DB init guard: run init-data.sh ONLY if the cycle table is missing:
#      docker exec airline-db mysql -u<user> -p<pass> airline -e "SELECT 1 FROM cycle LIMIT 1"
#    (first init loads world data and can take 15-30 min — log progress)
# 4. Restart sim and web: docker exec -d airline-app sh /home/airline/start-data.sh
#                         docker exec -d airline-app sh /home/airline/start-web.sh
#    (kill previous sbt processes inside the container first: pkill -f MainSimulation, pkill -f "sbt run")
# 5. Wait for http://localhost:9000 to return HTTP 200 (timeout ~10 min on first
#    compile inside container; subsequent runs much faster from cached target/)
```
Credentials come from the compose file (already there); no new secrets needed.

### 3.2 Enable pause-when-idle on the box without forking config
Add an env passthrough to `.docker/data/start.sh`:
```
SBT_OPTS="-Xmx1536M ... $SIM_EXTRA_OPTS" sbt "runMain com.patson.MainSimulation"
```
and have the deploy workflow export
`SIM_EXTRA_OPTS="-Dsimulation.pauseWhenIdle=true"` into the container environment
(compose `environment:` entry or `docker exec -e`). Typesafe Config reads `-D`
system properties, so no conf file edits are needed and upstream defaults stay.

### 3.3 `.github/workflows/optiplex-deploy.yml` (new)
```
name: OptiPlex Deploy & Verify
on:
  workflow_dispatch:        # flip to push: branches: [master] AFTER first green run
concurrency:
  group: optiplex-deploy    # never overlap deploys
  cancel-in-progress: false
jobs:
  deploy:
    runs-on: [self-hosted, optiplex]
    timeout-minutes: 60     # first DB init may need a bump or pre-seeding manually
    steps:
      - checkout
      - run scripts/optiplex-deploy.sh
      - npm --prefix e2e ci && npx playwright install chromium
      - npm --prefix e2e test          # against live http://localhost:9000
      - if failure: upload e2e/test-results as artifact
      - job summary: docker stats --no-stream; last ">>>>> cycle" phase-timing
        lines from the sim log (docker exec airline-app ... or docker logs)
```
Result: every master merge deploys to the OptiPlex and proves the game serves —
upstream's flow plus verification.

### 3.4 Follow-ups after first green dispatch
1. One-line PR flipping the trigger to `push: branches: [master]` (keep dispatch too).
2. Extend `e2e/tests/` beyond the homepage smoke test: login page renders, airport
   search API responds, websocket connects (roadmap Phase 6).
3. Record the first baseline into `docs/performance-baseline.md` from the job
   summary (container RSS, cycle phase timings, page-load time).

## Acceptance criteria
- [ ] Guest survives reboot: runner service auto-starts, containers restart
      (`restart: unless-stopped` may need adding to docker-compose.small.yaml)
- [ ] `workflow_dispatch` run from a clean guest: deploy completes, e2e passes
- [ ] Second dispatch is fast (no re-init, cached sbt target) and idempotent
- [ ] With no players, sim logs show "Simulation paused" and guest CPU is near idle
- [ ] Fork-PR approval requirement verified in repo Actions settings

## Ops crib sheet
- Manual redeploy: Actions → OptiPlex Deploy & Verify → Run workflow
- Logs: `docker exec airline-app tmux ls` is NOT used here (unlike upstream) —
  use `docker logs airline-app` and the sbt session output files
- Full reset: `docker compose -f docker-compose.small.yaml down -v` (drops DB) then dispatch
- Slow-query log for the index audit: inside airline-db, `performance: see .docker/db/small.cnf`
