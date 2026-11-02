package de.baschek.gymapp.workout

import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.progression.prefillSet
import de.baschek.gymapp.data.GymRepository
import de.baschek.gymapp.sync.SyncScheduler
import de.baschek.gymapp.timer.TimerController
import de.baschek.gymapp.timer.TimerPhase
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Values the user entered for one set. [value] is reps, seconds or meters depending on the exercise target. */
data class SetInput(val weightKg: Double?, val value: Double?, val effort: String?)

/** Workout operations shared by the UI and the notification actions. */
class WorkoutActions(
    private val repository: GymRepository,
    private val timer: TimerController,
    private val scheduler: SyncScheduler,
) {
    suspend fun logSet(sessionId: String, exerciseId: String, setId: String, input: SetInput) {
        val current = repository.getSession(sessionId) ?: return
        val running = timer.state.value
        val setSeconds = if (running?.phase == TimerPhase.SET) running.elapsedSeconds() else null
        if (running != null && running.phase != TimerPhase.SET) timer.stop()
        val before = timer.consumeBeforeSet()
        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

        repository.updateSession(sessionId) { s ->
            s.copy(
                exercises = s.exercises.map { ex ->
                    if (ex.id != exerciseId) ex
                    else ex.copy(
                        sets = ex.sets.map { set ->
                            if (set.id != setId) set
                            else set.copy(
                                status = SetStatus.COMPLETED,
                                weightKg = input.weightKg,
                                reps = if (ex.target.metric == Metric.REPS) input.value?.toInt() else set.reps,
                                seconds = if (ex.target.metric == Metric.SECONDS) input.value?.toInt() else set.seconds,
                                meters = if (ex.target.metric == Metric.METERS) input.value else set.meters,
                                effort = input.effort,
                                setDurationSeconds = setSeconds,
                                restBeforeSeconds = before.restSeconds,
                                setupBeforeSeconds = before.setupSeconds,
                                completedAt = now,
                            )
                        },
                    )
                },
            )
        }

        val exercise = current.exercises.firstOrNull { it.id == exerciseId }
        val pendingLeft = current.exercises.sumOf { ex ->
            ex.sets.count { it.status == SetStatus.PENDING && !(ex.id == exerciseId && it.id == setId) }
        }
        if (pendingLeft > 0 && exercise != null) timer.start(TimerPhase.REST, exercise.name, exercise.restSeconds) else timer.stop()
    }

    /** Logs the next pending set with the prefilled values (used by the notification action). */
    suspend fun logNextPrefilled(): Boolean {
        val active = repository.getActiveSession() ?: return false
        val exercise = nextExercise(active.value.exercises) ?: return false
        val set = exercise.sets.first { it.status == SetStatus.PENDING }
        val weights = repository.getMachineWeights(active.value.gymId)[exercise.exerciseId].orEmpty()
        val pre = prefillSet(exercise, weights)
        logSet(active.id, exercise.id, set.id, SetInput(pre.weightKg, pre.value, pre.effort))
        return true
    }

    fun skipRest() {
        if (timer.state.value?.phase == TimerPhase.REST) timer.stop()
    }

    suspend fun finish(sessionId: String) {
        timer.reset()
        repository.finishSession(sessionId)
        scheduler.requestSync()
    }

    companion object {
        fun nextExercise(exercises: List<SessionExercise>): SessionExercise? =
            exercises.firstOrNull { ex -> ex.sets.any { it.status == SetStatus.PENDING } }
    }
}
