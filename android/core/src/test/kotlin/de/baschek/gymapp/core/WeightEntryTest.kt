package de.baschek.gymapp.core

import de.baschek.gymapp.core.progression.isSelectableWeight
import de.baschek.gymapp.core.progression.neighborWeights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeightEntryTest {
    private val levels = listOf(10.0, 15.0, 20.0, 25.0)

    @Test
    fun levelsAndAddonSumsAreSelectable() {
        assertTrue(isSelectableWeight(20.0, levels, emptyList()))
        assertTrue(isSelectableWeight(22.5, levels, listOf(2.5)))
    }

    @Test
    fun oddValuesAreNotSelectable() {
        assertFalse(isSelectableWeight(17.0, levels, emptyList()))
        assertFalse(isSelectableWeight(22.5, levels, emptyList()))
        assertFalse(isSelectableWeight(10.0, emptyList(), emptyList()))
    }

    @Test
    fun neighborsAreStrictlyAroundTheValue() {
        assertEquals(15.0 to 20.0, neighborWeights(levels, 17.0))
        assertEquals(10.0 to 20.0, neighborWeights(levels, 15.0))
        assertNull(neighborWeights(levels, 5.0).first)
        assertNull(neighborWeights(levels, 30.0).second)
    }
}
