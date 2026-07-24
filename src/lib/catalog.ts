import seedCatalog from "../data/catalog.json";
import type { Exercise, MeasurementType, ProfileId } from "../types";
import { createId, db } from "./db";

const basicFitAliases: Record<string, string[]> = {
  "seated leg press": ["basic-fit horizontal seated leg press"],
  "lat pulldown": ["basic-fit lat pull-down"],
  "triceps pushdown": ["basic-fit cable triceps bar"],
  "cable biceps curl": ["basic-fit cable biceps bar"],
  "rowing, stationary": ["basic-fit rowing machine"]
};

export function inferMovementFamily(exercise: Pick<Exercise, "name" | "primaryMuscles">): string | undefined {
  const name = exercise.name.toLowerCase();
  const rules: Array<[string, RegExp]> = [
    ["horizontal-push", /\b(bench press|chest press|push[- ]?up)\b/],
    ["vertical-push", /\b(overhead press|shoulder press|military press)\b/],
    ["vertical-pull", /\b(pull[- ]?up|chin[- ]?up|pulldown|pull-down)\b/],
    ["horizontal-pull", /\b(row|rowing)\b/],
    ["squat-pattern", /\b(squat|leg press|hack press)\b/],
    ["hip-hinge", /\b(deadlift|romanian deadlift|good morning)\b/],
    ["knee-extension", /\b(leg extension)\b/],
    ["knee-flexion", /\b(leg curl)\b/],
    ["elbow-flexion", /\b(curl)\b/],
    ["elbow-extension", /\b(triceps|pushdown|skull crusher)\b/],
    ["calf-raise", /\b(calf raise)\b/],
    ["chest-fly", /\b(fly|pec deck)\b/]
  ];
  return rules.find(([, pattern]) => pattern.test(name))?.[0];
}

function inferMeasurement(exercise: any): {
  measurementType: MeasurementType;
  loadDirection: Exercise["loadDirection"];
  loadBasis: Exercise["loadBasis"];
} {
  const name = String(exercise.name ?? "").toLowerCase();
  const category = String(exercise.category ?? "").toLowerCase();
  const equipment = String(exercise.equipment ?? "").toLowerCase();
  if (name.includes("assisted") && (name.includes("pull") || name.includes("dip"))) {
    return { measurementType: "assisted_reps", loadDirection: "lower_assistance", loadBasis: "assistance" };
  }
  if (category === "stretching" || name.includes("plank") || name.includes("hold")) {
    return { measurementType: "duration_only", loadDirection: "none", loadBasis: "none" };
  }
  if (name.includes("carry") || name.includes("farmer")) {
    return { measurementType: "load_distance_duration", loadDirection: "higher", loadBasis: "per_hand" };
  }
  if (equipment === "body only" || equipment === "bodyweight" || equipment === "none") {
    return { measurementType: "bodyweight_reps", loadDirection: "higher", loadBasis: "none" };
  }
  if (equipment === "dumbbell" || equipment === "kettlebells") {
    return { measurementType: "load_reps", loadDirection: "higher", loadBasis: "per_hand" };
  }
  return { measurementType: "load_reps", loadDirection: "higher", loadBasis: "stack" };
}

function normalizeImportedExercise(raw: any): Exercise {
  const inferred = inferMeasurement(raw);
  const name = String(raw.name ?? raw.id ?? "Unnamed exercise");
  const normalizedName = name.toLowerCase();
  const sourceImages = Array.isArray(raw.images) ? raw.images : [];
  return {
    id: String(raw.id),
    name,
    aliases: basicFitAliases[normalizedName] ?? [],
    equipment: String(raw.equipment ?? "other"),
    category: String(raw.category ?? "strength"),
    primaryMuscles: Array.isArray(raw.primaryMuscles) ? raw.primaryMuscles.map(String) : [],
    secondaryMuscles: Array.isArray(raw.secondaryMuscles) ? raw.secondaryMuscles.map(String) : [],
    instructions: Array.isArray(raw.instructions) ? raw.instructions.map(String) : [],
    imageUrls: sourceImages.map((path: string) => `${import.meta.env.BASE_URL}catalog/images/${path}`),
    ...inferred,
    movementFamily: inferMovementFamily({
      name,
      primaryMuscles: Array.isArray(raw.primaryMuscles) ? raw.primaryMuscles.map(String) : []
    }),
    basicFitCommon: Object.keys(basicFitAliases).includes(normalizedName),
    builtIn: true
  };
}

let catalogPromise: Promise<Exercise[]> | null = null;
const migrationPromises = new Map<ProfileId, Promise<void>>();

export function getBuiltInCatalog(): Promise<Exercise[]> {
  if (!catalogPromise) {
    catalogPromise = fetch(`${import.meta.env.BASE_URL}catalog/exercises.json`)
      .then((response) => {
        if (!response.ok) throw new Error("Full catalog unavailable");
        return response.json();
      })
      .then((rows: any[]) => rows.map(normalizeImportedExercise))
      .catch(() => seedCatalog as Exercise[]);
  }
  return catalogPromise;
}

async function migrateReferencedExercises(profileId: ProfileId): Promise<void> {
  const markerKey = `userExerciseLibraryMigrated:${profileId}`;
  if (await db.settings.get(markerKey)) return;

  const [plans, sessions, custom, equipmentProfiles, milestones, builtIn] =
    await Promise.all([
      db.plans.where("profileId").equals(profileId).toArray(),
      db.sessions.where("profileId").equals(profileId).toArray(),
      db.customExercises.where("profileId").equals(profileId).toArray(),
      db.equipmentProfiles.where("profileId").equals(profileId).toArray(),
      db.milestones.where("profileId").equals(profileId).toArray(),
      getBuiltInCatalog()
    ]);
  const customIds = new Set(custom.map((exercise) => exercise.id));
  const referencedIds = new Set([
    ...plans.flatMap((plan) => plan.exercises.map((exercise) => exercise.exerciseId)),
    ...sessions.flatMap((session) =>
      session.exercises.map((exercise) => exercise.exerciseId)
    ),
    ...equipmentProfiles.map((profile) => profile.exerciseId),
    ...milestones.map((milestone) => milestone.exerciseId)
  ]);
  const idsToConvert = [...referencedIds].filter((id) => !customIds.has(id));
  const builtInById = new Map(builtIn.map((exercise) => [exercise.id, exercise]));
  const sessionByExerciseId = new Map(
    sessions.flatMap((session) =>
      session.exercises.map((exercise) => [exercise.exerciseId, exercise] as const)
    )
  );
  const idMap = new Map<string, string>();
  const converted = idsToConvert.map((oldId) => {
    const source = builtInById.get(oldId);
    const snapshot = sessionByExerciseId.get(oldId);
    const id = createId("exercise");
    idMap.set(oldId, id);
    if (source) {
      return {
        ...source,
        id,
        builtIn: false,
        profileId
      };
    }
    return {
      id,
      name: snapshot?.name ?? oldId.replaceAll("_", " "),
      aliases: [],
      equipment: "other",
      category: "strength",
      primaryMuscles: [],
      secondaryMuscles: [],
      instructions: [],
      imageUrls: [],
      measurementType: snapshot?.measurementType ?? "load_reps",
      loadDirection: snapshot?.loadDirection ?? "higher",
      loadBasis: snapshot?.loadBasis ?? "stack",
      builtIn: false,
      profileId
    } satisfies Exercise;
  });
  const mapId = (id: string) => idMap.get(id) ?? id;

  await db.transaction(
    "rw",
    [
      db.customExercises,
      db.plans,
      db.sessions,
      db.equipmentProfiles,
      db.milestones,
      db.settings
    ],
    async () => {
      if (converted.length) await db.customExercises.bulkAdd(converted);
      if (idMap.size) {
        await db.plans.bulkPut(
          plans.map((plan) => ({
            ...plan,
            exercises: plan.exercises.map((exercise) => ({
              ...exercise,
              exerciseId: mapId(exercise.exerciseId)
            }))
          }))
        );
        await db.sessions.bulkPut(
          sessions.map((session) => ({
            ...session,
            exercises: session.exercises.map((exercise) => ({
              ...exercise,
              exerciseId: mapId(exercise.exerciseId)
            }))
          }))
        );
        const movedEquipment = equipmentProfiles
          .filter((profile) => idMap.has(profile.exerciseId))
          .map((profile) => {
            const exerciseId = mapId(profile.exerciseId);
            return {
              ...profile,
              id: `${profileId}:${profile.gymId ?? "basic_fit"}:${exerciseId}`,
              exerciseId
            };
          });
        if (movedEquipment.length) {
          await db.equipmentProfiles.bulkDelete(
            equipmentProfiles
              .filter((profile) => idMap.has(profile.exerciseId))
              .map((profile) => profile.id)
          );
          await db.equipmentProfiles.bulkPut(movedEquipment);
        }
        await db.milestones.bulkPut(
          milestones.map((milestone) => ({
            ...milestone,
            exerciseId: mapId(milestone.exerciseId)
          }))
        );
      }
      await db.settings.put({ key: markerKey, value: new Date().toISOString() });
    }
  );
}

export async function getCatalog(profileId: ProfileId): Promise<Exercise[]> {
  if (!migrationPromises.has(profileId)) {
    migrationPromises.set(profileId, migrateReferencedExercises(profileId));
  }
  await migrationPromises.get(profileId);
  return db.customExercises
    .where("profileId")
    .equals(profileId)
    .sortBy("name");
}

export function searchExercises(exercises: Exercise[], query: string, equipment?: string): Exercise[] {
  const needle = query.trim().toLowerCase();
  return exercises.filter((exercise) => {
    if (equipment && equipment !== "all" && exercise.equipment !== equipment) return false;
    if (!needle) return true;
    const haystack = [
      exercise.name,
      ...exercise.aliases,
      exercise.equipment,
      ...exercise.primaryMuscles,
      ...exercise.secondaryMuscles
    ]
      .join(" ")
      .toLowerCase();
    return haystack.includes(needle);
  });
}

export function findExerciseMatch(exercises: Exercise[], id: string | undefined, name: string): Exercise | undefined {
  if (id) {
    const exactId = exercises.find((exercise) => exercise.id.toLowerCase() === id.toLowerCase());
    if (exactId) return exactId;
  }
  const normalized = name.trim().toLowerCase().replace(/[^a-z0-9]+/g, " ");
  return exercises.find((exercise) =>
    [exercise.name, ...exercise.aliases].some(
      (candidate) => candidate.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim() === normalized.trim()
    )
  );
}
