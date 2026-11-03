package de.baschek.gymapp.core

import de.baschek.gymapp.core.api.BaseUrl
import de.baschek.gymapp.core.api.InvalidBaseUrlException
import de.baschek.gymapp.core.model.Plan
import de.baschek.gymapp.core.model.effectiveWeights
import de.baschek.gymapp.core.model.PlanExercise
import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionExercise
import de.baschek.gymapp.core.model.SetLog
import de.baschek.gymapp.core.model.Target
import de.baschek.gymapp.core.progression.GymExerciseKey
import de.baschek.gymapp.core.progression.finishSession
import de.baschek.gymapp.core.update.AppVersion
import de.baschek.gymapp.core.update.ReleaseParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaseUrlTest {
    @Test fun addsHttpsAndStripsSlash() = assertEquals("https://sync.example.org", BaseUrl.normalize("  Sync.Example.org/ "))
    @Test fun keepsPortAndPath() = assertEquals("https://h.example:8443/api", BaseUrl.normalize("https://h.example:8443/api/"))
    @Test fun rejectsPlainHttp() {
        assertFailsWith<InvalidBaseUrlException> { BaseUrl.normalize("http://sync.example.org") }
        assertFailsWith<InvalidBaseUrlException> { BaseUrl.normalize("http://localhost:8080") }
    }
    @Test fun debugAllowsLocalCleartextOnly() {
        assertEquals("http://10.0.2.2:8080", BaseUrl.normalize("http://10.0.2.2:8080", allowInsecureLocal = true))
        assertFailsWith<InvalidBaseUrlException> { BaseUrl.normalize("http://sync.example.org", allowInsecureLocal = true) }
    }
    @Test fun rejectsCredentialsAndQuery() {
        assertFailsWith<InvalidBaseUrlException> { BaseUrl.normalize("https://u:p@sync.example.org") }
        assertFailsWith<InvalidBaseUrlException> { BaseUrl.normalize("https://sync.example.org/?x=1") }
        assertFailsWith<InvalidBaseUrlException> { BaseUrl.normalize("") }
    }
}

class UpdateTest {
    private fun release(tag: String, apk: String = "https://github.com/baschek/GymApp/releases/download/$tag/app.apk", pre: Boolean = false, draft: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"body":"notes","assets":[{"name":"app.apk","size":5,"browser_download_url":"$apk"}]}"""

    @Test fun versionOrdering() {
        assertTrue(AppVersion.parse("1.10.0")!! > AppVersion.parse("1.9.9")!!)
        assertTrue(AppVersion.parse("1.0.0")!! > AppVersion.parse("1.0.0-beta.1")!!)
        assertEquals(0, AppVersion.parse("1.0")!!.compareTo(AppVersion.parse("1.0.0")!!))
        assertNull(AppVersion.parse("abc"))
    }

    @Test fun picksNewestAndroidReleaseOnly() {
        val json = "[" + listOf(release("v9.0.0"), release("android-v0.2.0"), release("android-v0.3.0"), release("android-v0.4.0", draft = true), release("android-v0.5.0", pre = true)).joinToString(",") + "]"
        assertEquals("0.3.0", ReleaseParser.newestUpdate(json, "0.1.0")!!.versionName)
        assertEquals("0.5.0", ReleaseParser.newestUpdate(json, "0.1.0", includePreReleases = true)!!.versionName)
        assertNull(ReleaseParser.newestUpdate(json, "0.3.0"))
    }

    @Test fun untrustedDownloadHostIsIgnored() {
        val json = "[" + release("android-v0.2.0", apk = "https://evil.example/app.apk") + "]"
        assertNull(ReleaseParser.newestUpdate(json, "0.1.0"))
        assertNotNull(ReleaseParser.newestUpdate("[" + release("android-v0.2.0") + "]", "0.1.0"))
    }

    @Test fun garbageYieldsNull() {
        assertNull(ReleaseParser.newestUpdate("not json", "0.1.0"))
    }
}

class FinishSessionTest {
    private val target = Target("reps", 8.0, 12.0, 1.0, 2.0)
    private fun set(i: Int, status: String = "completed") =
        SetLog(id = "s$i", order = i, status = status, weightKg = 25.0, reps = 10, effort = if (status == "completed") "two_three" else null)

    private fun session(gym: String?) = Session(
        gymId = gym, planId = "plan", planName = "p", startedAt = "2026-01-01T10:00:00Z",
        exercises = listOf(SessionExercise(id = "se", sourcePlanExerciseId = "pe", exerciseId = "x", name = "n", target = target, sets = listOf(set(1), set(2), set(3, "pending")))),
    )

    @Test fun pendingSetsGetReasonAndGymWeightProgresses() {
        val r = finishSession("sid", session("gym-a"), "skipped_time", "2026-01-01T11:00:00Z", 3600, { "m" })
        assertEquals("completed", r.session.status)
        assertEquals("skipped_time", r.session.exercises[0].sets[2].status)
        assertEquals(25.0, r.workingWeights[GymExerciseKey("gym-a", "x")]!!.weightKg)
        assertEquals(1, r.milestones.size)
        assertEquals(null, r.milestones.single().milestone.previousWeightKg)
        assertFailsWith<IllegalArgumentException> { finishSession("sid", session("gym-a"), "completed", "t", 1, { "m" }) }
    }

    @Test fun sessionWithoutGymNeverChangesWeights() {
        val r = finishSession("sid", session(null), "skipped_other", "2026-01-01T11:00:00Z", 10, { "m" })
        assertEquals(emptyMap(), r.workingWeights)
        assertEquals(emptyList(), r.milestones)
    }

    @Test fun otherGymHistoryDoesNotCount() {
        val past = session("gym-b").copy(status = "completed", completedAt = "2025-12-01T11:00:00Z")
        val r = finishSession("sid", session("gym-a"), "skipped_other", "2026-01-01T11:00:00Z", 10, { "m" }, listOf("old" to past))
        assertEquals(null, r.milestones.single().milestone.previousWeightKg)
    }
}

class PrefillTest {
    private val ex = SessionExercise(
        id = "e", exerciseId = "x", name = "n", target = Target("reps", 8.0, 12.0, 1.0, 2.0), suggestedWeightKg = 40.0,
        sets = listOf(SetLog("a", 1, status = "completed", weightKg = 45.0, reps = 10), SetLog("b", 2)),
    )
    @Test fun lastCompletedWeightWins() {
        val p = de.baschek.gymapp.core.progression.prefillSet(ex, listOf(10.0, 20.0))
        assertEquals(45.0, p.weightKg)
        assertEquals(12.0, p.value)
        assertEquals("one_two", p.effort)
    }
    @Test fun suggestionThenFirstLevel() {
        val fresh = ex.copy(sets = listOf(SetLog("b", 1)))
        assertEquals(40.0, de.baschek.gymapp.core.progression.prefillSet(fresh, listOf(10.0)).weightKg)
        assertEquals(10.0, de.baschek.gymapp.core.progression.prefillSet(fresh.copy(suggestedWeightKg = null), listOf(10.0, 20.0)).weightKg)
        assertEquals(null, de.baschek.gymapp.core.progression.prefillSet(fresh.copy(measurementType = "reps_only"), listOf(10.0)).weightKg)
    }
    @Test fun roundDownKeepsLevelOrSmallest() {
        assertEquals(60.0, de.baschek.gymapp.core.progression.roundDownToLevel(65.0, listOf(50.0, 60.0, 70.0)))
        assertEquals(50.0, de.baschek.gymapp.core.progression.roundDownToLevel(40.0, listOf(50.0, 60.0)))
        assertEquals(42.5, de.baschek.gymapp.core.progression.roundDownToLevel(42.9, null))
    }
}

class MachineWeightsTest {
    @Test fun explicitListWins() {
        val mp = de.baschek.gymapp.core.model.MachineProfile(availableWeightsKg = listOf(20.0, 10.0, 10.0))
        assertEquals(listOf(10.0, 20.0), mp.effectiveWeights())
    }
    @Test fun rangesAreExpanded() {
        val mp = de.baschek.gymapp.core.model.MachineProfile(
            weightRanges = listOf(
                de.baschek.gymapp.core.model.WeightRange("a", 5.0, 10.0, 2.5),
                de.baschek.gymapp.core.model.WeightRange("b", 10.0, 20.0, 5.0),
            ),
        )
        assertEquals(listOf(5.0, 7.5, 10.0, 15.0, 20.0), mp.effectiveWeights())
    }
}
