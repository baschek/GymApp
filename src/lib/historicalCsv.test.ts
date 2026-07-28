import { describe, expect, it } from "vitest";
import type { Exercise } from "../types";
import { historicalRowsToSessions, parseHistoricalCsv } from "./historicalCsv";

const exercise: Exercise = {
  id: "test-press",
  name: "Test Press",
  aliases: [],
  equipment: "machine",
  category: "strength",
  primaryMuscles: ["chest"],
  secondaryMuscles: [],
  instructions: [],
  imageUrls: [],
  measurementType: "load_reps",
  loadDirection: "higher",
  loadBasis: "stack",
  builtIn: true
};

describe("historical workout RIR conversion", () => {
  it("maps numeric RIR into the new non-overlapping effort bands", () => {
    const header =
      "format_version,session_id,date,start_time,gym,workout_name,duration_minutes,exercise_order,exercise_id,exercise_name,set_number,set_type,status,weight_kg,reps,seconds,meters,rir,rest_seconds,set_duration_seconds,exercise_notes,session_notes";
    const rows = [0, 2, 3, 5].map(
      (rir, index) =>
        `1,rir-test,2026-07-20,18:00,Basic Fit,RIR Test,40,1,test-press,Test Press,${
          index + 1
        },working,completed,20,10,,,${rir},90,30,,`
    );
    const parsed = parseHistoricalCsv([header, ...rows].join("\n"));
    expect(parsed.errors).toEqual([]);

    const session = historicalRowsToSessions(
      parsed.rows,
      Object.fromEntries(parsed.rows.map((_, index) => [index, exercise.id])),
      [exercise],
      "personal"
    )[0];
    expect(session.exercises[0].sets.map((set) => set.effort)).toEqual([
      "limit",
      "one_two",
      "three_four",
      "five_plus"
    ]);
  });
});
