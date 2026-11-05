package com.example.rosterrebuild.update

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class UpdateManifestFetcher(
    private val context: Context,
    private val configStore: UpdateConfigStore = UpdateConfigStore(context),
    private val networkFetcher: ((String) -> String)? = null
) {
    /**
     * Checks for updates by querying the configured HTTPS manifest.
     *
     * @param customUrl Optional override URL to test specific manifest endpoints
     * @return [UpdateCheckResult] indicating update status
     */
    suspend fun checkForUpdates(customUrl: String? = null): UpdateCheckResult = withContext(Dispatchers.IO) {
        val targetUrl = customUrl ?: configStore.getManifestUrl()

        if (targetUrl.isBlank()) {
            return@withContext UpdateCheckResult.Error(
                message = "App updates are not configured yet.",
                technicalDetails = "No update endpoint URL has been configured.",
                errorType = UpdateErrorType.NOT_CONFIGURED
            )
        }

        if (!UpdateManifestValidator.isHttpsUrl(targetUrl)) {
            return@withContext UpdateCheckResult.Error(
                message = "App updates are not configured yet.",
                technicalDetails = "Security violation: Endpoint must use HTTPS. Received: '$targetUrl'",
                errorType = UpdateErrorType.NOT_CONFIGURED
            )
        }

        val jsonString = try {
            if (networkFetcher != null) {
                networkFetcher.invoke(targetUrl)
            } else {
                fetchHttpsString(targetUrl)
            }
        } catch (e: Exception) {
            return@withContext classifyError(e)
        }

        val manifest = try {
            UpdateManifest.fromJson(jsonString)
        } catch (e: Exception) {
            return@withContext UpdateCheckResult.Error(
                message = "Unable to check for updates. Your saved rosters and offline features remain available.",
                technicalDetails = "Failed to parse update manifest JSON: ${e.message}",
                errorType = UpdateErrorType.INVALID_MANIFEST,
                throwable = e
            )
        }

        val packageInfo = getPackageInfo(context)
        val currentVersionCode = getVersionCode(packageInfo)
        val currentVersionName = packageInfo.versionName ?: "unknown"
        val expectedAppId = context.packageName

        val result = UpdateManifestValidator.evaluate(
            manifest = manifest,
            expectedApplicationId = expectedAppId,
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName
        )

        if (result !is UpdateCheckResult.Error) {
            configStore.recordCheckTimestamp()
        }

        result
    }

    /**
     * Categorizes network, TLS, and server exceptions into patient-safe user messages
     * and detailed technical diagnostics for administrators.
     */
    fun classifyError(e: Throwable): UpdateCheckResult.Error {
        var curr: Throwable? = e
        var isTls = false
        var isExpired = false

        while (curr != null) {
            val name = curr.javaClass.name
            val msg = curr.message ?: ""
            if (curr is javax.net.ssl.SSLException ||
                curr is java.security.GeneralSecurityException ||
                name.contains("CertPathValidatorException") ||
                name.contains("CertificateException") ||
                name.contains("SSL")
            ) {
                isTls = true
            }
            if (curr is java.security.cert.CertificateExpiredException ||
                name.contains("CertificateExpiredException") ||
                msg.contains("expired", ignoreCase = true)
            ) {
                isExpired = true
            }
            curr = curr.cause
        }

        return when {
            isExpired -> UpdateCheckResult.Error(
                message = "The update server's security certificate has expired. Contact your system administrator.",
                technicalDetails = "TLS Certificate Expired: ${e.message ?: e.javaClass.simpleName}",
                errorType = UpdateErrorType.TLS_EXPIRED,
                throwable = e
            )
            isTls -> UpdateCheckResult.Error(
                message = "The update server's HTTPS certificate could not be trusted. Your connection is secure and unverified updates were blocked.",
                technicalDetails = "TLS Trust Verification Failed: ${e.message ?: e.javaClass.simpleName}",
                errorType = UpdateErrorType.TLS_TRUST_FAILURE,
                throwable = e
            )
            else -> UpdateCheckResult.Error(
                message = "Unable to check for updates. Your saved rosters and offline features remain available.",
                technicalDetails = "Server communication failure: ${e.javaClass.simpleName}${e.message?.let { " - $it" } ?: ""}",
                errorType = UpdateErrorType.SERVER_UNAVAILABLE,
                throwable = e
            )
        }
    }

    private fun fetchHttpsString(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as? HttpsURLConnection
            ?: throw IllegalArgumentException("Target URL does not use HTTPS: $urlString")

        connection.apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MedicalRosterUpdateClient/1.0")
        }

        return try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTPS server responded with code $responseCode")
            }

            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun getPackageInfo(context: Context): PackageInfo {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (_: Exception) {
            PackageInfo().apply {
                packageName = context.packageName
                versionName = "1.0.0-pilot.2"
                @Suppress("DEPRECATION")
                versionCode = 2
            }
        }
    }

    private fun getVersionCode(packageInfo: PackageInfo): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (packageInfo.longVersionCode and 0xFFFFFFFFL).toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
    }
}
