import type {
  EquipmentProfile,
  Exercise,
  GymId,
  PlanExercise,
  PlanTarget,
  SessionExercise,
  SetLog,
  WorkoutSession
} from "../types";
import { effortLowerBound } from "../types";

export interface WeightSuggestion {
  weightKg: number;
  reason: string;
  detail: string;
  confidence: "low" | "medium" | "high";
}

function roundDown(value: number, step = 0.5): number {
  return Math.max(0, Math.floor(value / step) * step);
}

function chooseAvailable(value: number, weights?: number[]): number {
  if (!weights?.length) return roundDown(value);
  const sorted = [...weights].sort((a, b) => a - b);
  return [...sorted].reverse().find((item) => item <= value) ?? sorted[0];
}

export function estimatedOneRepMax(set: SetLog, baseLoadKg = 0): number | undefined {
  const totalLoad = (set.weightKg ?? 0) + baseLoadKg;
  if (!totalLoad || !set.reps || !set.effort) return undefined;
  const effectiveReps = Math.min(20, set.reps + effortLowerBound[set.effort]);
  return totalLoad * (1 + effectiveReps / 30);
}

export function suggestNextWeight(
  set: SetLog,
  target: PlanTarget,
  exercise: Pick<Exercise, "measurementType" | "loadDirection">,
  equipment?: EquipmentProfile,
  bodyWeightKg?: number
): WeightSuggestion | undefined {
  if (set.status !== "completed" || !set.effort || set.setType === "warmup") return undefined;
  const weights = equipment?.availableWeightsKg;
  const effort = effortLowerBound[set.effort];

  if (
    exercise.measurementType === "load_reps" ||
    exercise.measurementType === "added_weight_reps"
  ) {
    const baseLoadKg =
      exercise.measurementType === "added_weight_reps" ? (bodyWeightKg ?? 0) : 0;
    const estimate = estimatedOneRepMax(set, baseLoadKg);
    const performedReps = set.reps;
    if (!estimate || performedReps === undefined) return undefined;
    const desiredEffectiveReps = target.max + (target.rirMin + target.rirMax) / 2;
    const calculated = Math.max(
      0,
      estimate / (1 + desiredEffectiveReps / 30) - baseLoadKg
    );
    const chosen = chooseAvailable(calculated, weights);
    return {
      weightKg: chosen,
      reason: chosen > (set.weightKg ?? 0) ? "This set supports a heavier trial" : "Use this conservative target",
      detail: `Calculated from ${set.reps} reps and ${effort} or more reps in reserve, rounded down to available equipment.`,
      confidence: performedReps + effort >= 20 ? "low" : "medium"
    };
  }

  if (exercise.measurementType === "assisted_reps" && set.weightKg && set.reps) {
    const sorted = [...(weights ?? [])].sort((a, b) => b - a);
    const currentIndex = sorted.findIndex((value) => value === set.weightKg);
    if (set.reps >= target.max && effort >= target.rirMin && currentIndex >= 0 && currentIndex < sorted.length - 1) {
      return {
        weightKg: sorted[currentIndex + 1],
        reason: "Try less assistance",
        detail: "You reached the top of the target with reserve. Lower assistance is harder.",
        confidence: "medium"
      };
    }
  }

  if (
    set.weightKg &&
    (exercise.measurementType === "load_duration" ||
      exercise.measurementType === "load_distance_duration")
  ) {
    const achieved = target.metric === "meters" ? set.meters : set.seconds;
    const sorted = [...(weights ?? [])].sort((a, b) => a - b);
    const currentIndex = sorted.findIndex((value) => value === set.weightKg);
    if (achieved && achieved >= target.max && effort >= target.rirMin && currentIndex >= 0 && currentIndex < sorted.length - 1) {
      return {
        weightKg: sorted[currentIndex + 1],
        reason: "Try the next available load",
        detail: "The target duration or distance was reached with reserve.",
        confidence: "medium"
      };
    }
  }

  return undefined;
}

function normalizeTotalLoad(weightKg: number, loadBasis: Exercise["loadBasis"]): number {
  return loadBasis === "per_hand" ? weightKg * 2 : weightKg;
}

function normalizeTargetLoad(weightKg: number, loadBasis: Exercise["loadBasis"]): number {
  return loadBasis === "per_hand" ? weightKg / 2 : weightKg;
}

export function guidedStartingEstimate(
  targetExercise: Exercise,
  sessions: WorkoutSession[],
  catalog: Exercise[],
  equipment?: EquipmentProfile
): WeightSuggestion | undefined {
  if (
    !targetExercise.movementFamily ||
    !["load_reps", "added_weight_reps"].includes(targetExercise.measurementType)
  ) {
    return undefined;
  }

  const catalogById = new Map(catalog.map((exercise) => [exercise.id, exercise]));
  const newestFirst = [...sessions]
    .filter((session) => session.status === "completed")
    .sort((a, b) => (b.completedAt ?? b.startedAt).localeCompare(a.completedAt ?? a.startedAt));

  for (const session of newestFirst) {
    for (const performed of session.exercises) {
      const source = catalogById.get(performed.exerciseId);
      if (
        !source ||
        source.id === targetExercise.id ||
        source.movementFamily !== targetExercise.movementFamily
      ) {
        continue;
      }
      const latestSet = [...performed.sets]
        .reverse()
        .find(
          (set) =>
            set.status === "completed" &&
            set.setType === "working" &&
            set.weightKg !== undefined &&
            set.weightKg > 0
        );
      if (!latestSet?.weightKg) continue;

      const relatedTotal = normalizeTotalLoad(latestSet.weightKg, performed.loadBasis);
      const conservativeTarget = normalizeTargetLoad(
        relatedTotal * 0.6,
        targetExercise.loadBasis
      );
      const chosen = chooseAvailable(conservativeTarget, equipment?.availableWeightsKg);
      return {
        weightKg: chosen,
        reason: `Conservative starting point from ${performed.name}`,
        detail:
          "Uses 60% of your latest related working load, normalizes per-hand weights, and rounds down. Treat this as a first-set check, not a strength prediction.",
        confidence: "low"
      };
    }
  }
  return undefined;
}

export function historicalWorkingWeight(
  targetExercise: Exercise,
  sessions: WorkoutSession[],
  gymId: GymId
): WeightSuggestion | undefined {
  const latest = [...sessions]
    .filter(
      (session) =>
        session.status === "completed" &&
        (session.gymId ?? "basic_fit") === gymId &&
        session.exercises.some((exercise) => exercise.exerciseId === targetExercise.id)
    )
    .sort((a, b) =>
      (b.completedAt ?? b.startedAt).localeCompare(a.completedAt ?? a.startedAt)
    )
    .at(0);
  if (!latest) return undefined;

  const performed = latest.exercises.find(
    (exercise) => exercise.exerciseId === targetExercise.id
  );
  const weightedSets =
    performed?.sets.filter(
      (set) =>
        set.status === "completed" &&
        set.setType === "working" &&
        set.weightKg !== undefined
    ) ?? [];
  if (!weightedSets.length) return undefined;

  const counts = new Map<number, number>();
  weightedSets.forEach((set) => {
    const weight = set.weightKg as number;
    counts.set(weight, (counts.get(weight) ?? 0) + 1);
  });
  const highestFrequency = Math.max(...counts.values());
  const representative = [...weightedSets]
    .reverse()
    .find((set) => counts.get(set.weightKg as number) === highestFrequency)?.weightKg;
  if (representative === undefined) return undefined;

  return {
    weightKg: representative,
    reason: "Based on your latest workout for this exercise",
    detail: `Uses the most frequently recorded working weight from ${new Date(
      latest.completedAt ?? latest.startedAt
    ).toLocaleDateString()} at the selected gym.`,
    confidence: weightedSets.length >= 2 ? "medium" : "low"
  };
}

function setMeetsTarget(set: SetLog, target: PlanTarget): boolean {
  if (set.status !== "completed" || set.setType !== "working" || !set.effort) return false;
  const actual =
    target.metric === "reps" ? set.reps : target.metric === "seconds" ? set.seconds : set.meters;
  if (actual === undefined || actual < target.min) return false;
  return effortLowerBound[set.effort] >= target.rirMin;
}

export function provenWorkingWeight(
  exercise: SessionExercise,
  currentWeightKg?: number
): number | undefined {
  const qualifying = exercise.sets
    .filter((set) => set.weightKg !== undefined && setMeetsTarget(set, exercise.target))
    .map((set) => set.weightKg as number);
  if (qualifying.length < 2) return undefined;

  const sorted = qualifying.sort((a, b) => a - b);
  const candidate =
    exercise.loadDirection === "lower_assistance"
      ? sorted[1]
      : sorted[sorted.length - 2];

  if (currentWeightKg === undefined) return candidate;
  if (exercise.loadDirection === "lower_assistance") {
    return candidate < currentWeightKg ? candidate : undefined;
  }
  return candidate > currentWeightKg ? candidate : undefined;
}

export function planWeightAfterSession(
  planEntry: PlanExercise,
  sessionExercise: SessionExercise
): number | undefined {
  return provenWorkingWeight(
    sessionExercise,
    planEntry.currentWeightKg ?? planEntry.startingWeightKg
  );
}

export function totalVolume(exercise: SessionExercise): number {
  return exercise.sets.reduce((sum, set) => {
    if (set.status !== "completed") return sum;
    const multiplier = exercise.loadBasis === "per_hand" ? 2 : 1;
    return sum + (set.weightKg ?? 0) * (set.reps ?? 0) * multiplier;
  }, 0);
}
