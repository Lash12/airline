import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  timeout: 60_000,
  retries: 1, // enables trace-on-retry usefulness
  use: {
    baseURL: "http://localhost:9000",
    headless: true,
    screenshot: "only-on-failure",
    trace: "on-first-retry",
    video: "retain-on-failure",
  },
  reporter: [["html", { open: "never" }], ["list"]],
  outputDir: "test-results",
});
