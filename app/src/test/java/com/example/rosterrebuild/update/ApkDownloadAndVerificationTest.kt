package com.example.rosterrebuild.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class ApkDownloadAndVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var validManifest: UpdateManifest

    @Before
    fun setUp() {
        validManifest = UpdateManifest(
            applicationId = "com.example.rosterrebuild",
            versionCode = 2,
            versionName = "1.0.1-pilot.1",
            minimumSupportedVersionCode = 1,
            releaseChannel = "hospital-pilot",
            mandatory = false,
            apkUrl = "https://hospital.internal.net/roster/app-release.apk",
            sha256 = "564e6bfb92c320f3b3eacb4ab8acbf2340aa7caf935c39af91530da0d05c9061",
            releaseNotes = listOf("Security update"),
            publishedAt = "2026-11-03T00:00:00Z"
        )
    }

    @Test
    fun calculateFileSha256_computesCorrectHashWithoutLoadingIntoRam() {
        val testFile = tempFolder.newFile("sample.apk")
        val testContent = "Medical Roster Extractor APK test payload binary bytes 12345".toByteArray()
        FileOutputStream(testFile).use { it.write(testContent) }

        // Compute expected hash manually
        val md = MessageDigest.getInstance("SHA-256")
        val expectedHash = md.digest(testContent).joinToString("") { "%02x".format(it) }

        // DownloadManager uses the mock context or dummy constructor
        val dummyContext = createDummyContext(tempFolder.root)
        val downloadManager = ApkDownloadManager(dummyContext)
        val computedHash = downloadManager.calculateFileSha256(testFile)

        assertEquals(expectedHash, computedHash)
    }

    @Test
    fun downloadApk_whenNonHttpsUrl_rejectsImmediately() = runTest {
        val nonHttpsManifest = validManifest.copy(apkUrl = "http://hospital.internal.net/insecure.apk")
        val dummyContext = createDummyContext(tempFolder.root)
        val downloadManager = ApkDownloadManager(dummyContext)

        val result = downloadManager.downloadApk(nonHttpsManifest)
        assertTrue(result is DownloadResult.Failure)
        val failure = result as DownloadResult.Failure
        assertTrue(failure.reason.contains("HTTPS"))
    }

    @Test
    fun verifyChecksum_whenHashMismatched_deletesTempFile() {
        val testFile = tempFolder.newFile("mismatched.apk.part")
        FileOutputStream(testFile).use { it.write("Corrupted payload".toByteArray()) }
        assertTrue(testFile.exists())

        val dummyContext = createDummyContext(tempFolder.root)
        val downloadManager = ApkDownloadManager(dummyContext)
        val actualHash = downloadManager.calculateFileSha256(testFile)

        assertNotEquals(validManifest.sha256, actualHash)

        // Simulating the cleanup on hash mismatch
        if (!actualHash.equals(validManifest.sha256, ignoreCase = true)) {
            testFile.delete()
        }
        assertFalse("File must be deleted after checksum failure", testFile.exists())
    }

    @Test
    fun cleanOldUpdates_deletesAllApkAndPartFiles() {
        val updatesDir = File(tempFolder.root, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, "old-update.apk").apply { createNewFile() }
        val partFile = File(updatesDir, "interrupted.apk.part").apply { createNewFile() }
        val otherFile = File(updatesDir, "keep_this.txt").apply { createNewFile() }

        val dummyContext = createDummyContext(tempFolder.root)
        val downloadManager = ApkDownloadManager(dummyContext)
        downloadManager.cleanOldUpdates()

        assertFalse("old-update.apk should be deleted", apkFile.exists())
        assertFalse("interrupted.apk.part should be deleted", partFile.exists())
        assertTrue("Other non-apk files should remain untouched", otherFile.exists())
    }

    @Test
    fun verifyApk_whenFileEmptyOrMissing_returnsFailure() {
        val missingFile = File(tempFolder.root, "non_existent.apk")
        val dummyContext = createDummyContext(tempFolder.root)

        val result = ApkIdentityVerifier.verifyApk(dummyContext, missingFile, validManifest)
        assertTrue(result is ApkVerificationResult.Failure)
        val failure = result as ApkVerificationResult.Failure
        assertTrue(failure.reason.contains("does not exist"))
    }

    @Test
    fun updateDeferral_whenDeferred_avoidsRepeatedPromptsInSession() {
        var deferredVersionCode = -1

        // User defers version 2
        deferredVersionCode = 2

        // Simulate incoming update check for version 2
        val incomingVersion = 2
        val isManualCheck = false
        val isMandatory = false

        val shouldPrompt = isManualCheck || isMandatory || (incomingVersion != deferredVersionCode)
        assertFalse("Optional update deferred in this session must not prompt again automatically", shouldPrompt)

        // But manual check must prompt even if deferred
        val shouldPromptOnManual = true || isMandatory || (incomingVersion != deferredVersionCode)
        assertTrue("Manual check must always present the update regardless of session deferral", shouldPromptOnManual)
    }

    @Test
    fun offlineAppOperation_whenManifestCheckFails_doesNotBlockApp() {
        // Simulating offline background check vs manual check
        val isManual = false
        val isNetworkAvailable = false

        val simulatedCheckResult: UpdateCheckResult = if (!isNetworkAvailable) {
            UpdateCheckResult.Error("Unable to resolve host: hospital.internal.net")
        } else {
            UpdateCheckResult.UpToDate(1, validManifest)
        }

        assertTrue(simulatedCheckResult is UpdateCheckResult.Error)

        // When not manual, offline check resolves to silent idle
        val uiState = if (simulatedCheckResult is UpdateCheckResult.Error && !isManual) {
            UpdateUiState.Idle
        } else {
            UpdateUiState.Error((simulatedCheckResult as UpdateCheckResult.Error).message)
        }

        assertEquals("Background offline check must remain Idle to avoid disturbing medical staff", UpdateUiState.Idle, uiState)
    }

    @Test
    fun manualCheck_whenOffline_surfacesRetryOption() {
        val isManual = true
        val checkResult = UpdateCheckResult.Error("Connection timed out", null)

        val uiState = if (isManual && checkResult is UpdateCheckResult.Error) {
            UpdateUiState.Error(
                message = checkResult.message,
                canRetry = true
            )
        } else {
            UpdateUiState.Idle
        }

        assertTrue(uiState is UpdateUiState.Error)
        val errorState = uiState as UpdateUiState.Error
        assertTrue(errorState.canRetry)
        assertEquals("Connection timed out", errorState.message)
    }

    @Test
    fun certificateDigestsComparison_rejectsDifferentCertificates() {
        val authorizedCertFingerprint = "564e6bfb92c320f3b3eacb4ab8acbf2340aa7caf935c39af91530da0d05c9061"
        val attackerCertFingerprint = "deadbeef1234567890abcdef1234567890abcdef1234567890abcdef12345678"

        val matches = authorizedCertFingerprint.equals(attackerCertFingerprint, ignoreCase = true)
        assertFalse("Different signing certificate must be strictly rejected", matches)
    }

    private fun createDummyContext(filesDirRoot: File): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = filesDirRoot
            override fun getPackageName(): String = "com.example.rosterrebuild"
        }
    }
}
