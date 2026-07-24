import {
  Camera,
  Download,
  FileUp,
  Pencil,
  Plus,
  Search,
  Trash2,
  X
} from "lucide-react";
import { useMemo, useRef, useState } from "react";
import { EmptyState, IconButton, Modal } from "../components/ui";
import { useApp } from "../context/AppContext";
import { useQuery } from "../hooks/useQuery";
import { getCatalog, searchExercises } from "../lib/catalog";
import { createId, db } from "../lib/db";
import {
  applyExerciseImport,
  classifyExerciseImports,
  defaultLoadBasis,
  exerciseCsvTemplate,
  loadBasisLabels,
  loadBasisOptions,
  loadDirectionForMeasurement,
  measurementLabels,
  parseExerciseCsv,
  type ExerciseCsvParseResult,
  type ExerciseImportDecision
} from "../lib/exerciseCsv";
import type { Exercise, MeasurementType } from "../types";

async function resizePhoto(file: File): Promise<string> {
  const bitmap = await createImageBitmap(file);
  const max = 960;
  const scale = Math.min(1, max / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext("2d")!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL("image/webp", 0.8);
}

export function CustomExerciseModal({
  exercise,
  initialName = "",
  onClose,
  onSaved
}: {
  exercise?: Exercise;
  initialName?: string;
  onClose: () => void;
  onSaved: (exercise: Exercise) => void;
}) {
  const { profileId } = useApp();
  const [name, setName] = useState(exercise?.name ?? initialName);
  const [equipment, setEquipment] = useState(exercise?.equipment ?? "machine");
  const [measurementType, setMeasurementType] = useState<MeasurementType>(
    exercise?.measurementType ?? "load_reps"
  );
  const [loadBasis, setLoadBasis] = useState<Exercise["loadBasis"]>(
    exercise?.loadBasis ?? defaultLoadBasis(exercise?.measurementType ?? "load_reps")
  );
  const [muscles, setMuscles] = useState(exercise?.primaryMuscles.join(", ") ?? "");
  const [instructions, setInstructions] = useState(exercise?.instructions.join("\n") ?? "");
  const [photo, setPhoto] = useState(exercise?.customPhoto ?? "");
  const fileRef = useRef<HTMLInputElement>(null);

  async function save() {
    if (!name.trim()) return;
    const saved: Exercise = {
      id: exercise?.id ?? createId("custom_exercise"),
      name: name.trim(),
      aliases: exercise?.aliases ?? [],
      equipment: equipment.trim() || "other",
      category: exercise?.category ?? "strength",
      primaryMuscles: muscles.split(",").map((item) => item.trim()).filter(Boolean),
      secondaryMuscles: exercise?.secondaryMuscles ?? [],
      instructions: instructions.split("\n").map((item) => item.trim()).filter(Boolean),
      imageUrls: exercise?.imageUrls ?? [],
      measurementType,
      loadDirection: loadDirectionForMeasurement(measurementType),
      loadBasis,
      builtIn: false,
      profileId,
      customPhoto: photo || undefined
    };
    await db.transaction(
      "rw",
      [db.customExercises, db.sessions, db.milestones],
      async () => {
        await db.customExercises.put(saved);
        if (!exercise) return;

        const [sessions, milestones] = await Promise.all([
          db.sessions.where("profileId").equals(profileId).toArray(),
          db.milestones.where("profileId").equals(profileId).toArray()
        ]);
        const changedSessions = sessions
          .filter((session) =>
            session.exercises.some((item) => item.exerciseId === saved.id)
          )
          .map((session) => ({
            ...session,
            exercises: session.exercises.map((item) =>
              item.exerciseId === saved.id
                ? {
                    ...item,
                    name: saved.name,
                    measurementType: saved.measurementType,
                    loadDirection: saved.loadDirection,
                    loadBasis: saved.loadBasis
                  }
                : item
            )
          }));
        const changedMilestones = milestones
          .filter((milestone) => milestone.exerciseId === saved.id)
          .map((milestone) => ({ ...milestone, exerciseName: saved.name }));
        if (changedSessions.length) await db.sessions.bulkPut(changedSessions);
        if (changedMilestones.length) await db.milestones.bulkPut(changedMilestones);
      }
    );
    onSaved(saved);
    onClose();
  }

  return (
    <Modal title={exercise ? "Edit exercise" : "Create exercise"} onClose={onClose}>
      <label className="photo-picker">
        <button type="button" onClick={() => fileRef.current?.click()}>
          {photo ? <img src={photo} alt="Custom exercise preview" /> : <Camera size={28} />}
        </button>
        <span>Optional local photo</span>
        <input
          ref={fileRef}
          type="file"
          accept="image/*"
          capture="environment"
          hidden
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) void resizePhoto(file).then(setPhoto);
          }}
        />
      </label>
      <label className="field">
        <span>Name</span>
        <input value={name} onChange={(event) => setName(event.target.value)} autoFocus />
      </label>
      <label className="field">
        <span>Equipment</span>
        <input value={equipment} onChange={(event) => setEquipment(event.target.value)} />
      </label>
      <label className="field">
        <span>Measurement</span>
        <select
          value={measurementType}
          onChange={(event) => {
            const next = event.target.value as MeasurementType;
            setMeasurementType(next);
            if (
              !(loadBasisOptions[next] as readonly Exercise["loadBasis"][]).includes(
                loadBasis
              )
            ) {
              setLoadBasis(defaultLoadBasis(next));
            }
          }}
        >
          {Object.entries(measurementLabels).map(([value, label]) => (
            <option value={value} key={value}>
              {label}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>Weight basis</span>
        <select
          value={loadBasis}
          onChange={(event) =>
            setLoadBasis(event.target.value as Exercise["loadBasis"])
          }
        >
          {loadBasisOptions[measurementType].map((value) => (
            <option value={value} key={value}>
              {loadBasisLabels[value]}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>Primary muscles, comma-separated</span>
        <input value={muscles} onChange={(event) => setMuscles(event.target.value)} />
      </label>
      <label className="field">
        <span>Instructions, one step per line</span>
        <textarea value={instructions} onChange={(event) => setInstructions(event.target.value)} />
      </label>
      <button className="primary wide-button" disabled={!name.trim()} onClick={() => void save()}>
        {exercise ? "Save exercise" : "Create exercise"}
      </button>
    </Modal>
  );
}

function ExerciseImportModal({
  preview,
  exercises,
  onClose,
  onImported
}: {
  preview: ExerciseCsvParseResult;
  exercises: Exercise[];
  onClose: () => void;
  onImported: (result: {
    created: number;
    updated: number;
    skipped: number;
  }) => void;
}) {
  const { profileId } = useApp();
  const classified = useMemo(
    () => classifyExerciseImports(preview.rows, exercises),
    [exercises, preview.rows]
  );
  const [decisions, setDecisions] = useState<
    Record<number, ExerciseImportDecision | undefined>
  >(() =>
    Object.fromEntries(
      classified.map((item) => [
        item.row.sourceRow,
        item.status === "new"
          ? "create"
          : item.status === "existing"
            ? "skip"
            : undefined
      ])
    )
  );
  const [saving, setSaving] = useState(false);
  const [commitError, setCommitError] = useState("");
  const unresolved = classified.filter(
    (item) => item.status === "conflict" && !decisions[item.row.sourceRow]
  ).length;
  const selected = classified.filter(
    (item) =>
      decisions[item.row.sourceRow] &&
      decisions[item.row.sourceRow] !== "skip"
  ).length;
  const counts = {
    new: classified.filter((item) => item.status === "new").length,
    existing: classified.filter((item) => item.status === "existing").length,
    conflict: classified.filter((item) => item.status === "conflict").length
  };

  async function commit() {
    if (preview.errors.length || unresolved || !selected) return;
    setSaving(true);
    setCommitError("");
    try {
      const result = await applyExerciseImport(classified, decisions, profileId);
      onImported(result);
    } catch (error) {
      setCommitError(
        error instanceof Error ? error.message : "The exercise import failed."
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal title="Import exercises" onClose={onClose} wide>
      {preview.errors.length > 0 && (
        <div className="callout danger">
          <strong>Fix these CSV errors</strong>
          {preview.errors.map((error) => (
            <p key={error}>{error}</p>
          ))}
        </div>
      )}
      <div className="import-summary">
        <span>
          <small>Rows</small>
          <strong>{preview.rows.length}</strong>
        </span>
        <span>
          <small>New</small>
          <strong>{counts.new}</strong>
        </span>
        <span>
          <small>Existing</small>
          <strong>{counts.existing}</strong>
        </span>
        <span>
          <small>Conflicts</small>
          <strong>{counts.conflict}</strong>
        </span>
      </div>
      {counts.conflict > 0 && (
        <div className="callout warning">
          <strong>Review conflicting definitions</strong>
          <p>
            Choose whether to keep, update, or copy each conflicting exercise.
          </p>
        </div>
      )}
      {commitError && (
        <div className="callout danger">
          <strong>Import failed</strong>
          <p>{commitError}</p>
        </div>
      )}
      <div className="exercise-import-list">
        {classified.map((item) => {
          const decision = decisions[item.row.sourceRow] ?? "";
          return (
            <article
              className={`exercise-import-row import-${item.status}`}
              key={item.row.sourceRow}
            >
              <header>
                <span>
                  <strong>{item.row.name}</strong>
                  <small>CSV row {item.row.sourceRow}</small>
                </span>
                <span className={`import-status status-${item.status}`}>
                  {item.status === "new"
                    ? "New"
                    : item.status === "existing"
                      ? "Existing"
                      : "Conflict"}
                </span>
              </header>
              <div className="exercise-import-definition">
                <span>
                  <small>Measurement</small>
                  <strong>{measurementLabels[item.row.measurementType]}</strong>
                </span>
                <span>
                  <small>Weight basis</small>
                  <strong>{loadBasisLabels[item.row.loadBasis]}</strong>
                </span>
                <span>
                  <small>Equipment</small>
                  <strong>{item.row.equipment}</strong>
                </span>
              </div>
              {item.existing && (
                <p className="import-match-copy">
                  Matches <strong>{item.existing.name}</strong> by{" "}
                  {item.matchedBy === "id" ? "exercise ID" : "name"}.
                </p>
              )}
              {item.nameCollision && (
                <p className="import-match-copy">
                  The supplied ID belongs to <strong>{item.existing?.name}</strong>,
                  but this name is already used by{" "}
                  <strong>{item.nameCollision.name}</strong>. Updating is disabled.
                </p>
              )}
              {item.suppliedIdUnavailable && (
                <p className="import-match-copy">
                  The supplied ID is not present in this profile. A new internal
                  ID will be created unless the name matches an existing exercise.
                </p>
              )}
              <label className="field compact-field">
                <span>Import action</span>
                <select
                  value={decision}
                  onChange={(event) =>
                    setDecisions((current) => ({
                      ...current,
                      [item.row.sourceRow]: event.target
                        .value as ExerciseImportDecision
                    }))
                  }
                >
                  {item.status === "conflict" && (
                    <option value="">Choose action...</option>
                  )}
                  {item.status === "new" && (
                    <option value="create">Create exercise</option>
                  )}
                  <option value="skip">
                    {item.existing ? "Keep existing / skip" : "Skip row"}
                  </option>
                  {item.existing && !item.nameCollision && (
                    <option value="update">Update existing exercise</option>
                  )}
                  {item.existing && (
                    <option value="copy">Import as a copy</option>
                  )}
                </select>
              </label>
            </article>
          );
        })}
      </div>
      {!preview.errors.length && !classified.length && (
        <p className="modal-copy">The CSV does not contain any exercise rows.</p>
      )}
      <button
        className="primary wide-button"
        disabled={
          saving ||
          preview.errors.length > 0 ||
          unresolved > 0 ||
          selected === 0
        }
        onClick={() => void commit()}
      >
        {saving
          ? "Importing..."
          : `Import ${selected} exercise${selected === 1 ? "" : "s"}`}
      </button>
    </Modal>
  );
}

function ExerciseDetail({
  exercise,
  onClose,
  onDeleted,
  onUpdated
}: {
  exercise: Exercise;
  onClose: () => void;
  onDeleted: () => void;
  onUpdated: () => void;
}) {
  const [editing, setEditing] = useState(false);
  async function remove() {
    if (exercise.builtIn || !window.confirm(`Permanently delete "${exercise.name}"?`)) return;
    const plans = await db.plans.where("profileId").equals(exercise.profileId!).toArray();
    if (plans.some((plan) => plan.exercises.some((entry) => entry.exerciseId === exercise.id))) {
      window.alert("Remove this exercise from saved plans before deleting it.");
      return;
    }
    const sessions = await db.sessions.where("profileId").equals(exercise.profileId!).toArray();
    if (sessions.some((session) => session.exercises.some((entry) => entry.exerciseId === exercise.id))) {
      window.alert("Delete workout history using this exercise before deleting its definition.");
      return;
    }
    await db.customExercises.delete(exercise.id);
    onDeleted();
    onClose();
  }

  const images = exercise.customPhoto ? [exercise.customPhoto] : exercise.imageUrls;
  return (
    <Modal title={exercise.name} onClose={onClose} wide>
      {images.length > 0 && (
        <div className="exercise-images">
          {images.slice(0, 2).map((url) => (
            <img src={url} alt={`${exercise.name} demonstration`} key={url} />
          ))}
        </div>
      )}
      <div className="tag-row">
        <span>{exercise.equipment}</span>
        {exercise.primaryMuscles.map((muscle) => (
          <span key={muscle}>{muscle}</span>
        ))}
      </div>
      <div className="instruction-list">
        {exercise.instructions.length ? (
          exercise.instructions.map((instruction, index) => (
            <p key={`${instruction}-${index}`}>
              <strong>{index + 1}</strong>
              {instruction}
            </p>
          ))
        ) : (
          <p>No instructions are available for this exercise yet.</p>
        )}
      </div>
      {!exercise.builtIn && (
        <div className="button-row">
          <button className="secondary" onClick={() => setEditing(true)}>
            <Pencil size={17} /> Edit exercise
          </button>
          <button className="secondary danger-text" onClick={() => void remove()}>
            <Trash2 size={17} /> Delete exercise
          </button>
        </div>
      )}
      {editing && (
        <CustomExerciseModal
          exercise={exercise}
          onClose={() => setEditing(false)}
          onSaved={() => {
            onUpdated();
            onClose();
          }}
        />
      )}
    </Modal>
  );
}

export function ExercisesScreen() {
  const { profileId, refresh, notify } = useApp();
  const catalog = useQuery(() => getCatalog(profileId), [] as Exercise[]);
  const [query, setQuery] = useState("");
  const [equipment, setEquipment] = useState("all");
  const [customOpen, setCustomOpen] = useState(false);
  const [importPreview, setImportPreview] =
    useState<ExerciseCsvParseResult | null>(null);
  const [selected, setSelected] = useState<Exercise | null>(null);
  const importRef = useRef<HTMLInputElement>(null);
  const equipmentOptions = useMemo(
    () => ["all", ...new Set(catalog.map((exercise) => exercise.equipment).filter(Boolean))].sort(),
    [catalog]
  );
  const filtered = useMemo(() => searchExercises(catalog, query, equipment), [catalog, equipment, query]);

  function downloadTemplate() {
    const blob = new Blob([exerciseCsvTemplate], {
      type: "text/csv;charset=utf-8"
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "gymapp-exercises-template.csv";
    link.click();
    URL.revokeObjectURL(url);
  }

  return (
    <>
      <div className="page-heading">
        <div>
          <p className="eyebrow">{catalog.length} exercises configured</p>
          <h1>My exercises</h1>
        </div>
        <div className="heading-actions">
          <IconButton
            label="Download exercise CSV template"
            onClick={downloadTemplate}
          >
            <Download size={18} />
          </IconButton>
          <button
            className="secondary compact"
            onClick={() => importRef.current?.click()}
          >
            <FileUp size={18} /> Import CSV
          </button>
          <button className="primary compact" onClick={() => setCustomOpen(true)}>
            <Plus size={18} /> New
          </button>
          <input
            ref={importRef}
            type="file"
            accept=".csv,text/csv"
            hidden
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) {
                void file.text().then((content) => {
                  setImportPreview(parseExerciseCsv(content));
                  event.target.value = "";
                });
              }
            }}
          />
        </div>
      </div>
      <div className="catalog-controls">
        <label className="search-field">
          <Search size={18} />
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search exercises" />
          {query && (
            <IconButton label="Clear search" onClick={() => setQuery("")}>
              <X size={16} />
            </IconButton>
          )}
        </label>
        <select value={equipment} onChange={(event) => setEquipment(event.target.value)}>
          {equipmentOptions.map((value) => (
            <option key={value} value={value}>
              {value === "all" ? "All equipment" : value}
            </option>
          ))}
        </select>
      </div>
      {filtered.length === 0 ? (
        <EmptyState
          title={catalog.length ? "No matching exercise" : "No exercises configured"}
          detail={
            catalog.length
              ? "Try a different name, muscle, or equipment filter."
              : "Create each exercise with the measurement and progression direction you use."
          }
        />
      ) : (
        <div className="exercise-grid">
          {filtered.map((exercise) => (
            <button className="exercise-card" key={exercise.id} onClick={() => setSelected(exercise)}>
              <span className="exercise-card-image">
                {exercise.customPhoto || exercise.imageUrls[0] ? (
                  <img src={exercise.customPhoto ?? exercise.imageUrls[0]} alt="" loading="lazy" />
                ) : (
                  <span>{exercise.name.slice(0, 2).toUpperCase()}</span>
                )}
              </span>
              <span>
                <strong>{exercise.name}</strong>
                <small>{exercise.equipment}</small>
                <small>{exercise.primaryMuscles.slice(0, 2).join(", ")}</small>
              </span>
            </button>
          ))}
        </div>
      )}
      {customOpen && (
        <CustomExerciseModal
          onClose={() => setCustomOpen(false)}
          onSaved={() => {
            refresh();
            notify("Exercise created");
          }}
        />
      )}
      {selected && (
        <ExerciseDetail
          exercise={selected}
          onClose={() => setSelected(null)}
          onDeleted={() => {
            refresh();
            notify("Exercise deleted");
          }}
          onUpdated={() => {
            refresh();
            notify("Exercise updated");
          }}
        />
      )}
      {importPreview && (
        <ExerciseImportModal
          preview={importPreview}
          exercises={catalog}
          onClose={() => setImportPreview(null)}
          onImported={(result) => {
            setImportPreview(null);
            refresh();
            notify(
              `${result.created} created, ${result.updated} updated, ${result.skipped} skipped`
            );
          }}
        />
      )}
    </>
  );
}
