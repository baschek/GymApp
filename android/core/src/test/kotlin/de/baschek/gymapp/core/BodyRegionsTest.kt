package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.BodyRegions
import de.baschek.gymapp.core.model.Exercise
import de.baschek.gymapp.core.model.MuscleGroup
import de.baschek.gymapp.core.model.MuscleMapping
import de.baschek.gymapp.core.model.MuscleSub
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetStatus
import de.baschek.gymapp.core.model.SessionStatus
import de.baschek.gymapp.core.model.SetType
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.stats.MuscleSummary
import de.baschek.gymapp.core.stats.summarizeMuscles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyRegionsTest {
    private val regions get() = BodyRegions.FRONT.values + BodyRegions.BACK.values

    @Test
    fun everyMuscleGroupIsDrawnOnTheFrontOrTheBack() {
        assertEquals(MuscleGroup.ALL.sorted(), regions.map { it.group }.toSet().sorted())
    }

    @Test
    fun onlyKnownGroupsAreUsed() {
        assertTrue(regions.all { it.group in MuscleGroup.ALL })
    }

    @Test
    fun everySubgroupHasAnAreaOnTheBodyAndItsParentIsTheRegionGroup() {
        for (sub in MuscleSub.ALL) {
            val drawn = regions.filter { it.sub == sub }
            assertTrue(drawn.isNotEmpty(), "no area for $sub")
            assertTrue(drawn.all { it.group == MuscleSub.parent(sub) }, "area of $sub has another group")
        }
        assertTrue(regions.all { it.sub == null || it.sub in MuscleSub.ALL })
    }

    @Test
    fun subgroupsHaveKnownParentsAndLabels() {
        for (sub in MuscleSub.ALL) {
            assertTrue(MuscleSub.parent(sub) in MuscleGroup.ALL)
            assertTrue(MuscleSub.label(sub) != sub)
            assertTrue(sub in MuscleSub.childrenOf(MuscleSub.parent(sub)!!))
        }
        assertEquals(listOf(MuscleSub.BRUST_OBEN, MuscleSub.BRUST_UNTEN), MuscleSub.childrenOf(MuscleGroup.BRUST))
        assertEquals(
            listOf(MuscleSub.LATISSIMUS, MuscleSub.RHOMBOIDEN, MuscleSub.TRAPEZ_OBEN, MuscleSub.TRAPEZ_UNTEN),
            MuscleSub.childrenOf(MuscleGroup.RUECKEN),
        )
        assertEquals(emptyList(), MuscleSub.childrenOf(MuscleGroup.BIZEPS))
        assertNull(MuscleSub.parent("unknown"))
    }

    @Test
    fun groupsAreSideSpecificAndMatchTheLegacyTable() {
        assertEquals(MuscleGroup.BRUST, BodyRegions.groupOf("chest_upper", front = true))
        assertNull(BodyRegions.groupOf("chest_upper", front = false))
        assertEquals(MuscleGroup.UNTERER_RUECKEN, BodyRegions.groupOf("lower_back", front = false))
        assertEquals(MuscleGroup.BAUCH, BodyRegions.groupOf("obliques", front = true))
        assertEquals(MuscleGroup.QUADRIZEPS, BodyRegions.groupOf("adductors", front = false))
        assertEquals(MuscleGroup.GESAESS, BodyRegions.groupOf("abductors", front = true))
        assertNull(BodyRegions.groupOf("unknown", front = true))
    }

    @Test
    fun subgroupTableMatchesTheMappingFile() {
        assertEquals(MuscleMapping.subgroupTable, MuscleSub.PARENT)
    }

    @Test
    fun exercisesNameSubgroupsAndUnknownOnesAreSkipped() {
        val ex = Exercise(name = "x", muscleGroups = listOf("brust"), muscleSubgroups = listOf("brust_oben", "herz", "brust_oben"))
        assertEquals(listOf("brust_oben"), MuscleMapping.subgroupsOf(ex))
        assertEquals(emptyList(), MuscleMapping.subgroupsOf(Exercise(name = "y")))
    }

    private fun session(vararg exercises: Pair<String, Int>): Session {
        val list = exercises.mapIndexed { i, (id, n) ->
            SessionExercise(
                id = "se$i", exerciseId = id, name = id, order = i + 1,
                sets = (1..n).map { SetLog(id = "$id-$it", order = it, status = SetStatus.COMPLETED, setType = SetType.WORKING) },
            )
        }
        return Session(status = SessionStatus.COMPLETED, startedAt = "2026-11-03T10:00:00Z", completedAt = "2026-11-03T10:30:00Z", exercises = list)
    }

    @Test
    fun childrenSumToTheParentGroup() {
        val groups = mapOf("a" to listOf("brust", "schultern"), "b" to listOf("brust"), "c" to listOf("ruecken"))
        val subs = mapOf("a" to listOf("brust_oben", "brust_unten", "schulter_vorne"), "b" to listOf("brust_unten"), "c" to emptyList())
        val s = summarizeMuscles(
            listOf(session("a" to 4, "b" to 3, "c" to 2)), { groups[it].orEmpty() }, java.time.Instant.parse("2026-11-01T00:00:00Z"),
            subgroupsOf = { subs[it].orEmpty() },
        )
        // brust: a primary 4 (split 2/2 between oben and unten) + b primary 3 (unten)
        assertEquals(7.0, s.totals["brust"])
        assertEquals(2.0, s.subTotals[MuscleSub.BRUST_OBEN])
        assertEquals(5.0, s.subTotals[MuscleSub.BRUST_UNTEN])
        assertEquals(null, s.unassigned["brust"])
        for (group in MuscleGroup.ALL) {
            val children = MuscleSub.childrenOf(group)
            if (children.isEmpty()) continue
            val sum = children.sumOf { s.subTotals[it] ?: 0.0 } + (s.unassigned[group] ?: 0.0)
            assertEquals(s.totals[group] ?: 0.0, sum, 1e-9, "children of $group")
        }
        // schultern: secondary 0.5 * 4 = 2 for schulter_vorne; ruecken without subgroup is unassigned
        assertEquals(2.0, s.subTotals[MuscleSub.SCHULTER_VORNE])
        assertEquals(2.0, s.unassigned["ruecken"])
    }

    @Test
    fun withoutSubgroupsTheWholeGroupIsColoured() {
        val s = summarizeMuscles(listOf(session("a" to 4)), { listOf("brust") }, java.time.Instant.parse("2026-11-01T00:00:00Z"))
        assertEquals(4.0, s.totals["brust"])
        assertEquals(4.0, s.regionValue("brust", MuscleSub.BRUST_OBEN))
        assertEquals(4.0, s.regionValue("brust", MuscleSub.BRUST_UNTEN))
        assertEquals(4.0, s.regionValue("brust", null))
        assertEquals(0.0, MuscleSummary.EMPTY.regionValue("brust", MuscleSub.BRUST_OBEN))
    }

    @Test
    fun subgroupsOfAnotherGroupAreIgnored() {
        val s = summarizeMuscles(
            listOf(session("a" to 2)), { listOf("brust") }, java.time.Instant.parse("2026-11-01T00:00:00Z"),
            subgroupsOf = { listOf("latissimus") },
        )
        assertEquals(2.0, s.unassigned["brust"])
        assertTrue(s.subTotals.isEmpty())
    }

    @Test
    fun subTargetsStayOnGroupsSoTotalsAreUnchanged() {
        val groups = mapOf("a" to listOf("brust"))
        val plain = summarizeMuscles(listOf(session("a" to 5)), { groups[it].orEmpty() }, java.time.Instant.parse("2026-11-01T00:00:00Z"))
        val withSubs = summarizeMuscles(
            listOf(session("a" to 5)), { groups[it].orEmpty() }, java.time.Instant.parse("2026-11-01T00:00:00Z"),
            subgroupsOf = { listOf("brust_oben") },
        )
        assertEquals(plain.totals, withSubs.totals)
        assertEquals(plain.details, withSubs.details)
    }
}
