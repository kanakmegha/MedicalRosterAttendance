package com.example.rosterrebuild.update

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class UpdateConfigStoreTest {

    private val inMemoryPrefs = HashMap<String, Any>()
    private lateinit var dummyContext: Context

    private class FakeContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    @Before
    fun setUp() {
        inMemoryPrefs.clear()

        var editorRef: SharedPreferences.Editor? = null
        val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, args ->
            when (method.name) {
                "putString" -> {
                    inMemoryPrefs[args[0] as String] = args[1] as String
                    editorRef
                }
                "putLong" -> {
                    inMemoryPrefs[args[0] as String] = args[1] as Long
                    editorRef
                }
                "remove" -> {
                    inMemoryPrefs.remove(args[0] as String)
                    editorRef
                }
                "apply", "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor
        editorRef = editorProxy

        val prefsProxy = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> inMemoryPrefs[args[0] as String] ?: args[1]
                "getLong" -> inMemoryPrefs[args[0] as String] ?: args[1]
                "edit" -> editorProxy
                else -> null
            }
        } as SharedPreferences

        dummyContext = FakeContext(prefsProxy)
    }

    @Test
    fun isVersionDismissed_returnsTrueOnlyWhenMatching() {
        val store = UpdateConfigStore(dummyContext)
        assertFalse(store.isVersionDismissed("1.0.0-pilot.5"))

        store.dismissVersion("1.0.0-pilot.5")
        assertTrue(store.isVersionDismissed("1.0.0-pilot.5"))
        assertFalse(store.isVersionDismissed("1.0.0-pilot.6"))

        store.clearDismissedVersion()
        assertFalse(store.isVersionDismissed("1.0.0-pilot.5"))
    }

    @Test
    fun throttleCheck_respectsInterval() {
        val store = UpdateConfigStore(dummyContext)
        // Before any check
        assertFalse(store.shouldThrottleCheck(60_000L))

        // Record check now
        store.recordCheckTimestamp(System.currentTimeMillis())
        assertTrue(store.shouldThrottleCheck(60_000L))

        // Recorded 2 hours ago
        store.recordCheckTimestamp(System.currentTimeMillis() - 7_200_000L)
        assertFalse(store.shouldThrottleCheck(60_000L))
    }
}
