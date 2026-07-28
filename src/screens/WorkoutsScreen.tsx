import {
  ClipboardPlus,
  Download,
  FileUp,
  Info,
  MoreVertical,
  Pencil,
  Play,
  Trash2
} from "lucide-react";
import { useRef, useState } from "react";
import { ExerciseInfoModal } from "../components/ExerciseInfoModal";
import { CustomExerciseModal } from "./ExercisesScreen";
import { useApp } from "../context/AppContext";
import { useQuery } from "../hooks/useQuery";
import { db, createId } from "../lib/db";
import { getCatalog } from "../lib/catalog";
import {
  csvTemplate,
  importRowsToPlan,
  parsePlanCsv,
  type CsvParseResult
} from "../lib/csv";
import { guidedStartingEstimate, historicalWorkingWeight } from "../lib/progression";
import type {
  EquipmentProfile,
  Exercise,
  GymId,
  SessionExercise,
  WorkoutMode,
  WorkoutPlan,
  WorkoutSession
} from "../types";
import { EmptyState, IconButton, Modal, Segmented } from "../components/ui";
import { navigate } from "../components/AppShell";

function modeLabel(mode: WorkoutMode) {
  return mode === "ultra" ? "Ultra fast" : mode[0].toUpperCase() + mode.slice(1);
}

function StartWorkoutModal({
  plan,
  exercises,
  completedSessions,
  equipmentProfiles,
  gymId,
  onClose,
  onStarted
}: {
  plan: WorkoutPlan;
  exercises: Exercise[];
  completedSessions: WorkoutSession[];
  equipmentProfiles: EquipmentProfile[];
  gymId: GymId;
  onClose: () => void;
  onStarted: () => void;
}) {
  const [mode, setMode] = useState<WorkoutMode>("normal");
  const [exerciseInfo, setExerciseInfo] = useState<Exercise | null>(null);
  const [counts, setCounts] = useState<Record<string, number>>(() =>
    Object.fromEntries(plan.exercises.map((entry) => [entry.id, entry.modeSets.normal]))
  );

  function updateMode(next: WorkoutMode) {
    setMode(next);
    setCounts(Object.fromEntries(plan.exercises.map((entry) => [entry.id, entry.modeSets[next]])));
  }

  async function start() {
    const catalogMap = new Map(exercises.map((exercise) => [exercise.id, exercise]));
    const included = plan.exercises
      .filter((entry) => (counts[entry.id] ?? 0) > 0)
      .sort((a, b) => a.order - b.order);
    if (!included.length) return;
    const sessionExercises: SessionExercise[] = included.map((entry, index) => {
      const exercise = catalogMap.get(entry.exerciseId);
      const setCount = counts[entry.id];
      const savedWeight = entry.currentWeightKg ?? entry.startingWeightKg;
      const gymSessions = completedSessions.filter(
        (session) => (session.gymId ?? "basic_fit") === gymId
      );
      const exactHistoryEstimate =
        savedWeight === undefined && exercise
          ? historicalWorkingWeight(exercise, gymSessions, gymId)
          : undefined;
      const relatedEstimate =
        savedWeight === undefined && exercise
          ? guidedStartingEstimate(
              exercise,
              gymSessions,
              exercises,
              equipmentProfiles.find((item) => item.exerciseId === exercise.id)
            )
          : undefined;
      const startingEstimate = exactHistoryEstimate ?? relatedEstimate;
      return {
        id: createId("session_exercise"),
        sourcePlanExerciseId: entry.id,
        exerciseId: entry.exerciseId,
        name: exercise?.name ?? "Unknown exercise",
        order: index + 1,
        notes: "",
        measurementType: exercise?.measurementType ?? "load_reps",
        loadDirection: exercise?.loadDirection ?? "higher",
        loadBasis: exercise?.loadBasis ?? "stack",
        target: entry.target,
        restSeconds: entry.restSeconds,
        supersetGroup: entry.supersetGroup,
        plannedSets: setCount,
        suggestedWeightKg: savedWeight ?? startingEstimate?.weightKg,
        suggestionReason: startingEstimate?.reason,
        suggestionDetail: startingEstimate?.detail,
        suggestionEvidence: startingEstimate?.evidence,
        sets: Array.from({ length: setCount }, (_, setIndex) => ({
          id: createId("set"),
          order: setIndex + 1,
          setType: "working" as const,
          status: "pending" as const
        }))
      };
    });
    const session: WorkoutSession = {
      id: createId("session"),
      profileId: plan.profileId,
      gymId,
      planId: plan.id,
      planName: plan.name,
      mode,
      status: "active",
      startedAt: new Date().toISOString(),
      currentExerciseIndex: 0,
      notes: "",
      exercises: sessionExercises
    };
    await db.sessions.add(session);
    onStarted();
    onClose();
  }

  return (
    <>
    <Modal title={`Start ${plan.name}`} onClose={onClose}>
      <label className="field">
        <span>Time mode</span>
        <Segmented
          label="Workout time mode"
          value={mode}
          onChange={updateMode}
          options={[
            { value: "normal", label: "Normal" },
            { value: "fast", label: "Fast" },
            { value: "ultra", label: "Ultra fast" }
          ]}
        />
      </label>
      <div className="setup-list">
        {plan.exercises
          .sort((a, b) => a.order - b.order)
          .map((entry) => {
            const exercise = exercises.find((candidate) => candidate.id === entry.exerciseId);
            return (
              <div className="setup-row" key={entry.id}>
                <span>
                  <strong>{exercise?.name ?? "Unknown exercise"}</strong>
                  <small>{counts[entry.id] === 0 ? "Not included" : `${counts[entry.id]} working sets`}</small>
                </span>
                {exercise && (
                  <IconButton
                    label={`Information about ${exercise.name}`}
                    onClick={() => setExerciseInfo(exercise)}
                  >
                    <Info size={17} />
                  </IconButton>
                )}
                <select
                  aria-label={`Sets for ${exercise?.name}`}
                  value={counts[entry.id] ?? 0}
                  onChange={(event) =>
                    setCounts((current) => ({ ...current, [entry.id]: Number(event.target.value) }))
                  }
                >
                  {[0, 1, 2, 3, 4].map((value) => (
                    <option key={value} value={value}>
                      {value === 0 ? "Skip" : value}
                    </option>
                  ))}
                </select>
              </div>
            );
          })}
      </div>
      <button className="primary wide-button" onClick={start}>
        <Play size={18} /> Start {modeLabel(mode)}
      </button>
    </Modal>
    {exerciseInfo && (
      <ExerciseInfoModal exercise={exerciseInfo} onClose={() => setExerciseInfo(null)} />
    )}
    </>
  );
}

interface ImportPreview {
  parsed: CsvParseResult;
  resolved: Record<number, string>;
  unresolved: number[];
  exercises: Exercise[];
}

function ImportModal({
  preview,
  existingPlans,
  onClose,
  onImported
}: {
  preview: ImportPreview;
  existingPlans: WorkoutPlan[];
  onClose: () => void;
  onImported: () => void;
}) {
  const { profileId, notify } = useApp();
  const [resolved, setResolved] = useState(preview.resolved);
  const [availableExercises, setAvailableExercises] = useState(preview.exercises);
  const [creatingExercise, setCreatingExercise] = useState<{
    name: string;
    index: number;
  } | null>(null);
  const [conflictMode, setConflictMode] = useState<"replace" | "copy" | "skip">("replace");
  const planName = preview.parsed.rows[0]?.planName ?? "";
  const conflict = existingPlans.find((plan) => plan.name.toLowerCase() === planName.toLowerCase());
  const unresolved = preview.parsed.rows
    .map((row, index) => ({ row, index }))
    .filter(({ index }) => !resolved[index]);

  async function commit() {
    if (unresolved.length || preview.parsed.errors.length || (conflict && conflictMode === "skip")) return;
    const existingId = conflict && conflictMode === "replace" ? conflict.id : undefined;
    const plan = importRowsToPlan(preview.parsed.rows, resolved, profileId, existingId);
    if (existingId) {
      plan.createdAt = conflict!.createdAt;
      await db.plans.put(plan);
    } else {
      if (conflict && conflictMode === "copy") plan.name = `${plan.name} (copy)`;
      await db.plans.add(plan);
    }
    notify("Workout plan imported");
    onImported();
    onClose();
  }

  return (
    <Modal title="Import preview" onClose={onClose} wide>
      {preview.parsed.errors.length > 0 && (
        <div className="callout danger">
          <strong>Fix these CSV errors</strong>
          {preview.parsed.errors.map((error) => (
            <p key={error}>{error}</p>
          ))}
        </div>
      )}
      <div className="import-summary">
        <span>
          <small>Plan</small>
          <strong>{planName || "Invalid CSV"}</strong>
        </span>
        <span>
          <small>Exercises</small>
          <strong>{preview.parsed.rows.length}</strong>
        </span>
        <span>
          <small>Unresolved</small>
          <strong>{unresolved.length}</strong>
        </span>
      </div>
      {conflict && (
        <label className="field">
          <span>A plan with this name already exists</span>
          <select value={conflictMode} onChange={(event) => setConflictMode(event.target.value as typeof conflictMode)}>
            <option value="replace">Replace saved definition</option>
            <option value="copy">Import as a copy</option>
            <option value="skip">Skip import</option>
          </select>
        </label>
      )}
      {unresolved.map(({ row, index }) => (
        <div className="mapping-row" key={`${row.exerciseName}-${index}`}>
          <span>
            <strong>{row.exerciseName}</strong>
            <small>No matching exercise in My exercises</small>
          </span>
          <select
            value=""
            onChange={(event) => setResolved((current) => ({ ...current, [index]: event.target.value }))}
          >
            <option value="">Map to existing...</option>
            {availableExercises.map((exercise) => (
              <option value={exercise.id} key={exercise.id}>
                {exercise.name}
              </option>
            ))}
          </select>
          <button
            className="secondary"
            onClick={() => setCreatingExercise({ name: row.exerciseName, index })}
          >
            Define exercise
          </button>
        </div>
      ))}
      <button
        className="primary wide-button"
        onClick={commit}
        disabled={unresolved.length > 0 || preview.parsed.errors.length > 0 || conflictMode === "skip"}
      >
        Import plan
      </button>
      {creatingExercise && (
        <CustomExerciseModal
          initialName={creatingExercise.name}
          onClose={() => setCreatingExercise(null)}
          onSaved={(exercise) => {
            setAvailableExercises((current) => [...current, exercise]);
            setResolved((current) => ({
              ...current,
              [creatingExercise.index]: exercise.id
            }));
            setCreatingExercise(null);
          }}
        />
      )}
    </Modal>
  );
}

export function WorkoutsScreen() {
  const { profileId, gymId, refresh, notify } = useApp();
  const plans = useQuery(
    () => db.plans.where("profileId").equals(profileId).sortBy("updatedAt").then((rows) => rows.reverse()),
    [] as WorkoutPlan[]
  );
  const loadedExercises = useQuery(
    () => getCatalog(profileId),
    undefined as Exercise[] | undefined
  );
  const completedSessions = useQuery(
    () =>
      db.sessions
        .where("profileId")
        .equals(profileId)
        .filter((session) => session.status === "completed")
        .toArray(),
    [] as WorkoutSession[]
  );
  const equipmentProfiles = useQuery(
    () =>
      db.equipmentProfiles
        .where("profileId")
        .equals(profileId)
        .toArray()
        .then((profiles) =>
          profiles.filter((profile) => (profile.gymId ?? "basic_fit") === gymId)
        ),
    [] as EquipmentProfile[],
    [gymId]
  );
  const [startPlan, setStartPlan] = useState<WorkoutPlan | null>(null);
  const [importPreview, setImportPreview] = useState<ImportPreview | null>(null);
  const [packlistOpen, setPacklistOpen] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  if (!loadedExercises) return <div className="loading">Loading workouts...</div>;
  const exercises = loadedExercises;

  async function createPlan() {
    const now = new Date().toISOString();
    const plan: WorkoutPlan = {
      id: createId("plan"),
      profileId,
      name: "New workout",
      notes: "",
      createdAt: now,
      updatedAt: now,
      exercises: []
    };
    await db.plans.add(plan);
    refresh();
    navigate(`plan/${plan.id}`);
  }

  async function removePlan(plan: WorkoutPlan) {
    if (!window.confirm(`Permanently delete "${plan.name}"? Completed history will remain.`)) return;
    await db.plans.delete(plan.id);
    refresh();
    notify("Plan deleted");
  }

  async function handleFile(file: File) {
    const parsed = parsePlanCsv(await file.text());
    const resolved: Record<number, string> = {};
    parsed.rows.forEach((row, index) => {
      const match = exercises.find((exercise) => {
        if (row.exerciseId && exercise.id.toLowerCase() === row.exerciseId.toLowerCase()) return true;
        return [exercise.name, ...exercise.aliases].some(
          (name) => name.trim().toLowerCase() === row.exerciseName.trim().toLowerCase()
        );
      });
      if (match) resolved[index] = match.id;
    });
    setImportPreview({
      parsed,
      resolved,
      unresolved: parsed.rows.map((_, index) => index).filter((index) => !resolved[index]),
      exercises
    });
  }

  function downloadTemplate() {
    const blob = new Blob([csvTemplate], { type: "text/csv;charset=utf-8" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = "gymapp-workout-template.csv";
    link.click();
    URL.revokeObjectURL(link.href);
  }

  return (
    <>
      <div className="page-heading">
        <div>
          <p className="eyebrow">Choose your session</p>
          <h1>Workouts</h1>
        </div>
        <div className="heading-actions">
          <IconButton label="Gym packlist" onClick={() => setPacklistOpen(true)}>
            <Info size={20} />
          </IconButton>
          <IconButton label="Download CSV template" onClick={downloadTemplate}>
            <Download size={20} />
          </IconButton>
          <IconButton label="Import plan" onClick={() => fileRef.current?.click()}>
            <FileUp size={20} />
          </IconButton>
          <button className="primary compact" onClick={createPlan}>
            <ClipboardPlus size={18} /> New
          </button>
        </div>
        <input
          ref={fileRef}
          type="file"
          accept=".csv,text/csv"
          hidden
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) void handleFile(file);
            event.target.value = "";
          }}
        />
      </div>
      {plans.length === 0 ? (
        <EmptyState
          title="No saved workouts"
          detail="Create one here or import a professional CSV plan."
          action={
            <button className="primary" onClick={createPlan}>
              <ClipboardPlus size={18} /> Create workout
            </button>
          }
        />
      ) : (
        <div className="plan-list">
          {plans.map((plan) => (
            <article className="plan-card" key={plan.id}>
              <button className="plan-main" onClick={() => setStartPlan(plan)}>
                <span className="plan-icon">
                  <Play size={20} />
                </span>
                <span>
                  <strong>{plan.name}</strong>
                  <small>
                    {plan.exercises.length} exercises · Normal / Fast / Ultra fast
                  </small>
                </span>
              </button>
              <div className="plan-actions">
                <IconButton label={`Edit ${plan.name}`} onClick={() => navigate(`plan/${plan.id}`)}>
                  <Pencil size={18} />
                </IconButton>
                <details>
                  <summary aria-label={`More actions for ${plan.name}`}>
                    <MoreVertical size={18} />
                  </summary>
                  <div className="menu">
                    <button className="danger-text" onClick={() => void removePlan(plan)}>
                      <Trash2 size={16} /> Delete
                    </button>
                  </div>
                </details>
              </div>
            </article>
          ))}
        </div>
      )}
      {startPlan && (
        <StartWorkoutModal
          plan={startPlan}
          exercises={exercises}
          completedSessions={completedSessions}
          equipmentProfiles={equipmentProfiles}
          gymId={gymId}
          onClose={() => setStartPlan(null)}
          onStarted={() => {
            refresh();
            notify("Workout started");
          }}
        />
      )}
      {packlistOpen && (
        <Modal title="Gym packlist" onClose={() => setPacklistOpen(false)}>
          <p className="modal-copy">Pack these before leaving for the gym:</p>
          <ul className="packlist">
            <li>Headphones</li>
            <li>Towel</li>
            <li>Gym clothes</li>
            <li>Shoes</li>
            <li>Water bottle</li>
            <li>Lock</li>
          </ul>
        </Modal>
      )}
      {importPreview && (
        <ImportModal
          preview={importPreview}
          existingPlans={plans}
          onClose={() => setImportPreview(null)}
          onImported={refresh}
        />
      )}
    </>
  );
}
