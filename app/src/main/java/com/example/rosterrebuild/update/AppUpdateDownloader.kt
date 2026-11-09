package com.example.rosterrebuild.update

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import java.io.IOException

/**
 * Enqueues an APK download through Android's system DownloadManager.
 *
 * Architecture principles (aligned with the Mixtape reference implementation):
 * - User explicitly approves the download in the UI.
 * - Download is handled by Android's system service, running outside the application process.
 * - System notification (`VISIBILITY_VISIBLE_NOTIFY_COMPLETED`) prompts the user to install when complete.
 * - Application NEVER requests `REQUEST_INSTALL_PACKAGES` and NEVER calls `PackageInstaller`
 *   or installer intents. The user opens the system download notification to launch the installer.
 */
object AppUpdateDownloader {

    internal fun destinationFileNameFor(update: AvailableAppUpdate): String =
        "MyRoster-${update.versionName}.apk"

    fun enqueue(context: Context, update: AvailableAppUpdate): Long {
        require(update.apkDownloadUrl.startsWith("https://")) {
            "The update URL must use HTTPS."
        }
        require(AppUpdateChecker.isValidApkDownloadUrl(update.apkDownloadUrl)) {
            "The update URL does not match the official repository."
        }

        val manager = context.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE)
            as? DownloadManager
            ?: throw IOException(
                "The Android download service is unavailable on this device. " +
                        "You can download the update from GitHub Releases instead."
            )

        val destinationFileName = destinationFileNameFor(update)

        // Duplicate prevention: if this version is already queued or in progress,
        // do not start a second download of the same file.
        queryExistingDownload(manager, update)
            ?.takeIf { it.status.isActiveDownloadStatus() }
            ?.let { return it.downloadId }

        // A completed or failed entry with the same source would make a fresh
        // enqueue fail (file already exists). An explicit user approval to download
        // again overrides the stale entry, so remove it first.
        queryExistingDownload(manager, update)?.let { stale ->
            try {
                manager.remove(stale.downloadId)
            } catch (_: Exception) {
                // Best effort; enqueue failure afterwards surfaces a clear error.
            }
        }

        val request = DownloadManager.Request(Uri.parse(update.apkDownloadUrl)).apply {
            setTitle("MyRoster update ${update.versionName}")
            setDescription("Downloading the latest MyRoster update")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            // Save to the public Downloads directory so the user can open the APK
            // from the system notification or file manager.
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                destinationFileName
            )
        }

        return try {
            manager.enqueue(request)
        } catch (e: Exception) {
            throw IOException(
                "Android could not start the download (${e.message ?: "unknown error"}). " +
                        "You can download the update from GitHub Releases instead.",
                e
            )
        }
    }

    internal data class ExistingDownload(val downloadId: Long, val status: Int)

    /**
     * Looks up an existing DownloadManager entry whose source URI matches the
     * update's APK URL. Returns null when nothing matches or the cursor cannot
     * be read (e.g. vendor restrictions).
     */
    internal fun queryExistingDownload(
        manager: DownloadManager,
        update: AvailableAppUpdate
    ): ExistingDownload? {
        var cursor: Cursor? = null
        return try {
            cursor = manager.query(DownloadManager.Query())
            loop@ while (cursor?.moveToNext() == true) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                if (uri == update.apkDownloadUrl) {
                    return ExistingDownload(id, status)
                }
                continue@loop
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try {
                cursor?.close()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Download statuses that represent a download still occupying the destination file
 * (queued, running, or paused by the system, e.g. because it is waiting for Wi-Fi).
 */
internal fun Int.isActiveDownloadStatus(): Boolean =
    this == DownloadManager.STATUS_PENDING ||
            this == DownloadManager.STATUS_RUNNING ||
            this == DownloadManager.STATUS_PAUSED
