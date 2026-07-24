import type { Milestone, WorkoutPlan } from "../types";
import { createId, db } from "./db";
import { provenWorkingWeight } from "./progression";

export async function recalculatePlanProgression(planId: string): Promise<void> {
  const plan = await db.plans.get(planId);
  if (!plan) return;
  const sessions = (await db.sessions.where("planId").equals(planId).toArray())
    .filter((session) => session.status === "completed")
    .sort((a, b) => (a.completedAt ?? a.startedAt).localeCompare(b.completedAt ?? b.startedAt));
  const previousMilestones = await db.milestones.where("profileId").equals(plan.profileId).toArray();
  const seenSessionIds = new Set(previousMilestones.filter((item) => item.seen).map((item) => item.sessionId));
  const currentWeights = new Map<string, number | undefined>(
    plan.exercises.map((entry) => [entry.id, entry.startingWeightKg])
  );
  const milestones: Milestone[] = [];

  for (const session of sessions) {
    for (const entry of plan.exercises) {
      const performed = session.exercises.find(
        (item) => item.sourcePlanExerciseId === entry.id || item.exerciseId === entry.exerciseId
      );
      if (!performed) continue;
      const previous = currentWeights.get(entry.id);
      const next = provenWorkingWeight(performed, previous);
      if (next === undefined) continue;
      currentWeights.set(entry.id, next);
      milestones.push({
        id: createId("milestone"),
        profileId: plan.profileId,
        planId: plan.id,
        planExerciseId: entry.id,
        exerciseId: performed.exerciseId,
        exerciseName: performed.name,
        sessionId: session.id,
        occurredAt: session.completedAt ?? session.startedAt,
        previousWeightKg: previous,
        newWeightKg: next,
        direction: performed.loadDirection,
        seen: seenSessionIds.has(session.id)
      });
    }
  }

  const updated: WorkoutPlan = {
    ...plan,
    exercises: plan.exercises.map((entry) => ({
      ...entry,
      currentWeightKg: currentWeights.get(entry.id) ?? entry.startingWeightKg
    })),
    updatedAt: new Date().toISOString()
  };
  await db.transaction("rw", [db.plans, db.milestones], async () => {
    await db.plans.put(updated);
    const ids = previousMilestones.filter((item) => item.planId === planId).map((item) => item.id);
    await db.milestones.bulkDelete(ids);
    if (milestones.length) await db.milestones.bulkAdd(milestones);
  });
}
