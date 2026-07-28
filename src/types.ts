export type ProfileId = "personal" | "test";
export type GymId = "basic_fit" | "john_reed" | "ai_lahnstein" | "ai_koblenz";
export type ThemeMode = "dark" | "light" | "system";
export type WorkoutMode = "normal" | "fast" | "ultra";
export type PrimaryMetric = "reps" | "seconds" | "meters";
export type MeasurementType =
  | "load_reps"
  | "bodyweight_reps"
  | "added_weight_reps"
  | "assisted_reps"
  | "reps_only"
  | "load_duration"
  | "load_distance_duration"
  | "duration_only";
export type LoadDirection = "higher" | "lower_assistance" | "none";
export type LegacyEffortBand = "one" | "two_three" | "four_plus";
export type SelectableEffortBand =
  | "limit"
  | "one_two"
  | "three_four"
  | "five_plus";
export type EffortBand = SelectableEffortBand | LegacyEffortBand;
export type SetStatus =
  | "pending"
  | "completed"
  | "skipped_time"
  | "skipped_equipment"
  | "failed"
  | "pain"
  | "skipped_other";

export interface Exercise {
  id: string;
  name: string;
  aliases: string[];
  equipment: string;
  category: string;
  primaryMuscles: string[];
  secondaryMuscles: string[];
  instructions: string[];
  imageUrls: string[];
  measurementType: MeasurementType;
  loadDirection: LoadDirection;
  loadBasis: "stack" | "total" | "per_hand" | "added" | "assistance" | "none";
  movementFamily?: string;
  basicFitCommon?: boolean;
  builtIn: boolean;
  profileId?: ProfileId;
  customPhoto?: string;
}

export interface ModeSetCounts {
  normal: number;
  fast: number;
  ultra: number;
}

export interface PlanTarget {
  metric: PrimaryMetric;
  min: number;
  max: number;
  rirMin: number;
  rirMax: number;
}

export interface PlanExercise {
  id: string;
  exerciseId: string;
  order: number;
  modeSets: ModeSetCounts;
  target: PlanTarget;
  startingWeightKg?: number;
  currentWeightKg?: number;
  restSeconds: number;
  supersetGroup?: string;
  notes: string;
}

export interface WorkoutPlan {
  id: string;
  profileId: ProfileId;
  name: string;
  notes: string;
  createdAt: string;
  updatedAt: string;
  exercises: PlanExercise[];
}

export interface EquipmentProfile {
  id: string;
  profileId: ProfileId;
  gymId?: GymId;
  exerciseId: string;
  availableWeightsKg: number[];
  weightRanges?: WeightIncrementRange[];
  setupParameters?: MachineSetupParameter[];
  prompted: boolean;
  updatedAt: string;
}

export interface WeightIncrementRange {
  id: string;
  minKg: number;
  maxKg: number;
  stepKg: number;
}

export interface MachineSetupParameter {
  id: string;
  name: string;
  value: string;
}

export interface SetLog {
  id: string;
  order: number;
  setType: "working" | "warmup";
  status: SetStatus;
  weightKg?: number;
  reps?: number;
  seconds?: number;
  meters?: number;
  effort?: EffortBand;
  setDurationSeconds?: number;
  restBeforeSeconds?: number;
  setupBeforeSeconds?: number;
  completedAt?: string;
}

export interface SessionExercise {
  id: string;
  sourcePlanExerciseId?: string;
  exerciseId: string;
  name: string;
  order: number;
  notes: string;
  measurementType: MeasurementType;
  loadDirection: LoadDirection;
  loadBasis: Exercise["loadBasis"];
  target: PlanTarget;
  restSeconds: number;
  supersetGroup?: string;
  plannedSets: number;
  suggestedWeightKg?: number;
  suggestionReason?: string;
  suggestionDetail?: string;
  suggestionEvidence?: string;
  /** Retained so existing stored sessions and backups remain readable. */
  suggestionConfidence?: "low" | "medium" | "high";
  sets: SetLog[];
}

export interface WorkoutSession {
  id: string;
  profileId: ProfileId;
  gymId?: GymId;
  planId?: string;
  planName: string;
  mode: WorkoutMode;
  status: "active" | "completed";
  startedAt: string;
  completedAt?: string;
  durationSeconds?: number;
  importKey?: string;
  currentExerciseIndex: number;
  notes: string;
  exercises: SessionExercise[];
  setStartedAt?: string;
  timingSetId?: string;
  restStartedAt?: string;
  timerPhase?: "rest" | "machine_setup";
  restEndsAt?: string;
  restDurationSeconds?: number;
}

export interface BodyWeightEntry {
  id: string;
  profileId: ProfileId;
  date: string;
  weightKg: number;
  notes: string;
}

export interface Milestone {
  id: string;
  profileId: ProfileId;
  planId: string;
  planExerciseId: string;
  exerciseId: string;
  exerciseName: string;
  sessionId: string;
  occurredAt: string;
  previousWeightKg?: number;
  newWeightKg: number;
  direction: LoadDirection;
  seen: boolean;
}

export interface AppSetting {
  key: string;
  value: unknown;
}

export interface BackupPayload {
  format: "gymapp-backup";
  formatVersion: 1;
  exportedAt: string;
  appVersion: string;
  profile: "personal";
  plans: WorkoutPlan[];
  sessions: WorkoutSession[];
  bodyWeights: BodyWeightEntry[];
  customExercises: Exercise[];
  equipmentProfiles: EquipmentProfile[];
  milestones: Milestone[];
}

export interface ImportPlanRow {
  formatVersion: 1;
  planName: string;
  order: number;
  exerciseId?: string;
  exerciseName: string;
  normalSets: number;
  fastSets: number;
  ultraFastSets: number;
  targetMetric: PrimaryMetric;
  targetMin: number;
  targetMax: number;
  startingWeightKg?: number;
  targetRirMin: number;
  targetRirMax: number;
  restSeconds: number;
  supersetGroup?: string;
}

export const effortLowerBound: Record<EffortBand, number> = {
  limit: 0,
  one: 1,
  two_three: 2,
  four_plus: 4,
  one_two: 1,
  three_four: 3,
  five_plus: 5
};

export const effortLabels: Record<EffortBand, string> = {
  limit: "At limit",
  one: "1 left",
  two_three: "2-3 left",
  four_plus: "4+ / easy",
  one_two: "1-2 left",
  three_four: "3-4 left",
  five_plus: "5+ / easy"
};

export const selectableEffortBands: SelectableEffortBand[] = [
  "limit",
  "one_two",
  "three_four",
  "five_plus"
];
