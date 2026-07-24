import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.goto("./#/workouts");
  await page.evaluate(async () => {
    localStorage.clear();
    await new Promise<void>((resolve) => {
      const request = indexedDB.deleteDatabase("gymapp");
      request.onsuccess = () => resolve();
      request.onerror = () => resolve();
      request.onblocked = () => resolve();
    });
  });
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Workouts", exact: true })
  ).toBeVisible();
});

test("creates a profile-owned exercise without overflow", async ({
  page
}, testInfo) => {
  await page.getByRole("button", { name: "Exercises" }).click();
  await expect(
    page.getByRole("heading", { name: "My exercises" })
  ).toBeVisible();
  await expect(page.getByText("No exercises configured")).toBeVisible();
  await page.getByRole("button", { name: "New", exact: true }).click();
  await page.getByLabel("Name").fill("Beta Chest Press");
  await page.getByLabel("Equipment").fill("Chest press machine");
  await page
    .getByLabel("Primary muscles, comma-separated")
    .fill("Chest, Triceps");
  await page
    .getByLabel("Instructions, one step per line")
    .fill("Set the seat\nPress under control");
  await page.getByRole("button", { name: "Create exercise" }).click();
  const exerciseCard = page
    .locator(".exercise-card")
    .filter({ hasText: "Beta Chest Press" });
  await expect(exerciseCard).toBeVisible();
  await exerciseCard.click();
  await expect(
    page.getByRole("dialog", { name: "Beta Chest Press" })
  ).toBeVisible();
  await expect(page.getByText("Set the seat")).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth
    )
  ).toBe(true);
  await page.screenshot({
    path: testInfo.outputPath("personal-exercise.png"),
    fullPage: true
  });
});

test("creates a plan and logs a typed working weight", async ({
  page
}, testInfo) => {
  await page.getByRole("button", { name: "New" }).click();
  await expect(
    page.getByRole("heading", { name: "Edit workout" })
  ).toBeVisible();
  await page.getByLabel("Workout name").fill("Browser Test Plan");
  await page.getByRole("button", { name: "Add exercise" }).click();
  await page.getByRole("button", { name: "Define new exercise" }).click();
  await page.getByLabel("Name", { exact: true }).fill("Leg Press");
  await page.getByLabel("Equipment").fill("Leg press machine");
  await page.getByRole("button", { name: "Create exercise" }).click();
  await page.getByLabel("Starting weight (kg)").fill("20");
  await page.getByRole("button", { name: "Save" }).click();
  await page.getByRole("button", { name: "Back to workouts" }).click();
  await page
    .getByRole("button", { name: /^Browser Test Plan 1 exercise/ })
    .click();
  await page.getByRole("button", { name: "Start Normal" }).click();
  await expect(page.locator(".workout-top h1")).toHaveText("Leg Press");
  await page.getByRole("button", { name: "Configure later" }).click();

  await expect(page.getByText("Suggested set weight")).toBeVisible();
  await expect(page.locator(".set-weight-reference")).toContainText("20 kg");
  await page.getByRole("button", { name: "Start set" }).click();
  await expect(page.locator(".set-weight-reference")).toContainText("20 kg");
  await page.getByRole("button", { name: "Finish set" }).click();
  await page.getByLabel("Weight (kg)").fill("20");
  await page.getByLabel("Repetitions").fill("15");
  await page.getByRole("button", { name: "4+ / easy" }).click();
  await page.getByRole("button", { name: "Save set" }).click();
  await expect(page.getByText("Rest", { exact: true })).toBeVisible();
  await expect(page.locator(".logged-set").first()).toContainText("20 kg");
  await expect(page.locator(".logged-set").first()).toContainText("15");

  await page.getByRole("button", { name: "Note", exact: true }).click();
  await page
    .getByLabel("Note for this workout")
    .fill("Beta workout note");
  await page.getByRole("button", { name: "Save note" }).click();
  await expect(page.getByText("Workout note saved")).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth
    )
  ).toBe(true);
  await page.screenshot({
    path: testInfo.outputPath("active-workout.png"),
    fullPage: true
  });
});

test("switches to an isolated Test profile", async ({ page }) => {
  await page.getByRole("button", { name: "Settings" }).click();
  await page.getByRole("button", { name: "Test", exact: true }).click();
  await expect(page.getByText("TEST PROFILE", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Workouts" }).click();
  await expect(page.getByText("No saved workouts")).toBeVisible();
});

test("cold-launches the installed app while offline", async ({
  page,
  context
}) => {
  test.setTimeout(90_000);
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await page.reload();
  await page.waitForFunction(() => Boolean(navigator.serviceWorker.controller));

  await context.setOffline(true);
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Workouts", exact: true })
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Exercises" })).toBeVisible();
});
