const { defineConfig } = require("@playwright/test");

module.exports = defineConfig({
  testDir: "src/test/e2e",
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  workers: process.env.CI ? 1 : undefined,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["line"], ["html", { open: "never" }]] : "line",
  use: {
    baseURL: process.env.PLAYWRIGHT_TEST_BASE_URL || "http://127.0.0.1:8081",
    trace: "retain-on-failure",
    screenshot: "only-on-failure"
  },
  projects: [
    { name: "chromium", use: { browserName: "chromium", viewport: { width: 1280, height: 900 } } },
    { name: "webkit", use: { browserName: "webkit", viewport: { width: 1280, height: 900 } } }
  ]
});
