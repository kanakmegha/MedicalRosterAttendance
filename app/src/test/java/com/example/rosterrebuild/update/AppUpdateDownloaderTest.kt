package com.example.rosterrebuild.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure, JVM-testable parts of AppUpdateDownloader:
 * filename shape, URL gating, duplicate-status semantics, and the
 * DownloadManager-unavailable failure path.
 *
 * Actual enqueue behaviour against the system DownloadManager is covered
 * by on-device tests (out of scope for JVM unit tests).
 */
class AppUpdateDownloaderTest {

    private val sampleUpdate = AvailableAppUpdate(
        versionName = "1.0.0-pilot.6",
        versionCode = 6L,
        apkDownloadUrl =
            "https://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk",
        releasePageUrl =
            "https://github.com/kanakmegha/MedicalRosterAttendance/releases/tag/v1.0.0-pilot.6"
    )

    @Test
    fun destinationFileNameFollowsPredictablePattern() {
        assertEquals(
            "MyRoster-1.0.0-pilot.6.apk",
            AppUpdateDownloader.destinationFileNameFor(sampleUpdate)
        )
    }

    @Test
    fun enqueue_rejectsNonHttpsUrl() {
        val insecure = sampleUpdate.copy(
            apkDownloadUrl = "http://github.com/kanakmegha/MedicalRosterAttendance/releases/download/v1.0.0-pilot.6/app-release.apk"
        )

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            AppUpdateDownloader.enqueue(
                com.example.rosterrebuild.update.FakeUpdateContext(),
                insecure
            )
        }
        assertTrue(thrown.message!!.contains("HTTPS"))
    }

    @Test
    fun enqueue_rejectsUnauthorizedRepositoryUrl() {
        val foreign = sampleUpdate.copy(
            apkDownloadUrl = "https://github.com/otheruser/OtherRepo/releases/download/v1.0.0-pilot.6/app-release.apk"
        )

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            AppUpdateDownloader.enqueue(
                FakeUpdateContext(),
                foreign
            )
        }
        assertTrue(thrown.message!!.contains("official repository"))
    }

    @Test
    fun enqueue_failsGracefullyWhenDownloadServiceUnavailable() {
        val thrown = assertThrows(java.io.IOException::class.java) {
            AppUpdateDownloader.enqueue(FakeUpdateContext(), sampleUpdate)
        }
        assertTrue(thrown.message!!.contains("download service is unavailable"))
    }

    @Test
    fun activeDownloadStatusSemantics() {
        assertTrue(AppDownloadManagerStatus.PENDING.isActiveDownloadStatus())
        assertTrue(AppDownloadManagerStatus.RUNNING.isActiveDownloadStatus())
        assertTrue(AppDownloadManagerStatus.PAUSED.isActiveDownloadStatus())
        assertFalse(AppDownloadManagerStatus.SUCCESSFUL.isActiveDownloadStatus())
        assertFalse(AppDownloadManagerStatus.FAILED.isActiveDownloadStatus())
    }
}

/** Mirrors DownloadManager status constants, since they cannot be referenced directly on the JVM. */
private object AppDownloadManagerStatus {
    const val PENDING = android.app.DownloadManager.STATUS_PENDING
    const val RUNNING = android.app.DownloadManager.STATUS_RUNNING
    const val PAUSED = android.app.DownloadManager.STATUS_PAUSED
    const val SUCCESSFUL = android.app.DownloadManager.STATUS_SUCCESSFUL
    const val FAILED = android.app.DownloadManager.STATUS_FAILED
}
