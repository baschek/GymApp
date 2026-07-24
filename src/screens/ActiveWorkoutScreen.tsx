import {
  AlertTriangle,
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  Check,
  Clock3,
  Dumbbell,
  Info,
  List,
  NotebookPen,
  Play,
  Plus,
  Settings2,
  SkipForward,
  Square,
  TimerReset,
  Trash2,
  X
} from "lucide-react";
import { useEffect, useState } from "react";
import { ExerciseInfoModal } from "../components/ExerciseInfoModal";
import { ExercisePicker } from "../components/ExercisePicker";
import {
  MachineProfileEditor,
  type MachineProfileValues
} from "../components/MachineProfileEditor";
import { IconButton, Modal } from "../components/ui";
import { useApp } from "../context/AppContext";
import { useQuery } from "../hooks/useQuery";
import { getCatalog } from "../lib/catalog";
import { createId, db } from "../lib/db";
import { getGym } from "../lib/gyms";
import {
  historicalWorkingWeight,
  planWeightAfterSession,
  suggestNextWeight
} from "../lib/progression";
import type {
  BodyWeightEntry,
  EffortBand,
  EquipmentProfile,
  Exercise,
  Milestone,
  SessionExercise,
  SetLog,
  SetStatus,
  WorkoutPlan,
  WorkoutSession
} from "../types";
import { effortLabels } from "../types";
import { CustomExerciseModal } from "./ExercisesScreen";

const loadMeasurements = new Set([
  "load_reps",
  "added_weight_reps",
  "assisted_reps",
  "load_duration",
  "load_distance_duration"
]);

function formatTimer(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

function useWakeLock(active: boolean) {
  useEffect(() => {
    let lock: WakeLockSentinel | null = null;
    async function request() {
      if (active && "wakeLock" in navigator) {
        try {
          lock = await navigator.wakeLock.request("screen");
        } catch {
          // The screen can still operate normally when wake lock is unavailable.
        }
      }
    }
    void request();
    return () => {
      void lock?.release();
    };
  }, [active]);
}

function ElapsedTimer({
  session,
  suggestedRestSeconds
}: {
  session: WorkoutSession;
  suggestedRestSeconds: number;
}) {
  const [now, setNow] = useState(Date.now());
  const activeSince = session.setStartedAt ?? session.restStartedAt;
  const elapsed = activeSince
    ? Math.max(0, Math.floor((now - new Date(activeSince).getTime()) / 1000))
    : 0;
  const phase = session.setStartedAt ? "set" : session.restStartedAt ? "rest" : "idle";

  useEffect(() => {
    setNow(Date.now());
    if (!activeSince) return;
    const timer = window.setInterval(() => setNow(Date.now()), 500);
    return () => window.clearInterval(timer);
  }, [activeSince]);

  return (
    <div className={`elapsed-timer phase-${phase}`}>
      <div>
        <Clock3 size={20} />
        <span>
          <small>{phase === "set" ? "Current set" : phase === "rest" ? "Rest" : "Ready"}</small>
          <strong>{formatTimer(elapsed)}</strong>
        </span>
      </div>
      <span className="suggested-rest-reference">
        <small>Suggested rest</small>
        <strong>{formatTimer(suggestedRestSeconds)}</strong>
      </span>
    </div>
  );
}

function FinishModal({
  session,
  onClose,
  onFinish
}: {
  session: WorkoutSession;
  onClose: () => void;
  onFinish: (status: Exclude<SetStatus, "pending" | "completed" | "failed">) => Promise<void>;
}) {
  const pending = session.exercises.reduce(
    (count, exercise) => count + exercise.sets.filter((set) => set.status === "pending").length,
    0
  );
  return (
    <Modal title="Finish workout" onClose={onClose}>
      {pending > 0 ? (
        <>
          <p className="modal-copy">{pending} planned sets are still untouched. Apply one reason to them.</p>
          <div className="reason-grid">
            <button onClick={() => void onFinish("skipped_time")}>Time</button>
            <button onClick={() => void onFinish("skipped_equipment")}>Equipment</button>
            <button className="danger-text" onClick={() => void onFinish("pain")}>
              Pain / discomfort
            </button>
            <button onClick={() => void onFinish("skipped_other")}>Other</button>
          </div>
        </>
      ) : (
        <button className="primary wide-button" onClick={() => void onFinish("skipped_other")}>
          <Check size={18} /> Complete workout
        </button>
      )}
    </Modal>
  );
}

function WorkoutNoteModal({
  initialValue,
  onClose,
  onSave
}: {
  initialValue: string;
  onClose: () => void;
  onSave: (value: string) => Promise<void>;
}) {
  const [value, setValue] = useState(initialValue);
  const [saving, setSaving] = useState(false);

  async function save() {
    setSaving(true);
    try {
      await onSave(value.trim());
      onClose();
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal title="Workout note" onClose={onClose}>
      <label className="field">
        <span>Note for this workout</span>
        <textarea
          value={value}
          onChange={(event) => setValue(event.target.value)}
          autoFocus
        />
      </label>
      <button
        className="primary wide-button"
        disabled={saving}
        onClick={() => void save()}
      >
        {saving ? "Saving..." : "Save note"}
      </button>
    </Modal>
  );
}

function OverviewModal({
  session,
  catalog,
  onClose,
  onChange,
  onSelect,
  onAdd,
  onInfo
}: {
  session: WorkoutSession;
  catalog: Exercise[];
  onClose: () => void;
  onChange: (session: WorkoutSession) => Promise<void>;
  onSelect: (index: number) => void;
  onAdd: () => void;
  onInfo: (exercise: Exercise) => void;
}) {
  async function move(index: number, direction: -1 | 1) {
    const nextIndex = index + direction;
    if (nextIndex < 0 || nextIndex >= session.exercises.length) return;
    const exercises = [...session.exercises];
    [exercises[index], exercises[nextIndex]] = [exercises[nextIndex], exercises[index]];
    await onChange({
      ...session,
      exercises: exercises.map((exercise, order) => ({ ...exercise, order: order + 1 })),
      currentExerciseIndex: nextIndex
    });
  }

  return (
    <Modal title="Current workout" onClose={onClose}>
      <div className="overview-list">
        {session.exercises.map((exercise, index) => {
          const completed = exercise.sets.filter((set) => set.status !== "pending").length;
          return (
            <div className="overview-row" key={exercise.id}>
              <button
                className="overview-main"
                onClick={() => {
                  onSelect(index);
                  onClose();
                }}
              >
                <span className="order-number">{index + 1}</span>
                <span>
                  <strong>{exercise.name}</strong>
                  <small>
                    {completed}/{exercise.sets.length} sets
                  </small>
                </span>
              </button>
              {catalog.find((item) => item.id === exercise.exerciseId) && (
                <IconButton
                  label={`Information about ${exercise.name}`}
                  onClick={() =>
                    onInfo(catalog.find((item) => item.id === exercise.exerciseId)!)
                  }
                >
                  <Info size={16} />
                </IconButton>
              )}
              <IconButton label="Move up" onClick={() => void move(index, -1)} disabled={index === 0}>
                <ArrowUp size={16} />
              </IconButton>
              <IconButton
                label="Move down"
                onClick={() => void move(index, 1)}
                disabled={index === session.exercises.length - 1}
              >
                <ArrowDown size={16} />
              </IconButton>
              <IconButton
                label="Remove exercise"
                className="danger-text"
                onClick={() =>
                  void onChange({
                    ...session,
                    exercises: session.exercises.filter((_, candidateIndex) => candidateIndex !== index),
                    currentExerciseIndex: Math.max(0, Math.min(session.currentExerciseIndex, session.exercises.length - 2))
                  })
                }
              >
                <Trash2 size={16} />
              </IconButton>
            </div>
          );
        })}
      </div>
      <button className="secondary wide-button" onClick={onAdd}>
        <Plus size={17} /> Add temporary exercise
      </button>
    </Modal>
  );
}

export function ActiveWorkoutScreen({ session: initialSession }: { session: WorkoutSession }) {
  const { profileId, gymId, refresh, notify } = useApp();
  const session = useQuery(
    () => db.sessions.get(initialSession.id).then((value) => value ?? initialSession),
    initialSession,
    [initialSession.id]
  );
  const workoutGymId = session.gymId ?? gymId;
  const catalog = useQuery(() => getCatalog(profileId), [] as Exercise[]);
  const equipmentProfiles = useQuery(
    () =>
      db.equipmentProfiles
        .where("profileId")
        .equals(profileId)
        .toArray()
        .then((profiles) =>
          profiles.filter((profile) => (profile.gymId ?? "basic_fit") === workoutGymId)
        ),
    [] as EquipmentProfile[],
    [workoutGymId]
  );
  const latestBodyWeight = useQuery<BodyWeightEntry | undefined>(
    () =>
      db.bodyWeights
        .where("profileId")
        .equals(profileId)
        .sortBy("date")
        .then((entries) => entries.at(-1)),
    undefined
  );
  const historicalSessions = useQuery(
    () =>
      db.sessions
        .where("profileId")
        .equals(profileId)
        .filter((candidate) => candidate.status === "completed")
        .toArray(),
    [] as WorkoutSession[],
    [workoutGymId]
  );
  const [overview, setOverview] = useState(false);
  const [picker, setPicker] = useState(false);
  const [creatingExercise, setCreatingExercise] = useState(false);
  const [workoutNoteOpen, setWorkoutNoteOpen] = useState(false);
  const [finish, setFinish] = useState(false);
  const [weightSetup, setWeightSetup] = useState(false);
  const [exerciseInfo, setExerciseInfo] = useState<Exercise | null>(null);
  const [handledWeightPrompts, setHandledWeightPrompts] = useState<Set<string>>(() => new Set());
  const [weight, setWeight] = useState("");
  const [reps, setReps] = useState("");
  const [seconds, setSeconds] = useState("");
  const [meters, setMeters] = useState("");
  const [effort, setEffort] = useState<EffortBand | "">("");

  useWakeLock(true);

  const exercise = session.exercises[session.currentExerciseIndex];
  const currentSet = exercise?.sets.find((set) => set.status === "pending");
  const equipment = equipmentProfiles.find(
    (item) =>
      item.exerciseId === exercise?.exerciseId &&
      (item.gymId ?? "basic_fit") === workoutGymId
  );
  const sourceExercise = catalog.find((item) => item.id === exercise?.exerciseId);
  const historicalEstimate =
    exercise?.suggestedWeightKg === undefined && sourceExercise
      ? historicalWorkingWeight(sourceExercise, historicalSessions, workoutGymId)
      : undefined;
  const displayedSuggestedWeight =
    exercise?.suggestedWeightKg ?? historicalEstimate?.weightKg;
  const displayedSuggestionReason =
    exercise?.suggestionReason ?? historicalEstimate?.reason;
  const displayedSuggestionDetail =
    exercise?.suggestionDetail ?? historicalEstimate?.detail;
  const displayedSuggestionConfidence =
    exercise?.suggestionConfidence ?? historicalEstimate?.confidence;
  const needsWeight = exercise ? loadMeasurements.has(exercise.measurementType) : false;
  const needsReps = exercise?.target.metric === "reps";
  const needsSeconds =
    exercise?.measurementType === "load_distance_duration" || exercise?.target.metric === "seconds";
  const needsMeters =
    exercise?.measurementType === "load_distance_duration" || exercise?.target.metric === "meters";

  useEffect(() => {
    if (
      exercise &&
      exercise.loadDirection !== "none" &&
      !equipment &&
      !handledWeightPrompts.has(`${workoutGymId}:${exercise.exerciseId}`)
    ) {
      setWeightSetup(true);
    }
  }, [equipment, exercise, handledWeightPrompts, workoutGymId]);

  async function persist(next: WorkoutSession) {
    await db.sessions.put(next);
    refresh();
  }

  async function saveWorkoutNote(notes: string) {
    await persist({ ...session, notes });
    notify(notes ? "Workout note saved" : "Workout note removed");
  }

  function clearInputs() {
    setWeight("");
    setReps("");
    setSeconds("");
    setMeters("");
    setEffort("");
  }

  async function saveEquipment(values: MachineProfileValues) {
    if (!exercise) return;
    setHandledWeightPrompts((current) =>
      new Set(current).add(`${workoutGymId}:${exercise.exerciseId}`)
    );
    setWeightSetup(false);
    await db.equipmentProfiles.put({
      id: `${profileId}:${workoutGymId}:${exercise.exerciseId}`,
      profileId,
      gymId: workoutGymId,
      exerciseId: exercise.exerciseId,
      ...values,
      prompted: true,
      updatedAt: new Date().toISOString()
    });
    refresh();
  }

  async function skipEquipment() {
    await saveEquipment({
      availableWeightsKg: [],
      weightRanges: [],
      setupParameters: []
    });
  }

  async function saveSetupParameter(parameterId: string, value: string) {
    if (!equipment) return;
    await db.equipmentProfiles.put({
      ...equipment,
      setupParameters: (equipment.setupParameters ?? []).map((parameter) =>
        parameter.id === parameterId ? { ...parameter, value } : parameter
      ),
      updatedAt: new Date().toISOString()
    });
    refresh();
    notify("Machine setup saved");
  }

  function updateExercise(nextExercise: SessionExercise): WorkoutSession {
    return {
      ...session,
      exercises: session.exercises.map((candidate, index) =>
        index === session.currentExerciseIndex ? nextExercise : candidate
      )
    };
  }

  async function startCurrentSet() {
    if (!exercise || !currentSet || session.setStartedAt) return;
    const now = new Date();
    const restBeforeSeconds = session.restStartedAt
      ? Math.max(
          0,
          Math.round((now.getTime() - new Date(session.restStartedAt).getTime()) / 1000)
        )
      : undefined;
    const timedSet = { ...currentSet, restBeforeSeconds };
    await persist({
      ...updateExercise({
        ...exercise,
        sets: exercise.sets.map((set) => (set.id === currentSet.id ? timedSet : set))
      }),
      setStartedAt: now.toISOString(),
      timingSetId: currentSet.id,
      restStartedAt: undefined,
      restEndsAt: undefined,
      restDurationSeconds: undefined
    });
  }

  async function stopCurrentSet() {
    if (!exercise || !currentSet || !session.setStartedAt) return;
    const finishedAt = new Date();
    const duration = Math.max(
      0,
      Math.round((finishedAt.getTime() - new Date(session.setStartedAt).getTime()) / 1000)
    );
    const timedSet = { ...currentSet, setDurationSeconds: duration };
    await persist({
      ...updateExercise({
        ...exercise,
        sets: exercise.sets.map((set) => (set.id === currentSet.id ? timedSet : set))
      }),
      setStartedAt: undefined,
      timingSetId: undefined,
      restStartedAt: finishedAt.toISOString()
    });
  }

  async function completeCurrent() {
    if (
      !exercise ||
      !currentSet ||
      currentSet.setDurationSeconds === undefined ||
      !effort
    ) {
      return;
    }
    const nextSet: SetLog = {
      ...currentSet,
      status: "completed",
      weightKg: weight ? Number(weight) : undefined,
      reps: reps ? Number(reps) : undefined,
      seconds: seconds ? Number(seconds) : undefined,
      meters: meters ? Number(meters) : undefined,
      effort,
      completedAt: new Date().toISOString()
    };
    const suggestion =
      sourceExercise &&
      suggestNextWeight(
        nextSet,
        exercise.target,
        sourceExercise,
        equipment,
        latestBodyWeight?.weightKg
      );
    const nextExercise = {
      ...exercise,
      suggestedWeightKg:
        suggestion?.weightKg ?? exercise.suggestedWeightKg ?? historicalEstimate?.weightKg,
      suggestionReason:
        suggestion?.reason ?? exercise.suggestionReason ?? historicalEstimate?.reason,
      suggestionDetail:
        suggestion?.detail ?? exercise.suggestionDetail ?? historicalEstimate?.detail,
      suggestionConfidence:
        suggestion?.confidence ??
        exercise.suggestionConfidence ??
        historicalEstimate?.confidence,
      sets: exercise.sets.map((set) => (set.id === currentSet.id ? nextSet : set))
    };
    let nextIndex = session.currentExerciseIndex;
    if (!nextExercise.sets.some((set) => set.status === "pending")) {
      if (exercise.supersetGroup) {
        const groupCandidate = session.exercises.findIndex(
          (candidate, index) =>
            index !== session.currentExerciseIndex &&
            candidate.supersetGroup === exercise.supersetGroup &&
            candidate.sets.some((set) => set.status === "pending")
        );
        nextIndex =
          groupCandidate >= 0
            ? groupCandidate
            : Math.min(session.currentExerciseIndex + 1, session.exercises.length - 1);
      } else {
        nextIndex = Math.min(session.currentExerciseIndex + 1, session.exercises.length - 1);
      }
    }
    await persist({
      ...updateExercise(nextExercise),
      currentExerciseIndex: nextIndex,
      restStartedAt: session.restStartedAt ?? new Date().toISOString(),
      restEndsAt: undefined,
      restDurationSeconds: undefined
    });
    if (suggestion) notify(`${suggestion.reason}: ${suggestion.weightKg} kg`);
    clearInputs();
  }

  async function markCurrent(status: SetStatus) {
    if (!exercise || !currentSet) return;
    const next: SetLog = {
      ...currentSet,
      status,
      setDurationSeconds:
        currentSet.setDurationSeconds ??
        (session.setStartedAt
          ? Math.max(
              0,
              Math.round((Date.now() - new Date(session.setStartedAt).getTime()) / 1000)
            )
          : undefined),
      weightKg: weight ? Number(weight) : undefined,
      reps: reps ? Number(reps) : undefined,
      seconds: seconds ? Number(seconds) : undefined,
      meters: meters ? Number(meters) : undefined,
      completedAt: new Date().toISOString()
    };
    await persist({
      ...updateExercise({
        ...exercise,
        sets: exercise.sets.map((set) => (set.id === currentSet.id ? next : set))
      }),
      setStartedAt: undefined,
      timingSetId: undefined,
      restStartedAt: session.restStartedAt ?? new Date().toISOString()
    });
    clearInputs();
  }

  async function addSet(type: "working" | "warmup") {
    if (!exercise) return;
    const next: SetLog = {
      id: createId("set"),
      order: exercise.sets.length + 1,
      setType: type,
      status: "pending"
    };
    await persist(updateExercise({ ...exercise, sets: [...exercise.sets, next], plannedSets: exercise.plannedSets + (type === "working" ? 1 : 0) }));
  }

  async function finishWorkout(reason: Exclude<SetStatus, "pending" | "completed" | "failed">) {
    const now = new Date();
    const finalized: WorkoutSession = {
      ...session,
      status: "completed",
      completedAt: now.toISOString(),
      durationSeconds: Math.max(0, Math.round((now.getTime() - new Date(session.startedAt).getTime()) / 1000)),
      setStartedAt: undefined,
      timingSetId: undefined,
      restStartedAt: undefined,
      restEndsAt: undefined,
      exercises: session.exercises.map((item) => ({
        ...item,
        sets: item.sets.map((set) => (set.status === "pending" ? { ...set, status: reason } : set))
      }))
    };
    await db.transaction("rw", [db.sessions, db.plans, db.milestones], async () => {
      await db.sessions.put(finalized);
      if (!finalized.planId) return;
      const plan = await db.plans.get(finalized.planId);
      if (!plan) return;
      const milestones: Milestone[] = [];
      const updatedEntries = plan.exercises.map((entry) => {
        const performed = finalized.exercises.find((item) => item.sourcePlanExerciseId === entry.id);
        if (!performed) return entry;
        const nextWeight = planWeightAfterSession(entry, performed);
        if (nextWeight === undefined) return entry;
        milestones.push({
          id: createId("milestone"),
          profileId,
          planId: plan.id,
          planExerciseId: entry.id,
          exerciseId: performed.exerciseId,
          exerciseName: performed.name,
          sessionId: finalized.id,
          occurredAt: now.toISOString(),
          previousWeightKg: entry.currentWeightKg ?? entry.startingWeightKg,
          newWeightKg: nextWeight,
          direction: performed.loadDirection,
          seen: false
        });
        return { ...entry, currentWeightKg: nextWeight };
      });
      const updatedPlan: WorkoutPlan = { ...plan, exercises: updatedEntries, updatedAt: now.toISOString() };
      await db.plans.put(updatedPlan);
      if (milestones.length) await db.milestones.bulkAdd(milestones);
    });
    setFinish(false);
    refresh();
    notify("Workout completed");
  }

  async function addTemporary(exerciseToAdd: Exercise) {
    const added: SessionExercise = {
      id: createId("session_exercise"),
      exerciseId: exerciseToAdd.id,
      name: exerciseToAdd.name,
      order: session.exercises.length + 1,
      notes: "",
      measurementType: exerciseToAdd.measurementType,
      loadDirection: exerciseToAdd.loadDirection,
      loadBasis: exerciseToAdd.loadBasis,
      target: { metric: exerciseToAdd.measurementType.includes("duration") ? "seconds" : "reps", min: 8, max: 12, rirMin: 1, rirMax: 2 },
      restSeconds: 90,
      plannedSets: 2,
      sets: [1, 2].map((order) => ({ id: createId("set"), order, setType: "working", status: "pending" }))
    };
    await persist({ ...session, exercises: [...session.exercises, added] });
    setPicker(false);
    setOverview(false);
  }

  if (!exercise) {
    return (
      <div className="empty-state">
        <h2>This workout has no exercises</h2>
        <button className="secondary" onClick={() => setPicker(true)}>
          Add an exercise
        </button>
      </div>
    );
  }

  const completedSets = exercise.sets.filter((set) => set.status !== "pending");
  const setInProgress = Boolean(
    session.setStartedAt && session.timingSetId === currentSet?.id
  );
  const awaitingSetData = Boolean(
    currentSet && currentSet.setDurationSeconds !== undefined && !setInProgress
  );
  const pendingCount = session.exercises.reduce(
    (count, item) => count + item.sets.filter((set) => set.status === "pending").length,
    0
  );
  const canComplete =
    awaitingSetData &&
    Boolean(effort) &&
    (!needsWeight || Boolean(weight)) &&
    (!needsReps || Boolean(reps)) &&
    (!needsSeconds || Boolean(seconds)) &&
    (!needsMeters || Boolean(meters));

  return (
    <>
      <div className="workout-top">
        <div>
          <p className="eyebrow">
            {session.planName} / {getGym(workoutGymId).name} /{" "}
            {session.mode === "ultra" ? "Ultra fast" : session.mode}
          </p>
          <div className="exercise-title-row">
            <h1>{exercise.name}</h1>
            {sourceExercise && (
              <IconButton
                label={`Information about ${exercise.name}`}
                onClick={() => setExerciseInfo(sourceExercise)}
              >
                <Info size={19} />
              </IconButton>
            )}
          </div>
        </div>
        <div className="heading-actions">
          <button
            className={`secondary compact ${session.notes ? "has-note" : ""}`}
            onClick={() => setWorkoutNoteOpen(true)}
          >
            <NotebookPen size={18} /> Note
          </button>
          <button
            className="secondary compact"
            disabled={Boolean(session.setStartedAt)}
            onClick={() => setOverview(true)}
          >
            <List size={18} /> Overview
          </button>
        </div>
      </div>
      <ElapsedTimer session={session} suggestedRestSeconds={exercise.restSeconds || 90} />
      <div className="exercise-position">
        <IconButton
          label="Previous exercise"
          disabled={session.currentExerciseIndex === 0 || Boolean(session.setStartedAt)}
          onClick={() => void persist({ ...session, currentExerciseIndex: session.currentExerciseIndex - 1 })}
        >
          <ArrowLeft size={19} />
        </IconButton>
        <span>
          Exercise {session.currentExerciseIndex + 1} of {session.exercises.length}
        </span>
        <IconButton
          label="Next exercise"
          disabled={
            session.currentExerciseIndex === session.exercises.length - 1 ||
            Boolean(session.setStartedAt)
          }
          onClick={() => void persist({ ...session, currentExerciseIndex: session.currentExerciseIndex + 1 })}
        >
          <ArrowRight size={19} />
        </IconButton>
      </div>
      <section className="exercise-focus">
        <div className="target-strip">
          <span>
            <small>Target</small>
            <strong>
              {exercise.target.min}-{exercise.target.max} {exercise.target.metric}
            </strong>
          </span>
          <span>
            <small>Effort</small>
            <strong>
              {exercise.target.rirMin}-{exercise.target.rirMax} RIR
            </strong>
          </span>
          <span>
            <small>Working weight</small>
            <strong>
              {displayedSuggestedWeight !== undefined
                ? `${displayedSuggestedWeight} kg`
                : "Not set"}
            </strong>
          </span>
        </div>
        {displayedSuggestionReason && (
          <details className="suggestion-detail">
            <summary>
              <span>{displayedSuggestionReason}</span>
              <small>{displayedSuggestionConfidence ?? "low"} confidence</small>
            </summary>
            {displayedSuggestionDetail && <p>{displayedSuggestionDetail}</p>}
          </details>
        )}
        <div className="machine-setup-panel">
          <div className="section-title-row">
            <div>
              <strong>Machine setup</strong>
              <small>{getGym(workoutGymId).name}</small>
            </div>
            <button className="secondary compact" onClick={() => setWeightSetup(true)}>
              <Settings2 size={16} /> Configure
            </button>
          </div>
          {equipment?.setupParameters?.length ? (
            <div className="machine-setup-fields">
              {equipment.setupParameters.map((parameter) => (
                <label className="field compact-field" key={parameter.id}>
                  <span>{parameter.name}</span>
                  <input
                    defaultValue={parameter.value}
                    onBlur={(event) =>
                      void saveSetupParameter(parameter.id, event.currentTarget.value)
                    }
                  />
                </label>
              ))}
            </div>
          ) : (
            <p>No saved setup parameters for this machine at this gym.</p>
          )}
        </div>
        <div className="set-history">
          {completedSets.map((set) => (
            <div className={`logged-set status-${set.status}`} key={set.id}>
              <span>Set {set.order}</span>
              <strong>
                {set.weightKg !== undefined ? `${set.weightKg} kg` : ""}
                {set.reps !== undefined ? ` × ${set.reps}` : ""}
                {set.seconds !== undefined ? ` · ${set.seconds}s` : ""}
                {set.meters !== undefined ? ` · ${set.meters}m` : ""}
              </strong>
              <small>
                {set.effort ? effortLabels[set.effort] : set.status.replaceAll("_", " ")}
                {set.setDurationSeconds !== undefined
                  ? ` / set ${formatTimer(set.setDurationSeconds)}`
                  : ""}
                {set.restBeforeSeconds !== undefined
                  ? ` / rest ${formatTimer(set.restBeforeSeconds)}`
                  : ""}
              </small>
            </div>
          ))}
        </div>
        {currentSet ? (
          <div className="set-entry">
            <div className="set-entry-header">
              <span className="set-badge">
                {currentSet.setType === "warmup" ? "Warm-up" : "Working"} set {currentSet.order}
              </span>
              {awaitingSetData && displayedSuggestedWeight !== undefined && needsWeight && (
                <button className="suggestion-chip" onClick={() => setWeight(String(displayedSuggestedWeight))}>
                  <Dumbbell size={15} /> Use {displayedSuggestedWeight} kg
                </button>
              )}
            </div>
            {needsWeight && (
              <div className="set-weight-reference">
                <Dumbbell size={21} />
                <span>
                  <small>
                    {exercise.loadDirection === "lower_assistance"
                      ? "Suggested assistance"
                      : "Suggested set weight"}
                  </small>
                  <strong>
                    {displayedSuggestedWeight !== undefined
                      ? `${displayedSuggestedWeight} kg`
                      : "Not set"}
                  </strong>
                </span>
              </div>
            )}
            {setInProgress ? (
              <button className="stop-set-button wide-button" onClick={() => void stopCurrentSet()}>
                <Square size={19} /> Finish set
              </button>
            ) : !awaitingSetData ? (
              <button className="primary start-set-button wide-button" onClick={() => void startCurrentSet()}>
                <Play size={19} /> Start set
              </button>
            ) : (
              <>
                <p className="set-duration-note">
                  Set duration: {formatTimer(currentSet.setDurationSeconds ?? 0)}
                </p>
                <div className="entry-grid">
                  {needsWeight && (
                    <label className="field">
                      <span>
                        {exercise.loadDirection === "lower_assistance"
                          ? "Assistance (kg)"
                          : "Weight (kg)"}
                      </span>
                      <input
                        type="number"
                        inputMode="decimal"
                        min="0"
                        step="any"
                        value={weight}
                        onChange={(event) => setWeight(event.target.value)}
                      />
                    </label>
                  )}
                  {needsReps && (
                    <label className="field">
                      <span>Repetitions</span>
                      <input
                        type="number"
                        inputMode="numeric"
                        min="0"
                        value={reps}
                        onChange={(event) => setReps(event.target.value)}
                      />
                    </label>
                  )}
                  {needsSeconds && (
                    <label className="field">
                      <span>Seconds</span>
                      <input
                        type="number"
                        inputMode="numeric"
                        min="0"
                        value={seconds}
                        onChange={(event) => setSeconds(event.target.value)}
                      />
                    </label>
                  )}
                  {needsMeters && (
                    <label className="field">
                      <span>Meters</span>
                      <input
                        type="number"
                        inputMode="decimal"
                        min="0"
                        value={meters}
                        onChange={(event) => setMeters(event.target.value)}
                      />
                    </label>
                  )}
                </div>
                <fieldset className="effort-fieldset">
                  <legend>{needsReps ? "Repetitions in reserve" : "Effort reserve"}</legend>
                  <div className="effort-options">
                    {(Object.keys(effortLabels) as EffortBand[]).map((value) => (
                      <button
                        type="button"
                        key={value}
                        className={effort === value ? "selected" : ""}
                        onClick={() => setEffort(value)}
                      >
                        {effortLabels[value]}
                      </button>
                    ))}
                  </div>
                </fieldset>
                <button
                  className="primary complete-set"
                  disabled={!canComplete}
                  onClick={() => void completeCurrent()}
                >
                  <Check size={19} /> Save set
                </button>
              </>
            )}
            <div className="set-secondary-actions">
              <button className="text-button" onClick={() => void markCurrent("failed")}>
                <AlertTriangle size={16} /> Failed / too heavy
              </button>
              <button className="text-button danger-text" onClick={() => void markCurrent("pain")}>
                <X size={16} /> Pain / discomfort
              </button>
              <button className="text-button" onClick={() => void markCurrent("skipped_time")}>
                <SkipForward size={16} /> Skip
              </button>
            </div>
          </div>
        ) : (
          <div className="exercise-complete">
            <Check size={24} />
            <strong>Exercise complete</strong>
            <span>Move on whenever you are ready.</span>
          </div>
        )}
        <div className="add-set-actions">
          <button className="secondary" onClick={() => void addSet("working")}>
            <Plus size={16} /> Working set
          </button>
          <button className="secondary" onClick={() => void addSet("warmup")}>
            <TimerReset size={16} /> Warm-up set
          </button>
        </div>
      </section>
      <div className="workout-footer">
        <span>{pendingCount} planned sets remaining</span>
        <button className="primary" onClick={() => setFinish(true)}>
          Finish workout
        </button>
      </div>
      {weightSetup && (
        <MachineProfileEditor
          exerciseName={exercise.name}
          gymName={getGym(workoutGymId).name}
          profile={equipment}
          onClose={() => {
            setHandledWeightPrompts((current) =>
              new Set(current).add(`${workoutGymId}:${exercise.exerciseId}`)
            );
            setWeightSetup(false);
          }}
          onSave={saveEquipment}
          onSkip={equipment ? undefined : skipEquipment}
        />
      )}
      {overview && (
        <OverviewModal
          session={session}
          catalog={catalog}
          onClose={() => setOverview(false)}
          onChange={persist}
          onSelect={(index) => void persist({ ...session, currentExerciseIndex: index })}
          onAdd={() => setPicker(true)}
          onInfo={(item) => setExerciseInfo(item)}
        />
      )}
      {picker && (
        <Modal title="Add temporary exercise" onClose={() => setPicker(false)} wide>
          <ExercisePicker
            exercises={catalog}
            onSelect={(item) => void addTemporary(item)}
            onCreate={() => setCreatingExercise(true)}
          />
        </Modal>
      )}
      {creatingExercise && (
        <CustomExerciseModal
          onClose={() => setCreatingExercise(false)}
          onSaved={(exercise) => {
            refresh();
            void addTemporary(exercise);
          }}
        />
      )}
      {exerciseInfo && (
        <ExerciseInfoModal exercise={exerciseInfo} onClose={() => setExerciseInfo(null)} />
      )}
      {workoutNoteOpen && (
        <WorkoutNoteModal
          initialValue={session.notes}
          onClose={() => setWorkoutNoteOpen(false)}
          onSave={saveWorkoutNote}
        />
      )}
      {finish && <FinishModal session={session} onClose={() => setFinish(false)} onFinish={finishWorkout} />}
    </>
  );
}
