import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  retries: 0,
  timeout: 60_000,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://127.0.0.1:4173/GymApp/",
    headless: true,
    browserName: "chromium",
    trace: "retain-on-failure"
  },
  webServer: {
    command: "npm run preview -- --host 127.0.0.1 --port 4173",
    url: "http://127.0.0.1:4173/GymApp/",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000
  },
  projects: [
    {
      name: "android",
      use: {
        ...devices["Pixel 7"]
      }
    },
    {
      name: "desktop",
      use: {
        viewport: { width: 1440, height: 900 }
      }
    }
  ]
});
