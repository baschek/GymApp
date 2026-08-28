import Dexie, { type EntityTable } from "dexie";
import type {
  AppSetting,
  BodyWeightEntry,
  EquipmentProfile,
  Exercise,
  Milestone,
  WorkoutPlan,
  WorkoutSession
} from "../types";

class GymDatabase extends Dexie {
  plans!: EntityTable<WorkoutPlan, "id">;
  sessions!: EntityTable<WorkoutSession, "id">;
  bodyWeights!: EntityTable<BodyWeightEntry, "id">;
  customExercises!: EntityTable<Exercise, "id">;
  equipmentProfiles!: EntityTable<EquipmentProfile, "id">;
  milestones!: EntityTable<Milestone, "id">;
  settings!: EntityTable<AppSetting, "key">;

  constructor() {
    super("gymapp");
    this.version(1).stores({
      plans: "id, profileId, name, updatedAt",
      sessions: "id, profileId, status, startedAt, completedAt, planId",
      bodyWeights: "id, profileId, date",
      customExercises: "id, profileId, name",
      equipmentProfiles: "id, profileId, exerciseId",
      milestones: "id, profileId, occurredAt, sessionId, seen",
      settings: "key"
    });
    this.version(2)
      .stores({
        equipmentProfiles: "id, [profileId+gymId], profileId, gymId, exerciseId"
      })
      .upgrade(async (transaction) => {
        const table = transaction.table<EquipmentProfile, string>("equipmentProfiles");
        const existing = await table.toArray();
        if (!existing.length) return;
        await table.clear();
        await table.bulkPut(
          existing.map((profile) => ({
            ...profile,
            id: `${profile.profileId}:basic_fit:${profile.exerciseId}`,
            gymId: "basic_fit"
          }))
        );
      });
  }
}

export const db = new GymDatabase();

export type StoragePersistenceStatus = "persistent" | "best-effort" | "unsupported";

export function createId(prefix: string): string {
  return `${prefix}_${crypto.randomUUID()}`;
}

export async function getStoragePersistenceStatus(): Promise<StoragePersistenceStatus> {
  if (typeof navigator === "undefined" || !navigator.storage?.persisted) return "unsupported";
  try {
    return (await navigator.storage.persisted()) ? "persistent" : "best-effort";
  } catch {
    return "unsupported";
  }
}

export async function requestPersistentStorage(): Promise<boolean> {
  if (typeof navigator === "undefined" || !navigator.storage?.persist) return false;
  try {
    if (await navigator.storage.persisted?.()) return true;
    return await navigator.storage.persist();
  } catch {
    return false;
  }
}

export async function resetTestProfile(): Promise<void> {
  await db.transaction(
    "rw",
    [db.plans, db.sessions, db.bodyWeights, db.customExercises, db.equipmentProfiles, db.milestones],
    async () => {
      await Promise.all([
        db.plans.where("profileId").equals("test").delete(),
        db.sessions.where("profileId").equals("test").delete(),
        db.bodyWeights.where("profileId").equals("test").delete(),
        db.customExercises.where("profileId").equals("test").delete(),
        db.equipmentProfiles.where("profileId").equals("test").delete(),
        db.milestones.where("profileId").equals("test").delete()
      ]);
    }
  );
}
