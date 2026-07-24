import { ArrowDown, ArrowLeft, ArrowUp, Info, Plus, Save, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { ExerciseInfoModal } from "../components/ExerciseInfoModal";
import { ExercisePicker } from "../components/ExercisePicker";
import { navigate } from "../components/AppShell";
import { IconButton, Modal } from "../components/ui";
import { useApp } from "../context/AppContext";
import { useQuery } from "../hooks/useQuery";
import { getCatalog } from "../lib/catalog";
import { createId, db } from "../lib/db";
import type { Exercise, PlanExercise, WorkoutPlan } from "../types";
import { CustomExerciseModal } from "./ExercisesScreen";

export function PlanEditorScreen({ planId }: { planId: string }) {
  const { profileId, revision, refresh, notify } = useApp();
  const loaded = useQuery(() => db.plans.get(planId), undefined as WorkoutPlan | undefined, [planId]);
  const catalog = useQuery(() => getCatalog(profileId), [] as Exercise[]);
  const [plan, setPlan] = useState<WorkoutPlan | null>(null);
  const [picker, setPicker] = useState(false);
  const [creatingExercise, setCreatingExercise] = useState(false);
  const [exerciseInfo, setExerciseInfo] = useState<Exercise | null>(null);

  useEffect(() => {
    if (loaded && !plan) setPlan(structuredClone(loaded));
  }, [loaded, plan, revision]);

  if (!plan) return <div className="loading">Loading plan...</div>;

  function updateEntry(id: string, patch: Partial<PlanExercise>) {
    setPlan((current) =>
      current
        ? { ...current, exercises: current.exercises.map((entry) => (entry.id === id ? { ...entry, ...patch } : entry)) }
        : current
    );
  }

  function move(id: string, direction: -1 | 1) {
    setPlan((current) => {
      if (!current) return current;
      const sorted = [...current.exercises].sort((a, b) => a.order - b.order);
      const index = sorted.findIndex((entry) => entry.id === id);
      const swap = index + direction;
      if (swap < 0 || swap >= sorted.length) return current;
      [sorted[index], sorted[swap]] = [sorted[swap], sorted[index]];
      return {
        ...current,
        exercises: sorted.map((entry, order) => ({ ...entry, order: order + 1 }))
      };
    });
  }

  function addExercise(exercise: Exercise) {
    const currentPlan = plan;
    if (!currentPlan) return;
    const entry: PlanExercise = {
      id: createId("plan_exercise"),
      exerciseId: exercise.id,
      order: currentPlan.exercises.length + 1,
      modeSets: { normal: 3, fast: 2, ultra: 1 },
      target: {
        metric:
          exercise.measurementType.includes("duration")
            ? "seconds"
            : exercise.measurementType === "load_distance_duration"
              ? "meters"
              : "reps",
        min: exercise.measurementType.includes("duration") ? 30 : 8,
        max: exercise.measurementType.includes("duration") ? 45 : 12,
        rirMin: 1,
        rirMax: 2
      },
      restSeconds: 90,
      notes: ""
    };
    setPlan((current) => (current ? { ...current, exercises: [...current.exercises, entry] } : current));
    setPicker(false);
  }

  async function save() {
    if (!plan) return;
    const updated: WorkoutPlan = {
      ...plan,
      name: plan.name.trim() || "Untitled workout",
      updatedAt: new Date().toISOString()
    };
    await db.plans.put(updated);
    setPlan(updated);
    refresh();
    notify("Plan saved");
  }

  return (
    <>
      <div className="page-heading">
        <div className="heading-with-back">
          <IconButton label="Back to workouts" onClick={() => navigate("workouts")}>
            <ArrowLeft size={20} />
          </IconButton>
          <div>
            <p className="eyebrow">Permanent plan editor</p>
            <h1>Edit workout</h1>
          </div>
        </div>
        <button className="primary compact" onClick={() => void save()}>
          <Save size={18} /> Save
        </button>
      </div>
      <div className="editor-meta">
        <label className="field">
          <span>Workout name</span>
          <input value={plan.name} onChange={(event) => setPlan({ ...plan, name: event.target.value })} />
        </label>
      </div>
      <div className="editor-list">
        {plan.exercises
          .sort((a, b) => a.order - b.order)
          .map((entry, index) => {
            const exercise = catalog.find((candidate) => candidate.id === entry.exerciseId);
            return (
              <article className="editor-card" key={entry.id}>
                <header>
                  <span className="order-number">{index + 1}</span>
                  <div>
                    <div className="editor-title-row">
                      <h2>{exercise?.name ?? "Unknown exercise"}</h2>
                      {exercise && (
                        <IconButton
                          label={`Information about ${exercise.name}`}
                          onClick={() => setExerciseInfo(exercise)}
                        >
                          <Info size={16} />
                        </IconButton>
                      )}
                    </div>
                    <p>{exercise?.equipment ?? "Exercise definition unavailable"}</p>
                  </div>
                  <div className="move-actions">
                    <IconButton label="Move up" disabled={index === 0} onClick={() => move(entry.id, -1)}>
                      <ArrowUp size={17} />
                    </IconButton>
                    <IconButton
                      label="Move down"
                      disabled={index === plan.exercises.length - 1}
                      onClick={() => move(entry.id, 1)}
                    >
                      <ArrowDown size={17} />
                    </IconButton>
                    <IconButton
                      label="Remove exercise"
                      className="danger-text"
                      onClick={() =>
                        setPlan({
                          ...plan,
                          exercises: plan.exercises
                            .filter((candidate) => candidate.id !== entry.id)
                            .map((candidate, order) => ({ ...candidate, order: order + 1 }))
                        })
                      }
                    >
                      <Trash2 size={17} />
                    </IconButton>
                  </div>
                </header>
                <div className="editor-grid">
                  {(["normal", "fast", "ultra"] as const).map((mode) => (
                    <label className="field compact-field" key={mode}>
                      <span>{mode === "ultra" ? "Ultra fast sets" : `${mode[0].toUpperCase()}${mode.slice(1)} sets`}</span>
                      <select
                        value={entry.modeSets[mode]}
                        onChange={(event) =>
                          updateEntry(entry.id, {
                            modeSets: { ...entry.modeSets, [mode]: Number(event.target.value) }
                          })
                        }
                      >
                        {[0, 1, 2, 3, 4].map((value) => (
                          <option key={value}>{value}</option>
                        ))}
                      </select>
                    </label>
                  ))}
                  <label className="field compact-field">
                    <span>Target metric</span>
                    <select
                      value={entry.target.metric}
                      onChange={(event) =>
                        updateEntry(entry.id, {
                          target: { ...entry.target, metric: event.target.value as PlanExercise["target"]["metric"] }
                        })
                      }
                    >
                      <option value="reps">Repetitions</option>
                      <option value="seconds">Seconds</option>
                      <option value="meters">Meters</option>
                    </select>
                  </label>
                  <label className="field compact-field">
                    <span>Target min</span>
                    <input
                      type="number"
                      value={entry.target.min}
                      onChange={(event) =>
                        updateEntry(entry.id, { target: { ...entry.target, min: Number(event.target.value) } })
                      }
                    />
                  </label>
                  <label className="field compact-field">
                    <span>Target max</span>
                    <input
                      type="number"
                      value={entry.target.max}
                      onChange={(event) =>
                        updateEntry(entry.id, { target: { ...entry.target, max: Number(event.target.value) } })
                      }
                    />
                  </label>
                  <label className="field compact-field">
                    <span>Starting weight (kg)</span>
                    <input
                      type="number"
                      step="0.5"
                      value={entry.currentWeightKg ?? entry.startingWeightKg ?? ""}
                      onChange={(event) =>
                        updateEntry(entry.id, {
                          startingWeightKg: event.target.value ? Number(event.target.value) : undefined,
                          currentWeightKg: event.target.value ? Number(event.target.value) : undefined
                        })
                      }
                    />
                  </label>
                  <label className="field compact-field">
                    <span>Target RIR min</span>
                    <input
                      type="number"
                      min="0"
                      max="4"
                      value={entry.target.rirMin}
                      onChange={(event) =>
                        updateEntry(entry.id, { target: { ...entry.target, rirMin: Number(event.target.value) } })
                      }
                    />
                  </label>
                  <label className="field compact-field">
                    <span>Target RIR max</span>
                    <input
                      type="number"
                      min="0"
                      max="4"
                      value={entry.target.rirMax}
                      onChange={(event) =>
                        updateEntry(entry.id, { target: { ...entry.target, rirMax: Number(event.target.value) } })
                      }
                    />
                  </label>
                  <label className="field compact-field">
                    <span>Rest seconds</span>
                    <input
                      type="number"
                      value={entry.restSeconds}
                      onChange={(event) => updateEntry(entry.id, { restSeconds: Number(event.target.value) })}
                    />
                  </label>
                  <label className="field compact-field">
                    <span>Superset group</span>
                    <input
                      value={entry.supersetGroup ?? ""}
                      onChange={(event) => updateEntry(entry.id, { supersetGroup: event.target.value || undefined })}
                      placeholder="Optional, e.g. A"
                    />
                  </label>
                </div>
              </article>
            );
          })}
      </div>
      <button className="secondary wide-button" onClick={() => setPicker(true)}>
        <Plus size={18} /> Add exercise
      </button>
      {picker && (
        <Modal title="Add exercise" onClose={() => setPicker(false)} wide>
          <ExercisePicker
            exercises={catalog}
            onSelect={addExercise}
            onCreate={() => setCreatingExercise(true)}
          />
        </Modal>
      )}
      {creatingExercise && (
        <CustomExerciseModal
          onClose={() => setCreatingExercise(false)}
          onSaved={(exercise) => {
            refresh();
            addExercise(exercise);
          }}
        />
      )}
      {exerciseInfo && (
        <ExerciseInfoModal exercise={exerciseInfo} onClose={() => setExerciseInfo(null)} />
      )}
    </>
  );
}
