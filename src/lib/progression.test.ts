import { describe, expect, it } from "vitest";
import type {
  EquipmentProfile,
  Exercise,
  SessionExercise,
  SetLog,
  WorkoutSession
} from "../types";
import {
  estimatedOneRepMax,
  guidedStartingEstimate,
  nearestAvailableWeight,
  progressionEvidence,
  provenWorkingWeight,
  suggestNextWeight
} from "./progression";

const target = { metric: "reps" as const, min: 8, max: 12, rirMin: 1, rirMax: 2 };
const equipment: EquipmentProfile = {
  id: "personal:test",
  profileId: "personal",
  exerciseId: "test",
  availableWeightsKg: [10, 15, 20, 22.5, 25, 30, 35, 40],
  prompted: true,
  updatedAt: new Date(0).toISOString()
};

function set(
  weightKg: number,
  reps: number,
  effort: SetLog["effort"],
  status: SetLog["status"] = "completed"
): SetLog {
  return {
    id: crypto.randomUUID(),
    order: 1,
    setType: "working",
    status,
    weightKg,
    reps,
    effort
  };
}

function sessionExercise(sets: SetLog[], direction: SessionExercise["loadDirection"] = "higher"): SessionExercise {
  return {
    id: "session-exercise",
    exerciseId: "test",
    name: "Test press",
    order: 1,
    notes: "",
    measurementType: direction === "lower_assistance" ? "assisted_reps" : "load_reps",
    loadDirection: direction,
    loadBasis: direction === "lower_assistance" ? "assistance" : "stack",
    target,
    restSeconds: 90,
    plannedSets: sets.length,
    sets
  };
}

describe("progression", () => {
  it("selects the nearest available weight and resolves ties downward", () => {
    expect(nearestAvailableWeight(23.9, [22.5, 25])).toBe(25);
    expect(nearestAvailableWeight(23.75, [22.5, 25])).toBe(22.5);
    expect(nearestAvailableWeight(23.8)).toBe(24);
  });

  it("calculates a conservative between-set target and uses an available value", () => {
    const result = suggestNextWeight(
      set(20, 15, "four_plus"),
      target,
      { measurementType: "load_reps", loadDirection: "higher" },
      equipment
    );
    expect(result?.weightKg).toBe(22.5);
    expect(result?.evidence).toContain("Current working set");
  });

  it("advances to the highest load corroborated by two qualifying sets", () => {
    const exercise = sessionExercise([
      set(20, 15, "four_plus"),
      set(25, 10, "two_three"),
      set(25, 9, "one")
    ]);
    expect(provenWorkingWeight(exercise, 20)).toBe(25);
  });

  it("uses the new effort bands and explains permanent progression evidence", () => {
    const oneProof = sessionExercise([set(25, 8, "one_two")]);
    expect(provenWorkingWeight(oneProof, 20)).toBeUndefined();
    expect(progressionEvidence(oneProof, 20)).toContain("1 of 2");

    const proven = sessionExercise([
      set(25, 8, "one_two"),
      set(25, 8, "one_two")
    ]);
    expect(provenWorkingWeight(proven, 20)).toBe(25);
    expect(progressionEvidence(proven, 20)).toContain("will be saved");
  });

  it("does not treat one good set or a time-skipped set as proof", () => {
    const skipped = set(25, 0, undefined, "skipped_time");
    const exercise = sessionExercise([set(25, 10, "two_three"), skipped]);
    expect(provenWorkingWeight(exercise, 20)).toBeUndefined();
  });

  it("treats lower assistance as progression", () => {
    const exercise = sessionExercise(
      [set(35, 10, "two_three"), set(35, 9, "one")],
      "lower_assistance"
    );
    expect(provenWorkingWeight(exercise, 40)).toBe(35);
  });

  it("excludes failed working sets from permanent progression", () => {
    const exercise = sessionExercise([
      set(25, 10, "two_three"),
      set(25, 6, undefined, "failed")
    ]);
    expect(provenWorkingWeight(exercise, 20)).toBeUndefined();
  });

  it("includes body weight when estimating added-load performance", () => {
    expect(estimatedOneRepMax(set(10, 10, "two_three"), 80)).toBeCloseTo(126);
  });

  it("offers a related-exercise estimate, normalizes per-hand load, and picks the nearest weight", () => {
    const source: Exercise = {
      id: "dumbbell-press",
      name: "Dumbbell Bench Press",
      aliases: [],
      equipment: "dumbbell",
      category: "strength",
      primaryMuscles: ["chest"],
      secondaryMuscles: [],
      instructions: [],
      imageUrls: [],
      measurementType: "load_reps",
      loadDirection: "higher",
      loadBasis: "per_hand",
      movementFamily: "horizontal-push",
      builtIn: true
    };
    const targetExercise: Exercise = {
      ...source,
      id: "chest-press",
      name: "Chest Press",
      equipment: "machine",
      loadBasis: "stack"
    };
    const performed = sessionExercise([set(20, 10, "two_three")]);
    performed.exerciseId = source.id;
    performed.name = source.name;
    performed.loadBasis = source.loadBasis;
    const session: WorkoutSession = {
      id: "completed-session",
      profileId: "personal",
      planName: "Upper",
      mode: "normal",
      status: "completed",
      startedAt: "2026-01-01T10:00:00.000Z",
      completedAt: "2026-01-01T11:00:00.000Z",
      currentExerciseIndex: 0,
      notes: "",
      exercises: [performed]
    };

    const result = guidedStartingEstimate(targetExercise, [session], [source, targetExercise], {
      ...equipment,
      exerciseId: targetExercise.id
    });
    expect(result?.weightKg).toBe(25);
    expect(result?.evidence).toContain("Latest related working set");
    expect(result?.reason).toContain("Dumbbell Bench Press");
  });
});
