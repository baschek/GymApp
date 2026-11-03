package de.baschek.gymapp.core

import de.baschek.gymapp.core.update.ReleaseParser
import de.baschek.gymapp.core.update.UpdateInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateUrlTest {
    @Test
    fun acceptsGithubHttpsHosts() {
        assertTrue(ReleaseParser.isTrustedDownloadUrl("https://github.com/o/r/releases/download/android-v1.0.0/app.apk"))
        assertTrue(ReleaseParser.isTrustedDownloadUrl("https://objects.githubusercontent.com/x?y=1"))
        assertTrue(ReleaseParser.isTrustedDownloadUrl("https://GitHub.com:443/o/r"))
    }

    @Test
    fun rejectsTrickyOrForeignUrls() {
        listOf(
            null,
            "",
            "http://github.com/a",
            "https://github.com:x@evil.tld/",
            "https://github.com@evil.tld/",
            "https://user@github.com/a",
            "https://evil.tld/github.com",
            "https://github.com.evil.tld/",
            "https://evilgithubusercontent.com/",
            "https://evil.tld/?@github.com",
            "https://github.com\\@evil.tld/",
            "ftp://github.com/a",
            "https:///a",
        ).forEach { assertFalse(ReleaseParser.isTrustedDownloadUrl(it), "should be rejected: $it") }
    }

    @Test
    fun missingDigestBlocksTheInstall() {
        val info = UpdateInfo("android-v1.0.0", "1.0.0", "https://github.com/a/b.apk", "b.apk", 1L, null, null)
        val error = assertFailsWith<IllegalStateException> { ReleaseParser.requireDigest(info) }
        assertTrue(error.message!!.contains("SHA-256"))
        val digest = "a".repeat(64)
        assertEquals(digest, ReleaseParser.requireDigest(info.copy(sha256 = digest)))
    }
}
