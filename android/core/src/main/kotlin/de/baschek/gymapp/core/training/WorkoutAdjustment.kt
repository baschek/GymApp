package de.baschek.gymapp.core.training

import de.baschek.gymapp.core.model.AdjustmentAction
import de.baschek.gymapp.core.model.AdjustmentOp
import de.baschek.gymapp.core.model.AdjustmentResult
import de.baschek.gymapp.core.model.AdjustmentStatus
import de.baschek.gymapp.core.model.Metric
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.WorkoutAdjustment

/*
 * Applying an adjustment the assistant asked for (contract v1 amendment D) to the running session. Pure functions:
 * the repository applies them inside one transaction. Rules: a logged, skipped or failed set is never changed;
 * everything works on open (pending) sets only; every operation reports "applied" or "skipped" with a short code.
 */

/** `suggestion_reason` of an exercise whose weight the assistant set. */
const val ASSISTANT_SUGGESTION = "assistant"

/** Codes of `status_reason` / skipped operations. */
object AdjustmentCode {
    const val NO_ACTIVE_SESSION = "no_active_session"
    const val SESSION_FINISHED = "session_finished"
    const val NOTHING_APPLIED = "nothing_applied"
    const val UNDONE = "undone"
    const val NO_EXERCISE = "exercise_not_found"
    const val FINISHED = "finished"
    const val INVALID = "invalid"
    const val ALREADY_LOGGED = "already_logged"
    const val UNCHANGED = "unchanged"
    const val CANNOT_MOVE = "cannot_move"
}

data class AdjustmentOutcome(
    val session: Session,
    val results: List<AdjustmentResult>,
    val status: String,
    val statusReason: String?,
)

private fun SetLog.isOpenWorking() = status == SetStatus.PENDING && setType == SetType.WORKING

private fun SessionExercise.hasOpenSets() = sets.any { it.status == SetStatus.PENDING }

private fun renumberSets(sets: List<SetLog>) = sets.mapIndexed { i, s -> s.copy(order = i + 1) }

private fun renumberExercises(list: List<SessionExercise>) = list.mapIndexed { i, e -> e.copy(order = i + 1) }

private class Skip(val code: String)

private fun Session.mapExercise(id: String?, change: (SessionExercise) -> SessionExercise): Session =
    copy(exercises = exercises.map { if (it.id == id) change(it) else it })

/**
 * Applies the operations of [adjustment] one after another. Only an active session can be adjusted. Returns the new
 * session and the result per operation; the status is `applied` when at least one operation took effect.
 */
fun applyAdjustment(session: Session, adjustment: WorkoutAdjustment, newId: () -> String): AdjustmentOutcome {
    if (session.status != SessionStatus.ACTIVE) {
        return AdjustmentOutcome(session, emptyList(), AdjustmentStatus.REJECTED, AdjustmentCode.SESSION_FINISHED)
    }
    var current = session
    val results = adjustment.ops.mapIndexed { index, op ->
        val outcome = runCatching { applyOp(current, op, newId) }.getOrElse { Skip(AdjustmentCode.INVALID) }
        when (outcome) {
            is Session -> {
                current = outcome
                AdjustmentResult(index, "applied")
            }
            is Skip -> AdjustmentResult(index, "skipped", outcome.code)
            else -> AdjustmentResult(index, "skipped", AdjustmentCode.INVALID)
        }
    }
    val any = results.any { it.status == "applied" }
    return AdjustmentOutcome(
        current, results,
        if (any) AdjustmentStatus.APPLIED else AdjustmentStatus.REJECTED,
        if (any) null else AdjustmentCode.NOTHING_APPLIED,
    )
}

/** The new [Session] or a [Skip]. */
private fun applyOp(session: Session, op: AdjustmentOp, newId: () -> String): Any {
    if (op.action == AdjustmentAction.ADD_EXERCISE) return addExerciseOp(session, op, newId)
    if (op.action == AdjustmentAction.NOTE && op.sessionExerciseId == null) {
        return if (op.text.isNullOrBlank()) Skip(AdjustmentCode.INVALID) else session // a hint without a target
    }
    val ex = session.exercises.firstOrNull { it.id == op.sessionExerciseId } ?: return Skip(AdjustmentCode.NO_EXERCISE)
    return when (op.action) {
        AdjustmentAction.SET_WEIGHT -> setWeightOp(session, ex, op)
        AdjustmentAction.SET_REPS -> setRepsOp(session, ex, op)
        AdjustmentAction.SET_SETS -> setSetsOp(session, ex, op, newId)
        AdjustmentAction.SET_REST -> {
            val rest = op.restSeconds
            when {
                rest == null || rest !in 10..600 -> Skip(AdjustmentCode.INVALID)
                !ex.hasOpenSets() -> Skip(AdjustmentCode.FINISHED)
                rest == ex.restSeconds -> Skip(AdjustmentCode.UNCHANGED)
                else -> session.mapExercise(ex.id) { it.copy(restSeconds = rest) }
            }
        }
        AdjustmentAction.SKIP_EXERCISE -> when {
            ex.sets.any { it.status == SetStatus.COMPLETED } -> Skip(AdjustmentCode.ALREADY_LOGGED)
            !ex.hasOpenSets() -> Skip(AdjustmentCode.UNCHANGED)
            else -> session.mapExercise(ex.id) { e ->
                e.copy(sets = e.sets.map { if (it.status == SetStatus.PENDING) it.copy(status = SetStatus.SKIPPED_OTHER, weightKg = null, addonWeightsKg = null) else it })
            }
        }
        AdjustmentAction.MOVE_EXERCISE -> {
            val position = op.position
            val from = session.exercises.indexOfFirst { it.id == ex.id }
            if (position == null || position !in 1..session.exercises.size) Skip(AdjustmentCode.INVALID)
            else {
                val moved = moveExercise(session, ex.id, (position - 1) - from)
                if (moved == session) Skip(if (position - 1 == from) AdjustmentCode.UNCHANGED else AdjustmentCode.CANNOT_MOVE) else moved
            }
        }
        AdjustmentAction.NOTE -> {
            val text = op.text?.trim().orEmpty()
            if (text.isEmpty()) Skip(AdjustmentCode.INVALID)
            else session.mapExercise(ex.id) { e ->
                val old = e.notes.orEmpty()
                e.copy(notes = if (old.isBlank()) text else if (old.contains(text)) old else old + "\n" + text)
            }
        }
        else -> Skip(AdjustmentCode.INVALID)
    }
}

private fun setWeightOp(session: Session, ex: SessionExercise, op: AdjustmentOp): Any {
    val weight = op.weightKg
    if (weight == null || weight <= 0.0 || weight > 2000.0) return Skip(AdjustmentCode.INVALID)
    if (ex.sets.none { it.isOpenWorking() }) return Skip(AdjustmentCode.FINISHED)
    val updated = ex.copy(
        suggestedWeightKg = weight, suggestionReason = ASSISTANT_SUGGESTION, suggestionSourceGymId = null,
        // the open sets carry the planned total weight; the next log clears it again (see clearPlannedWeights)
        sets = ex.sets.map { if (it.isOpenWorking()) it.copy(weightKg = weight, addonWeightsKg = null) else it },
    )
    return if (updated == ex) Skip(AdjustmentCode.UNCHANGED) else session.mapExercise(ex.id) { updated }
}

private fun setRepsOp(session: Session, ex: SessionExercise, op: AdjustmentOp): Any {
    val low = op.repsMin
    val high = op.repsMax
    if (low == null || high == null || low < 1 || high > 100 || low > high || ex.target.metric != Metric.REPS) return Skip(AdjustmentCode.INVALID)
    if (!ex.hasOpenSets()) return Skip(AdjustmentCode.FINISHED)
    if (ex.target.min == low.toDouble() && ex.target.max == high.toDouble()) return Skip(AdjustmentCode.UNCHANGED)
    return session.mapExercise(ex.id) { it.copy(target = it.target.copy(min = low.toDouble(), max = high.toDouble())) }
}

private fun setSetsOp(session: Session, ex: SessionExercise, op: AdjustmentOp, newId: () -> String): Any {
    val wanted = op.sets
    if (wanted == null || wanted !in 1..20) return Skip(AdjustmentCode.INVALID)
    if (!ex.hasOpenSets()) return Skip(AdjustmentCode.FINISHED)
    val working = ex.sets.filter { it.setType == SetType.WORKING }
    val handled = working.count { it.status != SetStatus.PENDING }
    val target = maxOf(wanted, handled) // never below what was logged or skipped
    if (target == working.size) return Skip(AdjustmentCode.UNCHANGED)
    val sets = if (target > working.size) {
        val planned = working.lastOrNull { it.isOpenWorking() }?.weightKg
        ex.sets + (1..(target - working.size)).map { SetLog(id = newId(), setType = SetType.WORKING, status = SetStatus.PENDING, weightKg = planned) }
    } else {
        val dropIds = mutableSetOf<String>()
        for (set in ex.sets.reversed()) {
            if (dropIds.size == working.size - target) break
            if (set.isOpenWorking()) dropIds += set.id
        }
        ex.sets.filter { it.id !in dropIds }
    }
    return session.mapExercise(ex.id) { it.copy(sets = renumberSets(sets), plannedSets = target) }
}

private fun addExerciseOp(session: Session, op: AdjustmentOp, newId: () -> String): Any {
    val catalogId = op.exerciseId ?: return Skip(AdjustmentCode.INVALID)
    val count = (op.sets ?: 3).coerceIn(1, 20)
    val weight = op.weightKg?.takeIf { it > 0.0 && it <= 2000.0 }
    val template = templateFor(
        catalogId, op.exerciseName ?: catalogId,
        op.measurementType ?: de.baschek.gymapp.core.model.MeasurementType.LOAD_REPS,
        op.loadDirection ?: de.baschek.gymapp.core.model.LoadDirection.HIGHER,
        op.loadBasis ?: de.baschek.gymapp.core.model.LoadBasis.STACK,
        op.restSeconds?.coerceIn(10, 600) ?: 90,
    )
    val low = op.repsMin
    val high = op.repsMax
    val target = if (low != null || high != null) {
        val min = (low ?: template.target.min.toInt()).toDouble()
        val max = (high ?: maxOf(min.toInt(), template.target.max.toInt())).toDouble()
        if (min > max) return Skip(AdjustmentCode.INVALID)
        template.target.copy(min = min, max = max)
    } else template.target
    val exercise = template.copy(
        id = newId(), plannedSets = count, target = target,
        suggestedWeightKg = weight, suggestionReason = if (weight != null) ASSISTANT_SUGGESTION else null,
        sets = (1..count).map { SetLog(id = newId(), order = it, setType = SetType.WORKING, status = SetStatus.PENDING, weightKg = weight) },
    )
    // finished exercises stay where they are: the new one goes after the last of them
    val firstFree = session.exercises.indexOfLast { !isMovable(it) } + 1
    val at = ((op.position ?: (session.exercises.size + 1)) - 1).coerceIn(firstFree, session.exercises.size)
    val list = session.exercises.toMutableList().apply { add(at, exercise) }
    return session.copy(exercises = renumberExercises(list))
}

/**
 * Called when a set is logged ([loggedSetId], warm-up sets are ignored): the planned weights the assistant put on the other open sets are dropped, so the next
 * set follows what was actually used (the usual "last logged weight" rule).
 */
fun clearPlannedWeights(ex: SessionExercise, loggedSetId: String? = null): SessionExercise =
    if (ex.sets.firstOrNull { it.id == loggedSetId }?.setType == SetType.WARMUP) ex // a warm-up says nothing about the working weight
    else if (ex.sets.none { it.isOpenWorking() && it.weightKg != null }) ex
    else ex.copy(sets = ex.sets.map { if (it.isOpenWorking() && it.weightKg != null) it.copy(weightKg = null, addonWeightsKg = null) else it })

/** Total weight planned for a pending working set (set by an adjustment), or null. */
fun plannedWeight(set: SetLog): Double? = if (set.isOpenWorking()) set.weightKg else null

// ---- undo ---------------------------------------------------------------------------------------

/**
 * Takes an applied adjustment back: [before] is the exercise list as it was before the adjustment. Exercises that
 * exist in both are restored (target, rest, note, order of sets); sets that were logged or skipped meanwhile keep
 * their values; exercises the adjustment added disappear unless something was logged in them.
 */
fun revertAdjustment(current: Session, before: List<SessionExercise>): Session {
    val now = current.exercises.associateBy { it.id }
    val beforeIds = before.map { it.id }.toSet()
    val restored = before.mapNotNull { old ->
        val cur = now[old.id] ?: return@mapNotNull null
        val curSets = cur.sets.associateBy { it.id }
        val merged = old.sets.map { s ->
            val c = curSets[s.id]
            if (c != null && c.status != SetStatus.PENDING && s.status == SetStatus.PENDING) c else s
        }
        val oldIds = old.sets.map { it.id }.toSet()
        val extras = cur.sets.filter { it.id !in oldIds && it.status != SetStatus.PENDING }
        old.copy(sets = renumberSets(merged + extras))
    }
    val kept = current.exercises.filter { it.id !in beforeIds && it.sets.any { s -> s.status != SetStatus.PENDING } }
    return current.copy(exercises = renumberExercises(restored + kept))
}

// ---- wording ------------------------------------------------------------------------------------

private fun kg(value: Double): String = deNum(value) + " kg"

/** One German phrase per applied operation, e.g. "Rudern: 52,5 kg". */
fun describeOp(op: AdjustmentOp, exerciseName: String?): String {
    val name = op.exerciseName ?: exerciseName ?: "Übung"
    return when (op.action) {
        AdjustmentAction.SET_WEIGHT -> "$name: ${op.weightKg?.let(::kg) ?: "anderes Gewicht"}"
        AdjustmentAction.SET_REPS -> "$name: ${op.repsMin}–${op.repsMax} Wdh."
        AdjustmentAction.SET_SETS -> "$name: ${op.sets} Sätze"
        AdjustmentAction.SET_REST -> "$name: ${op.restSeconds} s Pause"
        AdjustmentAction.ADD_EXERCISE -> "$name hinzugefügt"
        AdjustmentAction.SKIP_EXERCISE -> "$name übersprungen"
        AdjustmentAction.MOVE_EXERCISE -> "$name an Position ${op.position}"
        AdjustmentAction.NOTE -> if (op.sessionExerciseId == null) "Hinweis: ${op.text}" else "$name: Notiz \"${op.text}\""
        else -> name
    }
}

/** "Claude hat angepasst: Rudern: 52,5 kg; Beinpresse übersprungen (Wenig Zeit.)" using the applied operations only. */
fun describeAdjustment(adjustment: WorkoutAdjustment, namesById: Map<String, String> = emptyMap()): String {
    val applied = adjustment.opResults?.filter { it.status == "applied" }?.map { it.index }?.toSet()
    val parts = adjustment.ops.filterIndexed { i, _ -> applied == null || i in applied }
        .map { describeOp(it, it.sessionExerciseId?.let(namesById::get)) }
    val what = parts.joinToString("; ").ifBlank { "Training" }
    val why = adjustment.reason.trim().takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
    return "Claude hat angepasst: $what$why"
}
