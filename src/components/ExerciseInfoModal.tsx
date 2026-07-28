import type { Exercise } from "../types";
import { Modal } from "./ui";

export function ExerciseInfoModal({
  exercise,
  onClose
}: {
  exercise: Exercise;
  onClose: () => void;
}) {
  const images = exercise.customPhoto ? [exercise.customPhoto] : exercise.imageUrls;
  const description = `A ${exercise.equipment || "general"} exercise primarily targeting ${
    exercise.primaryMuscles.join(", ") || "the selected muscle groups"
  }.`;

  return (
    <Modal title={exercise.name} onClose={onClose} wide>
      {images[0] && (
        <div className="exercise-info-image">
          <img src={images[0]} alt={`${exercise.name} illustration`} />
        </div>
      )}
      {exercise.instructions.length === 0 && (
        <p className="exercise-description">{description}</p>
      )}
      <div className="tag-row">
        <span>{exercise.equipment}</span>
        {exercise.primaryMuscles.map((muscle) => (
          <span key={muscle}>{muscle}</span>
        ))}
      </div>
      {exercise.instructions.length > 0 && (
        <div className="instruction-list">
          {exercise.instructions.slice(0, 4).map((instruction, index) => (
            <p key={`${instruction}-${index}`}>
              <strong>{index + 1}</strong>
              {instruction}
            </p>
          ))}
        </div>
      )}
    </Modal>
  );
}
