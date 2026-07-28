import { Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import type {
  EquipmentProfile,
  MachineSetupParameter,
  WeightIncrementRange
} from "../types";
import { createId } from "../lib/db";
import { IconButton, Modal } from "./ui";

export interface MachineProfileValues {
  availableWeightsKg: number[];
  weightRanges: WeightIncrementRange[];
  setupParameters: MachineSetupParameter[];
}

function generateWeightValues(ranges: WeightIncrementRange[], extra: number[]): number[] {
  const generated = ranges.flatMap((range) => {
    if (range.stepKg <= 0 || range.maxKg < range.minKg) return [];
    const values: number[] = [];
    for (let value = range.minKg; value <= range.maxKg + 0.0001; value += range.stepKg) {
      values.push(Number(value.toFixed(3)));
      if (values.length >= 500) break;
    }
    return values;
  });
  return [...new Set([...generated, ...extra])]
    .filter((value) => Number.isFinite(value) && value >= 0)
    .sort((a, b) => a - b);
}

function defaultRanges(): WeightIncrementRange[] {
  return [
    { id: createId("weight_range"), minKg: 5, maxKg: 100, stepKg: 2 },
    { id: createId("weight_range"), minKg: 105, maxKg: 200, stepKg: 5 }
  ];
}

interface WeightRangeDraft {
  id: string;
  minKg: string;
  maxKg: string;
  stepKg: string;
}

function rangeToDraft(range: WeightIncrementRange): WeightRangeDraft {
  return {
    id: range.id,
    minKg: String(range.minKg),
    maxKg: String(range.maxKg),
    stepKg: String(range.stepKg)
  };
}

function parseDecimal(value: string): number | undefined {
  if (!value.trim()) return undefined;
  const parsed = Number(value.trim().replace(",", "."));
  return Number.isFinite(parsed) ? parsed : undefined;
}

function parseRange(range: WeightRangeDraft): WeightIncrementRange | undefined {
  const minKg = parseDecimal(range.minKg);
  const maxKg = parseDecimal(range.maxKg);
  const stepKg = parseDecimal(range.stepKg);
  if (
    minKg === undefined ||
    maxKg === undefined ||
    stepKg === undefined ||
    minKg < 0 ||
    maxKg < minKg ||
    stepKg <= 0
  ) {
    return undefined;
  }
  return { id: range.id, minKg, maxKg, stepKg };
}

export function MachineProfileEditor({
  exerciseName,
  gymName,
  profile,
  onClose,
  onSave,
  onSkip
}: {
  exerciseName: string;
  gymName: string;
  profile?: EquipmentProfile;
  onClose: () => void;
  onSave: (values: MachineProfileValues) => Promise<void>;
  onSkip?: () => Promise<void>;
}) {
  const [ranges, setRanges] = useState<WeightRangeDraft[]>(() =>
    (profile ? structuredClone(profile.weightRanges ?? []) : defaultRanges()).map(rangeToDraft)
  );
  const [parameters, setParameters] = useState<MachineSetupParameter[]>(() =>
    structuredClone(profile?.setupParameters ?? [])
  );
  const [extraValues, setExtraValues] = useState(() => {
    if (!profile?.availableWeightsKg.length) return "";
    const generated = new Set(generateWeightValues(profile.weightRanges ?? [], []));
    return profile.availableWeightsKg.filter((value) => !generated.has(value)).join(", ");
  });

  const extras = extraValues
    .split(/[,;\s]+/)
    .map(Number)
    .filter((value) => Number.isFinite(value) && value >= 0);
  const parsedRanges = ranges.map(parseRange);
  const validRanges = parsedRanges.filter(
    (range): range is WeightIncrementRange => range !== undefined
  );
  const rangesValid = validRanges.length === ranges.length;
  const availableWeightsKg = generateWeightValues(validRanges, extras);

  function updateRange(id: string, patch: Partial<WeightRangeDraft>) {
    setRanges((current) =>
      current.map((range) => (range.id === id ? { ...range, ...patch } : range))
    );
  }

  function updateParameter(id: string, patch: Partial<MachineSetupParameter>) {
    setParameters((current) =>
      current.map((parameter) =>
        parameter.id === id ? { ...parameter, ...patch } : parameter
      )
    );
  }

  return (
    <Modal title="Machine settings" onClose={onClose} wide>
      <p className="modal-copy">
        {exerciseName} at {gymName}. These settings are reused only at this gym.
      </p>

      <section className="machine-editor-section">
        <div className="section-title-row">
          <div>
            <h3>Weight increments</h3>
            <p>{availableWeightsKg.length} selectable values generated</p>
          </div>
          <button
            className="secondary compact"
            onClick={() =>
              setRanges((current) => [
                ...current,
                rangeToDraft({
                  id: createId("weight_range"),
                  minKg: 5,
                  maxKg: 100,
                  stepKg: 5
                })
              ])
            }
          >
            <Plus size={16} /> Range
          </button>
        </div>
        <div className="weight-range-list">
          {ranges.map((range) => (
            <div className="weight-range-row" key={range.id}>
              <label className="field compact-field">
                <span>From kg</span>
                <input
                  type="text"
                  inputMode="decimal"
                  value={range.minKg}
                  onChange={(event) => updateRange(range.id, { minKg: event.target.value })}
                />
              </label>
              <label className="field compact-field">
                <span>Through kg</span>
                <input
                  type="text"
                  inputMode="decimal"
                  value={range.maxKg}
                  onChange={(event) => updateRange(range.id, { maxKg: event.target.value })}
                />
              </label>
              <label className="field compact-field">
                <span>Step kg</span>
                <input
                  type="text"
                  inputMode="decimal"
                  value={range.stepKg}
                  onChange={(event) => updateRange(range.id, { stepKg: event.target.value })}
                />
              </label>
              <IconButton
                label="Remove weight range"
                className="danger-text"
                onClick={() =>
                  setRanges((current) => current.filter((candidate) => candidate.id !== range.id))
                }
              >
                <Trash2 size={17} />
              </IconButton>
            </div>
          ))}
        </div>
        {!rangesValid && (
          <p className="field-error" role="alert">
            Complete each range with valid numbers. Commas and decimal points are accepted.
          </p>
        )}
        <label className="field">
          <span>Additional individual weights</span>
          <input
            value={extraValues}
            onChange={(event) => setExtraValues(event.target.value)}
            placeholder="2.5, 7.5, 102.5"
          />
        </label>
      </section>

      <section className="machine-editor-section">
        <div className="section-title-row">
          <div>
            <h3>Setup parameters</h3>
            <p>Names and values are completely custom.</p>
          </div>
          <button
            className="secondary compact"
            onClick={() =>
              setParameters((current) => [
                ...current,
                { id: createId("setup_parameter"), name: "", value: "" }
              ])
            }
          >
            <Plus size={16} /> Parameter
          </button>
        </div>
        <div className="setup-parameter-list">
          {parameters.map((parameter) => (
            <div className="setup-parameter-row" key={parameter.id}>
              <label className="field compact-field">
                <span>Parameter name</span>
                <input
                  value={parameter.name}
                  onChange={(event) => updateParameter(parameter.id, { name: event.target.value })}
                  placeholder="Seat height"
                />
              </label>
              <label className="field compact-field">
                <span>Saved value</span>
                <input
                  value={parameter.value}
                  onChange={(event) => updateParameter(parameter.id, { value: event.target.value })}
                  placeholder="4"
                />
              </label>
              <IconButton
                label="Remove setup parameter"
                className="danger-text"
                onClick={() =>
                  setParameters((current) =>
                    current.filter((candidate) => candidate.id !== parameter.id)
                  )
                }
              >
                <Trash2 size={17} />
              </IconButton>
            </div>
          ))}
        </div>
      </section>

      <div className="button-row modal-actions">
        {onSkip && (
          <button className="secondary" onClick={() => void onSkip()}>
            Configure later
          </button>
        )}
        <button
          className="primary"
          disabled={!rangesValid}
          onClick={() =>
            void onSave({
              availableWeightsKg,
              weightRanges: validRanges,
              setupParameters: parameters
                .map((parameter) => ({ ...parameter, name: parameter.name.trim() }))
                .filter((parameter) => parameter.name)
            })
          }
        >
          Save machine settings
        </button>
      </div>
    </Modal>
  );
}
