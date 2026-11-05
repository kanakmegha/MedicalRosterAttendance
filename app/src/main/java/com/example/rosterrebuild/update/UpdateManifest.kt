package com.example.rosterrebuild.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable representation of the private hospital release manifest.
 */
data class UpdateManifest(
    val applicationId: String,
    val versionCode: Int,
    val versionName: String,
    val minimumSupportedVersionCode: Int,
    val releaseChannel: String,
    val mandatory: Boolean,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: List<String>,
    val publishedAt: String
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("applicationId", applicationId)
        json.put("versionCode", versionCode)
        json.put("versionName", versionName)
        json.put("minimumSupportedVersionCode", minimumSupportedVersionCode)
        json.put("releaseChannel", releaseChannel)
        json.put("mandatory", mandatory)
        json.put("apkUrl", apkUrl)
        json.put("sha256", sha256)

        val notesArray = JSONArray()
        releaseNotes.forEach { notesArray.put(it) }
        json.put("releaseNotes", notesArray)

        json.put("publishedAt", publishedAt)
        return json.toString(2)
    }

    companion object {
        /**
         * Parses and extracts an [UpdateManifest] from a JSON string.
         * Throws [IllegalArgumentException] if required fields are missing or malformed.
         */
        fun fromJson(jsonStr: String): UpdateManifest {
            val json = try {
                JSONObject(jsonStr)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid manifest JSON: ${e.message}", e)
            }

            val applicationId = json.optString("applicationId")
            if (applicationId.isBlank()) throw IllegalArgumentException("Manifest missing required 'applicationId'")

            val versionCode = json.optInt("versionCode", -1)
            if (versionCode <= 0) throw IllegalArgumentException("Manifest has invalid or missing 'versionCode': $versionCode")

            val versionName = json.optString("versionName")
            if (versionName.isBlank()) throw IllegalArgumentException("Manifest missing required 'versionName'")

            val minimumSupportedVersionCode = json.optInt("minimumSupportedVersionCode", 1)
            val releaseChannel = json.optString("releaseChannel", "hospital-pilot")
            val mandatory = json.optBoolean("mandatory", false)

            val apkUrl = json.optString("apkUrl")
            if (apkUrl.isBlank()) throw IllegalArgumentException("Manifest missing required 'apkUrl'")

            val sha256 = json.optString("sha256")
            if (sha256.isBlank()) throw IllegalArgumentException("Manifest missing required 'sha256'")

            val releaseNotesList = mutableListOf<String>()
            val notesArray = json.optJSONArray("releaseNotes")
            if (notesArray != null) {
                for (i in 0 until notesArray.length()) {
                    val note = notesArray.optString(i)
                    if (note.isNotBlank()) {
                        releaseNotesList.add(note)
                    }
                }
            }

            val publishedAt = json.optString("publishedAt", "")

            return UpdateManifest(
                applicationId = applicationId,
                versionCode = versionCode,
                versionName = versionName,
                minimumSupportedVersionCode = minimumSupportedVersionCode,
                releaseChannel = releaseChannel,
                mandatory = mandatory,
                apkUrl = apkUrl,
                sha256 = sha256,
                releaseNotes = releaseNotesList,
                publishedAt = publishedAt
            )
        }
    }
}

enum class UpdateErrorType {
    NOT_CONFIGURED,
    TLS_TRUST_FAILURE,
    TLS_EXPIRED,
    SERVER_UNAVAILABLE,
    INVALID_MANIFEST,
    VALIDATION_FAILURE
}

/**
 * Result of checking an update manifest against current app version and configuration.
 */
sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val manifest: UpdateManifest,
        val isMandatory: Boolean,
        val currentVersionCode: Int,
        val currentVersionName: String
    ) : UpdateCheckResult()

    data class UpToDate(
        val currentVersionCode: Int,
        val manifest: UpdateManifest
    ) : UpdateCheckResult()

    data class Error(
        val message: String,
        val technicalDetails: String? = null,
        val errorType: UpdateErrorType = UpdateErrorType.SERVER_UNAVAILABLE,
        val throwable: Throwable? = null
    ) : UpdateCheckResult()
}

