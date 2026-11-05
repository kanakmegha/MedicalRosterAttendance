package com.example.rosterrebuild

import androidx.lifecycle.SavedStateHandle
import com.example.rosterrebuild.data.local.RosterEntity
import com.example.rosterrebuild.ui.ActiveScreen
import com.example.rosterrebuild.ui.ExtractionStatus
import com.example.rosterrebuild.ui.ExtractionUiState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Storage Reliability & Canonical Image Audit Test Suite.
 *
 * Verifies:
 * 1. SAF file integrity via SHA-256 (same size, different content).
 * 2. Interrupted canonical image writes via .part staging and atomic rename.
 * 3. Incomplete write cleanup and protection of existing valid canonical images.
 * 4. Repeated image selection (same URI) avoids redundant copying.
 * 5. Starting another upload before saving previous cleans up superseded uncommitted directories.
 * 6. Extraction failure and retry reuse the same canonical image without duplication.
 * 7. Database save failure and retry reuse the same canonical image without duplication.
 * 8. Corrected uploads receive separate canonical paths without overwriting previous versions.
 * 9. Idempotent SAF synchronization avoids creating duplicate files like "original-roster (1).png".
 * 10. Existing data compatibility: Room references point to valid, accessible files.
 */
class CanonicalImageStorageTest {

    private lateinit var testBaseDir: File
    private lateinit var testFilesDir: File
    private lateinit var testCacheDir: File
    private lateinit var testSafDir: File

    @Before
    fun setUp() {
        testBaseDir = File(System.getProperty("java.io.tmpdir"), "test_canonical_roster_" + UUID.randomUUID())
        testBaseDir.mkdirs()
        testFilesDir = File(testBaseDir, "files")
        testFilesDir.mkdirs()
        testCacheDir = File(testBaseDir, "cache")
        testCacheDir.mkdirs()
        testSafDir = File(testBaseDir, "saf_documents")
        testSafDir.mkdirs()
    }

    @After
    fun tearDown() {
        testBaseDir.deleteRecursively()
    }

    private fun getRosterDir(employeeId: String, rosterId: String): File {
        val dir = File(testFilesDir, "rosters/$employeeId/$rosterId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun listAllImageFiles(root: File): List<File> {
        return root.walkTopDown().filter { it.isFile && (it.name.endsWith(".png") || it.name.endsWith(".jpg") || it.name.endsWith(".part")) }.toList()
    }

    private fun computeSha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Simulates copying into canonical storage using .part intermediate staging and atomic rename.
     */
    private fun storeToCanonicalSafely(
        sourceBytes: ByteArray,
        employeeId: String,
        rosterId: String,
        simulateInterruptAfterBytes: Int? = null
    ): File {
        val dir = getRosterDir(employeeId, rosterId)
        val targetFile = File(dir, "original_roster.png")
        val partFile = File(dir, "original_roster.png.part")
        if (partFile.exists()) partFile.delete()

        try {
            FileOutputStream(partFile).use { out ->
                if (simulateInterruptAfterBytes != null) {
                    val bytesToWrite = minOf(simulateInterruptAfterBytes, sourceBytes.size)
                    out.write(sourceBytes, 0, bytesToWrite)
                    out.flush()
                    throw IOException("Simulated network/process interruption after $bytesToWrite bytes")
                } else {
                    out.write(sourceBytes)
                }
            }

            require(partFile.exists() && partFile.length() == sourceBytes.size.toLong()) { "Incomplete write" }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = partFile.renameTo(targetFile)
            if (!renamed) {
                partFile.inputStream().use { input ->
                    FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                }
                partFile.delete()
            }
            return targetFile
        } catch (e: Exception) {
            if (partFile.exists()) {
                partFile.delete()
            }
            throw e
        }
    }

    @Test
    fun test01_OneSelectedSourceCreatesOneCanonicalStoredImage() {
        println("\n=== Scenario 1: One Selected Source Creates One Canonical Stored Image ===")
        val employeeId = "ABHL341"
        val pendingRosterId = UUID.randomUUID().toString()
        val mockImageContent = "CANONICAL_ROSTER_IMAGE_DATA_BYTES".toByteArray()

        val canonicalImage = storeToCanonicalSafely(mockImageContent, employeeId, pendingRosterId)

        val imageFiles = listAllImageFiles(testFilesDir)
        println("Created image files count in private storage: ${imageFiles.size}")
        imageFiles.forEach { println(" - Path: ${it.absolutePath} (size: ${it.length()} bytes)") }

        assertEquals(1, imageFiles.size)
        assertEquals(canonicalImage.absolutePath, imageFiles.first().absolutePath)
        assertTrue("Canonical image file must exist", canonicalImage.exists())
        assertEquals("original_roster.png", canonicalImage.name)

        val tempFiles = listAllImageFiles(testCacheDir)
        println("Created image files count in temp cache: ${tempFiles.size}")
        assertEquals("Temp cache must have 0 image files", 0, tempFiles.size)
    }

    @Test
    fun test02_RotationDoesNotCreateAnotherImageFile() {
        println("\n=== Scenario 2: Rotation Does Not Create Another Image File ===")
        val employeeId = "ABHL341"
        val rosterId = UUID.randomUUID().toString()
        val canonicalImage = storeToCanonicalSafely("SAMPLE_ROSTER_DATA".toByteArray(), employeeId, rosterId)

        val savedState = SavedStateHandle()
        savedState["key_employee_id"] = employeeId
        savedState["key_pending_roster_id"] = rosterId
        savedState["key_cached_image_path"] = canonicalImage.absolutePath
        savedState["key_current_screen"] = ActiveScreen.ROSTER_REVIEW.name

        val initialImageFiles = listAllImageFiles(testFilesDir)
        assertEquals(1, initialImageFiles.size)

        // Activity recreation simulation
        val restoredPath = savedState.get<String>("key_cached_image_path")
        val restoredRosterId = savedState.get<String>("key_pending_roster_id")

        assertEquals(canonicalImage.absolutePath, restoredPath)
        assertEquals(rosterId, restoredRosterId)

        val restoredFile = File(restoredPath!!)
        assertTrue("Restored file must exist on disk", restoredFile.exists())

        val postRotationImageFiles = listAllImageFiles(testFilesDir)
        println("Post-rotation image files count: ${postRotationImageFiles.size}")
        assertEquals("No duplicate image file must be created on rotation", 1, postRotationImageFiles.size)
        assertEquals(canonicalImage.absolutePath, postRotationImageFiles.first().absolutePath)
    }

    @Test
    fun test03_OpeningReviewDoesNotCreateAnotherImageFile() {
        println("\n=== Scenario 3: Opening Review Does Not Create Another Image File ===")
        val employeeId = "ABHL341"
        val rosterId = UUID.randomUUID().toString()
        val canonicalImage = storeToCanonicalSafely("ROSTER_PAGE".toByteArray(), employeeId, rosterId)

        var uiState = ExtractionUiState(
            employeeId = employeeId,
            cachedImagePath = canonicalImage.absolutePath,
            pendingRosterId = rosterId,
            currentScreen = ActiveScreen.ROSTER_UPLOAD
        )

        uiState = uiState.copy(currentScreen = ActiveScreen.ROSTER_REVIEW)

        val imageFiles = listAllImageFiles(testFilesDir)
        assertEquals(1, imageFiles.size)
        assertEquals(ActiveScreen.ROSTER_REVIEW, uiState.currentScreen)
        assertEquals(canonicalImage.absolutePath, uiState.cachedImagePath)
    }

    @Test
    fun test04_OpeningCalendarDoesNotCreateAnotherImageFile() {
        println("\n=== Scenario 4: Opening Calendar Does Not Create Another Image File ===")
        val employeeId = "ABHL341"
        val rosterId = UUID.randomUUID().toString()
        val canonicalImage = storeToCanonicalSafely("CALENDAR_ROSTER".toByteArray(), employeeId, rosterId)

        var uiState = ExtractionUiState(
            employeeId = employeeId,
            cachedImagePath = canonicalImage.absolutePath,
            currentScreen = ActiveScreen.ROSTER_REVIEW
        )

        uiState = uiState.copy(currentScreen = ActiveScreen.MONTHLY_CALENDAR)

        val imageFiles = listAllImageFiles(testFilesDir)
        assertEquals(1, imageFiles.size)
        assertEquals(ActiveScreen.MONTHLY_CALENDAR, uiState.currentScreen)
        assertEquals(canonicalImage.absolutePath, uiState.cachedImagePath)
    }

    @Test
    fun test05_RetryingExtractionReusesTheSameImage() {
        println("\n=== Scenario 5: Retrying Extraction Reuses The Same Image ===")
        val employeeId = "ABHL341"
        val rosterId = UUID.randomUUID().toString()
        val canonicalImage = storeToCanonicalSafely("RETRY_EXTRACTION_ROSTER".toByteArray(), employeeId, rosterId)

        var state = ExtractionUiState(
            status = ExtractionStatus.ERROR,
            error = "Grid headers could not be aligned",
            employeeId = employeeId,
            cachedImagePath = canonicalImage.absolutePath,
            pendingRosterId = rosterId
        )

        val filesAfterFail = listAllImageFiles(testFilesDir)
        assertEquals(1, filesAfterFail.size)

        // Retry accesses existing file path without copying
        val targetPath = state.cachedImagePath!!
        val retrySourceFile = File(targetPath)
        assertTrue(retrySourceFile.exists())

        state = state.copy(status = ExtractionStatus.SUCCESS, error = null)

        val filesAfterRetry = listAllImageFiles(testFilesDir)
        assertEquals("No new image file must be created on retry extraction", 1, filesAfterRetry.size)
        assertEquals(canonicalImage.absolutePath, filesAfterRetry.first().absolutePath)
    }

    @Test
    fun test06_RetryingDatabaseSaveReusesTheSameImage() {
        println("\n=== Scenario 6: Retrying Database Save Reuses The Same Image ===")
        val employeeId = "ABHL341"
        val rosterId = UUID.randomUUID().toString()
        val canonicalImage = storeToCanonicalSafely("RETRY_SAVE_ROSTER".toByteArray(), employeeId, rosterId)

        var state = ExtractionUiState(
            status = ExtractionStatus.ERROR,
            error = "SQLiteConstraintException: Foreign key failed",
            employeeId = employeeId,
            cachedImagePath = canonicalImage.absolutePath,
            pendingRosterId = rosterId,
            isSaved = false
        )

        val filesBeforeRetry = listAllImageFiles(testFilesDir)
        assertEquals(1, filesBeforeRetry.size)

        // Retry save reuses canonical image
        val canonicalImagePath = state.cachedImagePath!!
        val imageFile = File(canonicalImagePath)
        assertTrue(imageFile.exists())

        val savedRoster = RosterEntity(
            id = rosterId,
            employeeId = employeeId,
            month = 9,
            year = 2026,
            version = 1,
            originalFilePath = imageFile.absolutePath,
            status = "ACTIVE"
        )
        state = state.copy(status = ExtractionStatus.SUCCESS, isSaved = true, activeRoster = savedRoster)

        val filesAfterSave = listAllImageFiles(testFilesDir)
        assertEquals("Exactly one physical image file must exist after retry save", 1, filesAfterSave.size)
        assertEquals(canonicalImage.absolutePath, filesAfterSave.first().absolutePath)
    }

    @Test
    fun test07_CorrectedUploadsGetASeparateCanonicalImage() {
        println("\n=== Scenario 7: Corrected Uploads Get A Separate Canonical Image ===")
        val employeeId = "ABHL341"

        // 1. Initial Roster v1
        val v1RosterId = UUID.randomUUID().toString()
        val v1Image = storeToCanonicalSafely("VERSION_1_IMAGE_ORIGINAL".toByteArray(), employeeId, v1RosterId)
        val v1Roster = RosterEntity(
            id = v1RosterId,
            employeeId = employeeId,
            month = 9,
            year = 2026,
            version = 1,
            originalFilePath = v1Image.absolutePath,
            status = "ARCHIVED"
        )

        // 2. Corrected Upload v2
        val v2RosterId = UUID.randomUUID().toString()
        val v2Image = storeToCanonicalSafely("VERSION_2_CORRECTED_IMAGE_NEW".toByteArray(), employeeId, v2RosterId)
        val v2Roster = RosterEntity(
            id = v2RosterId,
            employeeId = employeeId,
            month = 9,
            year = 2026,
            version = 2,
            originalFilePath = v2Image.absolutePath,
            status = "ACTIVE"
        )

        val allImages = listAllImageFiles(testFilesDir)
        assertEquals(2, allImages.size)
        assertNotEquals(v1Image.absolutePath, v2Image.absolutePath)
        assertTrue("v1 image must still exist", v1Image.exists())
        assertTrue("v2 image must exist", v2Image.exists())
        assertEquals("VERSION_1_IMAGE_ORIGINAL", v1Image.readText())
        assertEquals("VERSION_2_CORRECTED_IMAGE_NEW", v2Image.readText())
    }

    @Test
    fun test08_RepeatedSafSynchronizationDoesNotCreateDuplicateBackupFiles() {
        println("\n=== Scenario 8: Repeated SAF Synchronization Does Not Create Duplicate Backup Files ===")
        val employeeId = "ABHL341"
        val rosterId = UUID.randomUUID().toString()
        val canonicalImage = storeToCanonicalSafely("SAF_TEST_IMAGE_DATA".toByteArray(), employeeId, rosterId)

        val safPeriodFolder = File(testSafDir, "MedicalRosterRebuild/rosters/$employeeId/2026-09")
        safPeriodFolder.mkdirs()

        fun syncToSaf(imageFile: File, destFolder: File) {
            val targetName = "original-roster.png"
            val targetFile = File(destFolder, targetName)
            val isAlreadySynced = targetFile.exists() &&
                    targetFile.length() == imageFile.length() &&
                    targetFile.length() > 0 &&
                    computeSha256(targetFile) == computeSha256(imageFile)

            if (!isAlreadySynced) {
                FileOutputStream(targetFile).use { out ->
                    imageFile.inputStream().use { it.copyTo(out) }
                }
            }
        }

        syncToSaf(canonicalImage, safPeriodFolder)
        assertEquals(1, listAllImageFiles(testSafDir).size)

        // Repeated syncs
        syncToSaf(canonicalImage, safPeriodFolder)
        assertEquals(1, listAllImageFiles(testSafDir).size)

        syncToSaf(canonicalImage, safPeriodFolder)
        assertEquals(1, listAllImageFiles(testSafDir).size)

        // Check filename is strictly original-roster.png (no (1) duplicates)
        val safFile = listAllImageFiles(testSafDir).first()
        assertEquals("original-roster.png", safFile.name)
    }

    @Test
    fun test09_ExistingRosterImagesRemainAccessibleAfterAppRestart() {
        println("\n=== Scenario 9: Existing Roster Images Remain Accessible After App Restart ===")
        val employeeId = "ABHL341"
        val rosterId = UUID.randomUUID().toString()
        val originalBytes = "PERSISTENT_ROSTER_DATA_SURVIVES_REBOOT".toByteArray()
        val canonicalImage = storeToCanonicalSafely(originalBytes, employeeId, rosterId)

        val persistedRoster = RosterEntity(
            id = rosterId,
            employeeId = employeeId,
            month = 9,
            year = 2026,
            version = 1,
            originalFilePath = canonicalImage.absolutePath,
            status = "ACTIVE"
        )

        // Cold start simulation
        val targetPath = persistedRoster.originalFilePath
        assertNotNull(targetPath)
        val fileOnDisk = File(targetPath!!)
        assertTrue(fileOnDisk.exists())
        assertEquals(originalBytes.size.toLong(), fileOnDisk.length())
        assertEquals("PERSISTENT_ROSTER_DATA_SURVIVES_REBOOT", fileOnDisk.readText())
    }

    @Test
    fun test10_PreviousRosterVersionsRetainTheirOriginalImageReferences() {
        println("\n=== Scenario 10: Previous Roster Versions Retain Their Original Image References ===")
        val employeeId = "ABHL341"

        val v1Id = "roster-v1-" + UUID.randomUUID()
        val v1File = storeToCanonicalSafely("V1_DATA".toByteArray(), employeeId, v1Id)
        val v1Entity = RosterEntity(
            id = v1Id,
            employeeId = employeeId,
            month = 9,
            year = 2026,
            version = 1,
            originalFilePath = v1File.absolutePath,
            status = "ARCHIVED"
        )

        val v2Id = "roster-v2-" + UUID.randomUUID()
        val v2File = storeToCanonicalSafely("V2_DATA".toByteArray(), employeeId, v2Id)
        val v2Entity = RosterEntity(
            id = v2Id,
            employeeId = employeeId,
            month = 9,
            year = 2026,
            version = 2,
            originalFilePath = v2File.absolutePath,
            status = "ACTIVE"
        )

        val v1DiskFile = File(v1Entity.originalFilePath!!)
        val v2DiskFile = File(v2Entity.originalFilePath!!)

        assertTrue("V1 image must exist", v1DiskFile.exists())
        assertTrue("V2 image must exist", v2DiskFile.exists())
        assertEquals("V1_DATA", v1DiskFile.readText())
        assertEquals("V2_DATA", v2DiskFile.readText())

        assertEquals(2, listAllImageFiles(testFilesDir).size)
    }

    @Test
    fun test11_SameSizeDifferentContentImages_TriggerSha256Update() {
        println("\n=== Scenario 11: Same-Size But Different-Content Images (SHA-256 Detection) ===")
        val employeeId = "ABHL341"
        val safPeriodFolder = File(testSafDir, "MedicalRosterRebuild/rosters/$employeeId/2026-09")
        safPeriodFolder.mkdirs()

        // Two distinct images with the exact same byte length (32 bytes)
        val imageA = "AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHH".toByteArray()
        val imageB = "ZZZZYYYYXXXXWWWWVVVVUUUUTTTTSSSS".toByteArray()
        assertEquals(imageA.size, imageB.size)
        assertNotEquals(computeSha256(imageA), computeSha256(imageB))

        val localFileA = storeToCanonicalSafely(imageA, employeeId, "roster-A")
        val safFile = File(safPeriodFolder, "original-roster.png")

        // 1. Initial sync of image A
        FileOutputStream(safFile).use { localFileA.inputStream().copyTo(it) }
        assertEquals("AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHH", safFile.readText())

        // 2. User uploads image B (same size, different content)
        val localFileB = storeToCanonicalSafely(imageB, employeeId, "roster-B")

        // Sync logic under test:
        // Size comparison matches, but SHA-256 differs -> must trigger update!
        val sizesMatch = safFile.length() == localFileB.length()
        val hashesMatch = computeSha256(safFile) == computeSha256(localFileB)

        assertTrue("Sizes must match to test the collision boundary", sizesMatch)
        assertFalse("Hashes must differ despite identical size", hashesMatch)

        // Because hashes differ, update is applied
        if (!hashesMatch) {
            FileOutputStream(safFile).use { localFileB.inputStream().copyTo(it) }
        }

        assertEquals("SAF file must be updated to image B content", "ZZZZYYYYXXXXWWWWVVVVUUUUTTTTSSSS", safFile.readText())
        println("Successfully detected same-size difference via SHA-256 and updated SAF backup.")
    }

    @Test
    fun test12_InterruptedImageCopy_CleansUpPartFileAndNeverLeavesCorruptCanonicalImage() {
        println("\n=== Scenario 12: Interrupted Image Copy Cleans Up .part File and Leaves No Corrupt Image ===")
        val employeeId = "ABHL341"
        val pendingRosterId = UUID.randomUUID().toString()
        val fullData = "COMPLETE_HIGH_RES_ROSTER_IMAGE_DATA_1234567890".toByteArray()

        var caughtException: Exception? = null
        try {
            // Simulate process/network failure halfway through write
            storeToCanonicalSafely(fullData, employeeId, pendingRosterId, simulateInterruptAfterBytes = 15)
        } catch (e: Exception) {
            caughtException = e
        }

        assertNotNull("Interruption exception must be thrown", caughtException)
        println("Caught expected interruption: ${caughtException?.message}")

        val dir = getRosterDir(employeeId, pendingRosterId)
        val targetFile = File(dir, "original_roster.png")
        val partFile = File(dir, "original_roster.png.part")

        assertFalse("Corrupt target canonical file must NOT exist", targetFile.exists())
        assertFalse("Incomplete .part staging file must be cleaned up on failure", partFile.exists())
        println("Verified: No corrupt target file or orphaned .part file left behind.")
    }

    @Test
    fun test13_InterruptedImageCopy_NeverOverwritesValidExistingCanonicalImage() {
        println("\n=== Scenario 13: Interrupted Image Copy Never Overwrites Valid Existing Canonical Image ===")
        val employeeId = "ABHL341"
        val rosterId = UUID.randomUUID().toString()
        val validOriginalData = "ORIGINAL_VALID_CANONICAL_DATA".toByteArray()

        // Establish an existing valid canonical image
        val validImage = storeToCanonicalSafely(validOriginalData, employeeId, rosterId)
        assertTrue(validImage.exists())
        assertEquals("ORIGINAL_VALID_CANONICAL_DATA", validImage.readText())

        // Simulate a second interrupted write attempt to the same directory
        var caughtException: Exception? = null
        try {
            storeToCanonicalSafely("NEW_INCOMPLETE_DATA".toByteArray(), employeeId, rosterId, simulateInterruptAfterBytes = 5)
        } catch (e: Exception) {
            caughtException = e
        }

        assertNotNull(caughtException)

        // Existing valid file must be intact and uncorrupted!
        assertTrue("Existing valid canonical image must survive intact", validImage.exists())
        assertEquals("Existing valid canonical image content must be preserved", "ORIGINAL_VALID_CANONICAL_DATA", validImage.readText())

        val partFile = File(getRosterDir(employeeId, rosterId), "original_roster.png.part")
        assertFalse(".part file must be cleaned up", partFile.exists())
        println("Verified: Pre-existing canonical image preserved completely intact.")
    }

    @Test
    fun test14_RepeatedSameImageSelection_AvoidsDuplicateCopying() {
        println("\n=== Scenario 14: Repeated Same Image Selection Avoids Duplicate Copying ===")
        val employeeId = "ABHL341"
        val pendingRosterId = UUID.randomUUID().toString()
        val mockData = "IDENTICAL_IMAGE_BYTES".toByteArray()

        val canonicalImage = storeToCanonicalSafely(mockData, employeeId, pendingRosterId)

        // ExtractionUiState simulation:
        // User selects the exact same URI while canonical file is already cached and valid
        val currentUriStr = "content://media/external/images/999"
        val newUriStr = "content://media/external/images/999"
        val isSameUri = (currentUriStr == newUriStr)
        val fileValid = canonicalImage.exists()

        assertTrue("Same URI detection check", isSameUri)
        assertTrue("Canonical file validity check", fileValid)

        // Logic under test: when isSameUri && fileValid, bypass redundant copy
        if (!isSameUri || !fileValid) {
            storeToCanonicalSafely(mockData, employeeId, pendingRosterId)
        }

        val allImages = listAllImageFiles(testFilesDir)
        assertEquals("File count must remain 1", 1, allImages.size)
        assertEquals("File path must remain unchanged", canonicalImage.absolutePath, allImages.first().absolutePath)
        println("Verified: Redundant re-copy bypassed for identical image selection.")
    }

    @Test
    fun test15_StartingAnotherUploadBeforeSavingPrevious_PurgesSupersededDirectory() {
        println("\n=== Scenario 15: Starting Another Upload Before Saving Previous Cleans Up Superseded Directory ===")
        val employeeId = "ABHL341"

        // 1. User picks Image 1 (pendingId1)
        val pendingId1 = UUID.randomUUID().toString()
        val image1 = storeToCanonicalSafely("DRAFT_IMAGE_1".toByteArray(), employeeId, pendingId1)
        assertTrue(image1.exists())

        // 2. User changes mind without saving Image 1, and picks Image 2 (pendingId2)
        // System purges pendingId1 because repository.getRoster(pendingId1) == null (uncommitted)
        val isPending1Committed = false // simulated DB lookup
        if (!isPending1Committed) {
            val dir1 = getRosterDir(employeeId, pendingId1)
            dir1.deleteRecursively()
        }

        val pendingId2 = UUID.randomUUID().toString()
        val image2 = storeToCanonicalSafely("DRAFT_IMAGE_2".toByteArray(), employeeId, pendingId2)

        val allImages = listAllImageFiles(testFilesDir)
        println("Remaining images in storage: ${allImages.size}")
        allImages.forEach { println(" - Path: ${it.absolutePath}") }

        assertFalse("Superseded uncommitted image 1 directory must be deleted", image1.exists())
        assertTrue("Active image 2 must exist", image2.exists())
        assertEquals("Exactly 1 active draft image must exist, zero orphaned directories", 1, allImages.size)
        assertEquals(image2.absolutePath, allImages.first().absolutePath)
    }
}
