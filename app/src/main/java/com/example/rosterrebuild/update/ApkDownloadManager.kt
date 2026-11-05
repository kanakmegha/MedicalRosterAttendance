package com.example.rosterrebuild.update

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

sealed class DownloadResult {
    data class Success(val apkFile: File) : DownloadResult()
    data class Failure(val reason: String, val throwable: Throwable? = null) : DownloadResult()
    object Cancelled : DownloadResult()
}

/**
 * Downloads and validates an APK from a secure HTTPS endpoint directly to an app-private directory.
 *
 * Employs streaming I/O with progress reporting, available storage pre-checks,
 * incomplete file cleanup, and post-download SHA-256 validation.
 */
class ApkDownloadManager(
    private val context: Context,
    private val baseDir: File? = null
) {

    companion object {
        private const val BUFFER_SIZE = 16 * 1024 // 16 KB streaming buffer
        private const val UPDATES_DIR_NAME = "updates"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val STORAGE_SAFETY_MARGIN_BYTES = 20L * 1024 * 1024 // 20 MB buffer
    }

    private val updatesDirectory: File by lazy {
        File(baseDir ?: context.filesDir, UPDATES_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Downloads the APK referenced in [manifest].
     *
     * @param manifest The validated update manifest
     * @param onProgress Callback receiving (bytesDownloaded, totalBytes, percent)
     */
    suspend fun downloadApk(
        manifest: UpdateManifest,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, percent: Int) -> Unit = { _, _, _ -> }
    ): DownloadResult = withContext(Dispatchers.IO) {
        // 1. Strict HTTPS Validation
        if (!UpdateManifestValidator.isHttpsUrl(manifest.apkUrl)) {
            return@withContext DownloadResult.Failure(
                "Security violation: APK URL must use HTTPS. Received: '${manifest.apkUrl}'"
            )
        }

        val targetApkName = "roster-v${manifest.versionCode}.apk"
        val tempFile = File(updatesDirectory, "$targetApkName.part")
        val finalFile = File(updatesDirectory, targetApkName)

        // Clean up any stale partial files before starting
        if (tempFile.exists()) tempFile.delete()

        var connection: HttpsURLConnection? = null
        try {
            val url = URL(manifest.apkUrl)
            connection = (url.openConnection() as? HttpsURLConnection)
                ?: return@withContext DownloadResult.Failure("Connection is not an HTTPS connection")

            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MedicalRosterUpdateDownloader/1.0")
            }

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                return@withContext DownloadResult.Failure("Server returned HTTP $responseCode")
            }

            val totalBytes = connection.contentLengthLong

            // 2. Storage Pre-check
            if (totalBytes > 0) {
                val availableBytes = getAvailableStorageBytes(updatesDirectory)
                if (availableBytes < totalBytes + STORAGE_SAFETY_MARGIN_BYTES) {
                    return@withContext DownloadResult.Failure(
                        "Insufficient storage space: requires ${(totalBytes + STORAGE_SAFETY_MARGIN_BYTES) / (1024 * 1024)} MB, " +
                        "available: ${availableBytes / (1024 * 1024)} MB"
                    )
                }
            }

            // 3. Stream to app-private temp file
            var bytesDownloaded = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(tempFile).use { output ->
                    var bytesRead: Int
                    var lastPercentReported = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (!coroutineContext.isActive) {
                            tempFile.delete()
                            return@withContext DownloadResult.Cancelled
                        }

                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        val percent = if (totalBytes > 0) {
                            ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else -1

                        if (percent != lastPercentReported) {
                            lastPercentReported = percent
                            onProgress(bytesDownloaded, totalBytes, percent)
                        }
                    }
                    output.flush()
                }
            }

            // 4. Check cancellation after stream closes
            if (!coroutineContext.isActive) {
                tempFile.delete()
                return@withContext DownloadResult.Cancelled
            }

            // 5. Verify SHA-256 Checksum on the downloaded stream
            val computedSha256 = calculateFileSha256(tempFile)
            if (!computedSha256.equals(manifest.sha256.trim(), ignoreCase = true)) {
                tempFile.delete()
                return@withContext DownloadResult.Failure(
                    "SHA-256 checksum mismatch! Expected: ${manifest.sha256}, Computed: $computedSha256"
                )
            }

            // 6. Atomic move to final target file
            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                // Fallback copy if renameTo fails
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            DownloadResult.Success(finalFile)

        } catch (e: Exception) {
            // Clean up partial file on any exception
            if (tempFile.exists()) tempFile.delete()
            DownloadResult.Failure("Download failed: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Calculates the SHA-256 checksum of [file] using streaming I/O.
     * Never loads the whole file into RAM.
     */
    fun calculateFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        FileInputStream(file).use { fis ->
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val hashBytes = digest.digest()
        val sb = StringBuilder(hashBytes.size * 2)
        for (b in hashBytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b))
        }
        return sb.toString()
    }

    /**
     * Cleans up all cached update APKs in the updates directory.
     */
    fun cleanOldUpdates() {
        try {
            updatesDirectory.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".apk") || file.name.endsWith(".part"))) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun getAvailableStorageBytes(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }
}

