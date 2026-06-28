# Test Architecture

Two layers of tests. No server required for the first; full stack required for the second.

---

## Layer 1 — Unit tests (CI, no server)

### Scala tests (`airline-data`)

Run in the `build` CI job. Cover simulation logic, cargo allocation, consultant advisor, and
route forecast service. Tests that need a DB get one from the CI MySQL service.

```bash
cd airline-data
sbt "testOnly com.patson.*"
# or the full suite:
sbt test
```

### JavaScript unit tests (`airline-web/test/javascript/`)

Run in the same `build` CI job via Jest. No browser, no server — pure JS logic.

```bash
cd airline-web
npm install      # one-time; installs jest
npm test
```

Files:
- `abbreviate-money.test.js` — number formatting
- `airline-switcher.test.js` — multi-airline switcher logic
- `route-planner.test.js` — forecast card rendering, candidateAircraft cards
- `websocket.test.js` — reconnect logic

### Playwright discovery (CI)

CI only runs `playwright test --list` — no browser, no server. This validates that every
spec file compiles and its tests are discoverable. Catches import errors and TypeScript
mistakes before they reach OptiPlex.

```bash
cd e2e
npm ci
npx playwright test --list
# or via npm script:
npm run test:list
```

---

## Layer 2 — Playwright end-to-end tests (OptiPlex deploy only)

Full browser tests that require a running game server. These run automatically on every push
to `master` via the **OptiPlex Deploy & Verify** workflow (`optiplex-deploy.yml`), after the
stack is deployed. They are **not** run in the normal `CI` workflow.

### Running locally

Requires the game stack running at `http://localhost:9000`.

```bash
# Start the stack (in the repo root):
docker compose -f docker-compose.small.yaml up -d

# Wait for it (check with):
curl -s http://localhost:9000/ | head -5

# Then run all e2e tests:
cd e2e
npm ci
npx playwright install chromium
npx playwright test

# Single file:
npx playwright test tests/airport-page.spec.ts

# Headed (visible browser) for debugging:
npx playwright test --headed tests/login-signup.spec.ts

# With BASE_URL override (non-default port):
BASE_URL=http://localhost:9000 npx playwright test
```

### Test files and coverage

| File | What it covers |
|------|---------------|
| `smoke.spec.ts` | Homepage loads, title matches |
| `authenticated-pages.spec.ts` | All primary SPA pages render; office shows consultant panel |
| `login-signup.spec.ts` | Login form renders; signup form toggle; full signup-to-game flow |
| `airport-page.spec.ts` | Airport canvas; cargo demand cards; cargo opportunities cards; asset modal (desktop) |
| `airport-mobile.spec.ts` | Same airport surfaces on mobile 390×844 viewport |
| `cargo-demand-panel.spec.ts` | `/airports/:id/cargo-demand` API: sorted, bounded response |
| `cargo-opportunities.spec.ts` | `renderCargoOpportunities()` JS function with mocked data (all card states) |
| `cargo-ui-validation.spec.ts` | Route planner cargo type switch; office cargo revenue row; asset section visible |
| `route-forecast.spec.ts` | Route forecast panel: mocked API → forecast card renders with correct values |
| `consultant.spec.ts` | `renderConsultantAdvice` reason chips, plan button IDs, expansion badge; `renderMarketOverview` |
| `ui-polish-verify.spec.ts` | Aircraft market cargo column; model detail cargo line; office income sheet |
| `aircraft-delivery.spec.ts` | Delivery countdown text follows current cycle (mocked API) |

### Adding a test

1. Create `e2e/tests/my-feature.spec.ts`.
2. Use the shared `bootstrap()` pattern (signup → login → set HQ at LAX 3599 → skip tutorial)
   from any existing spec as a template.
3. Use stable selectors — element IDs preferred, `data-testid` as fallback, text/copy selectors
   only as a last resort.
4. Mock external endpoints with `page.route()` when testing rendering in isolation.
5. Keep `test.setTimeout` at 60 000 ms or lower; most flows finish in 10–30 s.

---

## CI / OptiPlex split summary

| Test type | Where it runs | Why |
|-----------|--------------|-----|
| Scala unit tests | CI (ubuntu-latest) | No browser or full DB needed |
| JS unit tests (Jest) | CI (ubuntu-latest) | Pure logic, no server |
| Playwright discovery | CI (ubuntu-latest) | Catches compile/import errors cheaply |
| Playwright full suite | OptiPlex self-hosted | Needs a real game server + MySQL |

The CI job intentionally does **not** install Chromium or run browser tests. This keeps CI
fast (< 5 min) while the OptiPlex runner handles the full regression suite after every deploy.
