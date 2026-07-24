import { describe, expect, it } from "vitest";
import type { Exercise } from "../types";
import {
  classifyExerciseImports,
  exerciseCsvTemplate,
  parseExerciseCsv
} from "./exerciseCsv";

describe("exercise CSV import", () => {
  it("parses explicit measurement and weight basis values", () => {
    const result = parseExerciseCsv(exerciseCsvTemplate);
    expect(result.errors).toEqual([]);
    expect(result.rows).toHaveLength(3);
    expect(result.rows[1]).toMatchObject({
      name: "Assisted Pull-up Machine",
      measurementType: "assisted_reps",
      loadBasis: "assistance"
    });
  });

  it("rejects an incompatible measurement and weight basis", () => {
    const invalid = exerciseCsvTemplate.replace(
      "assisted_reps,assistance",
      "assisted_reps,stack"
    );
    const result = parseExerciseCsv(invalid);
    expect(
      result.errors.some((error) => error.includes("load_basis"))
    ).toBe(true);
  });

  it("marks a changed existing definition as a conflict", () => {
    const parsed = parseExerciseCsv(exerciseCsvTemplate);
    const existing: Exercise = {
      id: "chest-press",
      profileId: "personal",
      name: "Chest Press Machine",
      aliases: [],
      equipment: "Different machine",
      category: "strength",
      primaryMuscles: [],
      secondaryMuscles: [],
      instructions: [],
      imageUrls: [],
      measurementType: "load_reps",
      loadDirection: "higher",
      loadBasis: "stack",
      builtIn: false
    };
    const classified = classifyExerciseImports(parsed.rows, [existing]);
    expect(classified[0].status).toBe("conflict");
    expect(classified[0].existing?.id).toBe(existing.id);
  });
});
