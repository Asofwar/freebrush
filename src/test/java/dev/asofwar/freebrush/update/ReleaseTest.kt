package dev.asofwar.freebrush.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    private fun release(tag: String, vararg assets: String, extra: String = "") = """
        {"tag_name": "$tag", "html_url": "https://github.com/Asofwar/freebrush/releases/tag/$tag", "body": "### Added\n- Updates",
         $extra "assets": [${assets.joinToString(",")}]}
    """.trimIndent()

    private fun asset(name: String, size: Long = 100) =
        """{"name": "$name", "size": $size, "browser_download_url": "https://github.com/Asofwar/freebrush/releases/download/v0.2.0/$name"}"""

    @Test
    fun versions() {
        assertEquals(Version(0, 2, 0), Version.parse("v0.2.0"))
        assertEquals(Version(1, 10, 3, dev = true), Version.parse("1.10.3-dev+abc1234"))
        assertEquals(Version(0, 1, 0, dev = true), Version.parse("0.1.0-dev"))
        assertNull(Version.parse("nightly"))
        assertNull(Version.parse("1.2"))
    }

    @Test
    fun offers() {
        val release = Version(0, 2, 0)
        assertTrue(Version.shouldOffer(Version(0, 1, 9), release))
        assertTrue(Version.shouldOffer(Version(0, 1, 10, dev = true), release))
        assertFalse(Version.shouldOffer(Version(0, 2, 0), release))
        assertFalse(Version.shouldOffer(Version(0, 3, 0), release))
        // A dev build of the same numbers may hold newer code and data: never "update" it back to the release.
        assertFalse(Version.shouldOffer(Version(0, 2, 0, dev = true), release))
        // Numbers compare as numbers, not text.
        assertTrue(Version.shouldOffer(Version(0, 9, 0), Version(0, 10, 0)))
    }

    @Test
    fun parsesLatestRelease() {
        val r = ReleaseParser.parse(release("v0.2.0", asset("FreeBrush-0.2.0.apk", 4_200_000), asset("FreeBrush-0.2.0.apk.sha256")))!!
        assertEquals(Version(0, 2, 0), r.version)
        assertEquals("v0.2.0", r.tag)
        assertEquals(4_200_000L, r.apkSize)
        assertTrue(r.apkUrl.endsWith("/FreeBrush-0.2.0.apk"))
        assertTrue(r.sha256Url.endsWith("/FreeBrush-0.2.0.apk.sha256"))
        assertEquals("### Added\n- Updates", r.notes)
    }

    @Test
    fun skipsUnusableReleases() {
        val sha = asset("FreeBrush.apk.sha256")
        assertNull(ReleaseParser.parse(release("v0.2.0", asset("notes.txt"), sha)))
        assertNull(ReleaseParser.parse(release("latest", asset("FreeBrush.apk"), sha)))
        assertNull(ReleaseParser.parse(release("v0.2.0", asset("FreeBrush.apk"), sha, extra = "\"prerelease\": true,")))
        assertNull(ReleaseParser.parse(release("v0.2.0", asset("FreeBrush.apk").replace("https://", "http://"), sha)))
        // No checksum asset: not offered, the APK could not be checked.
        assertNull(ReleaseParser.parse(release("v0.2.0", asset("FreeBrush.apk"))))
    }

    @Test
    fun checksums() {
        val hex = "a".repeat(64)
        assertEquals(hex, ReleaseParser.parseSha256("$hex  FreeBrush-0.2.0.apk\n"))
        assertEquals(hex, ReleaseParser.parseSha256(hex.uppercase()))
        assertNull(ReleaseParser.parseSha256("not a digest"))
        assertNull(ReleaseParser.parseSha256(""))
    }
}
