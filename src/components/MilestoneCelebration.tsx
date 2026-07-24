import { ArrowDown, ArrowUp, Trophy } from "lucide-react";
import { useApp } from "../context/AppContext";
import { db } from "../lib/db";
import type { Milestone } from "../types";

export function MilestoneCelebration({ milestone }: { milestone: Milestone }) {
  const { refresh } = useApp();
  async function close() {
    await db.milestones.update(milestone.id, { seen: true });
    refresh();
  }
  const assisted = milestone.direction === "lower_assistance";
  return (
    <div className="celebration" role="dialog" aria-modal="true" aria-label="Progression milestone">
      <div className="celebration-rays" />
      <div className="celebration-content">
        <span className="trophy">
          <Trophy size={42} />
        </span>
        <p>New stage reached</p>
        <h2>{milestone.exerciseName}</h2>
        <div className="weight-advance">
          <span>{milestone.previousWeightKg !== undefined ? `${milestone.previousWeightKg} kg` : "Start"}</span>
          {assisted ? <ArrowDown size={28} /> : <ArrowUp size={28} />}
          <strong>{milestone.newWeightKg} kg</strong>
        </div>
        <p className="celebration-detail">
          {assisted
            ? "Two qualifying sets proved that you need less assistance."
            : "Two qualifying sets proved your new working weight."}
        </p>
        <button className="primary" onClick={() => void close()}>
          Continue
        </button>
      </div>
    </div>
  );
}
