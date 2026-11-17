package de.baschek.gymapp.core

import de.baschek.gymapp.core.update.ReleaseParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchUpdateTest {
    private val hash = "a".repeat(64)
    private fun release(version: String, assets: String, prerelease: Boolean = false, draft: Boolean = false) =
        """{"tag_name":"android-v$version","prerelease":$prerelease,"draft":$draft,"assets":[$assets]}"""
    private fun asset(version: String, name: String = "gymapp-watch-$version.apk", host: String = "github.com", digest: String = "sha256:$hash") =
        """{"name":"$name","browser_download_url":"https://$host/baschek/GymApp/releases/download/android-v$version/$name","size":123,"digest":"$digest"}"""

    @Test fun selectsOnlyExactWatchAssetInNewestEligibleRelease() {
        val releases = "[" + listOf(
            release("3.0.0", asset("3.0.0", "gymapp-3.0.0.apk")),
            release("2.8.0", asset("2.8.0")),
            release("2.9.0-beta.1", asset("2.9.0-beta.1"), prerelease = true),
            release("2.7.0", asset("2.7.0")),
        ).joinToString(",") + "]"
        val watch = ReleaseParser.newestWatchUpdate(releases, "2.6.0")!!
        assertEquals("2.8.0", watch.versionName)
        assertEquals("gymapp-watch-2.8.0.apk", watch.apkName)
        assertEquals(hash, watch.sha256)
        assertEquals("3.0.0", ReleaseParser.newestUpdate(releases, "2.6.0")?.versionName)
    }

    @Test fun rejectsWrongHostNamePathAndInvalidDigest() {
        val wrongHost = release("2.8.0", asset("2.8.0", host = "evil.example"))
        val wrongFile = release("2.8.0", asset("2.8.0", name = "gymapp-watch-2.8.0-debug.apk"))
        val wrongRepo = release("2.8.0", asset("2.8.0").replace("baschek/GymApp", "someone/Other"))
        listOf(wrongHost, wrongFile, wrongRepo).forEach { assertNull(ReleaseParser.newestWatchUpdate("[$it]", "2.6.0")) }
        val noDigest = release("2.8.0", asset("2.8.0", digest = "sha256:${"z".repeat(64)}"))
        assertNull(ReleaseParser.newestWatchUpdate("[$noDigest]", "2.6.0")?.sha256)
    }

    @Test fun ignoresDraftsAndDoesNotDowngrade() {
        val draft = release("3.0.0", asset("3.0.0"), draft = true)
        val same = release("2.6.0", asset("2.6.0"))
        assertNull(ReleaseParser.newestWatchUpdate("[$draft,$same]", "2.6.0"))
    }
}
