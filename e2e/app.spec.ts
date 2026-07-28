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

test("shows the read-only gym packlist from the start screen", async ({ page }) => {
  await page.getByRole("button", { name: "Gym packlist" }).click();
  const dialog = page.getByRole("dialog", { name: "Gym packlist" });
  await expect(dialog).toBeVisible();
  for (const item of [
    "Headphones",
    "Towel",
    "Gym clothes",
    "Shoes",
    "Water bottle",
    "Lock"
  ]) {
    await expect(dialog.getByText(item, { exact: true })).toBeVisible();
  }
  await expect(dialog.getByRole("checkbox")).toHaveCount(0);
});

test("creates a plan and logs a typed working weight", async ({
  page,
  context
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
  const fromKg = page.getByLabel("From kg").first();
  await fromKg.fill("");
  await fromKg.fill("75,7");
  await expect(fromKg).toHaveValue("75,7");
  await page.getByRole("button", { name: "Configure later" }).click();

  await expect(page.getByText("Suggested set weight")).toBeVisible();
  await expect(page.locator(".set-weight-reference")).toContainText("20 kg");
  await page.getByRole("button", { name: "Start set" }).click();
  await context.grantPermissions(["notifications"]);
  await page
    .getByRole("button", { name: "Show timer in status notification" })
    .click();
  await expect(
    page.getByRole("button", { name: "Hide timer status notification" })
  ).toBeVisible();
  expect(
    await page.evaluate(() =>
      localStorage.getItem("gymapp-timer-notifications-enabled")
    )
  ).toBe("true");
  await expect(page.locator(".set-weight-reference")).toContainText("20 kg");
  await page.getByRole("button", { name: "Finish set" }).click();
  await page.getByLabel("Weight (kg)").fill("20");
  await page.getByLabel("Repetitions").fill("15");
  await page.getByRole("button", { name: "5+ / easy" }).click();
  await page.getByRole("button", { name: "Save set" }).click();
  await expect(page.getByText("Rest", { exact: true })).toBeVisible();
  await expect(page.locator(".logged-set").first()).toContainText("20 kg");
  await expect(page.locator(".logged-set").first()).toContainText("15");

  await page.getByRole("button", { name: "Note", exact: true }).click();
  await page
    .getByLabel("Note for this exercise")
    .fill("Seat position 4");
  await page.getByRole("button", { name: "Save note" }).click();
  await expect(page.getByText("Exercise note saved")).toBeVisible();
  const storedNotes = await page.evaluate(async () => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open("gymapp");
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const session = await new Promise<{
      notes: string;
      exercises: Array<{ notes: string }>;
    }>((resolve, reject) => {
      const request = database
        .transaction("sessions", "readonly")
        .objectStore("sessions")
        .getAll();
      request.onsuccess = () => resolve(request.result[0]);
      request.onerror = () => reject(request.error);
    });
    database.close();
    return {
      workout: session.notes,
      exercise: session.exercises[0].notes
    };
  });
  expect(storedNotes).toEqual({ workout: "", exercise: "Seat position 4" });
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

test("separates machine setup time from rest and restores it after reload", async ({
  page
}) => {
  await page.evaluate(async () => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open("gymapp");
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const transaction = database.transaction(
      ["sessions", "equipmentProfiles"],
      "readwrite"
    );
    const exercise = (id: string, name: string, order: number) => ({
      id: `session-${id}`,
      exerciseId: id,
      name,
      order,
      notes: "",
      measurementType: "load_reps",
      loadDirection: "higher",
      loadBasis: "stack",
      target: { metric: "reps", min: 8, max: 12, rirMin: 1, rirMax: 2 },
      restSeconds: 90,
      plannedSets: 1,
      suggestedWeightKg: 20,
      sets: [
        {
          id: `set-${id}`,
          order: 1,
          setType: "working",
          status: "pending"
        }
      ]
    });
    transaction.objectStore("sessions").put({
      id: "transition-session",
      profileId: "personal",
      gymId: "basic_fit",
      planName: "Transition Test",
      mode: "normal",
      status: "active",
      startedAt: new Date().toISOString(),
      currentExerciseIndex: 0,
      notes: "",
      exercises: [
        exercise("leg-press", "Leg Press", 1),
        exercise("chest-press", "Chest Press", 2)
      ]
    });
    for (const id of ["leg-press", "chest-press"]) {
      transaction.objectStore("equipmentProfiles").put({
        id: `personal:basic_fit:${id}`,
        profileId: "personal",
        gymId: "basic_fit",
        exerciseId: id,
        availableWeightsKg: [20, 22.5, 25],
        prompted: true,
        updatedAt: new Date().toISOString()
      });
    }
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
    });
    database.close();
  });
  await page.reload();
  await expect(page.locator(".workout-top h1")).toHaveText("Leg Press");
  const configureLater = page.getByRole("button", { name: "Configure later" });
  if (await configureLater.isVisible({ timeout: 1000 }).catch(() => false)) {
    await configureLater.click();
  }

  await page.getByRole("button", { name: "Start set" }).click();
  await page.getByRole("button", { name: "Finish set" }).click();
  await page.getByLabel("Weight (kg)").fill("20");
  await page.getByLabel("Repetitions").fill("8");
  await page.getByRole("button", { name: "1-2 left" }).click();
  await page.getByRole("button", { name: "Save set" }).click();

  await expect(page.locator(".workout-top h1")).toHaveText("Chest Press");
  if (await configureLater.isVisible({ timeout: 1000 }).catch(() => false)) {
    await configureLater.click();
  }
  await expect(page.getByText("Machine change & setup", { exact: true })).toBeVisible();
  await page.waitForTimeout(1100);
  await page.reload();
  await expect(page.getByText("Machine change & setup", { exact: true })).toBeVisible();
  await expect(page.locator(".elapsed-timer")).not.toContainText("0:00");

  await page.getByRole("button", { name: "Start set" }).click();
  const recorded = await page.evaluate(async () => {
    const database = await new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open("gymapp");
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const session = await new Promise<{
      exercises: Array<{
        sets: Array<{ restBeforeSeconds?: number; setupBeforeSeconds?: number }>;
      }>;
    }>((resolve, reject) => {
      const request = database
        .transaction("sessions", "readonly")
        .objectStore("sessions")
        .get("transition-session");
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    database.close();
    return session.exercises[1].sets[0];
  });
  expect(recorded.setupBeforeSeconds).toBeGreaterThanOrEqual(1);
  expect(recorded.restBeforeSeconds).toBeUndefined();
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
