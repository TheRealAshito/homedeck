package com.therealashito.homedeck

import com.therealashito.homedeck.net.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun newerRemoteTagIsNewer() {
        assertTrue(UpdateChecker.isNewer("v1.2.0", "1.1.0"))
        assertTrue(UpdateChecker.isNewer("1.1.1", "1.1.0"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("v1.1.0", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("v1.0.9", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.1"))
    }

    @Test
    fun garbageNeverClaimsAnUpdate() {
        assertFalse(UpdateChecker.isNewer("latest", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("v1.2.0", "not-a-version"))
        assertFalse(UpdateChecker.isNewer("", ""))
    }

    @Test
    fun parsesLatestReleaseJsonWithApkAsset() {
        val json = """
            {
              "tag_name": "v1.2.0",
              "html_url": "https://github.com/TheRealAshito/homedeck/releases/tag/v1.2.0",
              "body": "release notes",
              "assets": [
                {"name": "Homedeck-1.2.0.apk",
                 "browser_download_url": "https://github.com/TheRealAshito/homedeck/releases/download/v1.2.0/Homedeck-1.2.0.apk"},
                {"name": "Source code (zip)",
                 "browser_download_url": "https://github.com/TheRealAshito/homedeck/zipball/v1.2.0"}
              ]
            }
        """.trimIndent()

        val release = UpdateChecker.parseLatest(json)
        assertNotNull(release)
        assertEquals("v1.2.0", release!!.tag)
        assertEquals("https://github.com/TheRealAshito/homedeck/releases/tag/v1.2.0", release.url)
        assertNotNull(release.apkUrl)
        assertTrue(release.apkUrl!!.endsWith("Homedeck-1.2.0.apk"))
        assertEquals("release notes", release.notes)
    }

    @Test
    fun releaseWithoutApkAssetStillParses() {
        val json = """{"tag_name": "v9.9.9", "html_url": "https://example/x", "assets": []}"""
        val release = UpdateChecker.parseLatest(json)
        assertNotNull(release)
        assertNull(release!!.apkUrl)
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(UpdateChecker.parseLatest("not json"))
        assertNull(UpdateChecker.parseLatest("{}"))
    }
}
