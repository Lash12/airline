# Airport Page Mobile UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the airport page usable on mobile — wide tables scroll horizontally instead of collapsing, asset build/sell happens in a detail modal with a large image and readable button, money is abbreviated on small screens.

**Architecture:** Pure-JS money helper (unit-tested). A single reusable asset detail modal driven by a normalized descriptor object built in `renderAirportAssets`; row click opens it; existing build/sell handlers fire from the modal. Mobile CSS scoped to `#airportCanvas` overrides the global table-collapse rule with horizontal scroll. No backend changes.

**Tech Stack:** Scala Play templates (`.scala.html`), jQuery, plain CSS, Jest (unit), Playwright (e2e). No JS build step — files in `public/javascripts/` are served directly.

## Global Constraints

- Target mobile breakpoint: `max-width: 640px` (matches existing `mobile.css`).
- Reuse existing CSS tokens (`main.css` `:root` / `html[data-theme='dark']`); do not hardcode colors.
- Reuse existing modal pattern: open with `$('#id').fadeIn(200)`, close with `closeModal($modal)` (defined `public/javascripts/gadgets.js:693`); close button `<span class="close" onclick="closeModal($(this).closest('.modal'))">&times;</span>`.
- Do NOT modify the global `.table.data` mobile rule (`mobile.css` ~166-179) or the `#linksCanvas` opt-out — scope all new rules to `#airportCanvas`.
- Existing action handlers, call verbatim: `buildAirportAsset(airportId, assetType)` and `sellAirportAsset(airportId, assetId)` (`public/javascripts/airport.js`).
- No backend / API / data-model changes; no API version bump (no model JSON schema change).

---

### Task 1: `abbreviateMoney()` helper

**Files:**
- Modify: `public/javascripts/gadgets.js` (add function near `commaSeparateNumber`)
- Test: `e2e/../test/javascript/abbreviate-money.test.js` → create `airline-web/test/javascript/abbreviate-money.test.js`

**Interfaces:**
- Produces: `abbreviateMoney(value: number) -> string`. Returns a `$`-prefixed string: `< 1000` → `"$" + rounded` (e.g. `$950`); thousands → `$N.NK` trimming trailing `.0`; millions → `$N.NM`; billions → `$N.NB`. Negative values keep the sign (`-$1.2M`). Non-finite input → `"-"`.

- [ ] **Step 1: Write the failing test**

Create `airline-web/test/javascript/abbreviate-money.test.js`:

```javascript
const fs = require("fs");
const path = require("path");
const vm = require("vm");

// Load just the helper from gadgets.js into a sandbox (no jQuery/DOM needed).
const src = fs.readFileSync(path.join(__dirname, "../../public/javascripts/gadgets.js"), "utf8");
const match = src.match(/function abbreviateMoney[\s\S]*?\n}/);
if (!match) throw new Error("abbreviateMoney not found in gadgets.js");
const sandbox = {};
vm.runInNewContext(match[0] + "\nthis.abbreviateMoney = abbreviateMoney;", sandbox);
const { abbreviateMoney } = sandbox;

describe("abbreviateMoney", () => {
  test("sub-thousand shows whole dollars", () => {
    expect(abbreviateMoney(0)).toBe("$0");
    expect(abbreviateMoney(950)).toBe("$950");
    expect(abbreviateMoney(999)).toBe("$999");
  });
  test("thousands", () => {
    expect(abbreviateMoney(1000)).toBe("$1K");
    expect(abbreviateMoney(1500)).toBe("$1.5K");
    expect(abbreviateMoney(340000)).toBe("$340K");
  });
  test("millions", () => {
    expect(abbreviateMoney(1200000)).toBe("$1.2M");
    expect(abbreviateMoney(324000000)).toBe("$324M");
  });
  test("billions", () => {
    expect(abbreviateMoney(2500000000)).toBe("$2.5B");
  });
  test("negative keeps sign", () => {
    expect(abbreviateMoney(-1200000)).toBe("-$1.2M");
  });
  test("non-finite returns dash", () => {
    expect(abbreviateMoney(undefined)).toBe("-");
    expect(abbreviateMoney(NaN)).toBe("-");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd airline-web && npx jest abbreviate-money`
Expected: FAIL — "abbreviateMoney not found in gadgets.js".

- [ ] **Step 3: Add the implementation**

In `public/javascripts/gadgets.js`, immediately above `function commaSeparateNumber`, add:

```javascript
// Compact money for tight (mobile) layouts: $1.2M, $340K, $950. Full precision
// stays in detail panels/modals. Non-finite input renders "-".
function abbreviateMoney(value) {
	if (!Number.isFinite(value)) return "-";
	var sign = value < 0 ? "-" : "";
	var n = Math.abs(value);
	function trim(x) { return (Math.round(x * 10) / 10).toString(); }
	if (n >= 1e9) return sign + "$" + trim(n / 1e9) + "B";
	if (n >= 1e6) return sign + "$" + trim(n / 1e6) + "M";
	if (n >= 1e3) return sign + "$" + trim(n / 1e3) + "K";
	return sign + "$" + Math.round(n);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd airline-web && npx jest abbreviate-money`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add airline-web/public/javascripts/gadgets.js airline-web/test/javascript/abbreviate-money.test.js
git commit -m "feat(ui): add abbreviateMoney helper for compact mobile money"
```

---

### Task 2: Asset detail modal markup + generic opener

**Files:**
- Modify: `app/views/fragments/modals.scala.html` (add modal near other modals)
- Modify: `public/javascripts/airport.js` (add `openAssetDetailsModal`)
- Modify: `public/stylesheets/main.css` (modal image sizing)

**Interfaces:**
- Produces: `openAssetDetailsModal(descriptor)` where `descriptor` is:
  ```
  {
    name: string, image: string|null, benefit: string,
    boost: string, levelText: string,
    rows: [ { label: string, value: string } ],   // optional spec rows (cost, size, income, payback, upkeep, status...)
    reason: string|null,        // disabled reason; null = enabled
    actionLabel: string,        // e.g. "Build", "Upgrade to 2", "Sell ($120M)"
    actionFn: function|null     // called on click when enabled; modal closes after
  }
  ```
  Later tasks (Task 3) build descriptors and call this.

- [ ] **Step 1: Add the modal markup**

In `app/views/fragments/modals.scala.html`, after the `facilityModal` block (search `id="facilityModal"`), add:

```html
	<div id="airportAssetDetailsModal" class="modal">
	  <div class="modal-content" style="width: 460px; max-width: 92vw;">
	    <span class="close" onclick="closeModal($(this).closest('.modal'))">&times;</span>
	    <h4 class="modalHeader"><span id="assetModalName"></span></h4>
	    <div id="assetModalImageWrap" style="text-align:center; margin: 8px 0;">
	      <img id="assetModalImage" loading="lazy" alt="" style="max-width: 200px; max-height: 200px;">
	    </div>
	    <p id="assetModalBenefit" class="leading-snug" style="margin-bottom: 8px;"></p>
	    <div class="table" style="width:100%">
	      <div class="table-row"><div class="label" style="width:55%">Boost:</div><div class="value" id="assetModalBoost"></div></div>
	      <div class="table-row"><div class="label" style="width:55%">Level:</div><div class="value" id="assetModalLevel"></div></div>
	      <div id="assetModalRows"></div>
	    </div>
	    <div id="assetModalReason" class="warning" style="display:none; margin-top:8px;"></div>
	    <button id="assetModalActionButton" class="button" style="width:100%; margin-top:12px;"></button>
	  </div>
	</div>
```

- [ ] **Step 2: Add modal image styling**

In `public/stylesheets/main.css`, append (end of file is fine):

```css
/* Airport asset detail modal: show the asset art prominently (24px thumbnails in
   the tables are too small to read on mobile). */
#airportAssetDetailsModal #assetModalImage {
    width: auto;
    image-rendering: auto;
    border-radius: 6px;
}
#airportAssetDetailsModal #assetModalImageWrap:empty,
#airportAssetDetailsModal #assetModalImageWrap.hidden {
    display: none;
}
```

- [ ] **Step 3: Add the opener function**

In `public/javascripts/airport.js`, add near `renderAirportAssets`:

```javascript
// Generic renderer for the asset detail modal. Task 3 builds the descriptor.
function openAssetDetailsModal(d) {
	$('#assetModalName').text(d.name)
	var $imgWrap = $('#assetModalImageWrap')
	if (d.image) {
		$('#assetModalImage').attr('src', '/assets/images/airport-assets/' + d.image).attr('alt', d.name)
		$imgWrap.removeClass('hidden')
	} else {
		$imgWrap.addClass('hidden')
	}
	$('#assetModalBenefit').text(d.benefit || '')
	$('#assetModalBoost').text(d.boost || '-')
	$('#assetModalLevel').text(d.levelText || '-')

	var $rows = $('#assetModalRows').empty()
	;(d.rows || []).forEach(function(r) {
		$rows.append($('<div class="table-row"></div>')
			.append($('<div class="label" style="width:55%"></div>').text(r.label))
			.append($('<div class="value"></div>').text(r.value)))
	})

	var $btn = $('#assetModalActionButton').off('click')
	var $reason = $('#assetModalReason')
	$btn.text(d.actionLabel)
	if (d.reason) {
		$btn.addClass('disabled').prop('disabled', true)
		$reason.text(d.reason).show()
	} else {
		$btn.removeClass('disabled').prop('disabled', false)
		$reason.hide()
		$btn.on('click', function() {
			closeModal($('#airportAssetDetailsModal'))
			d.actionFn()
		})
	}
	$('#airportAssetDetailsModal').fadeIn(200)
}
```

- [ ] **Step 4: Compile templates + syntax check JS**

Run: `cd airline-web && sbt compile && node --check public/javascripts/airport.js`
Expected: `[success]` and no JS syntax error.

- [ ] **Step 5: Commit**

```bash
git add airline-web/app/views/fragments/modals.scala.html airline-web/public/javascripts/airport.js airline-web/public/stylesheets/main.css
git commit -m "feat(ui): add airport asset detail modal shell + opener"
```

---

### Task 3: Drive the modal from asset rows; remove inline buttons

**Files:**
- Modify: `public/javascripts/airport.js` — `renderAirportAssets` (currently lines ~267-341)

**Interfaces:**
- Consumes: `openAssetDetailsModal(descriptor)` (Task 2), `buildAirportAsset`, `sellAirportAsset`.
- Per-row data already in scope: built asset `{ id, label, image, level, maxLevel, status, remainingCycles, weeklyIncome, weeklyUpkeep, sellValue }`; catalog entry `{ assetType, label, image, boostType, benefit, sizeRequirement, nextLevelCost, constructionDuration, nextLevelUpkeep, nextLevelIncome, nextLevelNet, nextLevelPayback, ownedLevel, generatesIncome, meetsSize, canUpgrade }`; plus `data.hasBase`, `data.balance`.

- [ ] **Step 1: Replace the built-assets row builder**

In `renderAirportAssets`, replace the `$.each(data.assets, ...)` block (the loop that builds `$row` with the inline `Sell` button) with:

```javascript
		$.each(data.assets, function(i, asset) {
			var statusText = asset.status === 'ACTIVE' ? 'Active' : ('Building (' + asset.remainingCycles + ' cycles)')
			var $row = $('<div class="table-row clickable"></div>')
			var $assetName = $('<div class="cell" style="width:24%"></div>')
			if (asset.image) {
				$('<img loading="lazy" style="height:24px; vertical-align:middle; margin-right:6px;">')
					.attr('src', '/assets/images/airport-assets/' + asset.image).appendTo($assetName)
			}
			$assetName.append($('<span></span>').text(asset.label))
			$row.append($assetName)
			$row.append($('<div class="cell" style="width:16%; text-align:right;"></div>').text(asset.level + '/' + asset.maxLevel))
			$row.append($('<div class="cell" style="width:24%"></div>').text(statusText))
			$row.append($('<div class="cell" style="width:18%; text-align:right;"></div>').text(formatAssetMoney(asset.weeklyIncome)))
			$row.append($('<div class="cell" style="width:18%; text-align:right;"></div>').text(formatAssetMoney(asset.weeklyUpkeep)))
			$row.on('click', function() {
				openAssetDetailsModal({
					name: asset.label,
					image: asset.image,
					benefit: '',
					boost: '-',
					levelText: asset.level + ' / ' + asset.maxLevel,
					rows: [
						{ label: 'Status:', value: statusText },
						{ label: 'Weekly income:', value: '$' + asset.weeklyIncome.toLocaleString() },
						{ label: 'Weekly upkeep:', value: '$' + asset.weeklyUpkeep.toLocaleString() }
					],
					reason: null,
					actionLabel: 'Sell ($' + asset.sellValue.toLocaleString() + ')',
					actionFn: function() {
						if (confirm('Sell ' + asset.label + ' for $' + asset.sellValue.toLocaleString() + '?')) {
							sellAirportAsset(airportId, asset.id)
						}
					}
				})
			})
			$list.append($row)
		})
```

Note: the built-assets table header in `airport_canvas.scala.html` still has an
"Action" column. Update it in Step 3.

- [ ] **Step 2: Replace the catalog row builder**

Replace the `$.each(data.catalog, ...)` block (the loop building `$row` with the inline `Build`/`Upgrade` button and the `img title=` tooltip) with:

```javascript
	$.each(data.catalog, function(i, entry) {
		var payback = entry.nextLevelPayback ? (entry.nextLevelPayback + ' cycles') : 'n/a (boost only)'
		var $row = $('<div class="table-row clickable"></div>')
		var $name = $('<div class="cell" style="width:26%"></div>')
		if (entry.image) {
			$('<img loading="lazy" style="height:24px; vertical-align:middle; margin-right:6px;">')
				.attr('src', '/assets/images/airport-assets/' + entry.image).appendTo($name)
		}
		$name.append($('<span></span>').text(entry.label))
		$row.append($name)
		$row.append($('<div class="cell" style="width:24%"></div>').text(entry.boostType))
		$row.append($('<div class="cell" style="width:12%; text-align:right;"></div>').text(entry.sizeRequirement))
		$row.append($('<div class="cell" style="width:20%; text-align:right;"></div>').text(formatAssetMoney(entry.nextLevelCost)))
		$row.append($('<div class="cell" style="width:18%; text-align:right;"></div>').text(entry.constructionDuration))

		var affordable = data.balance >= entry.nextLevelCost
		var reason = null
		if (!data.hasBase) { reason = 'Build a base at this airport first.' }
		else if (!entry.canUpgrade) { reason = 'Already at max level.' }
		else if (!entry.meetsSize) { reason = 'Airport is too small for this asset.' }
		else if (!affordable) { reason = 'Not enough cash.' }
		var actionLabel = !entry.canUpgrade ? 'Max level'
			: (entry.ownedLevel === 0 ? 'Build' : ('Upgrade to ' + (entry.ownedLevel + 1)))

		var rows = [
			{ label: 'Size required:', value: String(entry.sizeRequirement) },
			{ label: 'Cost:', value: '$' + entry.nextLevelCost.toLocaleString() },
			{ label: 'Build time:', value: String(entry.constructionDuration) },
			{ label: 'Weekly upkeep:', value: '$' + entry.nextLevelUpkeep.toLocaleString() }
		]
		if (entry.generatesIncome) {
			rows.push({ label: 'Weekly income:', value: '$' + entry.nextLevelIncome.toLocaleString() })
			rows.push({ label: 'Weekly net:', value: '$' + entry.nextLevelNet.toLocaleString() })
			rows.push({ label: 'Payback:', value: payback })
		}

		$row.on('click', function() {
			openAssetDetailsModal({
				name: entry.label,
				image: entry.image,
				benefit: entry.benefit,
				boost: entry.boostType,
				levelText: entry.ownedLevel === 0 ? 'Not built' : ('Current level ' + entry.ownedLevel),
				rows: rows,
				reason: reason,
				actionLabel: actionLabel,
				actionFn: function() { buildAirportAsset(airportId, entry.assetType) }
			})
		})
		$cat.append($row)
	})
```

- [ ] **Step 3: Drop the now-empty "Action" header columns and rebalance**

In `app/views/fragments/airport_canvas.scala.html`, in `#airportDetailsAssetList`
header remove the `Action` cell and widen the remaining columns to match the row
builder (24 / 16 / 24 / 18 / 18):

```html
					  <div class="table-header">
						  <div class="cell" style="width: 24%">Asset</div>
						  <div class="cell" style="width: 16%; text-align: right;">Level</div>
						  <div class="cell" style="width: 24%">Status</div>
						  <div class="cell" style="width: 18%; text-align: right;">Weekly Income</div>
						  <div class="cell" style="width: 18%; text-align: right;">Weekly Upkeep</div>
					  </div>
```

In `#airportDetailsAssetCatalog` header remove the `Action` cell and match
(26 / 24 / 12 / 20 / 18):

```html
					  <div class="table-header">
						  <div class="cell" style="width: 26%">Asset</div>
						  <div class="cell" style="width: 24%">Boost</div>
						  <div class="cell" style="width: 12%; text-align: right;">Size Req</div>
						  <div class="cell" style="width: 20%; text-align: right;">Next Level Cost</div>
						  <div class="cell" style="width: 18%; text-align: right;">Build Time</div>
					  </div>
```

- [ ] **Step 4: Add the money formatter used by the rows (mobile-aware)**

This is Task 5's helper but the rows in Steps 1-2 reference it, so add a stub now
that Task 5 finalizes. In `public/javascripts/airport.js`, near the top, add:

```javascript
// Money for airport table cells: compact on phones, full on desktop.
function formatAssetMoney(value) {
	if (window.matchMedia && window.matchMedia('(max-width: 640px)').matches) {
		return abbreviateMoney(value)
	}
	return '$' + Number(value).toLocaleString()
}
```

- [ ] **Step 5: Compile + syntax check**

Run: `cd airline-web && sbt compile && node --check public/javascripts/airport.js`
Expected: `[success]`, no JS error.

- [ ] **Step 6: Commit**

```bash
git add airline-web/public/javascripts/airport.js airline-web/app/views/fragments/airport_canvas.scala.html
git commit -m "feat(ui): open asset detail modal from rows; move build/sell into modal"
```

---

### Task 4: Mobile horizontal-scroll for airport tables

**Files:**
- Modify: `public/stylesheets/mobile.css` (add scoped rules inside the existing `@media only screen and (max-width : 640px)` block)

**Interfaces:** none (CSS only).

- [ ] **Step 1: Add scoped horizontal-scroll rules**

Inside the existing `@media only screen and (max-width : 640px) { ... }` block in
`public/stylesheets/mobile.css`, add (place near the other `.table.data` rules):

```css
    /* Airport page: keep wide data tables as scrollable grids instead of letting
       the global rule collapse every cell to width:auto (which stacks values
       vertically and is unreadable). Scoped to #airportCanvas so other pages and
       the #linksCanvas opt-out are unaffected. */
    #airportCanvas .table.data {
        overflow-x: auto;
        -webkit-overflow-scrolling: touch;
        min-width: 0;
    }
    #airportCanvas .table.data .table-header,
    #airportCanvas .table.data .table-row {
        min-width: 560px;        /* force overflow so columns keep their proportions */
    }
    #airportCanvas .table.data .cell {
        width: auto;             /* the inline % widths on each cell still apply via flex-basis */
        white-space: nowrap;     /* override the global word-break stacking */
        word-break: normal;
        overflow-wrap: normal;
        font-size: 11px;
        padding-left: 4px;
        padding-right: 4px;
    }
    /* Bigger tap target for clickable asset rows + a subtle hint they open a modal. */
    #airportCanvas .table.data .table-row.clickable {
        min-height: 34px;
        cursor: pointer;
    }
```

Note: `.cell` already uses inline `style="width: N%"`; keeping that with
`min-width: 560px` on the row makes the row overflow horizontally while columns
keep their ratios. The `#airportCanvas .table.data .cell` selector is more
specific than the global `.table.data .cell`, so it wins without `!important`.

- [ ] **Step 2: Manual visual smoke (desktop emulation of mobile)**

Run (after deploy or local server): open the airport page at a 390px-wide
viewport in dev tools; confirm the asset list, catalog, and traffic-analytics
tables scroll sideways and values stay on one line rather than stacking.
(Automated check is Task 6.)

- [ ] **Step 3: Commit**

```bash
git add airline-web/public/stylesheets/mobile.css
git commit -m "feat(ui): horizontal-scroll airport tables on mobile (scoped)"
```

---

### Task 5: Finalize mobile money in airport tables

`formatAssetMoney` (added in Task 3 Step 4) already switches to `abbreviateMoney`
on mobile and is used by the asset list + catalog money cells. This task confirms
coverage and applies the same to the traffic-analytics money cells if any exist.

**Files:**
- Modify: `public/javascripts/airport.js` (traffic analytics renderer, only if it prints money)

- [ ] **Step 1: Check the traffic-analytics renderer for money cells**

Run: `cd airline-web && grep -n "toLocaleString\|commaSeparateNumber\|\\$'" public/javascripts/airport.js | sed -n '1,40p'`
Expected: review hits inside `renderAirportTrafficAnalytics` (~374-400). The
analytics table shows pax counts and percentages, not money — if so, no change
needed and this task is a no-op confirmation. If a money cell is found, wrap its
value in `formatAssetMoney(...)`.

- [ ] **Step 2: (If a change was made) syntax check**

Run: `cd airline-web && node --check public/javascripts/airport.js`
Expected: no error. If Step 1 found no money cells, skip to Step 3.

- [ ] **Step 3: Commit (only if changed)**

```bash
git add airline-web/public/javascripts/airport.js
git commit -m "feat(ui): abbreviate money in airport analytics on mobile"
```

If no change was needed, note it and move on (no empty commit).

---

### Task 6: Playwright mobile verification

**Files:**
- Create: `e2e/tests/airport-mobile.spec.ts`

**Interfaces:** none (test only). Reuses the account/HQ bootstrap pattern from
`e2e/tests/ui-polish-verify.spec.ts`.

- [ ] **Step 1: Write the spec**

Create `e2e/tests/airport-mobile.spec.ts`:

```typescript
import { expect, type Page, test } from "@playwright/test";
import * as path from "path";

const SHOTS = path.join(__dirname, "..", "shots", "airport-mobile");

async function bootstrap(page: Page) {
  const s = Date.now().toString(36).slice(-8);
  await page.goto("/login/", { waitUntil: "load" });
  await page.request.post("/signup/json", { data: { username:`am${s}`, email:`am${s}@example.test`, password:`pw${s}`, passwordConfirm:`pw${s}`, airlineName:`Airport M ${s.replace(/[0-9]/g,"a")}` }});
  await page.goto("/login/", { waitUntil: "load" });
  await page.evaluate(()=>{localStorage.setItem("sessionActive","true");localStorage.setItem("announcementAgreed","2026-02-25")});
  await page.request.post("/user-login", { headers: { Accept: "application/json" }});
  await page.goto("/map/", { waitUntil: "load" });
  await page.waitForFunction(()=> (window as any).activeAirline, { timeout:15000 });
  await page.evaluate(async () => {
    const a=(window as any).activeAirline; const id=a.id;
    const ajax=(o:any)=>new Promise((res,rej)=>(window as any).$.ajax({...o,success:res,error:(_x:any,_s:any,e:any)=>rej(e)}));
    if(!a.headquarterAirport){
      await ajax({type:"GET",url:`/airlines/${id}/profiles?airportId=3599`,dataType:"json"});
      await ajax({type:"PUT",url:`/airlines/${id}/profiles/0?airportId=3599`,contentType:"application/json; charset=utf-8",dataType:"json"});
      await (window as any).updateAirlineInfo(id);
      await ajax({type:"POST",url:`/airlines/${id}/tutorial?skipTutorial=true`,dataType:"json"});
    }
  });
  await page.waitForFunction(()=> (window as any).activeAirline?.headquarterAirport, { timeout:15000 });
}

test.use({ viewport: { width: 390, height: 844 } });

test("airport page mobile: tables scroll, asset modal opens", async ({ page }) => {
  test.setTimeout(60000);
  await bootstrap(page);
  await page.goto("/airport/3599", { waitUntil: "load" });
  await page.waitForSelector("#airportDetailsAssetCatalog .table-row", { timeout: 15000 });

  // Catalog table should overflow horizontally (scrollWidth > clientWidth), not stack.
  const overflow = await page.$eval("#airportDetailsAssetCatalog", el => el.scrollWidth > el.clientWidth + 4);
  expect(overflow).toBeTruthy();
  await page.screenshot({ path: path.join(SHOTS, "airport_assets_mobile.png") });

  // Tap first catalog row -> modal with image + readable action button.
  await page.locator("#airportDetailsAssetCatalog .table-row").first().click();
  await page.waitForSelector("#airportAssetDetailsModal", { state: "visible", timeout: 5000 });
  await expect(page.locator("#assetModalActionButton")).toBeVisible();
  await expect(page.locator("#assetModalImage")).toBeVisible();
  await page.screenshot({ path: path.join(SHOTS, "asset_modal_mobile.png") });
});
```

- [ ] **Step 2: Run against the live deployment**

Run: `cd e2e && BASE_URL="http://192.168.1.52:9000" npx playwright test airport-mobile.spec.ts --retries=0 --reporter=line`
Expected: 1 passed. (Requires the branch deployed first — see Execution note.)

- [ ] **Step 3: Inspect screenshots**

Open `e2e/shots/airport-mobile/airport_assets_mobile.png` and
`asset_modal_mobile.png`; confirm tables scroll (not stacked) and the modal shows
a large image + full-width action button.

- [ ] **Step 4: Commit**

```bash
git add e2e/tests/airport-mobile.spec.ts
git commit -m "test(e2e): airport mobile scroll + asset modal verification"
```

---

## Execution note

The Playwright check in Task 6 runs against the live OptiPlex server, so it must
run after the branch is pushed to `master` and the OptiPlex Deploy & Verify
workflow succeeds (pre-authorized). Earlier tasks (1-5) are verified locally via
Jest, `sbt compile`, and `node --check`. Deploy once after Task 5, then run Task 6.

## Self-Review

- **Spec coverage:** horizontal-scroll tables → Task 4; asset detail modal with
  image + info + action → Tasks 2-3; money abbreviation → Tasks 1, 3, 5; tap
  targets → Task 4 (rows) + Task 2 (modal button); whole-page scope → Task 4
  scopes to `#airportCanvas` (covers all its tables). No page-level nav (correctly
  out of scope).
- **Placeholder scan:** none — all steps carry real code/commands. Task 5 is an
  explicit conditional no-op confirmation, not a placeholder.
- **Type consistency:** `openAssetDetailsModal(descriptor)` shape defined in Task 2
  matches the objects built in Task 3; `formatAssetMoney` defined Task 3 Step 4,
  used by Task 3 rows + Task 5; `abbreviateMoney` defined Task 1, used by
  `formatAssetMoney`. Handlers `buildAirportAsset`/`sellAirportAsset` used with
  the existing signatures.
