package com.example.rosterrebuild.update

import java.util.Locale

object UpdateManifestValidator {

    private val SHA256_HEX_REGEX = Regex("^[a-fA-F0-9]{64}$")

    /**
     * Validates an [UpdateManifest] against security requirements and the current installed package.
     *
     * @param manifest The parsed manifest
     * @param expectedApplicationId The running application's package name
     * @param currentVersionCode The running application's version code
     * @param currentVersionName The running application's version name
     * @return [UpdateCheckResult] indicating whether an update is available, mandatory, up-to-date, or invalid
     */
    fun evaluate(
        manifest: UpdateManifest,
        expectedApplicationId: String,
        currentVersionCode: Int,
        currentVersionName: String
    ): UpdateCheckResult {
        // 1. Application ID Verification
        if (!manifest.applicationId.equals(expectedApplicationId, ignoreCase = false)) {
            return UpdateCheckResult.Error(
                "Application ID mismatch: expected '$expectedApplicationId' but manifest declared '${manifest.applicationId}'"
            )
        }

        // 2. HTTPS Security Enforcement
        if (!manifest.apkUrl.lowercase(Locale.ROOT).startsWith("https://")) {
            return UpdateCheckResult.Error(
                "Security violation: APK download URL must use HTTPS. Received: '${manifest.apkUrl}'"
            )
        }

        // 3. SHA-256 Checksum Validation
        if (!SHA256_HEX_REGEX.matches(manifest.sha256.trim())) {
            return UpdateCheckResult.Error(
                "Security violation: Invalid SHA-256 checksum format in manifest: '${manifest.sha256}'"
            )
        }

        // 4. Version Evaluation
        return if (manifest.versionCode > currentVersionCode) {
            val isMandatory = manifest.mandatory || currentVersionCode < manifest.minimumSupportedVersionCode
            UpdateCheckResult.UpdateAvailable(
                manifest = manifest,
                isMandatory = isMandatory,
                currentVersionCode = currentVersionCode,
                currentVersionName = currentVersionName
            )
        } else {
            UpdateCheckResult.UpToDate(
                currentVersionCode = currentVersionCode,
                manifest = manifest
            )
        }
    }

    /**
     * Verifies that a given URL string is a valid HTTPS endpoint.
     */
    fun isHttpsUrl(url: String): Boolean {
        return url.isNotBlank() && url.lowercase(Locale.ROOT).startsWith("https://")
    }
}

