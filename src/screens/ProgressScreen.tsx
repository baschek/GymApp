import { Activity, Dumbbell, Plus, Scale, TrendingUp } from "lucide-react";
import { useMemo, useState } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis
} from "recharts";
import { EmptyState, Modal } from "../components/ui";
import { useApp } from "../context/AppContext";
import { useQuery } from "../hooks/useQuery";
import { createId, db } from "../lib/db";
import { estimatedOneRepMax, totalVolume } from "../lib/progression";
import type { BodyWeightEntry, WorkoutSession } from "../types";

function shortDate(value: string) {
  return new Date(value).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function BodyWeightModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const { profileId } = useApp();
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [weight, setWeight] = useState("");
  const [notes, setNotes] = useState("");
  async function save() {
    if (!weight) return;
    await db.bodyWeights.add({
      id: createId("body_weight"),
      profileId,
      date,
      weightKg: Number(weight),
      notes
    });
    onSaved();
    onClose();
  }
  return (
    <Modal title="Record body weight" onClose={onClose}>
      <label className="field">
        <span>Date</span>
        <input type="date" value={date} onChange={(event) => setDate(event.target.value)} />
      </label>
      <label className="field">
        <span>Body weight (kg)</span>
        <input
          type="number"
          inputMode="decimal"
          step="0.1"
          value={weight}
          onChange={(event) => setWeight(event.target.value)}
          autoFocus
        />
      </label>
      <label className="field">
        <span>Optional note</span>
        <textarea value={notes} onChange={(event) => setNotes(event.target.value)} />
      </label>
      <button className="primary wide-button" disabled={!weight} onClick={() => void save()}>
        Save entry
      </button>
    </Modal>
  );
}

export function ProgressScreen() {
  const { profileId, refresh, notify } = useApp();
  const sessions = useQuery(
    () =>
      db.sessions
        .where("profileId")
        .equals(profileId)
        .toArray()
        .then((items) =>
          items
            .filter((session) => session.status === "completed")
            .sort((a, b) => (a.completedAt ?? a.startedAt).localeCompare(b.completedAt ?? b.startedAt))
        ),
    [] as WorkoutSession[]
  );
  const bodyWeights = useQuery(
    () => db.bodyWeights.where("profileId").equals(profileId).sortBy("date"),
    [] as BodyWeightEntry[]
  );
  const [bodyWeightOpen, setBodyWeightOpen] = useState(false);
  const exerciseNames = useMemo(
    () => [...new Set(sessions.flatMap((session) => session.exercises.map((exercise) => exercise.name)))].sort(),
    [sessions]
  );
  const [selectedExercise, setSelectedExercise] = useState("");
  const selected = selectedExercise || exerciseNames[0] || "";

  const exerciseTrend = useMemo(
    () =>
      sessions
        .map((session) => {
          const exercise = session.exercises.find((item) => item.name === selected);
          if (!exercise) return null;
          const completed = exercise.sets.filter((set) => set.status === "completed");
          const weights = completed.map((set) => set.weightKg).filter((value): value is number => value !== undefined);
          const workingWeight =
            exercise.loadDirection === "lower_assistance"
              ? weights.length
                ? Math.min(...weights)
                : undefined
              : weights.length
                ? Math.max(...weights)
                : undefined;
          const e1rms = completed.map(estimatedOneRepMax).filter((value): value is number => value !== undefined);
          return {
            date: shortDate(session.completedAt ?? session.startedAt),
            workingWeight,
            estimatedPerformance: e1rms.length ? Number(Math.max(...e1rms).toFixed(1)) : undefined,
            volume: Math.round(totalVolume(exercise)),
            sets: completed.length
          };
        })
        .filter(Boolean),
    [selected, sessions]
  );

  const activityData = useMemo(() => {
    const byDate = new Map<string, number>();
    for (const session of sessions.slice(-30)) {
      const date = (session.completedAt ?? session.startedAt).slice(0, 10);
      byDate.set(date, (byDate.get(date) ?? 0) + 1);
    }
    return [...byDate].map(([date, count]) => ({ date: shortDate(date), count }));
  }, [sessions]);

  const completedSets = sessions.reduce(
    (sum, session) =>
      sum +
      session.exercises.reduce(
        (exerciseSum, exercise) => exerciseSum + exercise.sets.filter((set) => set.status === "completed").length,
        0
      ),
    0
  );
  const lastBodyWeight = bodyWeights.at(-1);

  return (
    <>
      <div className="page-heading">
        <div>
          <p className="eyebrow">Training trends</p>
          <h1>Progress</h1>
        </div>
        <button className="secondary compact" onClick={() => setBodyWeightOpen(true)}>
          <Plus size={18} /> Body weight
        </button>
      </div>
      <div className="metric-grid">
        <div className="metric">
          <Activity size={20} />
          <span>
            <strong>{sessions.length}</strong>
            <small>workouts</small>
          </span>
        </div>
        <div className="metric">
          <Dumbbell size={20} />
          <span>
            <strong>{completedSets}</strong>
            <small>completed sets</small>
          </span>
        </div>
        <div className="metric">
          <Scale size={20} />
          <span>
            <strong>{lastBodyWeight ? `${lastBodyWeight.weightKg} kg` : "—"}</strong>
            <small>latest body weight</small>
          </span>
        </div>
      </div>
      {sessions.length === 0 ? (
        <EmptyState title="No progress data yet" detail="Complete a workout to start building your training trends." />
      ) : (
        <>
          <section className="chart-section">
            <header>
              <div>
                <p className="eyebrow">Exercise trend</p>
                <h2>{selected}</h2>
              </div>
              <select value={selected} onChange={(event) => setSelectedExercise(event.target.value)}>
                {exerciseNames.map((name) => (
                  <option key={name}>{name}</option>
                ))}
              </select>
            </header>
            <div className="chart">
              <ResponsiveContainer width="100%" height={260}>
                <LineChart data={exerciseTrend}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="date" />
                  <YAxis width={42} />
                  <Tooltip />
                  <Line type="monotone" dataKey="workingWeight" name="Working kg" stroke="var(--accent)" strokeWidth={3} />
                  <Line
                    type="monotone"
                    dataKey="estimatedPerformance"
                    name="Estimated performance"
                    stroke="var(--success)"
                    strokeWidth={2}
                  />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </section>
          <section className="chart-section">
            <header>
              <div>
                <p className="eyebrow">Recent activity</p>
                <h2>Workout frequency</h2>
              </div>
              <TrendingUp size={21} />
            </header>
            <div className="chart">
              <ResponsiveContainer width="100%" height={210}>
                <BarChart data={activityData}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="date" />
                  <YAxis allowDecimals={false} width={30} />
                  <Tooltip />
                  <Bar dataKey="count" name="Workouts" fill="var(--accent)" radius={[3, 3, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </section>
        </>
      )}
      <section className="chart-section">
        <header>
          <div>
            <p className="eyebrow">Separate log</p>
            <h2>Body weight</h2>
          </div>
        </header>
        {bodyWeights.length ? (
          <div className="chart">
            <ResponsiveContainer width="100%" height={210}>
              <LineChart data={bodyWeights.map((entry) => ({ date: shortDate(entry.date), weight: entry.weightKg }))}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="date" />
                <YAxis domain={["dataMin - 2", "dataMax + 2"]} width={42} />
                <Tooltip />
                <Line type="monotone" dataKey="weight" name="Body weight kg" stroke="var(--accent)" strokeWidth={3} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <p className="section-placeholder">No body-weight entries. Logging is optional and separate from workouts.</p>
        )}
      </section>
      {bodyWeightOpen && (
        <BodyWeightModal
          onClose={() => setBodyWeightOpen(false)}
          onSaved={() => {
            refresh();
            notify("Body weight recorded");
          }}
        />
      )}
    </>
  );
}
