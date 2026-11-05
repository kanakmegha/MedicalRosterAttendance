package com.example.rosterrebuild.update

import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {

    private val validManifestJson = """
        {
          "applicationId": "com.example.rosterrebuild",
          "versionCode": 2,
          "versionName": "1.0.1-pilot.1",
          "minimumSupportedVersionCode": 1,
          "releaseChannel": "hospital-pilot",
          "mandatory": false,
          "apkUrl": "https://hospital-server.internal.net/medical-roster/app-release.apk",
          "sha256": "564e6bfb92c320f3b3eacb4ab8acbf2340aa7caf935c39af91530da0d05c9061",
          "releaseNotes": [
            "Improved roster extraction",
            "Fixed calendar navigation",
            "Improved attendance handling"
          ],
          "publishedAt": "2026-11-03T00:00:00Z"
        }
    """.trimIndent()

    @Test
    fun parseValidManifest_success() {
        val manifest = UpdateManifest.fromJson(validManifestJson)
        assertEquals("com.example.rosterrebuild", manifest.applicationId)
        assertEquals(2, manifest.versionCode)
        assertEquals("1.0.1-pilot.1", manifest.versionName)
        assertEquals(1, manifest.minimumSupportedVersionCode)
        assertEquals("hospital-pilot", manifest.releaseChannel)
        assertFalse(manifest.mandatory)
        assertEquals("https://hospital-server.internal.net/medical-roster/app-release.apk", manifest.apkUrl)
        assertEquals("564e6bfb92c320f3b3eacb4ab8acbf2340aa7caf935c39af91530da0d05c9061", manifest.sha256)
        assertEquals(3, manifest.releaseNotes.size)
        assertEquals("Improved roster extraction", manifest.releaseNotes[0])
        assertEquals("2026-11-03T00:00:00Z", manifest.publishedAt)
    }

    @Test
    fun evaluate_whenNewerVersion_returnsUpdateAvailable() {
        val manifest = UpdateManifest.fromJson(validManifestJson)
        val result = UpdateManifestValidator.evaluate(
            manifest = manifest,
            expectedApplicationId = "com.example.rosterrebuild",
            currentVersionCode = 1,
            currentVersionName = "1.0.0-pilot.1"
        )

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val available = result as UpdateCheckResult.UpdateAvailable
        assertEquals(2, available.manifest.versionCode)
        assertFalse(available.isMandatory)
    }

    @Test
    fun evaluate_whenCurrentVersionCodeBelowMinimumSupported_returnsMandatoryUpdate() {
        val json = validManifestJson.replace(
            "\"minimumSupportedVersionCode\": 1",
            "\"minimumSupportedVersionCode\": 2"
        )
        val manifest = UpdateManifest.fromJson(json)
        val result = UpdateManifestValidator.evaluate(
            manifest = manifest,
            expectedApplicationId = "com.example.rosterrebuild",
            currentVersionCode = 1,
            currentVersionName = "1.0.0-pilot.1"
        )

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val available = result as UpdateCheckResult.UpdateAvailable
        assertTrue("Expected update to be mandatory because current versionCode < minimumSupportedVersionCode", available.isMandatory)
    }

    @Test
    fun evaluate_whenMandatoryFlagIsTrue_returnsMandatoryUpdate() {
        val json = validManifestJson.replace(
            "\"mandatory\": false",
            "\"mandatory\": true"
        )
        val manifest = UpdateManifest.fromJson(json)
        val result = UpdateManifestValidator.evaluate(
            manifest = manifest,
            expectedApplicationId = "com.example.rosterrebuild",
            currentVersionCode = 1,
            currentVersionName = "1.0.0-pilot.1"
        )

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val available = result as UpdateCheckResult.UpdateAvailable
        assertTrue(available.isMandatory)
    }

    @Test
    fun evaluate_whenInstalledVersionIsEqualOrNewer_returnsUpToDate() {
        val manifest = UpdateManifest.fromJson(validManifestJson)
        val result = UpdateManifestValidator.evaluate(
            manifest = manifest,
            expectedApplicationId = "com.example.rosterrebuild",
            currentVersionCode = 2,
            currentVersionName = "1.0.1-pilot.1"
        )

        assertTrue(result is UpdateCheckResult.UpToDate)
        val upToDate = result as UpdateCheckResult.UpToDate
        assertEquals(2, upToDate.currentVersionCode)
    }

    @Test
    fun evaluate_whenApplicationIdMismatched_returnsError() {
        val manifest = UpdateManifest.fromJson(validManifestJson)
        val result = UpdateManifestValidator.evaluate(
            manifest = manifest,
            expectedApplicationId = "com.other.hospitalapp",
            currentVersionCode = 1,
            currentVersionName = "1.0.0-pilot.1"
        )

        assertTrue(result is UpdateCheckResult.Error)
        val error = result as UpdateCheckResult.Error
        assertTrue(error.message.contains("Application ID mismatch"))
    }

    @Test
    fun evaluate_whenApkUrlIsNotHttps_returnsError() {
        val json = validManifestJson.replace(
            "https://hospital-server",
            "http://hospital-server"
        )
        val manifest = UpdateManifest.fromJson(json)
        val result = UpdateManifestValidator.evaluate(
            manifest = manifest,
            expectedApplicationId = "com.example.rosterrebuild",
            currentVersionCode = 1,
            currentVersionName = "1.0.0-pilot.1"
        )

        assertTrue(result is UpdateCheckResult.Error)
        val error = result as UpdateCheckResult.Error
        assertTrue(error.message.contains("HTTPS"))
    }

    @Test
    fun evaluate_whenSha256ChecksumIsInvalid_returnsError() {
        val json = validManifestJson.replace(
            "564e6bfb92c320f3b3eacb4ab8acbf2340aa7caf935c39af91530da0d05c9061",
            "not-a-valid-sha256"
        )
        val manifest = UpdateManifest.fromJson(json)
        val result = UpdateManifestValidator.evaluate(
            manifest = manifest,
            expectedApplicationId = "com.example.rosterrebuild",
            currentVersionCode = 1,
            currentVersionName = "1.0.0-pilot.1"
        )

        assertTrue(result is UpdateCheckResult.Error)
        val error = result as UpdateCheckResult.Error
        assertTrue(error.message.contains("Invalid SHA-256"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseManifest_whenMissingApplicationId_throwsException() {
        val json = validManifestJson.replace("\"applicationId\": \"com.example.rosterrebuild\",", "")
        UpdateManifest.fromJson(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseManifest_whenInvalidVersionCode_throwsException() {
        val json = validManifestJson.replace("\"versionCode\": 2,", "\"versionCode\": -5,")
        UpdateManifest.fromJson(json)
    }
}

