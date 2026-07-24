import { z } from "zod";
import type { BackupPayload } from "../types";
import { db } from "./db";

const backupSchema = z.object({
  format: z.literal("gymapp-backup"),
  formatVersion: z.literal(1),
  exportedAt: z.string(),
  appVersion: z.string(),
  profile: z.literal("personal"),
  plans: z.array(z.any()),
  sessions: z.array(z.any()),
  bodyWeights: z.array(z.any()),
  customExercises: z.array(z.any()),
  equipmentProfiles: z.array(z.any()),
  milestones: z.array(z.any())
});

export async function createPersonalBackup(): Promise<BackupPayload> {
  const [plans, sessions, bodyWeights, customExercises, equipmentProfiles, milestones] = await Promise.all([
    db.plans.where("profileId").equals("personal").toArray(),
    db.sessions.where("profileId").equals("personal").toArray(),
    db.bodyWeights.where("profileId").equals("personal").toArray(),
    db.customExercises.where("profileId").equals("personal").toArray(),
    db.equipmentProfiles.where("profileId").equals("personal").toArray(),
    db.milestones.where("profileId").equals("personal").toArray()
  ]);
  return {
    format: "gymapp-backup",
    formatVersion: 1,
    exportedAt: new Date().toISOString(),
    appVersion: __APP_VERSION__,
    profile: "personal",
    plans,
    sessions,
    bodyWeights,
    customExercises,
    equipmentProfiles,
    milestones
  };
}

export function parseBackup(content: string): BackupPayload {
  return backupSchema.parse(JSON.parse(content)) as BackupPayload;
}

export function backupFile(payload: BackupPayload): File {
  const date = payload.exportedAt.slice(0, 10);
  return new File([JSON.stringify(payload, null, 2)], `gymapp-backup-${date}.json`, {
    type: "application/json"
  });
}

export async function shareOrDownloadBackup(file: File): Promise<"shared" | "downloaded"> {
  if (navigator.canShare?.({ files: [file] })) {
    await navigator.share({ files: [file], title: "GymApp backup" });
    return "shared";
  }
  const url = URL.createObjectURL(file);
  const link = document.createElement("a");
  link.href = url;
  link.download = file.name;
  link.click();
  URL.revokeObjectURL(url);
  return "downloaded";
}

export async function replacePersonalData(payload: BackupPayload): Promise<void> {
  await db.transaction(
    "rw",
    [db.plans, db.sessions, db.bodyWeights, db.customExercises, db.equipmentProfiles, db.milestones],
    async () => {
      await Promise.all([
        db.plans.where("profileId").equals("personal").delete(),
        db.sessions.where("profileId").equals("personal").delete(),
        db.bodyWeights.where("profileId").equals("personal").delete(),
        db.customExercises.where("profileId").equals("personal").delete(),
        db.equipmentProfiles.where("profileId").equals("personal").delete(),
        db.milestones.where("profileId").equals("personal").delete()
      ]);
      await Promise.all([
        db.plans.bulkPut(payload.plans),
        db.sessions.bulkPut(payload.sessions),
        db.bodyWeights.bulkPut(payload.bodyWeights),
        db.customExercises.bulkPut(payload.customExercises),
        db.equipmentProfiles.bulkPut(
          payload.equipmentProfiles.map((profile) => ({
            ...profile,
            id: `${profile.profileId}:${profile.gymId ?? "basic_fit"}:${profile.exerciseId}`,
            gymId: profile.gymId ?? "basic_fit"
          }))
        ),
        db.milestones.bulkPut(payload.milestones)
      ]);
    }
  );
}
