package com.example.rosterrebuild.data.storage

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class StorageManager(private val context: Context) {

    fun getRosterDirectory(employeeId: String, rosterId: String): File {
        val dir = File(context.filesDir, "rosters/$employeeId/$rosterId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Copies a user-selected image Uri directly from ContentResolver into the
     * canonical permanent app-private roster storage:
     * files/rosters/{employeeId}/{rosterId}/original_roster.png
     *
     * Ensures crash safety using an intermediate .part staging file and atomic rename.
     * Prevents partial/interrupted copies from being treated as canonical images.
     */
    fun copyUriToCanonicalImage(uri: Uri, employeeId: String, rosterId: String): File {
        val dir = getRosterDirectory(employeeId, rosterId)
        val targetFile = File(dir, "original_roster.png")
        val partFile = File(dir, "original_roster.png.part")
        if (partFile.exists()) partFile.delete()

        try {
            var bytesWritten: Long = 0
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open input stream for $uri" }
                FileOutputStream(partFile).use { output ->
                    bytesWritten = input.copyTo(output)
                }
            }
            require(bytesWritten > 0) { "Zero bytes copied from $uri" }
            require(partFile.exists() && partFile.length() == bytesWritten) { "Incomplete write for $uri" }

            // Validate that the staged file is a valid decodable image
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(partFile.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Copied file is not a valid decodable image" }

            // Atomic rename to target canonical file
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

    /**
     * Copies a source File directly into canonical permanent roster storage:
     * files/rosters/{employeeId}/{rosterId}/original_roster.png
     *
     * Uses intermediate .part file and atomic rename for reliability.
     */
    fun copyFileToCanonicalImage(sourceFile: File, employeeId: String, rosterId: String): File {
        val dir = getRosterDirectory(employeeId, rosterId)
        val targetFile = File(dir, "original_roster.png")
        if (sourceFile.absolutePath == targetFile.absolutePath && targetFile.exists()) {
            return targetFile
        }
        val partFile = File(dir, "original_roster.png.part")
        if (partFile.exists()) partFile.delete()

        try {
            var bytesWritten: Long = 0
            sourceFile.inputStream().use { input ->
                FileOutputStream(partFile).use { output ->
                    bytesWritten = input.copyTo(output)
                }
            }
            require(bytesWritten > 0) { "Zero bytes copied from ${sourceFile.absolutePath}" }
            require(partFile.exists() && partFile.length() == bytesWritten) { "Incomplete write from ${sourceFile.absolutePath}" }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(partFile.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Copied file is not a valid decodable image" }

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

    /**
     * Computes the SHA-256 hash of a local File.
     */
    fun computeSha256(file: File): String {
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
     * Computes the SHA-256 hash of a Uri from ContentResolver.
     */
    fun computeSha256(uri: Uri): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns the canonical image file for a given roster.
     */
    fun getCanonicalImageFile(employeeId: String, rosterId: String): File {
        val dir = getRosterDirectory(employeeId, rosterId)
        return File(dir, "original_roster.png")
    }

    fun saveOriginalImage(bitmap: Bitmap, employeeId: String, rosterId: String): File {
        val dir = getRosterDirectory(employeeId, rosterId)
        val file = File(dir, "original_roster.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    fun getWorkbookFile(employeeId: String, rosterId: String, month: Int, year: Int, version: Int): File {
        val dir = getRosterDirectory(employeeId, rosterId)
        val fileName = "Medical_Roster_${employeeId}_${year}_%02d_v%d.xlsx".format(month, version)
        return File(dir, fileName)
    }

    fun createShareIntent(file: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun createViewIntent(file: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                contentUri,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
