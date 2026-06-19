import { expect, type Page, test } from "@playwright/test";

const pages = [
  { path: "/map/", canvas: "#worldMapCanvas" },
  { path: "/flights/", canvas: "#linksCanvas" },
  { path: "/hangar/", canvas: "#airplaneCanvas" },
  { path: "/office/", canvas: "#officeCanvas" },
  { path: "/bank/", canvas: "#bankCanvas" },
  { path: "/oil/", canvas: "#oilCanvas" },
  { path: "/alliance/", canvas: "#allianceCanvas" },
  { path: "/country/", canvas: "#countryCanvas" },
  { path: "/olympics/", canvas: "#eventCanvas" },
  { path: "/search/", canvas: "#searchCanvas" },
];

async function createAccount(page: Page) {
  const suffix = Date.now().toString(36).slice(-8);
  const username = `e2e${suffix}`;
  const password = `pw${suffix}`;
  const airlineName = `Etest ${suffix.replace(/[0-9]/g, "a")} Pages`;

  await page.goto("/login/", { waitUntil: "domcontentloaded" });
  const signup = await page.request.post("/signup/json", {
    data: {
      username,
      email: `${username}@example.test`,
      password,
      passwordConfirm: password,
      airlineName,
    },
  });
  expect(signup.ok(), await signup.text()).toBeTruthy();
}

test("authenticated primary pages render expected panels", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));

  await createAccount(page);

  for (const item of pages) {
    errors.length = 0;
    await page.goto(item.path, { waitUntil: "domcontentloaded" });
    await expect(page.locator(item.canvas), `${item.path} canvas`).toBeVisible();

    if (item.path === "/office/") {
      await expect(page.locator("#consultantStatus")).toBeVisible();
      await expect(page.getByRole("heading", { name: "Route Consultant" })).toBeVisible();
    }

    expect(errors, `${item.path} page errors`).toEqual([]);
  }
});
