package com.example.rosterrebuild.update

import android.content.Context
import android.content.SharedPreferences
import com.example.rosterrebuild.BuildConfig

/**
 * Manages runtime update configuration for the Medical Roster in-app updater.
 *
 * Automatically knows its official GitHub Releases production manifest URL:
 * https://raw.githubusercontent.com/kanakmegha/MedicalRosterAttendance/main/release-manifest.json
 *
 * Requires zero user configuration. In release builds, always uses the official production endpoint.
 */
class UpdateConfigStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "medical_roster_update_config"
        private const val KEY_MANIFEST_URL = "manifest_url"
        private const val KEY_LAST_CHECK_TIMESTAMP = "last_check_timestamp"
        private const val KEY_LAST_KNOWN_MANIFEST_JSON = "last_known_manifest_json"

        // Official fixed production endpoint hosted on GitHub Releases / repository
        const val PRODUCTION_MANIFEST_URL =
            "https://raw.githubusercontent.com/kanakmegha/MedicalRosterAttendance/main/release-manifest.json"

        const val DEFAULT_MANIFEST_URL = PRODUCTION_MANIFEST_URL

        // Minimum time between automatic checks when app resumes (15 minutes)
        const val FOREGROUND_CHECK_THROTTLE_MS = 15 * 60 * 1000L
    }

    /**
     * Returns the official update manifest URL.
     * In release builds, always returns [PRODUCTION_MANIFEST_URL].
     * In debug builds, allows an internal developer override if configured.
     */
    fun getManifestUrl(): String {
        if (!BuildConfig.DEBUG) {
            return PRODUCTION_MANIFEST_URL
        }
        return prefs.getString(KEY_MANIFEST_URL, null)?.takeIf { it.isNotBlank() } ?: PRODUCTION_MANIFEST_URL
    }

    /**
     * Checks if a valid HTTPS update endpoint is currently configured.
     */
    fun isConfigured(): Boolean {
        val url = getManifestUrl()
        return url.isNotBlank() && UpdateManifestValidator.isHttpsUrl(url)
    }

    /**
     * Updates the manifest URL (debug builds only).
     */
    fun setManifestUrl(url: String): Boolean {
        if (!BuildConfig.DEBUG) {
            return false // Locked in release builds
        }
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            clearManifestUrl()
            return true
        }
        if (!UpdateManifestValidator.isHttpsUrl(trimmed)) {
            return false
        }
        prefs.edit().putString(KEY_MANIFEST_URL, trimmed).apply()
        return true
    }

    /**
     * Clears any custom manifest URL, reverting back to official production default.
     */
    fun clearManifestUrl() {
        prefs.edit().remove(KEY_MANIFEST_URL).apply()
    }

    /**
     * Resets the manifest URL back to default.
     */
    fun resetToDefault() {
        clearManifestUrl()
    }

    /**
     * Records timestamp of last update check.
     */
    fun recordCheckTimestamp(timestampMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CHECK_TIMESTAMP, timestampMillis).apply()
    }

    /**
     * Returns timestamp of last update check, or 0 if never checked.
     */
    fun getLastCheckTimestamp(): Long {
        return prefs.getLong(KEY_LAST_CHECK_TIMESTAMP, 0L)
    }

    /**
     * Checks if automatic foreground checks should be throttled.
     * Avoids excessive network requests when switching between apps.
     */
    fun shouldThrottleForegroundCheck(throttleIntervalMs: Long = FOREGROUND_CHECK_THROTTLE_MS): Boolean {
        val lastCheck = getLastCheckTimestamp()
        if (lastCheck <= 0L) return false
        val elapsed = System.currentTimeMillis() - lastCheck
        return elapsed in 0 until throttleIntervalMs
    }

    /**
     * Caches the latest known manifest locally for offline awareness.
     */
    fun saveLastKnownManifest(manifest: UpdateManifest) {
        prefs.edit().putString(KEY_LAST_KNOWN_MANIFEST_JSON, manifest.toJson()).apply()
    }

    /**
     * Returns the cached manifest if available, or null.
     */
    fun getLastKnownManifest(): UpdateManifest? {
        val json = prefs.getString(KEY_LAST_KNOWN_MANIFEST_JSON, null) ?: return null
        return try {
            UpdateManifest.fromJson(json)
        } catch (_: Exception) {
            null
        }
    }
}
