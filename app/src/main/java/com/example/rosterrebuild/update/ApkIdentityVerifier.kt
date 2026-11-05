package com.example.rosterrebuild.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.Locale

sealed class ApkVerificationResult {
    object Success : ApkVerificationResult()
    data class Failure(val reason: String) : ApkVerificationResult()
}

/**
 * Verifies the identity, package name, version, and cryptographic signature of an APK
 * before handing it off to the Android package installer.
 */
object ApkIdentityVerifier {

    // Hospital pilot release signing certificate SHA-256 fingerprint
    const val EXPECTED_RELEASE_CERT_SHA256 = "564e6bfb92c320f3b3eacb4ab8acbf2340aa7caf935c39af91530da0d05c9061"

    /**
     * Verifies the APK at [apkFile] against [manifest] and the currently installed app identity.
     */
    fun verifyApk(
        context: Context,
        apkFile: File,
        manifest: UpdateManifest
    ): ApkVerificationResult {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return ApkVerificationResult.Failure("APK file does not exist or is empty: ${apkFile.absolutePath}")
        }

        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return ApkVerificationResult.Failure("Failed to parse APK archive metadata. The file may be corrupt.")

        // 1. Verify Application ID / Package Name
        val archivePackageName = archiveInfo.packageName
        if (archivePackageName != context.packageName || archivePackageName != manifest.applicationId) {
            return ApkVerificationResult.Failure(
                "Package name mismatch: APK is '$archivePackageName', expected '${manifest.applicationId}'"
            )
        }

        // 2. Verify Version Code matches Manifest
        val archiveVersionCode = getVersionCode(archiveInfo)
        if (archiveVersionCode != manifest.versionCode) {
            return ApkVerificationResult.Failure(
                "Version code mismatch: APK has versionCode $archiveVersionCode, but manifest declared ${manifest.versionCode}"
            )
        }

        // 3. Verify Signing Certificate
        val apkCertDigests = getSigningCertDigests(archiveInfo)
        if (apkCertDigests.isEmpty()) {
            return ApkVerificationResult.Failure("APK does not contain valid signing certificates.")
        }

        val installedCertDigests = getInstalledSigningCertDigests(context)
        if (installedCertDigests.isEmpty()) {
            return ApkVerificationResult.Failure("Could not read installed application signing certificates.")
        }

        // Check if APK certificate matches the installed certificate
        val matchesInstalled = apkCertDigests.any { apkDigest ->
            installedCertDigests.any { it.equals(apkDigest, ignoreCase = true) }
        }

        if (!matchesInstalled) {
            return ApkVerificationResult.Failure(
                "Cryptographic signature mismatch! The downloaded APK is signed with a different key " +
                "than the installed application. In-place upgrade cannot proceed."
            )
        }

        // If running in production release build, also ensure it matches the authorized hospital release key
        val matchesExpectedReleaseKey = apkCertDigests.any {
            it.equals(EXPECTED_RELEASE_CERT_SHA256, ignoreCase = true)
        }
        val installedMatchesReleaseKey = installedCertDigests.any {
            it.equals(EXPECTED_RELEASE_CERT_SHA256, ignoreCase = true)
        }

        // If the installed app is the release build, enforce the official release fingerprint
        if (installedMatchesReleaseKey && !matchesExpectedReleaseKey) {
            return ApkVerificationResult.Failure(
                "APK signature does not match authorized hospital release key fingerprint: $EXPECTED_RELEASE_CERT_SHA256"
            )
        }

        return ApkVerificationResult.Success
    }

    /**
     * Extracts SHA-256 fingerprints of certificates used to sign the APK archive.
     */
    fun getSigningCertDigests(packageInfo: PackageInfo): List<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return emptyList()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }

        if (signatures.isNullOrEmpty()) return emptyList()

        val md = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            val digest = md.digest(sig.toByteArray())
            md.reset()
            bytesToHex(digest)
        }
    }

    /**
     * Extracts SHA-256 fingerprints of certificates used to sign the currently installed app.
     */
    fun getInstalledSigningCertDigests(context: Context): List<String> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }

        return getSigningCertDigests(packageInfo)
    }

    fun getVersionCode(packageInfo: PackageInfo): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (packageInfo.longVersionCode and 0xFFFFFFFFL).toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = java.lang.StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b))
        }
        return sb.toString()
    }
}

