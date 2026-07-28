import {
  CalendarDays,
  ChevronRight,
  Download,
  FileUp,
  Pencil,
  Trash2
} from "lucide-react";
import { useMemo, useRef, useState } from "react";
import { EmptyState, IconButton, Modal } from "../components/ui";
import { CustomExerciseModal } from "./ExercisesScreen";
import { useApp } from "../context/AppContext";
import { useQuery } from "../hooks/useQuery";
import { findExerciseMatch, getCatalog } from "../lib/catalog";
import { db } from "../lib/db";
import { getGym } from "../lib/gyms";
import {
  historicalCsvTemplate,
  historicalRowsToSessions,
  parseHistoricalCsv,
  type HistoricalCsvParseResult,
  type HistoricalCsvRow
} from "../lib/historicalCsv";
import { recalculatePlanProgression } from "../lib/recalculate";
import type { EffortBand, Exercise, WorkoutSession } from "../types";
import { effortLabels, selectableEffortBands } from "../types";

function formatDuration(seconds?: number) {
  if (!seconds) return "No duration";
  const minutes = Math.floor(seconds / 60);
  return `${minutes} min`;
}

function formatClock(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

interface HistoricalImportPreview {
  parsed: HistoricalCsvParseResult;
  resolved: Record<number, string>;
  exercises: Exercise[];
}

function exerciseReference(row: HistoricalCsvRow): string {
  return row.exerciseId
    ? `id:${row.exerciseId.toLowerCase()}`
    : `name:${row.exerciseName.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim()}`;
}

function HistoricalImportModal({
  preview,
  existingSessions,
  onClose,
  onImported
}: {
  preview: HistoricalImportPreview;
  existingSessions: WorkoutSession[];
  onClose: () => void;
  onImported: () => void;
}) {
  const { profileId, notify } = useApp();
  const [resolved, setResolved] = useState(preview.resolved);
  const [exercises, setExercises] = useState(preview.exercises);
  const [creatingExercise, setCreatingExercise] = useState<{
    row: HistoricalCsvRow;
    indexes: number[];
  } | null>(null);
  const sessionKeys = [...new Set(preview.parsed.rows.map((row) => row.sessionKey))];
  const existingKeys = new Set(
    existingSessions.map((session) => session.importKey).filter(Boolean)
  );
  const duplicateKeys = sessionKeys.filter((key) => existingKeys.has(`history_csv:${key}`));
  const duplicateKeySet = new Set(duplicateKeys);
  const unresolvedGroups = (() => {
    const groups = new Map<string, { row: HistoricalCsvRow; indexes: number[] }>();
    preview.parsed.rows.forEach((row, index) => {
      if (resolved[index] || duplicateKeySet.has(row.sessionKey)) return;
      const key = exerciseReference(row);
      const group = groups.get(key) ?? { row, indexes: [] };
      group.indexes.push(index);
      groups.set(key, group);
    });
    return [...groups.values()];
  })();
  const importCount = sessionKeys.length - duplicateKeys.length;

  function resolveGroup(indexes: number[], exerciseId: string) {
    setResolved((current) => ({
      ...current,
      ...Object.fromEntries(indexes.map((index) => [index, exerciseId]))
    }));
  }

  async function commit() {
    if (preview.parsed.errors.length || unresolvedGroups.length || importCount <= 0) return;
    const converted = historicalRowsToSessions(
      preview.parsed.rows,
      resolved,
      exercises,
      profileId
    ).filter((session) => !existingKeys.has(session.importKey));
    await db.sessions.bulkAdd(converted);
    notify(
      `${converted.length} historical workout${converted.length === 1 ? "" : "s"} imported`
    );
    onImported();
    onClose();
  }

  return (
    <Modal title="Historical workout import" onClose={onClose} wide>
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
          <small>Sessions</small>
          <strong>{sessionKeys.length}</strong>
        </span>
        <span>
          <small>Set rows</small>
          <strong>{preview.parsed.rows.length}</strong>
        </span>
        <span>
          <small>Unresolved</small>
          <strong>{unresolvedGroups.length}</strong>
        </span>
      </div>
      {duplicateKeys.length > 0 && (
        <div className="callout warning">
          <strong>{duplicateKeys.length} duplicate session IDs will be skipped</strong>
          <p>Change session_id in the CSV only when this is genuinely a different workout.</p>
        </div>
      )}
      {unresolvedGroups.map(({ row, indexes }) => (
        <div className="mapping-row" key={exerciseReference(row)}>
          <span>
            <strong>{row.exerciseName}</strong>
            <small>{indexes.length} set row{indexes.length === 1 ? "" : "s"}</small>
          </span>
          <select
            value=""
            aria-label={`Map ${row.exerciseName}`}
            onChange={(event) => resolveGroup(indexes, event.target.value)}
          >
            <option value="">Map to existing...</option>
            {exercises.map((exercise) => (
              <option value={exercise.id} key={exercise.id}>
                {exercise.name}
              </option>
            ))}
          </select>
          <button
            className="secondary"
            onClick={() => setCreatingExercise({ row, indexes })}
          >
            Define exercise
          </button>
        </div>
      ))}
      <div className="callout">
        <strong>Historical sessions do not modify saved plan weights</strong>
        <p>They appear in History and Progress after import.</p>
      </div>
      <button
        className="primary wide-button"
        disabled={
          preview.parsed.errors.length > 0 ||
          unresolvedGroups.length > 0 ||
          importCount <= 0
        }
        onClick={() => void commit()}
      >
        Import {importCount} workout{importCount === 1 ? "" : "s"}
      </button>
      {creatingExercise && (
        <CustomExerciseModal
          initialName={creatingExercise.row.exerciseName}
          onClose={() => setCreatingExercise(null)}
          onSaved={(exercise) => {
            setExercises((current) => [...current, exercise]);
            resolveGroup(creatingExercise.indexes, exercise.id);
            setCreatingExercise(null);
          }}
        />
      )}
    </Modal>
  );
}

function SessionModal({
  initial,
  onClose,
  onSaved,
  onDeleted
}: {
  initial: WorkoutSession;
  onClose: () => void;
  onSaved: () => void;
  onDeleted: () => void;
}) {
  const [session, setSession] = useState(structuredClone(initial));
  const [editing, setEditing] = useState(false);

  async function save() {
    await db.sessions.put(session);
    if (session.planId) await recalculatePlanProgression(session.planId);
    onSaved();
    setEditing(false);
  }

  async function remove() {
    const confirmation = window.prompt(
      `Type "${session.planName}" to permanently delete this session and recalculate progress.`
    );
    if (confirmation !== session.planName) return;
    await db.sessions.delete(session.id);
    await db.milestones.where("sessionId").equals(session.id).delete();
    if (session.planId) await recalculatePlanProgression(session.planId);
    onDeleted();
    onClose();
  }

  return (
    <Modal title={session.planName} onClose={onClose} wide>
      <div className="session-meta">
        <span>{new Date(session.completedAt ?? session.startedAt).toLocaleString()}</span>
        <span>{formatDuration(session.durationSeconds)}</span>
        <span>{session.mode === "ultra" ? "Ultra fast" : session.mode}</span>
        <span>{getGym(session.gymId ?? "basic_fit").name}</span>
      </div>
      {editing && session.notes ? (
        <label className="field">
          <span>Legacy workout note</span>
          <textarea
            value={session.notes}
            onChange={(event) =>
              setSession({ ...session, notes: event.target.value })
            }
          />
        </label>
      ) : (
        session.notes && (
          <div className="session-note">
            <small>Workout note</small>
            <p>{session.notes}</p>
          </div>
        )
      )}
      <div className="session-detail-list">
        {session.exercises.map((exercise, exerciseIndex) => (
          <section key={exercise.id} className="history-exercise">
            <h3>{exercise.name}</h3>
            {editing ? (
              <label className="field">
                <span>Note for {exercise.name}</span>
                <textarea
                  value={exercise.notes}
                  onChange={(event) => {
                    const exercises = structuredClone(session.exercises);
                    exercises[exerciseIndex].notes = event.target.value;
                    setSession({ ...session, exercises });
                  }}
                />
              </label>
            ) : (
              exercise.notes && (
                <div className="session-note exercise-note">
                  <small>Exercise note</small>
                  <p>{exercise.notes}</p>
                </div>
              )
            )}
            {exercise.sets.map((set, setIndex) => (
              <div className="history-set" key={set.id}>
                <span>Set {set.order}</span>
                {editing && set.status === "completed" ? (
                  <>
                    {set.weightKg !== undefined && (
                      <input
                        aria-label="Weight"
                        type="number"
                        step="0.5"
                        value={set.weightKg}
                        onChange={(event) => {
                          const exercises = structuredClone(session.exercises);
                          exercises[exerciseIndex].sets[setIndex].weightKg = Number(event.target.value);
                          setSession({ ...session, exercises });
                        }}
                      />
                    )}
                    {set.reps !== undefined && (
                      <input
                        aria-label="Repetitions"
                        type="number"
                        value={set.reps}
                        onChange={(event) => {
                          const exercises = structuredClone(session.exercises);
                          exercises[exerciseIndex].sets[setIndex].reps = Number(event.target.value);
                          setSession({ ...session, exercises });
                        }}
                      />
                    )}
                    {set.seconds !== undefined && (
                      <input
                        aria-label="Seconds"
                        type="number"
                        value={set.seconds}
                        onChange={(event) => {
                          const exercises = structuredClone(session.exercises);
                          exercises[exerciseIndex].sets[setIndex].seconds = Number(event.target.value);
                          setSession({ ...session, exercises });
                        }}
                      />
                    )}
                    <select
                      aria-label="Effort"
                      value={set.effort ?? ""}
                      onChange={(event) => {
                        const exercises = structuredClone(session.exercises);
                        exercises[exerciseIndex].sets[setIndex].effort = event.target.value as EffortBand;
                        setSession({ ...session, exercises });
                      }}
                    >
                      {([
                        ...(set.effort &&
                        !selectableEffortBands.some((value) => value === set.effort)
                          ? [set.effort]
                          : []),
                        ...selectableEffortBands
                      ] as EffortBand[]).map((value) => (
                        <option key={value} value={value}>
                          {effortLabels[value]}
                        </option>
                      ))}
                    </select>
                  </>
                ) : (
                  <>
                    <strong>
                      {set.weightKg !== undefined ? `${set.weightKg} kg` : ""}
                      {set.reps !== undefined ? ` × ${set.reps}` : ""}
                      {set.seconds !== undefined ? ` · ${set.seconds}s` : ""}
                      {set.meters !== undefined ? ` · ${set.meters}m` : ""}
                    </strong>
                    <small>
                      {set.effort ? effortLabels[set.effort] : set.status.replaceAll("_", " ")}
                      {set.restBeforeSeconds !== undefined
                        ? ` / rest ${formatClock(set.restBeforeSeconds)}`
                        : ""}
                      {set.setupBeforeSeconds !== undefined
                        ? ` / setup ${formatClock(set.setupBeforeSeconds)}`
                        : ""}
                    </small>
                  </>
                )}
              </div>
            ))}
          </section>
        ))}
      </div>
      <div className="button-row session-actions">
        {editing ? (
          <>
            <button className="secondary" onClick={() => setSession(structuredClone(initial))}>
              Reset
            </button>
            <button className="primary" onClick={() => void save()}>
              Save corrections
            </button>
          </>
        ) : (
          <button className="secondary" onClick={() => setEditing(true)}>
            <Pencil size={17} /> Correct data
          </button>
        )}
        <button className="secondary danger-text" onClick={() => void remove()}>
          <Trash2 size={17} /> Delete permanently
        </button>
      </div>
    </Modal>
  );
}

export function HistoryScreen() {
  const { profileId, refresh, notify } = useApp();
  const catalog = useQuery(() => getCatalog(profileId), [] as Exercise[]);
  const sessions = useQuery(
    () =>
      db.sessions
        .where("profileId")
        .equals(profileId)
        .toArray()
        .then((rows) =>
          rows
            .filter((session) => session.status === "completed")
            .sort((a, b) => (b.completedAt ?? b.startedAt).localeCompare(a.completedAt ?? a.startedAt))
        ),
    [] as WorkoutSession[]
  );
  const [date, setDate] = useState("");
  const [selected, setSelected] = useState<WorkoutSession | null>(null);
  const [importPreview, setImportPreview] = useState<HistoricalImportPreview | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const filtered = useMemo(
    () => (date ? sessions.filter((session) => (session.completedAt ?? session.startedAt).startsWith(date)) : sessions),
    [date, sessions]
  );

  async function handleImportFile(file: File) {
    const parsed = parseHistoricalCsv(await file.text());
    const resolved: Record<number, string> = {};
    parsed.rows.forEach((row, index) => {
      const match = findExerciseMatch(catalog, row.exerciseId, row.exerciseName);
      if (match) resolved[index] = match.id;
    });
    setImportPreview({ parsed, resolved, exercises: catalog });
  }

  function downloadHistoricalTemplate() {
    const blob = new Blob([historicalCsvTemplate], { type: "text/csv;charset=utf-8" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = "gymapp-historical-workouts-template.csv";
    link.click();
    URL.revokeObjectURL(link.href);
  }

  return (
    <>
      <div className="page-heading">
        <div>
          <p className="eyebrow">Dated training log</p>
          <h1>History</h1>
        </div>
        <div className="heading-actions">
          <IconButton label="Download historical CSV template" onClick={downloadHistoricalTemplate}>
            <Download size={19} />
          </IconButton>
          <IconButton label="Import historical workouts" onClick={() => fileRef.current?.click()}>
            <FileUp size={19} />
          </IconButton>
          <label className="date-filter">
            <CalendarDays size={18} />
            <input
              aria-label="Filter by date"
              type="date"
              value={date}
              onChange={(event) => setDate(event.target.value)}
            />
          </label>
          <input
            ref={fileRef}
            type="file"
            accept=".csv,text/csv"
            hidden
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) void handleImportFile(file);
              event.target.value = "";
            }}
          />
        </div>
      </div>
      {filtered.length === 0 ? (
        <EmptyState
          title={date ? "No workout on this date" : "No completed workouts"}
          detail={date ? "Choose another day or clear the filter." : "Completed sessions will appear here."}
        />
      ) : (
        <div className="history-list">
          {filtered.map((session) => {
            const completedSets = session.exercises.reduce(
              (sum, exercise) => sum + exercise.sets.filter((set) => set.status === "completed").length,
              0
            );
            return (
              <button className="history-card" key={session.id} onClick={() => setSelected(session)}>
                <span className="date-block">
                  <strong>{new Date(session.completedAt ?? session.startedAt).toLocaleDateString(undefined, { day: "2-digit" })}</strong>
                  <small>{new Date(session.completedAt ?? session.startedAt).toLocaleDateString(undefined, { month: "short" })}</small>
                </span>
                <span className="history-main">
                  <strong>{session.planName}</strong>
                  <small>
                    {completedSets} sets · {session.exercises.length} exercises · {formatDuration(session.durationSeconds)}
                  </small>
                </span>
                <ChevronRight size={19} />
              </button>
            );
          })}
        </div>
      )}
      {selected && (
        <SessionModal
          initial={selected}
          onClose={() => setSelected(null)}
          onSaved={() => {
            refresh();
            notify("History corrected");
          }}
          onDeleted={() => {
            refresh();
            notify("Session permanently deleted");
          }}
        />
      )}
      {importPreview && (
        <HistoricalImportModal
          preview={importPreview}
          existingSessions={sessions}
          onClose={() => setImportPreview(null)}
          onImported={refresh}
        />
      )}
    </>
  );
}
