package de.baschek.gymapp.core

import de.baschek.gymapp.core.model.BodyRegions
import de.baschek.gymapp.core.model.MuscleGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyRegionsTest {
    @Test
    fun everyMuscleGroupIsDrawnOnTheFrontOrTheBack() {
        val drawn = BodyRegions.FRONT.values + BodyRegions.BACK.values
        assertEquals(MuscleGroup.ALL.sorted(), drawn.toSet().sorted())
    }

    @Test
    fun onlyKnownGroupsAreUsedAndNeutralRegionsHaveNone() {
        assertTrue((BodyRegions.FRONT.values + BodyRegions.BACK.values).all { it in MuscleGroup.ALL })
        for (slug in BodyRegions.NEUTRAL) {
            assertNull(BodyRegions.groupOf(slug, front = true))
            assertNull(BodyRegions.groupOf(slug, front = false))
        }
    }

    @Test
    fun groupsAreSideSpecificAndMatchTheLegacyTable() {
        assertEquals(MuscleGroup.BRUST, BodyRegions.groupOf("chest", front = true))
        assertNull(BodyRegions.groupOf("chest", front = false))
        assertEquals(MuscleGroup.UNTERER_RUECKEN, BodyRegions.groupOf("lower-back", front = false))
        assertEquals(MuscleGroup.BAUCH, BodyRegions.groupOf("obliques", front = true))
        assertNull(BodyRegions.groupOf("unknown", front = true))
    }
}
