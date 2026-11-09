package com.example.rosterrebuild.update

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages persistent preferences for the update system.
 *
 * Stores:
 * - Dismissed update version (set when user taps "Later", preventing nag prompts on startup).
 * - Last update check timestamp (for throttling automatic startup checks).
 */
class UpdateConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "medical_roster_update_preferences"
        const val KEY_DISMISSED_UPDATE_VERSION = "dismissed_update_version"
        const val KEY_LAST_CHECK_TIMESTAMP = "last_check_timestamp"

        // Minimum time between automatic checks when app resumes (15 minutes)
        const val FOREGROUND_CHECK_THROTTLE_MS = 15 * 60 * 1000L
    }

    /**
     * Checks if this version was previously dismissed by the user.
     */
    fun isVersionDismissed(versionName: String): Boolean {
        val dismissed = prefs.getString(KEY_DISMISSED_UPDATE_VERSION, null) ?: return false
        return dismissed.equals(versionName.trim(), ignoreCase = true)
    }

    /**
     * Records a version as dismissed ("Later").
     */
    fun dismissVersion(versionName: String) {
        prefs.edit().putString(KEY_DISMISSED_UPDATE_VERSION, versionName.trim()).apply()
    }

    /**
     * Clears the dismissed version preference.
     */
    fun clearDismissedVersion() {
        prefs.edit().remove(KEY_DISMISSED_UPDATE_VERSION).apply()
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
     */
    fun shouldThrottleCheck(throttleIntervalMs: Long = FOREGROUND_CHECK_THROTTLE_MS): Boolean {
        val lastCheck = getLastCheckTimestamp()
        if (lastCheck <= 0L) return false
        val elapsed = System.currentTimeMillis() - lastCheck
        return elapsed in 0 until throttleIntervalMs
    }
}
