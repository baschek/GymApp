package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetStatus

/*
 * The one rule for "which exercise comes next" (watch design spec A6). Phone (focus after the last set, rest screen,
 * notification) and watch (label and action of "Nächste Übung") use it, so the label always names the exercise the
 * action goes to.
 *
 * Rule: after an exercise, continue with the FIRST exercise in list order that still has an open set (other than the
 * one just finished). Example with five exercises, 1 and 2 done, the user jumped to 4 and finished it: next is 3, then
 * 5, then 6. Skipped exercises have no open sets and are therefore never chosen.
 */

private fun hasOpenSet(ex: SessionExercise) = ex.sets.any { it.status == SetStatus.PENDING }

/**
 * Index of the exercise that follows [currentId]: the first exercise with an open set that is not [currentId].
 * -1 when no other exercise has an open set. [currentId] may be null or unknown (then simply the first open one).
 */
fun nextAfter(exercises: List<SessionExercise>, currentId: String?): Int =
    exercises.indexOfFirst { it.id != currentId && hasOpenSet(it) }

/** The exercise [nextAfter] points to, or null. */
fun nextExerciseAfter(exercises: List<SessionExercise>, currentId: String?): SessionExercise? =
    exercises.getOrNull(nextAfter(exercises, currentId))
