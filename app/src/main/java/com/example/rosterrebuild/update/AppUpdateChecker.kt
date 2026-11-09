package com.example.rosterrebuild.update

import android.net.Uri
import com.example.rosterrebuild.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AvailableAppUpdate(
    val versionName: String,
    val versionCode: Long?,
    val apkDownloadUrl: String,
    val releasePageUrl: String,
    val releaseNotes: String? = null,
    val sha256: String? = null
)

sealed interface AppUpdateCheckResult {
    data class UpdateAvailable(val update: AvailableAppUpdate) : AppUpdateCheckResult
    data class UpToDate(val currentVersion: String, val latestVersion: String) : AppUpdateCheckResult
    data class Failed(val message: String) : AppUpdateCheckResult
}

/**
 * Checks the latest stable GitHub Release for Medical Roster.
 *
 * Implements system-delegated discovery following the Mixtape architecture:
 * - Queries GitHub Releases API directly.
 * - Enforces HTTPS and strict repository/domain matching.
 * - Uses versionCode as authoritative comparison when available, with semantic version fallback.
 */
object AppUpdateChecker {
    const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/kanakmegha/MedicalRosterAttendance/releases/latest"
    const val APK_ASSET_NAME = "app-release.apk"
    const val RELEASES_PAGE_URL =
        "https://github.com/kanakmegha/MedicalRosterAttendance/releases"
    const val EXPECTED_REPO = "kanakmegha/MedicalRosterAttendance"

    suspend fun checkForUpdate(
        currentVersionName: String = BuildConfig.VERSION_NAME,
        currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
        fetchReleaseJson: suspend () -> String = ::fetchLatestReleaseJson
    ): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val payload = fetchReleaseJson()
            // Strict parsing: malformed payloads fail safely instead of guessing.
            val release = JSONObject(payload.trim())
            parseReleasePayload(
                release = release,
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode
            )
        } catch (e: Exception) {
            AppUpdateCheckResult.Failed(
                message = when (e) {
                    is java.net.UnknownHostException ->
                        "Couldn't connect to GitHub. Check your internet connection and try again."
                    is java.net.SocketTimeoutException ->
                        "GitHub took too long to respond. Please try again."
                    is org.json.JSONException ->
                        "The update information from GitHub could not be parsed."
                    is IOException ->
                        e.message ?: "Network error while checking for updates."
                    else -> "Couldn't check for updates. Please try again later."
                }
            )
        }
    }

    /**
     * Network seam: downloads the raw GitHub release JSON.
     * Only HTTPS is used; timeouts fail fast so local functionality is never blocked.
     */
    internal fun fetchLatestReleaseJson(): String {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MedicalRoster-Android")
        }
        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IOException("GitHub returned HTTP $statusCode")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parses a GitHub release JSON object and compares against installed version.
     *
     * Security rules:
     * - Draft releases and pre-release channels are rejected.
     * - The release versionCode is the authoritative comparison field; if metadata
     *   does not include a determinable versionCode the check fails safely rather
     *   than guessing from versionName alone.
     */
    fun parseReleasePayload(
        release: JSONObject,
        currentVersionName: String,
        currentVersionCode: Long
    ): AppUpdateCheckResult {
        if (release.optBoolean("draft", false)) {
            return AppUpdateCheckResult.Failed("Latest release is a draft.")
        }

        if (release.optBoolean("prerelease", false)) {
            return AppUpdateCheckResult.Failed(
                "The latest published release is a pre-release and not on this app's update channel."
            )
        }

        val tagName = release.optString("tag_name").trim()
        if (tagName.isEmpty()) {
            throw IOException("The latest release has no version tag.")
        }

        val latestVersionName = normalizeVersion(tagName)
        val bodyText = release.optString("body", "").trim()
        val latestVersionCode = extractVersionCode(bodyText, tagName)
        if (latestVersionCode == null || latestVersionCode <= 0) {
            return AppUpdateCheckResult.Failed(
                "Release metadata is missing a valid versionCode. Skipping this update for safety."
            )
        }
        if (latestVersionCode <= currentVersionCode) {
            return AppUpdateCheckResult.UpToDate(
                currentVersion = currentVersionName,
                latestVersion = latestVersionName
            )
        }

        val assets = release.optJSONArray("assets")
            ?: throw IOException("No release assets were found.")

        // Find primary asset (app-release.apk) or fallback to any valid APK asset
        var chosenAssetUrl: String? = null
        var chosenAssetSha256: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            val downloadUrl = asset.optString("browser_download_url", "")
            if (name == APK_ASSET_NAME && isValidApkDownloadUrl(downloadUrl)) {
                chosenAssetUrl = downloadUrl
                chosenAssetSha256 = asset.optString("digest", "").trim().ifBlank { null }
                break
            }
        }

        if (chosenAssetUrl == null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val downloadUrl = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && isValidApkDownloadUrl(downloadUrl)) {
                    chosenAssetUrl = downloadUrl
                    chosenAssetSha256 = asset.optString("digest", "").trim().ifBlank { null }
                    break
                }
            }
        }

        val apkUrl = chosenAssetUrl
            ?: throw IOException("The latest release does not include a valid APK download.")

        val rawPageUrl = release.optString("html_url")
        val releasePageUrl = if (isValidReleasePageUrl(rawPageUrl)) rawPageUrl else RELEASES_PAGE_URL

        return AppUpdateCheckResult.UpdateAvailable(
            AvailableAppUpdate(
                versionName = latestVersionName,
                versionCode = latestVersionCode,
                apkDownloadUrl = apkUrl,
                releasePageUrl = releasePageUrl,
                releaseNotes = bodyText.ifBlank { null },
                sha256 = chosenAssetSha256
            )
        )
    }

    /**
     * Validates that the APK download URL is HTTPS, hosted by GitHub, and targets our repository.
     */
    fun isValidApkDownloadUrl(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val uri = try {
            java.net.URI(url)
        } catch (_: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        val isAllowedHost = host == "github.com" ||
                host == "objects.githubusercontent.com" ||
                host.endsWith(".github.com") ||
                host.endsWith(".githubusercontent.com")
        if (!isAllowedHost) return false

        val path = uri.path ?: return false
        return path.contains(EXPECTED_REPO, ignoreCase = true)
    }

    /**
     * Validates that the release page URL is HTTPS on github.com.
     */
    fun isValidReleasePageUrl(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val uri = try {
            java.net.URI(url)
        } catch (_: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return (host == "github.com" || host.endsWith(".github.com")) &&
                (uri.path?.contains(EXPECTED_REPO, ignoreCase = true) == true)
    }
}

/**
 * Normalizes version string by removing leading 'v' or 'V'.
 */
internal fun normalizeVersion(version: String): String =
    version.trim().removePrefix("v").removePrefix("V")

/**
 * Extracts numeric versionCode from release body (e.g. `versionCode: 6`) or tag (e.g. `1.0.0-pilot.6`).
 */
internal fun extractVersionCode(body: String, tagName: String): Long? {
    // 1. Look for explicit versionCode/build in the release description
    val bodyRegex = Regex("""(?i)(?:versionCode|version_code|build)\s*[:=]\s*(\d+)""")
    bodyRegex.find(body)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }

    // 2. Look for pilot.X pattern in tag (e.g. v1.0.0-pilot.5 -> 5)
    val pilotRegex = Regex("""(?i)pilot[._-]?(\d+)""")
    pilotRegex.find(tagName)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }

    // 3. Look for single trailing numeric integer if tag is just a number (e.g. v5)
    val numericTag = tagName.trim().removePrefix("v").removePrefix("V")
    numericTag.toLongOrNull()?.let { return it }

    return null
}

/**
 * Compares numeric dotted versions, handling pre-release qualifiers safely.
 * e.g. 1.0.0-pilot.6 > 1.0.0-pilot.5, 1.0.1 > 1.0.0.
 */
internal fun compareVersions(candidate: String, installed: String): Int {
    fun parseComponents(value: String): List<Int> {
        val normalized = normalizeVersion(value)
        val mainPart = normalized.substringBefore('-').substringBefore('+')
        val mainComponents = mainPart.split('.').map { component ->
            component.toIntOrNull() ?: 0
        }

        // Check for qualifier suffix such as pilot.5
        val suffix = if (normalized.contains('-')) normalized.substringAfter('-') else ""
        val suffixNumber = Regex("""\d+""").find(suffix)?.value?.toIntOrNull() ?: 0

        return mainComponents + suffixNumber
    }

    val candidateParts = parseComponents(candidate)
    val installedParts = parseComponents(installed)
    for (index in 0 until maxOf(candidateParts.size, installedParts.size)) {
        val cPart = candidateParts.getOrElse(index) { 0 }
        val iPart = installedParts.getOrElse(index) { 0 }
        if (cPart != iPart) return cPart.compareTo(iPart)
    }
    return 0
}
