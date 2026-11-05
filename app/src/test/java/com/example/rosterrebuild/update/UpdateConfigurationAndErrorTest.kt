package com.example.rosterrebuild.update

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateConfigurationAndErrorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dummyContext: Context
    private val inMemoryPrefs = HashMap<String, Any>()

    private val validManifestJson = """
        {
          "applicationId": "com.example.rosterrebuild",
          "versionCode": 3,
          "versionName": "1.0.0-pilot.3",
          "minimumSupportedVersionCode": 1,
          "releaseChannel": "hospital-pilot",
          "mandatory": false,
          "apkUrl": "https://hospital-server.internal.net/roster-v3.apk",
          "sha256": "564e6bfb92c320f3b3eacb4ab8acbf2340aa7caf935c39af91530da0d05c9061",
          "releaseNotes": ["Test release notes"],
          "publishedAt": "2026-11-05T00:00:00Z"
        }
    """.trimIndent()

    @Before
    fun setUp() {
        inMemoryPrefs.clear()
        val mockSharedPreferences = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = inMemoryPrefs
            override fun getString(key: String?, defValue: String?): String? = (inMemoryPrefs[key] as? String) ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
            override fun getInt(key: String?, defValue: Int): Int = (inMemoryPrefs[key] as? Int) ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (inMemoryPrefs[key] as? Long) ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (inMemoryPrefs[key] as? Float) ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = (inMemoryPrefs[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = inMemoryPrefs.containsKey(key)
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    if (key != null && value != null) inMemoryPrefs[key] = value
                    return this
                }
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                    if (key != null) inMemoryPrefs[key] = value
                    return this
                }
                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
                override fun remove(key: String?): SharedPreferences.Editor {
                    if (key != null) inMemoryPrefs.remove(key)
                    return this
                }
                override fun clear(): SharedPreferences.Editor {
                    inMemoryPrefs.clear()
                    return this
                }
                override fun commit(): Boolean = true
                override fun apply() {}
            }
        }

        dummyContext = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.example.rosterrebuild"
            override fun getFilesDir(): File = tempFolder.root
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = mockSharedPreferences
        }
    }

    // =========================================================================
    // 1. Missing endpoint
    // =========================================================================
    @Test
    fun test1_missingEndpoint_returnsFriendlyNotConfiguredMessage() = runTest {
        val configStore = UpdateConfigStore(dummyContext)
        configStore.clearManifestUrl() // ensure empty

        val fetcher = UpdateManifestFetcher(
            context = dummyContext,
            configStore = configStore
        )

        val result = fetcher.checkForUpdates()
        assertTrue("Expected UpdateCheckResult.Error when endpoint is missing", result is UpdateCheckResult.Error)

        val error = result as UpdateCheckResult.Error
        assertEquals("App updates are not configured yet.", error.message)
        assertEquals(UpdateErrorType.NOT_CONFIGURED, error.errorType)
        assertNotNull(error.technicalDetails)
    }

    // =========================================================================
    // 2. Invalid endpoint (non-HTTPS)
    // =========================================================================
    @Test
    fun test2_invalidEndpoint_nonHttps_returnsNotConfiguredError() = runTest {
        val configStore = UpdateConfigStore(dummyContext)
        val fetcher = UpdateManifestFetcher(
            context = dummyContext,
            configStore = configStore
        )

        val result = fetcher.checkForUpdates(customUrl = "http://insecure-hospital-server.net/manifest.json")
        assertTrue("Expected UpdateCheckResult.Error for non-HTTPS URL", result is UpdateCheckResult.Error)

        val error = result as UpdateCheckResult.Error
        assertEquals("App updates are not configured yet.", error.message)
        assertEquals(UpdateErrorType.NOT_CONFIGURED, error.errorType)
        assertTrue(error.technicalDetails!!.contains("HTTPS"))
    }

    // =========================================================================
    // 3. Untrusted TLS certificate
    // =========================================================================
    @Test
    fun test3_untrustedTlsCertificate_returnsSecurityNoticeWithoutRawTrace() = runTest {
        val configStore = UpdateConfigStore(dummyContext)
        val certPathEx = Exception("Trust anchor for certification path not found.")
        val sslEx = SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.").apply {
            initCause(certPathEx)
        }

        val fetcher = UpdateManifestFetcher(
            context = dummyContext,
            configStore = configStore,
            networkFetcher = { _ -> throw sslEx }
        )

        val result = fetcher.checkForUpdates(customUrl = "https://hospital.internal/manifest.json")
        assertTrue("Expected UpdateCheckResult.Error on TLS trust failure", result is UpdateCheckResult.Error)

        val error = result as UpdateCheckResult.Error
        assertEquals(
            "The update server's HTTPS certificate could not be trusted. Your connection is secure and unverified updates were blocked.",
            error.message
        )
        assertEquals(UpdateErrorType.TLS_TRUST_FAILURE, error.errorType)
        assertTrue(error.technicalDetails!!.contains("TLS Trust Verification Failed"))
    }

    // =========================================================================
    // 4. Expired server certificate
    // =========================================================================
    @Test
    fun test4_expiredServerCertificate_returnsCertificateExpiredNotice() = runTest {
        val configStore = UpdateConfigStore(dummyContext)
        val expiredEx = CertificateExpiredException("Certificate expired on 2026-11-01")
        val sslEx = SSLHandshakeException("Certificate expired").apply {
            initCause(expiredEx)
        }

        val fetcher = UpdateManifestFetcher(
            context = dummyContext,
            configStore = configStore,
            networkFetcher = { _ -> throw sslEx }
        )

        val result = fetcher.checkForUpdates(customUrl = "https://hospital.internal/manifest.json")
        assertTrue("Expected UpdateCheckResult.Error on expired certificate", result is UpdateCheckResult.Error)

        val error = result as UpdateCheckResult.Error
        assertEquals(
            "The update server's security certificate has expired. Contact your system administrator.",
            error.message
        )
        assertEquals(UpdateErrorType.TLS_EXPIRED, error.errorType)
        assertTrue(error.technicalDetails!!.contains("TLS Certificate Expired"))
    }

    // =========================================================================
    // 5. Server unavailable
    // =========================================================================
    @Test
    fun test5_serverUnavailable_returnsOfflineRostersAvailableMessage() = runTest {
        val configStore = UpdateConfigStore(dummyContext)
        val fetcher = UpdateManifestFetcher(
            context = dummyContext,
            configStore = configStore,
            networkFetcher = { _ -> throw UnknownHostException("Unable to resolve host hospital.internal") }
        )

        val result = fetcher.checkForUpdates(customUrl = "https://hospital.internal/manifest.json")
        assertTrue("Expected UpdateCheckResult.Error when host is unreachable", result is UpdateCheckResult.Error)

        val error = result as UpdateCheckResult.Error
        assertEquals(
            "Unable to check for updates. Your saved rosters and offline features remain available.",
            error.message
        )
        assertEquals(UpdateErrorType.SERVER_UNAVAILABLE, error.errorType)
        assertTrue(error.technicalDetails!!.contains("Server communication failure"))
    }

    @Test
    fun test5b_serverConnectionRefused_returnsOfflineRostersAvailableMessage() = runTest {
        val configStore = UpdateConfigStore(dummyContext)
        val fetcher = UpdateManifestFetcher(
            context = dummyContext,
            configStore = configStore,
            networkFetcher = { _ -> throw ConnectException("Failed to connect to 192.168.1.100:443") }
        )

        val result = fetcher.checkForUpdates(customUrl = "https://hospital.internal/manifest.json")
        assertTrue(result is UpdateCheckResult.Error)
        val error = result as UpdateCheckResult.Error
        assertEquals(
            "Unable to check for updates. Your saved rosters and offline features remain available.",
            error.message
        )
        assertEquals(UpdateErrorType.SERVER_UNAVAILABLE, error.errorType)
    }

    // =========================================================================
    // 6. Valid HTTPS manifest
    // =========================================================================
    @Test
    fun test6_validHttpsManifest_parsesSuccessfully() = runTest {
        val configStore = UpdateConfigStore(dummyContext)
        val fetcher = UpdateManifestFetcher(
            context = dummyContext,
            configStore = configStore,
            networkFetcher = { _ -> validManifestJson }
        )

        val result = fetcher.checkForUpdates(customUrl = "https://hospital.internal/manifest.json")
        assertFalse("Expected successful check for valid manifest, but got Error", result is UpdateCheckResult.Error)
    }

    // =========================================================================
    // 7. No update available (current >= remote)
    // =========================================================================
    @Test
    fun test7_noUpdateAvailable_whenCurrentVersionCodeMatchesOrExceedsRemote() {
        val manifest = UpdateManifest.fromJson(validManifestJson).copy(versionCode = 2)

        val result = UpdateManifestValidator.evaluate(
            manifest = manifest,
            expectedApplicationId = "com.example.rosterrebuild",
            currentVersionCode = 2,
            currentVersionName = "1.0.0-pilot.2"
        )

        assertTrue("Expected UpToDate when current versionCode matches remote", result is UpdateCheckResult.UpToDate)
        val upToDate = result as UpdateCheckResult.UpToDate
        assertEquals(2, upToDate.currentVersionCode)
        assertEquals(2, upToDate.manifest.versionCode)
    }

    // =========================================================================
    // 8. Update available (remote > current)
    // =========================================================================
    @Test
    fun test8_updateAvailable_whenRemoteVersionCodeExceedsCurrent() {
        val manifest = UpdateManifest.fromJson(validManifestJson).copy(versionCode = 3)

        val result = UpdateManifestValidator.evaluate(
            manifest = manifest,
            expectedApplicationId = "com.example.rosterrebuild",
            currentVersionCode = 2,
            currentVersionName = "1.0.0-pilot.2"
        )

        assertTrue("Expected UpdateAvailable when remote versionCode is higher", result is UpdateCheckResult.UpdateAvailable)
        val available = result as UpdateCheckResult.UpdateAvailable
        assertEquals(3, available.manifest.versionCode)
        assertEquals("1.0.0-pilot.3", available.manifest.versionName)
        assertEquals(2, available.currentVersionCode)
        assertEquals("1.0.0-pilot.2", available.currentVersionName)
    }
}

