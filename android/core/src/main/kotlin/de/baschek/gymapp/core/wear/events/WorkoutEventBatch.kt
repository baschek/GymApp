package de.baschek.gymapp.core.wear.events

import java.util.PriorityQueue

data class WorkoutBatchResult(
    val acknowledgements: List<String>,
    val appliedCount: Int,
    val lastLeader: ReplicaState?,
)

/** Fold a delivery in causal order. The receiver persists accepted events and terminal rejections. */
inline fun applyWorkoutBatch(events: List<WorkoutEvent>, apply: (WorkoutEvent) -> EventResult): WorkoutBatchResult {
    val ids = events.mapTo(mutableSetOf()) { it.eventId }
    val waiting = events.groupBy { event -> event.predecessorEventId?.takeUnless { it == event.eventId } }.toMutableMap()
    val ready = PriorityQueue(compareBy(WorkoutEvent::occurredAtMillis, WorkoutEvent::deviceId, WorkoutEvent::eventId))
    // An external predecessor can already have a receipt. The reducer checks it as usual.
    for (predecessor in waiting.keys.filter { it !in ids }) ready.addAll(waiting.remove(predecessor).orEmpty())
    val acknowledged = mutableListOf<String>()
    var appliedCount = 0
    var leader: ReplicaState? = null
    while (ready.isNotEmpty()) {
        val event = ready.remove()
        when (val result = apply(event)) {
            is EventResult.Applied -> {
                appliedCount++
                if (result.state.lastOperation?.eventId == event.eventId) leader = result.state
            }
            is EventResult.Replayed -> Unit
            is EventResult.Rejected -> if (!EventRejections.shouldAcknowledge(result)) continue
        }
        acknowledged += event.eventId
        ready.addAll(waiting.remove(event.eventId).orEmpty())
    }
    return WorkoutBatchResult(acknowledged, appliedCount, leader)
}
