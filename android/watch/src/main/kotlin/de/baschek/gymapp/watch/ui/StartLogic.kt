package de.baschek.gymapp.watch.ui

import de.baschek.gymapp.core.model.Session

/*
 * Start screen suggestion (spec C): the plan trained longest ago (never trained first, then list order) and the gym
 * of the most recent workout. Pure, so it is unit-tested.
 */

fun suggestPlanId(planIds: List<String>, history: List<Session>): String? =
    planIds.minByOrNull { id -> history.filter { it.planId == id }.maxOfOrNull { it.effectiveTime }.orEmpty() }

fun lastGymId(history: List<Session>): String? = history.filter { !it.gymId.isNullOrBlank() }.maxByOrNull { it.effectiveTime }?.gymId
