package de.baschek.gymapp.core.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RecordingTest {
    @Test fun oneJsonObjectPerEventWithIndependentClocks() {
        for (dev in listOf("phone", "watch")) {
            val session = Recording(dev, 700)
            val first = session.event(NavEvent.SCREEN, "train", "", 7000, 720)!!
            val tap = session.event(NavEvent.TAP, null, "weight_step", 6000, 721)!!
            val auto = session.event(NavEvent.AUTO, null, "auto:remote_event", 6001, 722)!!
            val action = session.event(NavEvent.ACTION, null, "Log", 6002, 723)!!
            val rows = (first.line(true) + tap.line() + auto.line() + action.line()).trimEnd().lines()
                .map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(setOf("t", "mono", "dev", "type", "screen", "element", "shot"), rows[0].keys)
            assertEquals("screen-20.webp", rows[0]["shot"]!!.jsonPrimitive.content)
            assertEquals(setOf("t", "mono", "dev", "type", "screen", "element"), rows[1].keys)
            assertEquals(listOf("screen", "tap", "auto", "action"), rows.map { it["type"]!!.jsonPrimitive.content })
            assertTrue(rows.all { it["dev"]!!.jsonPrimitive.content == dev && it["screen"]!!.jsonPrimitive.content == "train" })
            assertEquals("6000", rows[1]["t"].toString())
            assertEquals("21", rows[1]["mono"].toString())
            assertFalse("shot" in first.line(false))
        }
    }

    @Test fun onlyChangedScreensRequestShotsSpacedAtLeastTwoSecondsApart() {
        val session = Recording("watch", 100)
        fun screen(name: String, elapsed: Long) = session.event(NavEvent.SCREEN, name, "", 1, 100 + elapsed)
        assertTrue(screen("home", 0)!!.capture)
        assertNull(screen("home", 1))
        assertFalse(screen("train", 1_999)!!.capture)
        assertFalse(session.event(NavEvent.AUTO, null, "auto:rest_end", 1, 2100)!!.capture)
        assertTrue(screen("rest", 2_000)!!.capture)
        assertNull(screen("rest", 9_000))
    }

    @Test fun deadlineIncludesSleepAndIgnoresWallClockChanges() {
        val session = Recording("phone", 600)
        val deadline = 600 + Recording.LIMIT_MS
        assertFalse(session.expired(deadline - 1))
        assertTrue(session.expired(deadline))
        assertNull(session.event(NavEvent.TAP, "settings", "back", 0, deadline))
    }

    @Test fun rejectsPayloadsIncludingTextThatLooksLikeAnIdentifier() {
        val session = Recording("watch", 0)
        for (payload in listOf("81.25 kg", "17", "synthetic_note", "note\nwith two lines", "workout|synthetic-id")) {
            for (type in NavEvent.entries) {
                val row = session.event(type, payload, payload, 1, 1)?.line() ?: continue
                assertFalse(payload in row)
                assertEquals("unknown", Json.parseToJsonElement(row).jsonObject["screen"]!!.jsonPrimitive.content)
            }
        }
    }
}
