import Papa from "papaparse";
import { z } from "zod";
import type { ImportPlanRow, WorkoutPlan } from "../types";
import { createId } from "./db";

const csvRowSchema = z.object({
  format_version: z.coerce.number().int().refine((value) => value === 1, "Only format version 1 is supported"),
  plan_name: z.string().trim().min(1),
  order: z.coerce.number().int().positive(),
  exercise_id: z.string().trim().optional().default(""),
  exercise_name: z.string().trim().min(1),
  normal_sets: z.coerce.number().int().min(0).max(4),
  fast_sets: z.coerce.number().int().min(0).max(4),
  ultra_fast_sets: z.coerce.number().int().min(0).max(4),
  target_metric: z.enum(["reps", "seconds", "meters"]),
  target_min: z.coerce.number().positive(),
  target_max: z.coerce.number().positive(),
  starting_weight_kg: z.union([z.literal(""), z.coerce.number().nonnegative()]).optional().default(""),
  target_rir_min: z.union([z.literal(""), z.coerce.number().min(0).max(4)]).optional().default(""),
  target_rir_max: z.union([z.literal(""), z.coerce.number().min(0).max(4)]).optional().default(""),
  rest_seconds: z.union([z.literal(""), z.coerce.number().int().nonnegative()]).optional().default(""),
  superset_group: z.string().trim().optional().default("")
});

export interface CsvParseResult {
  rows: ImportPlanRow[];
  errors: string[];
}

export function parsePlanCsv(content: string): CsvParseResult {
  const parsed = Papa.parse<Record<string, string>>(content, {
    header: true,
    skipEmptyLines: "greedy",
    transformHeader: (header) => header.trim().toLowerCase()
  });
  const errors = parsed.errors.map((error) => `Row ${(error.row ?? 0) + 2}: ${error.message}`);
  const rows: ImportPlanRow[] = [];

  parsed.data.forEach((raw, index) => {
    const result = csvRowSchema.safeParse(raw);
    if (!result.success) {
      for (const issue of result.error.issues) {
        errors.push(`Row ${index + 2}, ${issue.path.join(".")}: ${issue.message}`);
      }
      return;
    }
    const value = result.data;
    if (value.target_min > value.target_max) {
      errors.push(`Row ${index + 2}: target_min must not exceed target_max`);
      return;
    }
    rows.push({
      formatVersion: 1,
      planName: value.plan_name,
      order: value.order,
      exerciseId: value.exercise_id || undefined,
      exerciseName: value.exercise_name,
      normalSets: value.normal_sets,
      fastSets: value.fast_sets,
      ultraFastSets: value.ultra_fast_sets,
      targetMetric: value.target_metric,
      targetMin: value.target_min,
      targetMax: value.target_max,
      startingWeightKg: value.starting_weight_kg === "" ? undefined : value.starting_weight_kg,
      targetRirMin: value.target_rir_min === "" ? 1 : value.target_rir_min,
      targetRirMax: value.target_rir_max === "" ? 2 : value.target_rir_max,
      restSeconds: value.rest_seconds === "" ? 90 : value.rest_seconds,
      supersetGroup: value.superset_group || undefined
    });
  });

  const names = new Set(
    parsed.data
      .map((row) => row.plan_name?.trim())
      .filter((name): name is string => Boolean(name))
  );
  if (names.size > 1) errors.push("A CSV file may contain only one plan_name.");
  const orders = rows.map((row) => row.order);
  if (new Set(orders).size !== orders.length) errors.push("Each order value must be unique.");
  return { rows: rows.sort((a, b) => a.order - b.order), errors };
}

export function importRowsToPlan(
  rows: ImportPlanRow[],
  resolvedExerciseIds: Record<number, string>,
  profileId: "personal" | "test",
  existingId?: string
): WorkoutPlan {
  const now = new Date().toISOString();
  return {
    id: existingId ?? createId("plan"),
    profileId,
    name: rows[0]?.planName ?? "Imported workout",
    notes: "",
    createdAt: now,
    updatedAt: now,
    exercises: rows.map((row, index) => ({
      id: createId("plan_exercise"),
      exerciseId: resolvedExerciseIds[index],
      order: row.order,
      modeSets: {
        normal: row.normalSets,
        fast: row.fastSets,
        ultra: row.ultraFastSets
      },
      target: {
        metric: row.targetMetric,
        min: row.targetMin,
        max: row.targetMax,
        rirMin: row.targetRirMin,
        rirMax: row.targetRirMax
      },
      startingWeightKg: row.startingWeightKg,
      currentWeightKg: row.startingWeightKg,
      restSeconds: row.restSeconds,
      supersetGroup: row.supersetGroup,
      notes: ""
    }))
  };
}

export const csvTemplate = [
  "format_version,plan_name,order,exercise_id,exercise_name,normal_sets,fast_sets,ultra_fast_sets,target_metric,target_min,target_max,starting_weight_kg,target_rir_min,target_rir_max,rest_seconds,superset_group",
  "1,Full Body,1,Leg_Press,Leg Press,3,2,1,reps,8,12,40,1,2,90,",
  "1,Full Body,2,Chest_Press_Machine,Chest Press Machine,3,2,0,reps,8,12,20,1,2,90,",
  "1,Full Body,3,Farmers_Carry,Farmer's Carry,2,1,0,seconds,30,45,16,1,2,120,"
].join("\n");
