package com.example.rosterrebuild.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AppUpdateCheckerTest {

    @Test
    fun normalizeVersion_removesPrefixesAndTrims() {
        assertEquals("1.0.0-pilot.5", normalizeVersion("v1.0.0-pilot.5"))
        assertEquals("1.0.0-pilot.5", normalizeVersion("V1.0.0-pilot.5"))
        assertEquals("2.1.0", normalizeVersion("  v2.1.0  "))
        assertEquals("1.0.0", normalizeVersion("1.0.0"))
    }

    @Test
    fun extractVersionCode_parsesFromBody() {
        val body1 = "New pilot release\nversionCode: 6\nBug fixes included"
        assertEquals(6L, extractVersionCode(body1, "v1.0.0-pilot.6"))

        val body2 = "Update description\nversion_code = 12\nSome notes"
        assertEquals(12L, extractVersionCode(body2, "v1.0.0"))

        val body3 = "Hospital release\nBuild: 42"
        assertEquals(42L, extractVersionCode(body3, "v2.0.0"))
    }

    @Test
    fun extractVersionCode_parsesFromTagNameWhenNotInBody() {
        val emptyBody = "No explicit code in description"
        assertEquals(5L, extractVersionCode(emptyBody, "v1.0.0-pilot.5"))
        assertEquals(6L, extractVersionCode(emptyBody, "1.0.0-pilot.6"))
        assertEquals(7L, extractVersionCode(emptyBody, "pilot-7"))
        assertEquals(10L, extractVersionCode(emptyBody, "v10"))
    }

    @Test
    fun extractVersionCode_returnsNullWhenIndeterminate() {
        val body = "General updates without code"
        assertNull(extractVersionCode(body, "release-candidate"))
    }

    @Test
    fun compareVersions_correctlyOrdersVersions() {
        assertTrue(compareVersions("1.0.0-pilot.6", "1.0.0-pilot.5") > 0)
        assertTrue(compareVersions("1.0.0-pilot.5", "1.0.0-pilot.6") < 0)
        assertEquals(0, compareVersions("1.0.0-pilot.5", "1.0.0-pilot.5"))

        assertTrue(compareVersions("1.0.1", "1.0.0") > 0)
        assertTrue(compareVersions("1.1.0", "1.0.9") > 0)
        assertTrue(compareVersions("2.0.0", "1.9.9") > 0)
        assertTrue(compareVersions("1.0.0", "1.0.1") < 0)
        assertEquals(0, compareVersions("1.0.0", "1.0.0"))
    }

    @Test
    fun isValidApkDownloadUrl_acceptsOnlyOfficialGitHubUrls() {
        assertTrue(
            AppUpdateChecker.isValidApkDownloadUrl(
                "https://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk"
            )
        )
        assertTrue(
            AppUpdateChecker.isValidApkDownloadUrl(
                "https://objects.githubusercontent.com/github-production-release-asset-2e65be/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk"
            )
        )

        // Rejects non-HTTPS
        assertFalse(
            AppUpdateChecker.isValidApkDownloadUrl(
                "http://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk"
            )
        )

        // Rejects foreign domains
        assertFalse(
            AppUpdateChecker.isValidApkDownloadUrl(
                "https://malicious-server.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk"
            )
        )

        // Rejects different repositories
        assertFalse(
            AppUpdateChecker.isValidApkDownloadUrl(
                "https://github.com/otheruser/OtherRepo/releases/download/v1.0.0-pilot.6/app-release.apk"
            )
        )
    }

    @Test
    fun isValidReleasePageUrl_acceptsOnlyGitHubReleasePages() {
        assertTrue(
            AppUpdateChecker.isValidReleasePageUrl(
                "https://github.com/kanakmegha/MedicalRosterAttendance/releases/tag/v1.0.0-pilot.6"
            )
        )
        assertFalse(
            AppUpdateChecker.isValidReleasePageUrl(
                "http://github.com/kanakmegha/MedicalRosterAttendance/releases/tag/v1.0.0-pilot.6"
            )
        )
        assertFalse(
            AppUpdateChecker.isValidReleasePageUrl(
                "https://github.com/unrelated/repo/releases"
            )
        )
    }

    @Test
    fun parseReleasePayload_detectsNewerVersion() {
        val payload = JSONObject().apply {
            put("tag_name", "v1.0.0-pilot.6")
            put("draft", false)
            put("body", "Bug fixes and improvements\nversionCode: 6")
            put("html_url", "https://github.com/kanakmegha/MedicalRosterAttendance/releases/tag/v1.0.0-pilot.6")
            put("assets", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "app-release.apk")
                    put(
                        "browser_download_url",
                        "https://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk"
                    )
                })
            })
        }

        val result = AppUpdateChecker.parseReleasePayload(
            release = payload,
            currentVersionName = "1.0.0-pilot.5",
            currentVersionCode = 5L
        )

        assertTrue(result is AppUpdateCheckResult.UpdateAvailable)
        val update = (result as AppUpdateCheckResult.UpdateAvailable).update
        assertEquals("1.0.0-pilot.6", update.versionName)
        assertEquals(6L, update.versionCode)
        assertEquals(
            "https://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk",
            update.apkDownloadUrl
        )
    }

    @Test
    fun parseReleasePayload_detectsUpToDate() {
        val payload = JSONObject().apply {
            put("tag_name", "v1.0.0-pilot.5")
            put("draft", false)
            put("body", "Current release\nversionCode: 5")
            put("html_url", "https://github.com/kanakmegha/MedicalRosterAttendance/releases/tag/v1.0.0-pilot.5")
            put("assets", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "app-release.apk")
                    put(
                        "browser_download_url",
                        "https://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.5/app-release.apk"
                    )
                })
            })
        }

        val result = AppUpdateChecker.parseReleasePayload(
            release = payload,
            currentVersionName = "1.0.0-pilot.5",
            currentVersionCode = 5L
        )

        assertTrue(result is AppUpdateCheckResult.UpToDate)
        val upToDate = result as AppUpdateCheckResult.UpToDate
        assertEquals("1.0.0-pilot.5", upToDate.currentVersion)
        assertEquals("1.0.0-pilot.5", upToDate.latestVersion)
    }

    @Test
    fun parseReleasePayload_rejectsDrafts() {
        val payload = JSONObject().apply {
            put("tag_name", "v1.0.0-pilot.6")
            put("draft", true)
            put("body", "Draft release")
            put("assets", JSONArray())
        }

        val result = AppUpdateChecker.parseReleasePayload(
            release = payload,
            currentVersionName = "1.0.0-pilot.5",
            currentVersionCode = 5L
        )

        assertTrue(result is AppUpdateCheckResult.Failed)
        assertEquals("Latest release is a draft.", (result as AppUpdateCheckResult.Failed).message)
    }

    @Test(expected = IOException::class)
    fun parseReleasePayload_throwsWhenNoApkAsset() {
        val payload = JSONObject().apply {
            put("tag_name", "v1.0.0-pilot.6")
            put("draft", false)
            put("body", "Missing APK asset")
            put("assets", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "readme.txt")
                    put("browser_download_url", "https://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/readme.txt")
                })
            })
        }

        AppUpdateChecker.parseReleasePayload(
            release = payload,
            currentVersionName = "1.0.0-pilot.5",
            currentVersionCode = 5L
        )
    }

    @Test
    fun parseReleasePayload_olderVersionCodeReportsUpToDate() {
        val payload = JSONObject().apply {
            put("tag_name", "v1.0.0-pilot.4")
            put("draft", false)
            put("body", "versionCode: 4")
            put("assets", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "app-release.apk")
                    put("browser_download_url", "https://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.4/app-release.apk")
                })
            })
        }

        val result = AppUpdateChecker.parseReleasePayload(
            release = payload,
            currentVersionName = "1.0.0-pilot.5",
            currentVersionCode = 5L
        )

        assertTrue(result is AppUpdateCheckResult.UpToDate)
    }

    @Test
    fun parseReleasePayload_missingVersionCodeFailsSafely() {
        val payload = JSONObject().apply {
            put("tag_name", "v1.0.1") // No pilot marker, no numeric tag, no code in body
            put("draft", false)
            put("body", "Notes without any build number")
            put("assets", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "app-release.apk")
                    put("browser_download_url", "https://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.1/app-release.apk")
                })
            })
        }

        val result = AppUpdateChecker.parseReleasePayload(
            release = payload,
            currentVersionName = "1.0.0-pilot.5",
            currentVersionCode = 5L
        )

        assertTrue(result is AppUpdateCheckResult.Failed)
        assertTrue(
            (result as AppUpdateCheckResult.Failed).message.contains("versionCode")
        )
    }

    @Test
    fun parseReleasePayload_rejectsPrereleaseChannel() {
        val payload = JSONObject().apply {
            put("tag_name", "v1.0.0-pilot.6")
            put("draft", false)
            put("prerelease", true)
            put("body", "versionCode: 6")
            put("assets", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "app-release.apk")
                    put("browser_download_url", "https://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk")
                })
            })
        }

        val result = AppUpdateChecker.parseReleasePayload(
            release = payload,
            currentVersionName = "1.0.0-pilot.5",
            currentVersionCode = 5L
        )

        assertTrue(result is AppUpdateCheckResult.Failed)
        assertTrue(
            (result as AppUpdateCheckResult.Failed).message.contains("pre-release")
        )
    }

    @Test
    fun parseReleasePayload_invalidApkUrlIsRejected() {
        val payload = JSONObject().apply {
            put("tag_name", "v1.0.0-pilot.6")
            put("draft", false)
            put("body", "versionCode: 6")
            put("assets", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "app-release.apk")
                    // Non-HTTPS and wrong host: must not be chosen
                    put("browser_download_url", "http://evil.example.com/app-release.apk")
                })
            })
        }

        val result = runCatching {
            AppUpdateChecker.parseReleasePayload(
                release = payload,
                currentVersionName = "1.0.0-pilot.5",
                currentVersionCode = 5L
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun checkForUpdate_malformedPayloadFailsSafely() = kotlinx.coroutines.test.runTest {
        val result = AppUpdateChecker.checkForUpdate(
            currentVersionName = "1.0.0-pilot.5",
            currentVersionCode = 5L,
            fetchReleaseJson = { "<html>Not JSON</html>" }
        )

        assertTrue(result is AppUpdateCheckResult.Failed)
        assertTrue(
            (result as AppUpdateCheckResult.Failed).message.contains("parsed")
        )
    }

    @Test
    fun checkForUpdate_offlineNetworkFailsGracefully() = kotlinx.coroutines.test.runTest {
        val result = AppUpdateChecker.checkForUpdate(
            currentVersionName = "1.0.0-pilot.5",
            currentVersionCode = 5L,
            fetchReleaseJson = { throw java.net.UnknownHostException("api.github.com") }
        )

        assertTrue(result is AppUpdateCheckResult.Failed)
        assertTrue(
            (result as AppUpdateCheckResult.Failed).message.contains("internet")
        )
    }

    @Test
    fun checkForUpdate_failedHttpRequestFailsGracefully() = kotlinx.coroutines.test.runTest {
        val result = AppUpdateChecker.checkForUpdate(
            currentVersionName = "1.0.0-pilot.5",
            currentVersionCode = 5L,
            fetchReleaseJson = { throw IOException("GitHub returned HTTP 500") }
        )

        assertTrue(result is AppUpdateCheckResult.Failed)
        assertEquals("GitHub returned HTTP 500", (result as AppUpdateCheckResult.Failed).message)
    }

    @Test
    fun checkForUpdate_endToEndNewerReleaseIsAvailable() = kotlinx.coroutines.test.runTest {
        // Mirrors the real published release shape, including GitHub's asset digest field.
        val json = """
            {
              "tag_name": "v1.0.0-pilot.6",
              "draft": false,
              "prerelease": false,
              "body": "Pilot 6 fixes.",
              "html_url": "https://github.com/kanakmegha/MedicalRosterAttendance/releases/tag/v1.0.0-pilot.6",
              "assets": [
                {
                  "name": "app-release.apk",
                  "browser_download_url": "https://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk",
                  "digest": "sha256:7f5d2ca135870118a852ca585b6340bf3bd558ac4655fca8a61bf0d80983160c"
                }
              ]
            }
        """.trimIndent()

        val result = AppUpdateChecker.checkForUpdate(
            currentVersionName = "1.0.0-pilot.5",
            currentVersionCode = 5L,
            fetchReleaseJson = { json }
        )

        assertTrue(result is AppUpdateCheckResult.UpdateAvailable)
        val update = (result as AppUpdateCheckResult.UpdateAvailable).update
        assertEquals("1.0.0-pilot.6", update.versionName)
        assertEquals(6L, update.versionCode)
        assertEquals(
            "sha256:7f5d2ca135870118a852ca585b6340bf3bd558ac4655fca8a61bf0d80983160c",
            update.sha256
        )
        assertEquals(
            "https://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk",
            update.apkDownloadUrl
        )
    }

    @Test
    fun parseReleasePayload_invalidReleasePageFallsBackToOfficialPage() {
        val payload = JSONObject().apply {
            put("tag_name", "v1.0.0-pilot.6")
            put("draft", false)
            put("body", "versionCode: 6")
            // Foreign host claiming to be the release page
            put("html_url", "https://malicious.example.com/kanakmegha/MedicalRosterAttendance/releases/tag/v1.0.0-pilot.6")
            put("assets", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "app-release.apk")
                    put("browser_download_url", "https://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk")
                })
            })
        }

        val result = AppUpdateChecker.parseReleasePayload(
            release = payload,
            currentVersionName = "1.0.0-pilot.5",
            currentVersionCode = 5L
        )

        assertTrue(result is AppUpdateCheckResult.UpdateAvailable)
        assertEquals(
            AppUpdateChecker.RELEASES_PAGE_URL,
            (result as AppUpdateCheckResult.UpdateAvailable).update.releasePageUrl
        )
    }

    @Test
    fun parseReleasePayload_captureSha256Digest() {
        val payload = JSONObject().apply {
            put("tag_name", "v1.0.0-pilot.6")
            put("draft", false)
            put("body", "versionCode: 6")
            put("assets", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "app-release.apk")
                    put("browser_download_url", "https://objects.githubusercontent.com/github-production-release-asset-2e65be/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk")
                    put("digest", "sha256:abc123")
                })
            })
        }

        val result = AppUpdateChecker.parseReleasePayload(
            release = payload,
            currentVersionName = "1.0.0-pilot.5",
            currentVersionCode = 5L
        )

        assertEquals(
            "sha256:abc123",
            (result as AppUpdateCheckResult.UpdateAvailable).update.sha256
        )
    }
}
