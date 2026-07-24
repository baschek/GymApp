import Papa from "papaparse";
import { z } from "zod";
import type {
  Exercise,
  LoadDirection,
  MeasurementType,
  ProfileId
} from "../types";
import { createId, db } from "./db";

const measurementTypes = [
  "load_reps",
  "bodyweight_reps",
  "added_weight_reps",
  "assisted_reps",
  "reps_only",
  "load_duration",
  "load_distance_duration",
  "duration_only"
] as const satisfies readonly MeasurementType[];

export const loadBasisOptions = {
  load_reps: ["stack", "total", "per_hand"],
  bodyweight_reps: ["none"],
  added_weight_reps: ["added"],
  assisted_reps: ["assistance"],
  reps_only: ["none"],
  load_duration: ["stack", "total", "per_hand"],
  load_distance_duration: ["stack", "total", "per_hand"],
  duration_only: ["none"]
} as const satisfies Record<MeasurementType, readonly Exercise["loadBasis"][]>;

export const measurementLabels: Record<MeasurementType, string> = {
  load_reps: "Weight and repetitions",
  bodyweight_reps: "Bodyweight repetitions",
  added_weight_reps: "Added weight and repetitions",
  assisted_reps: "Assistance and repetitions",
  reps_only: "Repetitions only",
  load_duration: "Weight and duration",
  load_distance_duration: "Weight, distance, and duration",
  duration_only: "Duration only"
};

export const loadBasisLabels: Record<Exercise["loadBasis"], string> = {
  stack: "Machine stack",
  total: "Total weight",
  per_hand: "Weight per hand",
  added: "Added weight",
  assistance: "Assistance weight",
  none: "No weight"
};

const expectedHeaders = [
  "format_version",
  "exercise_id",
  "name",
  "equipment",
  "measurement_type",
  "load_basis",
  "primary_muscles",
  "secondary_muscles",
  "aliases",
  "instructions"
];

const rowSchema = z.object({
  format_version: z.coerce
    .number()
    .int()
    .refine((value) => value === 1, "Only format version 1 is supported"),
  exercise_id: z.string().trim().optional().default(""),
  name: z.string().trim().min(1, "Name is required"),
  equipment: z.string().trim().min(1, "Equipment is required"),
  measurement_type: z.enum(measurementTypes),
  load_basis: z.enum(["stack", "total", "per_hand", "added", "assistance", "none"]),
  primary_muscles: z.string().optional().default(""),
  secondary_muscles: z.string().optional().default(""),
  aliases: z.string().optional().default(""),
  instructions: z.string().optional().default("")
});

export interface ExerciseCsvRow {
  sourceRow: number;
  exerciseId?: string;
  name: string;
  equipment: string;
  measurementType: MeasurementType;
  loadBasis: Exercise["loadBasis"];
  primaryMuscles: string[];
  secondaryMuscles: string[];
  aliases: string[];
  instructions: string[];
}

export interface ExerciseCsvParseResult {
  rows: ExerciseCsvRow[];
  errors: string[];
}

export type ExerciseImportDecision = "create" | "skip" | "update" | "copy";
export type ExerciseImportStatus = "new" | "existing" | "conflict";

export interface ClassifiedExerciseImport {
  row: ExerciseCsvRow;
  existing?: Exercise;
  nameCollision?: Exercise;
  status: ExerciseImportStatus;
  matchedBy?: "id" | "name";
  suppliedIdUnavailable: boolean;
}

function parseList(value: string): string[] {
  return value
    .split("|")
    .map((item) => item.trim())
    .filter(Boolean);
}

export function normalizeExerciseName(value: string): string {
  return value
    .normalize("NFKD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim()
    .replace(/\s+/g, " ");
}

export function loadDirectionForMeasurement(
  measurementType: MeasurementType
): LoadDirection {
  if (measurementType === "assisted_reps") return "lower_assistance";
  if (
    measurementType === "bodyweight_reps" ||
    measurementType === "reps_only" ||
    measurementType === "duration_only"
  ) {
    return "none";
  }
  return "higher";
}

export function defaultLoadBasis(
  measurementType: MeasurementType
): Exercise["loadBasis"] {
  return loadBasisOptions[measurementType][0];
}

export function parseExerciseCsv(content: string): ExerciseCsvParseResult {
  const parsed = Papa.parse<Record<string, string>>(content, {
    header: true,
    skipEmptyLines: "greedy",
    transformHeader: (header) =>
      header.replace(/^\uFEFF/, "").trim().toLowerCase()
  });
  const errors = parsed.errors.map(
    (error) => `Row ${(error.row ?? 0) + 2}: ${error.message}`
  );
  const fields = parsed.meta.fields ?? [];
  const missing = expectedHeaders.filter((header) => !fields.includes(header));
  const unknown = fields.filter((header) => !expectedHeaders.includes(header));
  if (missing.length) errors.push(`Missing columns: ${missing.join(", ")}`);
  if (unknown.length) errors.push(`Unknown columns: ${unknown.join(", ")}`);

  const rows: ExerciseCsvRow[] = [];
  parsed.data.forEach((raw, index) => {
    const result = rowSchema.safeParse(raw);
    if (!result.success) {
      result.error.issues.forEach((issue) => {
        errors.push(
          `Row ${index + 2}, ${issue.path.join(".")}: ${issue.message}`
        );
      });
      return;
    }
    const value = result.data;
    const allowedBasis = loadBasisOptions[value.measurement_type] as readonly string[];
    if (!allowedBasis.includes(value.load_basis)) {
      errors.push(
        `Row ${index + 2}, load_basis: ${value.load_basis} is not valid for ${value.measurement_type}`
      );
      return;
    }
    rows.push({
      sourceRow: index + 2,
      exerciseId: value.exercise_id || undefined,
      name: value.name,
      equipment: value.equipment,
      measurementType: value.measurement_type,
      loadBasis: value.load_basis,
      primaryMuscles: parseList(value.primary_muscles),
      secondaryMuscles: parseList(value.secondary_muscles),
      aliases: parseList(value.aliases),
      instructions: parseList(value.instructions)
    });
  });

  const names = new Map<string, number>();
  const ids = new Map<string, number>();
  rows.forEach((row) => {
    const normalizedName = normalizeExerciseName(row.name);
    const previousNameRow = names.get(normalizedName);
    if (previousNameRow) {
      errors.push(
        `Rows ${previousNameRow} and ${row.sourceRow}: exercise names must be unique`
      );
    } else {
      names.set(normalizedName, row.sourceRow);
    }
    if (row.exerciseId) {
      const normalizedId = row.exerciseId.toLowerCase();
      const previousIdRow = ids.get(normalizedId);
      if (previousIdRow) {
        errors.push(
          `Rows ${previousIdRow} and ${row.sourceRow}: exercise_id values must be unique`
        );
      } else {
        ids.set(normalizedId, row.sourceRow);
      }
    }
  });

  return { rows, errors };
}

function sameList(left: string[], right: string[]): boolean {
  return (
    left.length === right.length &&
    left.every(
      (value, index) =>
        value.trim().toLowerCase() === right[index]?.trim().toLowerCase()
    )
  );
}

function definitionMatches(row: ExerciseCsvRow, exercise: Exercise): boolean {
  return (
    row.name === exercise.name &&
    row.equipment === exercise.equipment &&
    row.measurementType === exercise.measurementType &&
    row.loadBasis === exercise.loadBasis &&
    sameList(row.primaryMuscles, exercise.primaryMuscles) &&
    sameList(row.secondaryMuscles, exercise.secondaryMuscles) &&
    sameList(row.aliases, exercise.aliases) &&
    sameList(row.instructions, exercise.instructions)
  );
}

export function classifyExerciseImports(
  rows: ExerciseCsvRow[],
  exercises: Exercise[]
): ClassifiedExerciseImport[] {
  const byId = new Map(
    exercises.map((exercise) => [exercise.id.toLowerCase(), exercise])
  );
  const byName = new Map(
    exercises.map((exercise) => [
      normalizeExerciseName(exercise.name),
      exercise
    ])
  );
  return rows.map((row) => {
    const idMatch = row.exerciseId
      ? byId.get(row.exerciseId.toLowerCase())
      : undefined;
    const nameMatch = byName.get(normalizeExerciseName(row.name));
    const existing = idMatch ?? nameMatch;
    const nameCollision =
      idMatch && nameMatch && idMatch.id !== nameMatch.id
        ? nameMatch
        : undefined;
    return {
      row,
      existing,
      nameCollision,
      status: nameCollision
        ? "conflict"
        : !existing
        ? "new"
        : definitionMatches(row, existing)
          ? "existing"
          : "conflict",
      matchedBy: idMatch ? "id" : nameMatch ? "name" : undefined,
      suppliedIdUnavailable: Boolean(row.exerciseId && !idMatch)
    };
  });
}

function rowToExercise(
  row: ExerciseCsvRow,
  profileId: ProfileId,
  id: string,
  existing?: Exercise,
  name = row.name
): Exercise {
  return {
    id,
    name,
    aliases: row.aliases,
    equipment: row.equipment,
    category: existing?.category ?? "strength",
    primaryMuscles: row.primaryMuscles,
    secondaryMuscles: row.secondaryMuscles,
    instructions: row.instructions,
    imageUrls: existing?.imageUrls ?? [],
    measurementType: row.measurementType,
    loadDirection: loadDirectionForMeasurement(row.measurementType),
    loadBasis: row.loadBasis,
    movementFamily: existing?.movementFamily,
    builtIn: false,
    profileId,
    customPhoto: existing?.customPhoto
  };
}

function uniqueCopyName(name: string, usedNames: Set<string>): string {
  let suffix = 1;
  let candidate = `${name} (copy)`;
  while (usedNames.has(normalizeExerciseName(candidate))) {
    suffix += 1;
    candidate = `${name} (copy ${suffix})`;
  }
  usedNames.add(normalizeExerciseName(candidate));
  return candidate;
}

export async function applyExerciseImport(
  classified: ClassifiedExerciseImport[],
  decisions: Record<number, ExerciseImportDecision | undefined>,
  profileId: ProfileId
): Promise<{ created: number; updated: number; skipped: number }> {
  const existing = await db.customExercises
    .where("profileId")
    .equals(profileId)
    .toArray();
  const usedNames = new Set(
    existing.map((exercise) => normalizeExerciseName(exercise.name))
  );
  const toPut: Exercise[] = [];
  const updatedById = new Map<string, Exercise>();
  let created = 0;
  let updated = 0;
  let skipped = 0;

  classified.forEach((item) => {
    const decision = decisions[item.row.sourceRow];
    if (!decision) {
      throw new Error(`Row ${item.row.sourceRow} still needs a decision`);
    }
    if (decision === "skip") {
      skipped += 1;
      return;
    }
    if (decision === "update") {
      if (!item.existing) {
        throw new Error(`Row ${item.row.sourceRow} has no exercise to update`);
      }
      if (item.nameCollision) {
        throw new Error(
          `Row ${item.row.sourceRow} cannot update because its name belongs to another exercise`
        );
      }
      const exercise = rowToExercise(
        item.row,
        profileId,
        item.existing.id,
        item.existing
      );
      toPut.push(exercise);
      updatedById.set(exercise.id, exercise);
      updated += 1;
      return;
    }

    const name =
      decision === "copy"
        ? uniqueCopyName(item.row.name, usedNames)
        : item.row.name;
    usedNames.add(normalizeExerciseName(name));
    toPut.push(
      rowToExercise(
        item.row,
        profileId,
        createId("custom_exercise"),
        undefined,
        name
      )
    );
    created += 1;
  });

  await db.transaction(
    "rw",
    [db.customExercises, db.sessions, db.milestones],
    async () => {
      if (toPut.length) await db.customExercises.bulkPut(toPut);
      if (!updatedById.size) return;

      const [sessions, milestones] = await Promise.all([
        db.sessions.where("profileId").equals(profileId).toArray(),
        db.milestones.where("profileId").equals(profileId).toArray()
      ]);
      const changedSessions = sessions
        .filter((session) =>
          session.exercises.some((exercise) =>
            updatedById.has(exercise.exerciseId)
          )
        )
        .map((session) => ({
          ...session,
          exercises: session.exercises.map((exercise) => {
            const definition = updatedById.get(exercise.exerciseId);
            return definition
              ? {
                  ...exercise,
                  name: definition.name,
                  measurementType: definition.measurementType,
                  loadDirection: definition.loadDirection,
                  loadBasis: definition.loadBasis
                }
              : exercise;
          })
        }));
      const changedMilestones = milestones
        .filter((milestone) => updatedById.has(milestone.exerciseId))
        .map((milestone) => ({
          ...milestone,
          exerciseName:
            updatedById.get(milestone.exerciseId)?.name ??
            milestone.exerciseName
        }));
      if (changedSessions.length) await db.sessions.bulkPut(changedSessions);
      if (changedMilestones.length) await db.milestones.bulkPut(changedMilestones);
    }
  );

  return { created, updated, skipped };
}

export const exerciseCsvTemplate = [
  expectedHeaders.join(","),
  "1,,Chest Press Machine,Chest press,load_reps,stack,Chest,Triceps|Front deltoids,Chest Press,Adjust the seat|Keep shoulders against the pad",
  "1,,Assisted Pull-up Machine,Counterweight pull-up machine,assisted_reps,assistance,Back|Latissimus,Biceps,Assisted Pull-up|Klimmzugmaschine,Use the knee pad|Pull up under control",
  "1,,Dumbbell Curl,Dumbbells,load_reps,per_hand,Biceps,Forearms,,Keep elbows still|Lower the dumbbells under control"
].join("\n");
