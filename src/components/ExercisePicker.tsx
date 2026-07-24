import { Plus, Search } from "lucide-react";
import { useMemo, useState } from "react";
import type { Exercise } from "../types";
import { searchExercises } from "../lib/catalog";

export function ExercisePicker({
  exercises,
  onSelect,
  onCreate
}: {
  exercises: Exercise[];
  onSelect: (exercise: Exercise) => void;
  onCreate?: () => void;
}) {
  const [query, setQuery] = useState("");
  const [equipment, setEquipment] = useState("all");
  const equipmentOptions = useMemo(
    () => ["all", ...new Set(exercises.map((exercise) => exercise.equipment).filter(Boolean))].sort(),
    [exercises]
  );
  const results = useMemo(
    () => searchExercises(exercises, query, equipment).slice(0, 80),
    [equipment, exercises, query]
  );

  return (
    <div className="exercise-picker">
      <label className="search-field">
        <Search size={18} />
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search name, muscle, or equipment"
          autoFocus
        />
      </label>
      <select value={equipment} onChange={(event) => setEquipment(event.target.value)}>
        {equipmentOptions.map((value) => (
          <option value={value} key={value}>
            {value === "all" ? "All equipment" : value}
          </option>
        ))}
      </select>
      <div className="picker-results">
        {results.length === 0 && (
          <p className="empty-picker-copy">
            {exercises.length ? "No matching exercise." : "You have not defined any exercises yet."}
          </p>
        )}
        {results.map((exercise) => (
          <button key={exercise.id} className="picker-item" onClick={() => onSelect(exercise)}>
            <span className="exercise-thumb">
              {exercise.imageUrls[0] ? <img src={exercise.imageUrls[0]} alt="" loading="lazy" /> : exercise.name[0]}
            </span>
            <span>
              <strong>{exercise.name}</strong>
              <small>
                {exercise.equipment} · {exercise.primaryMuscles.slice(0, 2).join(", ") || "general"}
              </small>
            </span>
          </button>
        ))}
      </div>
      {onCreate && (
        <button className="secondary wide-button" onClick={onCreate}>
          <Plus size={17} /> Define new exercise
        </button>
      )}
    </div>
  );
}
