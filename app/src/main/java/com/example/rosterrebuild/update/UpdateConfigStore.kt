package com.example.rosterrebuild.update

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages runtime update configuration allowing the hospital manifest endpoint
 * to be configured dynamically without recompiling the application.
 *
 * Avoids any hardcoded fake production endpoint.
 */
class UpdateConfigStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "medical_roster_update_config"
        private const val KEY_MANIFEST_URL = "manifest_url"
        private const val KEY_LAST_CHECK_TIMESTAMP = "last_check_timestamp"

        // Default endpoint is empty until an administrator configures it.
        const val DEFAULT_MANIFEST_URL = ""
    }

    /**
     * Returns the currently configured manifest URL, or empty string if not configured.
     */
    fun getManifestUrl(): String {
        return prefs.getString(KEY_MANIFEST_URL, null)?.trim() ?: DEFAULT_MANIFEST_URL
    }

    /**
     * Checks if a valid HTTPS update endpoint is currently configured.
     */
    fun isConfigured(): Boolean {
        val url = getManifestUrl()
        return url.isNotBlank() && UpdateManifestValidator.isHttpsUrl(url)
    }

    /**
     * Updates the manifest URL.
     * Enforces HTTPS protocol. If empty or blank string is provided, clears the endpoint.
     *
     * @return true if successfully saved or cleared, false if rejected due to non-HTTPS scheme.
     */
    fun setManifestUrl(url: String): Boolean {
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
     * Clears the configured manifest URL.
     */
    fun clearManifestUrl() {
        prefs.edit().remove(KEY_MANIFEST_URL).apply()
    }

    /**
     * Resets the manifest URL back to default (unconfigured).
     */
    fun resetToDefault() {
        clearManifestUrl()
    }

    /**
     * Records timestamp of successful update check.
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
}
