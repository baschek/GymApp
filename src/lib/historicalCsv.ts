import Papa from "papaparse";
import { z } from "zod";
import type {
  EffortBand,
  Exercise,
  GymId,
  ProfileId,
  SetStatus,
  WorkoutSession
} from "../types";
import { createId } from "./db";

const optionalNumber = z
  .union([z.literal(""), z.coerce.number().nonnegative()])
  .optional()
  .default("");

const rowSchema = z.object({
  format_version: z.coerce
    .number()
    .int()
    .refine((value) => value === 1, "Only format version 1 is supported"),
  session_id: z.string().trim().optional().default(""),
  date: z.string().trim().regex(/^\d{4}-\d{2}-\d{2}$/, "Use YYYY-MM-DD"),
  start_time: z
    .string()
    .trim()
    .regex(/^$|^\d{2}:\d{2}(:\d{2})?$/, "Use HH:MM or HH:MM:SS")
    .optional()
    .default(""),
  gym: z.string().trim().min(1),
  workout_name: z.string().trim().min(1),
  duration_minutes: optionalNumber,
  exercise_order: z.coerce.number().int().positive(),
  exercise_id: z.string().trim().optional().default(""),
  exercise_name: z.string().trim().min(1),
  set_number: z.coerce.number().int().positive(),
  set_type: z
    .union([z.literal(""), z.enum(["working", "warmup"])])
    .optional()
    .default(""),
  status: z
    .union([
      z.literal(""),
      z.enum([
        "completed",
        "failed",
        "pain",
        "skipped_time",
        "skipped_equipment",
        "skipped_other"
      ])
    ])
    .optional()
    .default(""),
  weight_kg: optionalNumber,
  reps: optionalNumber,
  seconds: optionalNumber,
  meters: optionalNumber,
  rir: optionalNumber,
  rest_seconds: optionalNumber,
  set_duration_seconds: optionalNumber,
  exercise_notes: z.string().optional().default(""),
  session_notes: z.string().optional().default("")
});

export interface HistoricalCsvRow {
  sessionKey: string;
  sessionId?: string;
  date: string;
  startTime?: string;
  gymId: GymId;
  workoutName: string;
  durationMinutes?: number;
  exerciseOrder: number;
  exerciseId?: string;
  exerciseName: string;
  setNumber: number;
  setType: "working" | "warmup";
  status: Exclude<SetStatus, "pending">;
  weightKg?: number;
  reps?: number;
  seconds?: number;
  meters?: number;
  rir?: number;
  restSeconds?: number;
  setDurationSeconds?: number;
  exerciseNotes: string;
  sessionNotes: string;
}

export interface HistoricalCsvParseResult {
  rows: HistoricalCsvRow[];
  errors: string[];
}

function optionalValue(value: number | ""): number | undefined {
  return value === "" ? undefined : value;
}

function parseGym(value: string): GymId | undefined {
  const normalized = value.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  if (["basic fit", "basicfit"].includes(normalized)) return "basic_fit";
  if (["john reed", "johnreed"].includes(normalized)) return "john_reed";
  if (
    ["ai fitness lahnstein", "ai lahnstein", "all inclusive fitness lahnstein"].includes(
      normalized
    )
  ) {
    return "ai_lahnstein";
  }
  if (
    ["ai fitness koblenz", "ai koblenz", "all inclusive fitness koblenz"].includes(
      normalized
    )
  ) {
    return "ai_koblenz";
  }
  return undefined;
}

function effortFromRir(rir?: number): EffortBand | undefined {
  if (rir === undefined) return undefined;
  if (rir < 1) return "limit";
  if (rir < 3) return "one_two";
  if (rir < 5) return "three_four";
  return "five_plus";
}

export function parseHistoricalCsv(content: string): HistoricalCsvParseResult {
  const parsed = Papa.parse<Record<string, string>>(content, {
    header: true,
    skipEmptyLines: "greedy",
    transformHeader: (header) => header.trim().toLowerCase()
  });
  const errors = parsed.errors.map((error) => `Row ${(error.row ?? 0) + 2}: ${error.message}`);
  const rows: HistoricalCsvRow[] = [];

  parsed.data.forEach((raw, index) => {
    const result = rowSchema.safeParse(raw);
    if (!result.success) {
      result.error.issues.forEach((issue) => {
        errors.push(`Row ${index + 2}, ${issue.path.join(".")}: ${issue.message}`);
      });
      return;
    }
    const value = result.data;
    const gymId = parseGym(value.gym);
    if (!gymId) {
      errors.push(
        `Row ${index + 2}, gym: use Basic Fit, John Reed, AI Fitness Lahnstein, or AI Fitness Koblenz`
      );
      return;
    }
    const status = (value.status || "completed") as HistoricalCsvRow["status"];
    const metrics = [
      value.weight_kg,
      value.reps,
      value.seconds,
      value.meters
    ].filter((metric) => metric !== "").length;
    if (status === "completed" && metrics === 0) {
      errors.push(`Row ${index + 2}: a completed set needs at least one recorded metric`);
      return;
    }
    if (value.rir !== "" && value.rir > 20) {
      errors.push(`Row ${index + 2}, rir: must be between 0 and 20`);
      return;
    }
    const fallbackKey = [
      value.date,
      value.start_time || "no-time",
      gymId,
      value.workout_name.toLowerCase()
    ].join("|");
    rows.push({
      sessionKey: value.session_id || fallbackKey,
      sessionId: value.session_id || undefined,
      date: value.date,
      startTime: value.start_time || undefined,
      gymId,
      workoutName: value.workout_name,
      durationMinutes: optionalValue(value.duration_minutes),
      exerciseOrder: value.exercise_order,
      exerciseId: value.exercise_id || undefined,
      exerciseName: value.exercise_name,
      setNumber: value.set_number,
      setType: value.set_type || "working",
      status,
      weightKg: optionalValue(value.weight_kg),
      reps: optionalValue(value.reps),
      seconds: optionalValue(value.seconds),
      meters: optionalValue(value.meters),
      rir: optionalValue(value.rir),
      restSeconds: optionalValue(value.rest_seconds),
      setDurationSeconds: optionalValue(value.set_duration_seconds),
      exerciseNotes: value.exercise_notes,
      sessionNotes: value.session_notes
    });
  });

  const identities = new Set<string>();
  rows.forEach((row, index) => {
    const identity = `${row.sessionKey}|${row.exerciseOrder}|${row.setNumber}`;
    if (identities.has(identity)) {
      errors.push(
        `Row ${index + 2}: duplicate exercise_order and set_number within this session`
      );
    }
    identities.add(identity);
  });
  const sessionMetadata = new Map<string, string>();
  const exerciseMetadata = new Map<string, string>();
  rows.forEach((row, index) => {
    const signature = [
      row.date,
      row.startTime ?? "",
      row.gymId,
      row.workoutName,
      row.durationMinutes ?? ""
    ].join("|");
    const previous = sessionMetadata.get(row.sessionKey);
    if (previous && previous !== signature) {
      errors.push(
        `Row ${index + 2}: date, time, gym, workout_name, and duration must match other rows with this session_id`
      );
    }
    sessionMetadata.set(row.sessionKey, signature);

    const exerciseKey = `${row.sessionKey}|${row.exerciseOrder}`;
    const exerciseSignature = exerciseReferenceForValidation(row);
    const previousExercise = exerciseMetadata.get(exerciseKey);
    if (previousExercise && previousExercise !== exerciseSignature) {
      errors.push(
        `Row ${index + 2}: exercise_name and exercise_id must match other rows with this exercise_order`
      );
    }
    exerciseMetadata.set(exerciseKey, exerciseSignature);
  });
  return { rows, errors };
}

function exerciseReferenceForValidation(row: HistoricalCsvRow): string {
  return row.exerciseId
    ? `id:${row.exerciseId.toLowerCase()}`
    : `name:${row.exerciseName.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim()}`;
}

function sessionDate(row: HistoricalCsvRow): string {
  const time = row.startTime
    ? row.startTime.length === 5
      ? `${row.startTime}:00`
      : row.startTime
    : "12:00:00";
  return new Date(`${row.date}T${time}`).toISOString();
}

export function historicalRowsToSessions(
  rows: HistoricalCsvRow[],
  resolvedExerciseIds: Record<number, string>,
  catalog: Exercise[],
  profileId: ProfileId
): WorkoutSession[] {
  const catalogById = new Map(catalog.map((exercise) => [exercise.id, exercise]));
  const groups = new Map<string, Array<{ row: HistoricalCsvRow; sourceIndex: number }>>();
  rows.forEach((row, sourceIndex) => {
    const group = groups.get(row.sessionKey) ?? [];
    group.push({ row, sourceIndex });
    groups.set(row.sessionKey, group);
  });

  return [...groups.entries()].map(([sessionKey, group]) => {
    const first = group[0].row;
    const startedAt = sessionDate(first);
    const durationSeconds =
      first.durationMinutes === undefined ? undefined : Math.round(first.durationMinutes * 60);
    const exerciseGroups = new Map<
      number,
      Array<{ row: HistoricalCsvRow; sourceIndex: number }>
    >();
    group.forEach((item) => {
      const exerciseGroup = exerciseGroups.get(item.row.exerciseOrder) ?? [];
      exerciseGroup.push(item);
      exerciseGroups.set(item.row.exerciseOrder, exerciseGroup);
    });

    const exercises = [...exerciseGroups.entries()]
      .sort(([a], [b]) => a - b)
      .map(([order, exerciseRows]) => {
        const firstExercise = exerciseRows[0];
        const exerciseId = resolvedExerciseIds[firstExercise.sourceIndex];
        const catalogExercise = catalogById.get(exerciseId);
        if (!catalogExercise) throw new Error(`Exercise mapping is missing for row ${firstExercise.sourceIndex + 2}`);
        const metric: "reps" | "seconds" | "meters" = exerciseRows.some(
          ({ row }) => row.meters !== undefined
        )
          ? "meters"
          : exerciseRows.some(({ row }) => row.seconds !== undefined)
            ? "seconds"
            : "reps";
        const actualValues = exerciseRows
          .map(({ row }) =>
            metric === "meters" ? row.meters : metric === "seconds" ? row.seconds : row.reps
          )
          .filter((value): value is number => value !== undefined);
        return {
          id: createId("session_exercise"),
          exerciseId,
          name: catalogExercise.name,
          order,
          notes:
            exerciseRows.find(({ row }) => row.exerciseNotes.trim())?.row.exerciseNotes ?? "",
          measurementType: catalogExercise.measurementType,
          loadDirection: catalogExercise.loadDirection,
          loadBasis: catalogExercise.loadBasis,
          target: {
            metric,
            min: actualValues.length ? Math.min(...actualValues) : 1,
            max: actualValues.length ? Math.max(...actualValues) : 1,
            rirMin: 0,
            rirMax: 4
          },
          restSeconds:
            exerciseRows.find(({ row }) => row.restSeconds !== undefined)?.row.restSeconds ?? 90,
          plannedSets: exerciseRows.filter(({ row }) => row.setType === "working").length,
          sets: exerciseRows
            .sort((a, b) => a.row.setNumber - b.row.setNumber)
            .map(({ row }) => ({
              id: createId("set"),
              order: row.setNumber,
              setType: row.setType,
              status: row.status,
              weightKg: row.weightKg,
              reps: row.reps,
              seconds: row.seconds,
              meters: row.meters,
              effort: effortFromRir(row.rir),
              restBeforeSeconds: row.restSeconds,
              setDurationSeconds: row.setDurationSeconds,
              completedAt: row.status === "completed" ? startedAt : undefined
            }))
        };
      });
    const notes = [
      ...new Set(group.map(({ row }) => row.sessionNotes.trim()).filter(Boolean))
    ].join("\n");
    return {
      id: createId("session"),
      profileId,
      gymId: first.gymId,
      planName: first.workoutName,
      mode: "normal",
      status: "completed",
      startedAt,
      completedAt: durationSeconds
        ? new Date(new Date(startedAt).getTime() + durationSeconds * 1000).toISOString()
        : startedAt,
      durationSeconds,
      importKey: `history_csv:${sessionKey}`,
      currentExerciseIndex: 0,
      notes,
      exercises
    };
  });
}

export const historicalCsvTemplate = [
  "format_version,session_id,date,start_time,gym,workout_name,duration_minutes,exercise_order,exercise_id,exercise_name,set_number,set_type,status,weight_kg,reps,seconds,meters,rir,rest_seconds,set_duration_seconds,exercise_notes,session_notes",
  '1,2026-06-14-push,2026-06-14,18:30,Basic Fit,Push Day,55,1,Chest_Press_Machine,Chest Press Machine,1,working,completed,60,12,,,2,95,38,"Seat 4","Felt strong"',
  "1,2026-06-14-push,2026-06-14,18:30,Basic Fit,Push Day,55,1,Chest_Press_Machine,Chest Press Machine,2,working,completed,65,10,,,1,110,41,,",
  "1,2026-06-14-push,2026-06-14,18:30,Basic Fit,Push Day,55,2,,Triceps Pushdown,1,working,completed,25,12,,,2,80,34,,"
].join("\n");
