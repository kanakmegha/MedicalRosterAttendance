package com.example.rosterrebuild.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests AppUpdateManager behaviour without network: concurrent-check prevention,
 * dismissal persistence, manual-check UX, and DownloadManager failure handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateManagerTest {

    private val context = FakeUpdateContext()

    private fun sampleUpdate(version: String = "1.0.0-pilot.6") = AvailableAppUpdate(
        versionName = version,
        versionCode = 6L,
        apkDownloadUrl =
            "https://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk",
        releasePageUrl =
            "https://github.com/kanakmegha/MedicalRosterAttendance/releases/tag/v1.0.0-pilot.6",
        releaseNotes = "Notes",
        sha256 = null
    )

    @Test
    fun checkForUpdates_duplicateConcurrentChecksRunOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var checks = 0
        val manager = AppUpdateManager(
            context = context,
            scope = this,
            checkNow = {
                checks++
                delay(5_000) // Keep the check "in flight"
                AppUpdateCheckResult.UpdateAvailable(sampleUpdate())
            }
        )

        manager.checkForUpdates(isManual = true)
        manager.checkForUpdates(isManual = true) // Must be ignored while first check runs
        advanceUntilIdle()

        assertEquals(1, checks)
        assertTrue(manager.uiState.value is UpdateUiState.Available)
    }

    @Test
    fun dismissedVersionIsSuppressedOnStartupButNotManually() = runTest {
        val manager = AppUpdateManager(
            context = context,
            scope = this,
            checkNow = { AppUpdateCheckResult.UpdateAvailable(sampleUpdate()) }
        )
        manager.dismissUpdate("1.0.0-pilot.6")

        // Startup check for a dismissed version is silently suppressed
        manager.checkForUpdates(isManual = false)
        advanceUntilIdle()
        assertEquals(UpdateUiState.Idle, manager.uiState.value)

        // A manual check always surfaces the update regardless of dismissal
        manager.checkForUpdates(isManual = true)
        advanceUntilIdle()
        assertTrue(manager.uiState.value is UpdateUiState.Available)
    }

    @Test
    fun newVersionAfterDismissionIsShown() = runTest {
        val manager = AppUpdateManager(
            context = context,
            scope = this,
            checkNow = { AppUpdateCheckResult.UpdateAvailable(sampleUpdate("1.0.0-pilot.6")) }
        )
        manager.dismissUpdate("1.0.0-pilot.6")
        manager.checkForUpdates(isManual = false)
        advanceUntilIdle()
        assertEquals(UpdateUiState.Idle, manager.uiState.value)

        val manager2 = AppUpdateManager(
            context = context, // Same persisted prefs: pilot.6 dismissed
            scope = this,
            checkNow = { AppUpdateCheckResult.UpdateAvailable(sampleUpdate("1.0.0-pilot.7")) }
        )
        // Clear the throttle timestamp recorded by the first manager so the
        // startup check under test actually runs (isolate dismissal logic).
        manager2.configStore.recordCheckTimestamp(0L)
        manager2.checkForUpdates(isManual = false)
        advanceUntilIdle()
        assertTrue(manager2.uiState.value is UpdateUiState.Available)
        assertEquals("1.0.0-pilot.7", (manager2.uiState.value as UpdateUiState.Available).update.versionName)
    }

    @Test
    fun manualCheckShowsUpToDateResultAndBackgroundCheckStaysSilent() = runTest {
        val manager = AppUpdateManager(
            context = context,
            scope = this,
            checkNow = {
                AppUpdateCheckResult.UpToDate(
                    currentVersion = "1.0.0-pilot.5",
                    latestVersion = "1.0.0-pilot.5"
                )
            }
        )

        manager.checkForUpdates(isManual = false)
        advanceUntilIdle()
        assertEquals(UpdateUiState.Idle, manager.uiState.value)

        manager.checkForUpdates(isManual = true)
        advanceUntilIdle()
        assertTrue(manager.uiState.value is UpdateUiState.UpToDate)
    }

    @Test
    fun backgroundFailureStaysSilentAndManualFailureSurfaces() = runTest {
        val manager = AppUpdateManager(
            context = context,
            scope = this,
            checkNow = { AppUpdateCheckResult.Failed("Couldn't connect to GitHub.") }
        )

        manager.checkForUpdates(isManual = false)
        advanceUntilIdle()
        assertEquals(UpdateUiState.Idle, manager.uiState.value)

        manager.checkForUpdates(isManual = true)
        advanceUntilIdle()
        assertTrue(manager.uiState.value is UpdateUiState.Error)
        assertEquals(
            AppUpdateChecker.RELEASES_PAGE_URL,
            (manager.uiState.value as UpdateUiState.Error).releasePageUrl
        )
    }

    @Test
    fun throttleSuppressesAutomaticChecksButNotManualChecks() = runTest {
        val manager = AppUpdateManager(
            context = context,
            scope = this,
            checkNow = { AppUpdateCheckResult.UpToDate(currentVersion = "1.0.0-pilot.5", latestVersion = "1.0.0-pilot.5") }
        )
        manager.configStore.recordCheckTimestamp(System.currentTimeMillis())

        manager.checkForUpdates(isManual = false) // Throttled: no check
        advanceUntilIdle()
        assertEquals(UpdateUiState.Idle, manager.uiState.value)
        assertTrue(manager.configStore.shouldThrottleCheck())

        manager.checkForUpdates(isManual = true) // Manual checks bypass throttling by design
        advanceUntilIdle()
        assertTrue(manager.uiState.value is UpdateUiState.UpToDate)
    }

    @Test
    fun downloadEnqueueFailureFallsBackToReleasesPageError() = runTest {
        val manager = AppUpdateManager(
            context = context, // Fake context exposes no DownloadManager service
            scope = this,
            checkNow = { AppUpdateCheckResult.UpdateAvailable(sampleUpdate()) }
        )

        manager.enqueueDownload(sampleUpdate())

        val state = manager.uiState.value
        assertTrue(state is UpdateUiState.Error)
        val error = state as UpdateUiState.Error
        assertTrue(error.message.contains("GitHub Releases"))
        assertEquals(AppUpdateChecker.RELEASES_PAGE_URL, error.releasePageUrl)
    }
}
