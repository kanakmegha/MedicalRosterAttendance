package com.example.rosterrebuild.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

sealed class InstallHandoffResult {
    data class LaunchInstaller(val intent: Intent) : InstallHandoffResult()
    data class RequiresPermission(val permissionIntent: Intent, val explanation: String) : InstallHandoffResult()
    data class Error(val message: String, val throwable: Throwable? = null) : InstallHandoffResult()
}

/**
 * Handles preparing and launching the Android OS package installation flow for a verified APK.
 */
object PackageInstallerHelper {

    /**
     * Checks if the app currently has permission to request package installs on Android 8.0+ (API 26+).
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Creates an intent to navigate to system settings allowing the user to grant
     * "Install unknown apps" permission specifically for this application.
     */
    fun createPermissionIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Prepares the intent to launch the system package installer for [apkFile].
     *
     * @param context Application or Activity context
     * @param apkFile The verified APK file in app-private storage
     * @return [InstallHandoffResult] with the intent to launch or permission required state
     */
    fun prepareInstall(context: Context, apkFile: File): InstallHandoffResult {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return InstallHandoffResult.Error("Verified APK file is missing: ${apkFile.absolutePath}")
        }

        // 1. Check Unknown Sources / Install Packages Permission
        if (!canRequestPackageInstalls(context)) {
            val permissionIntent = createPermissionIntent(context)
            return InstallHandoffResult.RequiresPermission(
                permissionIntent = permissionIntent,
                explanation = "Private hospital APK distribution requires granting 'Install unknown apps' permission " +
                              "for Medical Roster Extractor to proceed with this verified update."
            )
        }

        // 2. Generate secure Content URI via FileProvider
        val contentUri: Uri = try {
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, apkFile)
        } catch (e: Exception) {
            return InstallHandoffResult.Error("Failed to obtain FileProvider URI for APK: ${e.message}", e)
        }

        // 3. Construct the ACTION_VIEW intent for package archive
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return InstallHandoffResult.LaunchInstaller(installIntent)
    }
}

