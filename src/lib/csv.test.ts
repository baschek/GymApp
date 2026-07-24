import { describe, expect, it } from "vitest";
import { csvTemplate, parsePlanCsv } from "./csv";

describe("parsePlanCsv", () => {
  it("parses the documented template and applies defaults", () => {
    const result = parsePlanCsv(csvTemplate);
    expect(result.errors).toEqual([]);
    expect(result.rows).toHaveLength(3);
    expect(result.rows[0]).toMatchObject({
      planName: "Full Body",
      normalSets: 3,
      fastSets: 2,
      ultraFastSets: 1,
      targetRirMin: 1,
      targetRirMax: 2
    });
  });

  it("rejects multiple plans and reversed target ranges", () => {
    const csv = [
      "format_version,plan_name,order,exercise_id,exercise_name,normal_sets,fast_sets,ultra_fast_sets,target_metric,target_min,target_max,starting_weight_kg,target_rir_min,target_rir_max,rest_seconds,superset_group",
      "1,Plan A,1,,Exercise A,3,2,1,reps,12,8,,1,2,90,",
      "1,Plan B,2,,Exercise B,3,2,1,reps,8,12,,1,2,90,"
    ].join("\n");
    const result = parsePlanCsv(csv);
    expect(result.errors.some((error) => error.includes("target_min"))).toBe(true);
    expect(result.errors.some((error) => error.includes("one plan_name"))).toBe(true);
  });

  it("rejects unsupported format versions", () => {
    const result = parsePlanCsv(csvTemplate.replace("\n1,", "\n2,"));
    expect(result.errors.some((error) => error.includes("format_version") || error.includes("format version"))).toBe(true);
  });
});
