import { afterEach, describe, expect, it } from "vitest";
import type { WorkoutPlan } from "../types";
import { createPersonalBackup } from "./backup";
import { db, resetTestProfile } from "./db";

function plan(id: string, profileId: "personal" | "test"): WorkoutPlan {
  const date = new Date(0).toISOString();
  return {
    id,
    profileId,
    name: `${profileId} plan`,
    notes: "",
    createdAt: date,
    updatedAt: date,
    exercises: []
  };
}

afterEach(async () => {
  await db.delete();
  await db.open();
});

describe("profile isolation", () => {
  it("resets Test data without touching Personal data", async () => {
    await db.plans.bulkAdd([plan("personal-plan", "personal"), plan("test-plan", "test")]);
    await resetTestProfile();
    expect(await db.plans.get("personal-plan")).toBeDefined();
    expect(await db.plans.get("test-plan")).toBeUndefined();
  });

  it("excludes Test data from full backups", async () => {
    await db.plans.bulkAdd([plan("personal-plan", "personal"), plan("test-plan", "test")]);
    const payload = await createPersonalBackup();
    expect(payload.profile).toBe("personal");
    expect(payload.plans.map((item) => item.id)).toEqual(["personal-plan"]);
  });
});
