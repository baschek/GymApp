package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.progression.WEIGHTED_TYPES

/** Store a manual draft only while the exact set is still the exercise's open set. */
fun setPlannedSetWeight(session: Session, exerciseId: String, setId: String, weight: Double): Session {
    if (session.status != SessionStatus.ACTIVE || !weight.isFinite() || weight < 0.0) return session
    return session.copy(exercises = session.exercises.map { exercise ->
        if (exercise.id != exerciseId || exercise.measurementType !in WEIGHTED_TYPES ||
            exercise.sets.firstOrNull { it.status == SetStatus.PENDING }?.id != setId) exercise
        else exercise.copy(sets = exercise.sets.map { set ->
            if (set.id == setId) set.copy(weightKg = weight, addonWeightsKg = null) else set
        })
    })
}
