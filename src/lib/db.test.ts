import { afterEach, describe, expect, it, vi } from "vitest";
import type { WorkoutPlan } from "../types";
import { createPersonalBackup } from "./backup";
import {
  db,
  getStoragePersistenceStatus,
  requestPersistentStorage,
  resetTestProfile
} from "./db";

const originalStorage = Object.getOwnPropertyDescriptor(navigator, "storage");

function mockStorage(storage: Partial<StorageManager>) {
  Object.defineProperty(navigator, "storage", {
    configurable: true,
    value: storage as StorageManager
  });
}

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
  if (originalStorage) Object.defineProperty(navigator, "storage", originalStorage);
  else Reflect.deleteProperty(navigator, "storage");
  await db.delete();
  await db.open();
});

describe("storage persistence", () => {
  it("reports when browser storage is persistent", async () => {
    mockStorage({ persisted: vi.fn().mockResolvedValue(true) });
    await expect(getStoragePersistenceStatus()).resolves.toBe("persistent");
  });

  it("requests persistence after confirming it is not already granted", async () => {
    const persist = vi.fn().mockResolvedValue(true);
    mockStorage({ persisted: vi.fn().mockResolvedValue(false), persist });
    await expect(requestPersistentStorage()).resolves.toBe(true);
    expect(persist).toHaveBeenCalledOnce();
  });

  it("handles unavailable or rejected persistence requests safely", async () => {
    mockStorage({ persisted: vi.fn().mockRejectedValue(new Error("blocked")) });
    await expect(getStoragePersistenceStatus()).resolves.toBe("unsupported");
    await expect(requestPersistentStorage()).resolves.toBe(false);
  });
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
